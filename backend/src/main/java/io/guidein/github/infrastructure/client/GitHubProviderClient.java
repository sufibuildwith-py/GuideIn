package io.guidein.github.infrastructure.client;

import io.guidein.github.infrastructure.auth.*;
import io.guidein.jobs.api.FailureCategory;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Provider DTOs never leave github; callers translate into change/provenance API records. */
public final class GitHubProviderClient {
    public static final Map<String,String> READ_PERMISSIONS=Map.of("metadata","read","pull_requests","read","contents","read","checks","read","statuses","read");
    private final GitHubTransport api;
    private final GitHubTransport oauth;
    private final GitHubAppJwtSigner signer;
    private final GitHubInstallationTokenProvider tokens;
    private final Clock clock;
    private final JsonMapper mapper=JsonMapper.builder().build();
    private final String clientId,clientSecret,callback;
    private final AccessGuard guard;
    public interface AccessGuard { void check(long installation,long generation); }
    public record Document(JsonNode json,String etag,String requestId) { }
    public record PageSet(List<JsonNode> rows,boolean complete,String requestId) { }

    public GitHubProviderClient(GitHubTransport api,GitHubTransport oauth,GitHubAppJwtSigner signer,Clock clock,
                                String clientId,String clientSecret,String callback,AccessGuard guard) {
        this.api=api;this.oauth=oauth;this.signer=signer;this.clock=clock;
        this.clientId=clientId;this.clientSecret=clientSecret;this.callback=callback;this.guard=guard;
        this.tokens=new CachedInstallationTokens(clock,this::mint);
    }
    private GitHubInstallationTokenProvider.InstallationToken mint(long installation,long generation) {
        guard.check(installation,generation);
        var response=api.request("POST","/app/installations/"+installation+"/access_tokens","Bearer "+signer.createJwt(),
                mapper.writeValueAsBytes(Map.of("permissions",READ_PERMISSIONS)),Map.of());
        JsonNode json=decode(response);
        var permissions=new LinkedHashMap<String,String>();
        json.path("permissions").properties().forEach(entry->permissions.put(entry.getKey(),entry.getValue().asText()));
        if(!permissions.equals(READ_PERMISSIONS)) throw new ProviderFailure(FailureCategory.AUTHORIZATION,null);
        guard.check(installation,generation);
        try { return new GitHubInstallationTokenProvider.InstallationToken(json.path("token").asText(),
                Instant.parse(json.path("expires_at").asText()),installation,permissions); }
        catch(RuntimeException ignored) { throw new ProviderFailure(FailureCategory.DEPENDENCY_UNAVAILABLE,null); }
    }
    public void evict(long installation) { tokens.evict(installation); }
    public Document appInstallation(long installation) {
        if(installation<1) throw new ProviderFailure(FailureCategory.INVALID_INPUT,null);
        var response=api.request("GET","/app/installations/"+installation,"Bearer "+signer.createJwt(),null,Map.of());
        return document(response);
    }
    public Document get(long installation,long generation,String path) { return get(installation,generation,path,null); }
    public Document get(long installation,long generation,String path,String etag) {
        guard.check(installation,generation);
        var token=tokens.tokenFor(installation,generation);
        var response=api.request("GET",path,"Bearer "+token.secret(),null,etag==null?Map.of():Map.of("If-None-Match",etag));
        if(response.status()==401) {
            tokens.evict(installation); guard.check(installation,generation);
            response=api.request("GET",path,"Bearer "+tokens.tokenFor(installation,generation).secret(),null,etag==null?Map.of():Map.of("If-None-Match",etag));
        }
        guard.check(installation,generation);
        if(response.status()==304) return new Document(null,etag,response.rate().requestId());
        return document(response);
    }
    public PageSet pages(long installation,long generation,String first,String arrayKey,int maximumPages) {
        return pages(installation,generation,first,arrayKey,maximumPages,clock.instant().plusSeconds(20));
    }
    public PageSet pages(long installation,long generation,String first,String arrayKey,int maximumPages,Instant deadline) {
        List<JsonNode> result=new ArrayList<>(); Set<String> seen=new HashSet<>(); String path=first,requestId="";
        for(int page=0;path!=null && page<maximumPages;page++) {
            if(!clock.instant().isBefore(deadline)) throw new ProviderFailure(FailureCategory.TIMEOUT,null);
            if(!seen.add(path)) throw new ProviderFailure(FailureCategory.DEPENDENCY_UNAVAILABLE,null);
            guard.check(installation,generation);
            var response=api.request("GET",path,"Bearer "+tokens.tokenFor(installation,generation).secret(),null,Map.of());
            if(response.status()==401) {
                tokens.evict(installation);guard.check(installation,generation);
                response=api.request("GET",path,"Bearer "+tokens.tokenFor(installation,generation).secret(),null,Map.of());
            }
            JsonNode json=decode(response),rows=arrayKey==null?json:json.path(arrayKey);
            if(!rows.isArray() || rows.size()>100) throw new ProviderFailure(FailureCategory.DEPENDENCY_UNAVAILABLE,null);
            rows.forEach(result::add);requestId=response.rate().requestId();
            path=api.nextPage(path,response.headers());
            guard.check(installation,generation);
        }
        return new PageSet(List.copyOf(result),path==null,requestId);
    }
    public JsonNode verifyUserInstallation(String code,String verifier,long installation) {
        String userToken=null;
        try {
            var tokenResponse=oauth.request("POST","/login/oauth/access_token","Bearer "+signer.createJwt(),
                    mapper.writeValueAsBytes(Map.of("client_id",clientId,"client_secret",clientSecret,"code",code,
                            "redirect_uri",callback,"code_verifier",verifier)),Map.of());
            userToken=decode(tokenResponse).path("access_token").asText();
            if(userToken.isEmpty()) throw new ProviderFailure(FailureCategory.AUTHENTICATION,null);
            JsonNode user=decode(api.request("GET","/user","Bearer "+userToken,null,Map.of()));
            boolean found=false;String path="/user/installations?per_page=100";
            Set<String> seen=new HashSet<>();
            for(int page=0;path!=null && page<100;page++) {
                if(!seen.add(path)) throw new ProviderFailure(FailureCategory.DEPENDENCY_UNAVAILABLE,null);
                var response=api.request("GET",path,"Bearer "+userToken,null,Map.of());
                for(var candidate:decode(response).path("installations")) if(candidate.path("id").asLong()==installation) found=true;
                path=api.nextPage(path,response.headers());
            }
            if(!found || user.path("id").asLong()<1) throw new ProviderFailure(FailureCategory.AUTHORIZATION,null);
            return user;
        } finally {
            if(userToken!=null && !userToken.isEmpty()) {
                // Best-effort revoke this transient token; never retain it or a refresh token.
                try { api.request("DELETE","/applications/"+clientId+"/token","Basic "+Base64.getEncoder().encodeToString(
                        (clientId+":"+clientSecret).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        mapper.writeValueAsBytes(Map.of("access_token",userToken)),Map.of()); }
                catch(RuntimeException ignored) { /* Discard even when revocation is unavailable. */ }
            }
        }
    }
    private Document document(GitHubTransport.Response response) {
        return new Document(decode(response),response.headers().firstValue("ETag").orElse(null),response.rate().requestId());
    }
    private JsonNode decode(GitHubTransport.Response response) {
        int status=response.status();
        if(status==429 || status==403 && (response.rate().retryAfter()!=null || Long.valueOf(0).equals(response.rate().remaining())
                || secondary(response.body()))) throw new ProviderFailure(FailureCategory.RATE_LIMIT,response.rate().notBefore(clock.instant(),1));
        if(status==401) throw new ProviderFailure(FailureCategory.AUTHENTICATION,null);
        if(status==403) throw new ProviderFailure(FailureCategory.AUTHORIZATION,null);
        if(status==404) throw new ProviderFailure(FailureCategory.STALE_SOURCE,null);
        if(status>=500) throw new ProviderFailure(FailureCategory.TRANSIENT_PROVIDER,null);
        if(status<200 || status>=300) throw new ProviderFailure(FailureCategory.DEPENDENCY_UNAVAILABLE,null);
        try { JsonNode json=mapper.readTree(response.body()); if(json==null) throw new IllegalArgumentException(); return json; }
        catch(RuntimeException ignored) { throw new ProviderFailure(FailureCategory.DEPENDENCY_UNAVAILABLE,null); }
    }
    private boolean secondary(byte[] bytes) {
        try { return mapper.readTree(bytes).path("message").asText().toLowerCase(Locale.ROOT).contains("secondary rate limit"); }
        catch(RuntimeException ignored) { return false; }
    }
}
