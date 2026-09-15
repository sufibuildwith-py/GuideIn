package io.guidein.integration;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.json.JsonMapper;

/** Full HTTP replay with an actual process crash. Never run before targeted trust proofs are green. */
@Testcontainers @Tag("integration")
class GitHubReplayIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:18.6-alpine")
            .withDatabaseName("guidein_replay").withUsername("postgres").withPassword("postgres").withInitScript("postgres-test-init.sql");
    static final LocalGitHub GITHUB=new LocalGitHub();static final LocalOidc OIDC=new LocalOidc();
    final JsonMapper mapper=JsonMapper.builder().build();final HttpClient http=HttpClient.newHttpClient();
    final List<Long> latencies=Collections.synchronizedList(new ArrayList<>());
    record Delivery(UUID id,String event,byte[] raw,String signature) { }
    @AfterAll static void close(){GITHUB.close();OIDC.close();}
    void sql(String query)throws Exception{try(var c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"postgres","postgres");var s=c.createStatement()){s.execute(query);}}
    long count(String query)throws Exception{try(var c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"postgres","postgres");var s=c.createStatement();var r=s.executeQuery(query)){r.next();return r.getLong(1);}}
    Delivery delivery(String event,Map<String,Object> payload)throws Exception{
        byte[] raw=mapper.writeValueAsBytes(payload);var mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(GitHubIngestionIT.SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return new Delivery(UUID.randomUUID(),event,raw,"sha256="+HexFormat.of().formatHex(mac.doFinal(raw)));
    }
    Delivery pr(int number)throws Exception{return delivery("pull_request",Map.of("installation",Map.of("id",101),"repository",Map.of("id",501),"number",number,"action","synchronize","pull_request",Map.of("head",Map.of("sha",LocalGitHub.A))));}
    int send(int port,Delivery d,boolean valid)throws Exception{
        long started=System.nanoTime();
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/webhooks/github"))
                .timeout(Duration.ofSeconds(30)).header("X-GitHub-Delivery",d.id().toString()).header("X-GitHub-Event",d.event())
                .header("X-GitHub-Hook-ID","1").header("X-Hub-Signature-256",valid?d.signature():"sha256="+"0".repeat(64))
                .POST(HttpRequest.BodyPublishers.ofByteArray(d.raw())).build(),HttpResponse.BodyHandlers.discarding());
        if(valid)latencies.add(System.nanoTime()-started);return response.statusCode();
    }
    void batch(int port,List<Delivery> deliveries,boolean valid)throws Exception{
        try(var executor=Executors.newFixedThreadPool(24)){
            List<Future<Integer>> futures=new ArrayList<>();for(var d:deliveries)futures.add(executor.submit(()->send(port,d,valid)));
            for(var future:futures)assertThat(future.get()).isEqualTo(valid?200:401);
        }
    }
    String authenticated(int port,String path,String token,String body)throws Exception{
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);return response.body();
    }
    @Test @Timeout(value=35,unit=TimeUnit.MINUTES)
    void tenThousandDeliveriesRecoverAcrossRealProcessCrashWithoutDuplicateEffects()throws Exception{
        long start=System.nanoTime();UUID tenant=UUID.fromString("20000000-0000-0000-0000-000000000001"),user=UUID.fromString("10000000-0000-0000-0000-000000000001");
        List<Delivery> unique=new ArrayList<>();for(int i=1;i<=5000;i++)unique.add(pr(i));
        GITHUB.head=LocalGitHub.B;
        try(var ingress=new ForkedGuideIn(POSTGRES.getJdbcUrl(),GITHUB,OIDC,false)){
            sql("INSERT INTO users(id,auth_issuer,external_subject,status) VALUES ('"+user+"','"+OIDC.issuer()+"','owner','ACTIVE')");
            sql("INSERT INTO tenants(id,slug,name,status) VALUES ('"+tenant+"','replay','Replay','ACTIVE')");
            sql("INSERT INTO memberships(id,tenant_id,user_id,role,scope_mode) VALUES (gen_random_uuid(),'"+tenant+"','"+user+"','OWNER','ALL_REPOSITORIES')");
            String token=OIDC.token("owner",OIDC.issuer(),"guidein-api",Instant.now().plusSeconds(600));String prefix="/api/v1/tenants/"+tenant+"/integrations/github";
            var preparation=mapper.readTree(authenticated(ingress.port,prefix+"/install",token,"{}"));
            authenticated(ingress.port,prefix+"/callback",token,mapper.writeValueAsString(Map.of("state",preparation.path("state").asText(),"verifier",preparation.path("verifier").asText(),"code","proof-code","installationId",101)));
            batch(ingress.port,unique,true);
            assertThat(count("SELECT count(*) FROM github_deliveries")).isEqualTo(5001);
            assertThat(count("SELECT count(*) FROM changes")).isZero();
            assertThat(count("SELECT count(*) FROM job_queue WHERE status='READY'")).isEqualTo(5001);
            ingress.crash();
        }
        try(var recovered=new ForkedGuideIn(POSTGRES.getJdbcUrl(),GITHUB,OIDC,true,1)){
            batch(recovered.port,unique.subList(0,3000),true);
            batch(recovered.port,unique.subList(0,1000),true);
            List<Delivery> later=new ArrayList<>();for(int i=1;i<=500;i++)later.add(pr(i));
            for(int i=0;i<250;i++){
                later.add(delivery("status",Map.of("installation",Map.of("id",101),"repository",Map.of("id",501),"sha",LocalGitHub.B,"state","pending")));
                later.add(delivery("installation",Map.of("installation",Map.of("id",101),"action","created")));
            }
            batch(recovered.port,later,true);
            List<Delivery> invalid=new ArrayList<>();for(int i=0;i<1000;i++)invalid.add(pr(20000+i));batch(recovered.port,invalid,false);
            long deadline=System.nanoTime()+Duration.ofMinutes(30).toNanos();
            while(count("SELECT count(*) FROM job_queue WHERE status<>'SUCCEEDED'")>0 && System.nanoTime()<deadline){
                assertThat(count("SELECT count(*) FROM job_queue WHERE status='DEAD'")).as("no accepted delivery may exhaust recovery").isZero();Thread.sleep(250);
            }
            long receipts=count("SELECT count(*) FROM github_deliveries WHERE event_type<>'reconcile'");
            long jobs=count("SELECT count(*) FROM job_queue"),completed=count("SELECT count(*) FROM job_queue WHERE status='SUCCEEDED'");
            long lost=count("SELECT count(*) FROM github_deliveries WHERE status<>'PROCESSED'");
            long changes=count("SELECT count(*) FROM changes");
            long duplicateEffects=count("SELECT count(*) FROM (SELECT repository_id,provider_change_id,head_sha FROM changes GROUP BY 1,2,3 HAVING count(*)>1) d")
                    +count("SELECT abs((SELECT count(*) FROM outbox_events WHERE event_type='change.normalized')-(SELECT count(*) FROM changes))");
            assertThat(receipts).isEqualTo(6000);assertThat(jobs).isEqualTo(6001);assertThat(completed).isEqualTo(jobs);
            assertThat(lost).isZero();assertThat(duplicateEffects).isZero();assertThat(changes).isEqualTo(5000);
            assertThat(count("SELECT count(*) FROM change_current_revisions WHERE head_sha<>'"+LocalGitHub.B+"'")).isZero();
            assertThat(GITHUB.authenticationViolations).hasValue(0);
            latencies.sort(Long::compare);assertThat(latencies).hasSize(10000);
            var results=new LinkedHashMap<String,Object>();results.put("deliveries_sent",10000);results.put("http_accepted",10000);results.put("verified",10000);
            results.put("invalid_population",1000);results.put("invalid_rejected",1000);results.put("invalid_signatures_accepted",0);results.put("unique_delivery_ids",receipts);
            results.put("duplicates_sent",4000);results.put("duplicates_suppressed",4000);results.put("jobs_created",jobs);results.put("jobs_completed",completed);
            results.put("semantic_changes",changes);results.put("lost_accepted_deliveries",lost);results.put("duplicate_semantic_effects",duplicateEffects);
            results.put("valid_verified",10000);results.put("duplicate_deliveries",4000);
            results.put("ingress_p50_ms",latencies.get(4999)/1_000_000d);results.put("ingress_p95_ms",latencies.get(9499)/1_000_000d);
            results.put("elapsed_seconds",(System.nanoTime()-start)/1_000_000_000d);results.put("crash_after_durable_acceptances",5000);
            results.put("processing_failures",count("SELECT count(*) FROM job_queue WHERE last_error_code IS NOT NULL"));
            verifyPlatformAfterReplay(tenant);
            results.put("platform_integrity_after_replay","PASS");
            results.put("elapsed_seconds",(System.nanoTime()-start)/1_000_000_000d);
            ProofEvidence.write("phase2-replay",results);
        }
    }
    void verifyPlatformAfterReplay(UUID tenant)throws Exception {
        assertThat(count("SELECT count(*) FROM job_queue WHERE status IN ('READY','RUNNING') OR lease_token IS NOT NULL")).isZero();
        assertThat(count("SELECT count(*) FROM outbox_events WHERE lock_token IS NOT NULL")).isZero();
        try(var app=new org.springframework.boot.builder.SpringApplicationBuilder(io.guidein.GuideInApplication.class).run(
                "--server.port=0","--spring.profiles.active=test","--guidein.github.enabled=false",
                "--spring.datasource.url="+POSTGRES.getJdbcUrl(),"--spring.datasource.username=guidein_app","--spring.datasource.password=guidein-app-test",
                "--spring.datasource.hikari.maximum-pool-size=1","--spring.flyway.url="+POSTGRES.getJdbcUrl(),"--spring.flyway.user=guidein_migrator","--spring.flyway.password=guidein-migrator-test",
                "--guidein.security.issuer-uri="+OIDC.issuer(),"--guidein.security.jwk-set-uri="+OIDC.issuer()+"/jwks")) {
            var transactions=new org.springframework.transaction.support.TransactionTemplate(app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            var context=app.getBean(io.guidein.platform.api.TenantContext.class);
            var audit=app.getBean(io.guidein.audit.api.AuditLedger.class);
            Boolean auditValid=transactions.execute(status->{context.setTenant(tenant);return audit.verify(tenant).valid();});
            assertThat(auditValid).isTrue();
            var dispatcher=app.getBean(io.guidein.events.api.OutboxDispatcher.class);
            while(dispatcher.dispatchNext(tenant,event->{})) { }
            assertThat(count("SELECT count(*) FROM outbox_events WHERE published_at IS NULL OR lock_token IS NOT NULL")).isZero();
            var jdbc=app.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
            UUID other=UUID.randomUUID();
            for(int i=0;i<1000;i++) {
                UUID selected=i%2==0?tenant:other;
                int visible=transactions.execute(status->{context.setTenant(selected);return jdbc.queryForObject("SELECT count(*) FROM changes",Integer.class);});
                assertThat(visible).isEqualTo(selected.equals(tenant)?5000:0);
            }
            assertThatThrownBy(()->transactions.execute(status->jdbc.queryForObject("SELECT count(*) FROM changes",Integer.class))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }
}
