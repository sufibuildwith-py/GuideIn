package io.guidein.integration;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/** A separate application JVM, not a reconstructed service or an in-memory restart simulation. */
final class ForkedGuideIn implements AutoCloseable {
    final Process process;
    final Path log;
    final int port;
    ForkedGuideIn(String database,LocalGitHub github,LocalOidc oidc,boolean worker) throws Exception {
        this(database,github,oidc,worker,1000);
    }
    ForkedGuideIn(String database,LocalGitHub github,LocalOidc oidc,boolean worker,int pollMillis) throws Exception {
        Path jar=Path.of("target","guidein-backend-2.0.0-SNAPSHOT.jar").toAbsolutePath();
        if(!Files.isRegularFile(jar))throw new IllegalStateException("Package application before restart proof");
        Path directory=Path.of("target","proof");Files.createDirectories(directory);
        log=Files.createTempFile(directory,"phase2-process-",".log");
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var arguments=new java.util.ArrayList<String>();arguments.add(java);
        if(pollMillis>=1000) {
            arguments.add("-javaagent:"+Path.of("target","agents","opentelemetry-javaagent.jar").toAbsolutePath());
            arguments.add("-Dotel.traces.exporter=logging");arguments.add("-Dotel.metrics.exporter=none");arguments.add("-Dotel.logs.exporter=none");
            arguments.add("-Dotel.bsp.schedule.delay=100");arguments.add("-Dotel.instrumentation.methods.include=io.guidein.github.application.GitHubHydrator[processNext]");
        }
        arguments.add("-jar");arguments.add(jar.toString());
        var builder=new ProcessBuilder(arguments);
        builder.environment().putAll(Map.ofEntries(
                Map.entry("SPRING_PROFILES_ACTIVE","test"),Map.entry("SERVER_PORT","0"),
                Map.entry("SPRING_DATASOURCE_URL",database),Map.entry("SPRING_DATASOURCE_USERNAME","guidein_app"),Map.entry("SPRING_DATASOURCE_PASSWORD","guidein-app-test"),
                Map.entry("SPRING_FLYWAY_URL",database),Map.entry("SPRING_FLYWAY_USER","guidein_migrator"),Map.entry("SPRING_FLYWAY_PASSWORD","guidein-migrator-test"),
                Map.entry("GUIDEIN_SECURITY_ISSUER_URI",oidc.issuer()),Map.entry("GUIDEIN_SECURITY_JWK_SET_URI",oidc.issuer()+"/jwks"),
                Map.entry("GUIDEIN_GITHUB_ENABLED","true"),Map.entry("GUIDEIN_GITHUB_WORKER_ENABLED",Boolean.toString(worker)),Map.entry("GUIDEIN_GITHUB_ALLOW_LOOPBACK_TEST","true"),
                Map.entry("GUIDEIN_GITHUB_POLL_DELAY_MS",Integer.toString(pollMillis)),
                Map.entry("GUIDEIN_GITHUB_API_ORIGIN",github.origin()),Map.entry("GUIDEIN_GITHUB_OAUTH_ORIGIN",github.origin()),
                Map.entry("GUIDEIN_GITHUB_CLIENT_ID","proof-client"),Map.entry("GUIDEIN_GITHUB_CLIENT_SECRET","CLIENT_SECRET_CANARY"),
                Map.entry("GUIDEIN_GITHUB_APP_SLUG","guidein-proof"),Map.entry("GUIDEIN_GITHUB_CALLBACK_URL","https://guidein.example.test/github/callback"),
                Map.entry("GUIDEIN_GITHUB_PRIVATE_KEY_PATH",github.keyPath.toString()),Map.entry("GUIDEIN_GITHUB_WEBHOOK_SECRET",GitHubIngestionIT.SECRET)));
        process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        int observed=0;long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
        try {
            while(System.nanoTime()<deadline && process.isAlive()) {
                var matcher=Pattern.compile("Tomcat started on port (\\d+)").matcher(Files.readString(log));
                if(matcher.find()){observed=Integer.parseInt(matcher.group(1));break;}
                Thread.sleep(100);
            }
            if(observed==0)throw new IllegalStateException("Application process did not become ready; see "+log);
            port=observed;
            try(var client=HttpClient.newHttpClient()) {
                var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health/readiness")).timeout(Duration.ofSeconds(10)).build(),HttpResponse.BodyHandlers.ofString());
                if(response.statusCode()!=200)throw new IllegalStateException("Application process is not ready");
            }
        } catch(Exception failed){close();throw failed;}
    }
    void crash() throws Exception {
        process.destroyForcibly();
        if(!process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Proof process did not terminate");
    }
    public void close(){if(process.isAlive())try{crash();}catch(Exception e){throw new IllegalStateException(e);}}
}
