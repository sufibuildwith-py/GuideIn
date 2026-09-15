package io.guidein.integration;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;
import io.guidein.github.infrastructure.client.GitHubProviderClient;

/** Real loopback provider transport. Only GitHub is simulated; GuideIn and PostgreSQL are real. */
final class LocalGitHub implements AutoCloseable {
    static final String A="a".repeat(40),B="b".repeat(40),BASE="c".repeat(40);
    final HttpServer server;
    final Path keyPath;
    final java.security.KeyPair key;
    final AtomicInteger requests=new AtomicInteger(),tokenRequests=new AtomicInteger();
    final AtomicInteger authenticationViolations=new AtomicInteger();
    final Set<String> credentials=ConcurrentHashMap.newKeySet();
    final Map<String,Integer> failures=new ConcurrentHashMap<>();
    final Map<String,java.util.Queue<Integer>> scriptedStatuses=new ConcurrentHashMap<>();
    final Set<String> malformed=ConcurrentHashMap.newKeySet(),resets=ConcurrentHashMap.newKeySet();
    final Map<String,Long> delays=new ConcurrentHashMap<>();
    final List<String> paths=Collections.synchronizedList(new ArrayList<>());
    volatile String head=A,actor="human",signatureReason="valid";
    volatile String actorKind="User",checkSha=null;
    volatile boolean twoCheckSources=false,mixedFiles=false;
    volatile boolean secondaryRateLimit=false;
    volatile Instant rateReset=null;
    volatile boolean suspended=false,deleted=false,association=true,access=true;
    volatile int fileCount=1;
    volatile String repositoryName="repo";
    final JsonMapper mapper=JsonMapper.builder().build();
    LocalGitHub() {
        try {
            var generator=java.security.KeyPairGenerator.getInstance("RSA");generator.initialize(2048);key=generator.generateKeyPair();
            keyPath=Files.createTempFile("guidein-phase2-proof-", ".pem");
            Files.writeString(keyPath,"-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(key.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----\n");
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/",exchange->{
                String path=exchange.getRequestURI().getPath();paths.add(path);requests.incrementAndGet();
                byte[] input=exchange.getRequestBody().readAllBytes();
                String authorization=exchange.getRequestHeaders().getFirst("Authorization");
                if(authorization!=null)credentials.add(authorization);
                int status=failures.getOrDefault(path,200);
                var script=scriptedStatuses.get(path);Integer next=script==null?null:script.poll();if(next!=null)status=next;
                if(resets.contains(path)){exchange.close();return;}
                Long delay=delays.get(path);if(delay!=null)try{Thread.sleep(delay);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                Object body=Map.of("message","provider failure");
                if(!validAuthorization(path,authorization)){authenticationViolations.incrementAndGet();status=401;body=Map.of("message","invalid auth");}
                else if(!"2026-03-10".equals(exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version"))) {status=400;body=Map.of("message","version missing");}
                else if(status==200) {
                    try { body=respond(path,input); }
                    catch(NoSuchElementException ignored) {status=404;}
                }
                if(status==429) exchange.getResponseHeaders().set("Retry-After","120");
                if(status==403 && !secondaryRateLimit) {
                    exchange.getResponseHeaders().set("x-ratelimit-remaining","0");
                    if(rateReset!=null)exchange.getResponseHeaders().set("x-ratelimit-reset",Long.toString(rateReset.getEpochSecond()));
                }
                if(status==403 && secondaryRateLimit)body=Map.of("message","You have exceeded a secondary rate limit");
                if(path.endsWith("/files") && fileCount>100) {
                    int page=1;
                    String query=exchange.getRequestURI().getQuery();
                    if(query!=null && query.contains("page=")) {
                        for(String param:query.split("&")) if(param.startsWith("page=")) page=Integer.parseInt(param.substring(5));
                    }
                    int max=Math.min(fileCount,3000),start=(page-1)*100;
                    List<Object> files=new ArrayList<>();
                    for(int i=start;i<Math.min(start+100,max);i++) files.add(file(i));
                    body=files;
                    if(start+100<max) exchange.getResponseHeaders().set("Link","<"+origin()+path+"?per_page=100&page="+(page+1)+">; rel=\"next\"");
                }
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.getResponseHeaders().set("X-GitHub-Request-Id","PROOF-REQUEST-1");
                exchange.getResponseHeaders().set("ETag","\"proof\"");
                byte[] bytes=malformed.contains(path)?"{not-json".getBytes(StandardCharsets.UTF_8):mapper.writeValueAsBytes(body);
                exchange.sendResponseHeaders(status,bytes.length);
                try(var output=exchange.getResponseBody()){output.write(bytes);}
            });
            server.start();
        } catch(Exception e) {throw new IllegalStateException(e);}
    }
    String origin(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    void reset(){head=A;actor="human";actorKind="User";checkSha=null;twoCheckSources=false;mixedFiles=false;secondaryRateLimit=false;rateReset=null;signatureReason="valid";suspended=false;deleted=false;association=true;access=true;fileCount=1;repositoryName="repo";failures.clear();scriptedStatuses.clear();malformed.clear();resets.clear();delays.clear();paths.clear();credentials.clear();requests.set(0);tokenRequests.set(0);authenticationViolations.set(0);}
    boolean validAuthorization(String path,String authorization) {
        if(authorization==null)return false;
        if(path.equals("/applications/proof-client/token"))return authorization.equals("Basic "+Base64.getEncoder().encodeToString("proof-client:CLIENT_SECRET_CANARY".getBytes(StandardCharsets.UTF_8)));
        if(path.startsWith("/user"))return authorization.equals("Bearer GITHUB_USER_TOKEN_CANARY");
        if(!path.startsWith("/app/") && !path.startsWith("/login/"))return authorization.startsWith("Bearer INSTALLATION_TOKEN_CANARY_");
        try {
            String[] parts=authorization.substring("Bearer ".length()).split("\\.");if(parts.length!=3)return false;
            var verifier=java.security.Signature.getInstance("SHA256withRSA");verifier.initVerify(key.getPublic());verifier.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
            if(!verifier.verify(Base64.getUrlDecoder().decode(parts[2])))return false;
            var header=mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));var claims=mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));long now=Instant.now().getEpochSecond();
            return header.path("alg").asText().equals("RS256") && claims.path("iss").asText().equals("proof-client")
                    && claims.path("iat").asLong()<=now && claims.path("exp").asLong()>now && claims.path("exp").asLong()<=now+600;
        }catch(Exception invalid){return false;}
    }
    Object respond(String path,byte[] input) {
        if(path.equals("/login/oauth/access_token")) return Map.of("access_token","GITHUB_USER_TOKEN_CANARY");
        if(path.equals("/user")) return Map.of("id",41,"login","installer");
        if(path.equals("/user/installations")) return Map.of("installations",association?List.of(Map.of("id",101)):List.of());
        if(path.equals("/applications/proof-client/token")) return Map.of();
        if(path.equals("/app/installations/101")) {
            if(deleted)throw new NoSuchElementException();
            var installation=new LinkedHashMap<String,Object>();
            installation.put("id",101);installation.put("app_slug","guidein-proof");installation.put("account",Map.of("id",11,"login","org"));
            installation.put("permissions",GitHubProviderClient.READ_PERMISSIONS);installation.put("suspended_at",suspended?Instant.now().toString():null);return installation;
        }
        if(path.equals("/app/installations/101/access_tokens")) {
            tokenRequests.incrementAndGet();
            if(deleted || suspended)throw new NoSuchElementException();
            if(!mapper.readTree(input).path("permissions").equals(mapper.valueToTree(GitHubProviderClient.READ_PERMISSIONS)))throw new IllegalArgumentException();
            return Map.of("token","INSTALLATION_TOKEN_CANARY_"+tokenRequests.get(),"expires_at",Instant.now().plusSeconds(3600).toString(),"permissions",GitHubProviderClient.READ_PERMISSIONS);
        }
        if(path.equals("/installation/repositories"))return Map.of("repositories",access?List.of(repo()):List.of());
        if(path.equals("/repositories/501"))return repo();
        if(path.matches("/repos/org/[^/]+/pulls/[0-9]+"))return Map.of("number",Long.parseLong(path.substring(path.lastIndexOf('/')+1)),"head",Map.of("sha",head),
                "base",Map.of("sha",BASE,"repo",Map.of("id",501)),"state","open","title","proof","changed_files",fileCount,"updated_at","2026-09-15T00:00:00Z");
        if(path.endsWith("/files"))return java.util.stream.IntStream.range(0,Math.min(fileCount,100)).mapToObj(this::file).toList();
        if(path.endsWith("/check-runs")) {
            List<Object> checks=new ArrayList<>();
            for(int app:twoCheckSources?List.of(77,78):List.of(77))checks.add(Map.of("id",701,"app",Map.of("id",app),"head_sha",checkSha==null?path.split("/")[5]:checkSha,"name","unit-tests","status","completed","conclusion","success","updated_at","2026-09-15T00:00:00Z"));
            return Map.of("check_runs",checks);
        }
        if(path.endsWith("/statuses"))return List.of(Map.of("id",801,"creator",Map.of("id",88),"context","legacy","state","success","updated_at","2026-09-15T00:00:00Z"));
        if(path.contains("/commits/")) {
            Map<String,Object> verification=new LinkedHashMap<>();verification.put("verified",signatureReason.equals("valid"));verification.put("reason",signatureReason);verification.put("verified_at",null);
            return Map.of("sha",path.substring(path.lastIndexOf('/')+1),"commit",Map.of("verification",verification),"author",Map.of("id",42,"login",actor,"type",actorKind),"files",List.of(file(0)));
        }
        throw new NoSuchElementException();
    }
    Object repo(){return Map.of("id",501,"owner",Map.of("login","org"),"name",repositoryName);}
    Object file(int i){
        if(!mixedFiles)return Map.of("filename","src/file"+i+".java","previous_filename","old/file"+i+".java","status","renamed","additions",1,"deletions",0,"changes",1,"sha",A);
        var f=new LinkedHashMap<String,Object>();f.put("filename",i==4?"asset.bin":"src/file"+i+".java");f.put("status",List.of("added","modified","removed","renamed","modified").get(i));
        if(i==3)f.put("previous_filename","old/renamed.java");if(i<4){f.put("additions",1);f.put("deletions",0);f.put("changes",1);}return f;
    }
    public void close(){server.stop(0);try{Files.deleteIfExists(keyPath);}catch(Exception ignored){}}
}
