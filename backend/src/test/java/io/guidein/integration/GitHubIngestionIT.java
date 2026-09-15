package io.guidein.integration;

import static org.assertj.core.api.Assertions.*;
import io.guidein.github.api.GitHubIntegration;
import io.guidein.github.infrastructure.client.GitHubProviderClient;
import io.guidein.identity.api.AuthenticatedSubject;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "guidein.github.enabled=true","guidein.github.worker-enabled=false","guidein.github.allow-loopback-test=true",
        "guidein.github.client-id=proof-client","guidein.github.client-secret=CLIENT_SECRET_CANARY","guidein.github.app-slug=guidein-proof",
        "guidein.github.callback-url=https://guidein.example.test/github/callback","guidein.github.webhook-secret=WEBHOOK_SECRET_CANARY_32_BYTES_LONG",
        "spring.datasource.hikari.maximum-pool-size=12","guidein.github.webhook.max-payload-bytes=4096"})
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class GitHubIngestionIT {
    static final LocalGitHub GITHUB=new LocalGitHub();
    static final LocalOidc OIDC=new LocalOidc();
    static final String SECRET="WEBHOOK_SECRET_CANARY_32_BYTES_LONG";
    static final UUID TENANT=UUID.fromString("20000000-0000-0000-0000-000000000001"),OTHER=UUID.fromString("20000000-0000-0000-0000-000000000002"),USER=UUID.fromString("10000000-0000-0000-0000-000000000001");
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:18.6-alpine").withDatabaseName("guidein_phase2")
            .withUsername("postgres").withPassword("postgres").withInitScript("postgres-test-init.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",()->"guidein_app");r.add("spring.datasource.password",()->"guidein-app-test");
        r.add("spring.flyway.url",POSTGRES::getJdbcUrl);r.add("spring.flyway.user",()->"guidein_migrator");r.add("spring.flyway.password",()->"guidein-migrator-test");
        r.add("guidein.security.issuer-uri",OIDC::issuer);r.add("guidein.security.jwk-set-uri",()->OIDC.issuer()+"/jwks");
        r.add("guidein.github.api-origin",GITHUB::origin);r.add("guidein.github.oauth-origin",GITHUB::origin);r.add("guidein.github.private-key-path",()->GITHUB.keyPath.toString());
    }
    @Autowired GitHubIntegration integration;
    @Autowired GitHubProviderClient provider;
    @Value("${local.server.port}") int port;
    final HttpClient http=HttpClient.newHttpClient();
    final JsonMapper mapper=JsonMapper.builder().build();
    final AuthenticatedSubject subject=new AuthenticatedSubject(USER,OIDC.issuer(),"owner");
    @BeforeEach void reset() throws Exception {
        GITHUB.reset();provider.evict(101);
        sql("TRUNCATE users,tenants,github_deliveries CASCADE");
        sql("INSERT INTO users(id,auth_issuer,external_subject,status) VALUES ('"+USER+"','"+OIDC.issuer()+"','owner','ACTIVE')");
        for(UUID tenant:List.of(TENANT,OTHER))sql("INSERT INTO tenants(id,slug,name,status) VALUES ('"+tenant+"','"+tenant+"','proof','ACTIVE')");
        sql("INSERT INTO memberships(id,tenant_id,user_id,role,scope_mode) VALUES (gen_random_uuid(),'"+TENANT+"','"+USER+"','OWNER','ALL_REPOSITORIES')");
    }
    @AfterAll static void closeFixtures(){GITHUB.close();OIDC.close();}
    void sql(String sql) throws Exception {try(var c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"postgres","postgres");var s=c.createStatement()){s.execute(sql);}}
    int count(String table) throws Exception {return number("SELECT count(*) FROM "+table);}
    int number(String sql) throws Exception {try(var c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"postgres","postgres");var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getInt(1);}}
    String text(String sql) throws Exception {try(var c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"postgres","postgres");var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getString(1);}}
    void bind() {
        var preparation=integration.prepare(subject,TENANT);
        integration.bind(subject,TENANT,preparation.state(),preparation.verifier(),"proof-code",101);
        assertThat(integration.processNext(TENANT)).isTrue();
    }
    byte[] pr(int number){return mapper.writeValueAsBytes(Map.of("installation",Map.of("id",101),"repository",Map.of("id",501),"number",number,"action","synchronize","sender",Map.of("login","untrusted")));}
    String sign(byte[] raw) throws Exception {
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return "sha256="+HexFormat.of().formatHex(mac.doFinal(raw));
    }
    HttpResponse<String> post(byte[] raw,String event,UUID delivery,String signature) throws Exception {
        return postAt(port,raw,event,delivery,signature);
    }
    HttpResponse<String> postAt(int targetPort,byte[] raw,String event,UUID delivery,String signature) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+targetPort+"/api/v1/webhooks/github"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").header("X-GitHub-Event",event)
                .header("X-GitHub-Delivery",delivery.toString()).header("X-GitHub-Hook-ID","1");
        if(signature!=null)request.header("X-Hub-Signature-256",signature);
        return http.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(raw)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void signedHttpReceiptHydratesOnlyCanonicalFactsAndCommitsProvenance() throws Exception {
        bind();byte[] raw=pr(7);
        var accepted=post(raw,"pull_request",UUID.randomUUID(),sign(raw));
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(count("changes")).isZero();
        assertThat(integration.processNext(TENANT)).isTrue();
        assertThat(count("changes")).isEqualTo(1);assertThat(count("change_files")).isEqualTo(1);assertThat(count("provenance_records")).isEqualTo(1);assertThat(count("ci_observations")).isEqualTo(2);
        assertThat(text("SELECT head_sha FROM changes")).isEqualTo(LocalGitHub.A);
        assertThat(text("SELECT previous_path FROM change_files")).isEqualTo("old/file0.java");
        assertThat(text("SELECT actor_external_id FROM provenance_records")).isEqualTo("42");
        assertThat(number("SELECT count(*) FROM job_queue WHERE status<>'SUCCEEDED'")).isZero();
    }
    @Test void invalidSignatureNeverPersistsReceiptOrDomain() throws Exception {
        byte[] raw=pr(7);assertThat(post(raw,"pull_request",UUID.randomUUID(),"sha256="+"0".repeat(64)).statusCode()).isEqualTo(401);
        assertThat(count("github_deliveries")).isZero();assertThat(count("changes")).isZero();
        assertThat(GITHUB.requests).hasValue(0);
    }
    @Test void spoofedBindingAndReusedStateAreDenied() throws Exception {
        var first=integration.prepare(subject,TENANT);
        assertThatThrownBy(()->integration.bind(subject,OTHER,first.state(),first.verifier(),"code",101)).isInstanceOf(RuntimeException.class);
        GITHUB.association=false;
        assertThatThrownBy(()->integration.bind(subject,TENANT,first.state(),first.verifier(),"code",101)).isInstanceOf(RuntimeException.class);
        assertThat(count("github_installation_routes")).isZero();
        GITHUB.association=true;
        assertThatThrownBy(()->integration.bind(subject,TENANT,first.state(),first.verifier(),"code",101)).isInstanceOf(RuntimeException.class);
        bind();assertThat(count("github_installation_routes")).isEqualTo(1);
    }
    @Test void concurrentDuplicatesHaveOneProcessingIntentAndOneRevision() throws Exception {
        bind();byte[] raw=pr(7);String signature=sign(raw);UUID delivery=UUID.randomUUID();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(100)) {
            List<java.util.concurrent.Future<Integer>> requests=new ArrayList<>();
            for(int i=0;i<100;i++)requests.add(executor.submit(()->post(raw,"pull_request",delivery,signature).statusCode()));
            for(var request:requests)assertThat(request.get()).isEqualTo(200);
        }
        assertThat(count("github_deliveries")).isEqualTo(2);assertThat(count("job_queue")).isEqualTo(2);
        assertThat(integration.processNext(TENANT)).isTrue();assertThat(integration.processNext(TENANT)).isFalse();
        assertThat(count("changes")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM outbox_events WHERE event_type='change.normalized'")).isEqualTo(1);
    }
    @Test void revisionsPreserveHistoryAndDelayedNotificationCannotRegressHead() throws Exception {
        bind();byte[] raw=pr(7);
        for(String head:List.of(LocalGitHub.A,LocalGitHub.B,LocalGitHub.B)) {
            GITHUB.head=head;assertThat(post(raw,"pull_request",UUID.randomUUID(),sign(raw)).statusCode()).isEqualTo(200);
            assertThat(integration.processNext(TENANT)).isTrue();
        }
        assertThat(count("changes")).isEqualTo(2);assertThat(text("SELECT head_sha FROM change_current_revisions")).isEqualTo(LocalGitHub.B);
    }
    @Test void giantFileSetIsExplicitlyIncomplete() throws Exception {
        bind();GITHUB.fileCount=3001;byte[] raw=pr(7);
        assertThat(post(raw,"pull_request",UUID.randomUUID(),sign(raw)).statusCode()).isEqualTo(200);
        assertThat(integration.processNext(TENANT)).isTrue();
        assertThat(count("change_files")).isEqualTo(3000);
        assertThat(text("SELECT file_set_status FROM changes")).isEqualTo("INCOMPLETE_PROVIDER_LIMIT");
    }
    @Test void suspensionDeniesPendingProviderWorkAndPreservesHistory() throws Exception {
        bind();byte[] raw=pr(7);post(raw,"pull_request",UUID.randomUUID(),sign(raw));integration.processNext(TENANT);
        post(raw,"pull_request",UUID.randomUUID(),sign(raw));
        byte[] suspension=mapper.writeValueAsBytes(Map.of("installation",Map.of("id",101),"action","suspend"));GITHUB.suspended=true;
        assertThat(post(suspension,"installation",UUID.randomUUID(),sign(suspension)).statusCode()).isEqualTo(200);
        int before=GITHUB.requests.get();integration.processNext(TENANT);
        assertThat(GITHUB.requests).hasValue(before);assertThat(count("changes")).isEqualTo(1);
        assertThat(text("SELECT status FROM github_installations")).isEqualTo("SUSPENDED");
    }
    @Test void rateLimitIsDurableAndNoPartialChangeCommits() throws Exception {
        bind();GITHUB.failures.put("/repositories/501",429);byte[] raw=pr(7);post(raw,"pull_request",UUID.randomUUID(),sign(raw));
        integration.processNext(TENANT);assertThat(count("changes")).isZero();
        assertThat(number("SELECT count(*) FROM job_queue WHERE status='READY' AND available_at>clock_timestamp()+interval '110 seconds'")).isEqualTo(1);
        int before=GITHUB.requests.get();assertThat(integration.processNext(TENANT)).isFalse();assertThat(GITHUB.requests).hasValue(before);
    }
    @Test void rawHttpSignatureAdversariesCannotCreateTrustedState() throws Exception {
        byte[] original=pr(7);
        for(String signature:Arrays.asList(null,"","forged","sha256=abc","sha256="+"0".repeat(64),"sha1="+"0".repeat(40),sign(original).substring(0,60))) {
            assertThat(post(original,"pull_request",UUID.randomUUID(),signature).statusCode()).as("signature rejection").isEqualTo(401);
        }
        byte[] spaced=(new String(original,StandardCharsets.UTF_8)+" ").getBytes(StandardCharsets.UTF_8);
        assertThat(mapper.readTree(spaced)).isEqualTo(mapper.readTree(original));
        assertThat(post(spaced,"pull_request",UUID.randomUUID(),sign(original)).statusCode()).isEqualTo(401);
        assertThat(post(pr(8),"pull_request",UUID.randomUUID(),sign(original)).statusCode()).isEqualTo(401);
        assertThat(count("github_deliveries")).isZero();assertThat(count("job_queue")).isZero();
        assertThat(count("changes")).isZero();assertThat(count("provenance_records")).isZero();assertThat(count("ci_observations")).isZero();
        byte[] unicode="{\"extra\":\"नमस्ते 🌏\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(post(unicode,"future_event",UUID.randomUUID(),sign(unicode)).statusCode()).isEqualTo(200);
        assertThat(text("SELECT status FROM github_deliveries")).isEqualTo("IGNORED");
        assertThat(GITHUB.requests).hasValue(0);
    }
    @Test void httpBodyBoundariesRejectOversizedFixedAndChunkedRequests() throws Exception {
        for(int size:List.of(4095,4096,4097)) {
            byte[] raw=("{}"+" ".repeat(size-2)).getBytes(StandardCharsets.UTF_8);
            assertThat(post(raw,"future_event",UUID.randomUUID(),sign(raw)).statusCode()).isEqualTo(size<=4096?200:413);
        }
        byte[] oversized=("{}"+" ".repeat(4095)).getBytes(StandardCharsets.UTF_8);
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/webhooks/github"))
                .header("X-GitHub-Event","future_event").header("X-GitHub-Delivery",UUID.randomUUID().toString())
                .header("X-GitHub-Hook-ID","1").header("X-Hub-Signature-256",sign(oversized))
                .POST(HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(oversized))).build();
        assertThat(http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(413);
        assertThat(count("github_deliveries")).isEqualTo(2);assertThat(count("changes")).isZero();assertThat(count("job_queue")).isZero();
    }
    @Test void additiveFieldsAndUnknownActionsRemainSafe() throws Exception {
        bind();
        byte[] unknown=mapper.writeValueAsBytes(Map.of("installation",Map.of("id",101),"repository",Map.of("id",501),"action","future_action","number",7));
        assertThat(post(unknown,"pull_request",UUID.randomUUID(),sign(unknown)).statusCode()).isEqualTo(200);
        assertThat(integration.processNext(TENANT)).isFalse();assertThat(count("changes")).isZero();assertThat(count("provenance_records")).isZero();assertThat(count("ci_observations")).isZero();
        byte[] additive=mapper.writeValueAsBytes(Map.of("installation",Map.of("id",101,"future","ignored"),"repository",Map.of("id",501,"extra",Map.of("tenant_id",OTHER)),
                "number",7,"action","synchronize","future_field",Map.of("authorized",true)));
        assertThat(post(additive,"pull_request",UUID.randomUUID(),sign(additive)).statusCode()).isEqualTo(200);
        integration.processNext(TENANT);assertThat(count("changes")).isEqualTo(1);
        assertThat(text("SELECT tenant_id FROM changes")).isEqualTo(TENANT.toString());
    }
    @Test void expiredWrongUserAndSpoofedInstallationStatesCannotBind() throws Exception {
        var expired=integration.prepare(subject,TENANT);
        sql("UPDATE github_binding_states SET expires_at=clock_timestamp()-interval '1 second'");
        assertThatThrownBy(()->integration.bind(subject,TENANT,expired.state(),expired.verifier(),"code",101)).isInstanceOf(RuntimeException.class);
        UUID otherUser=UUID.randomUUID();
        sql("INSERT INTO users(id,auth_issuer,external_subject,status) VALUES ('"+otherUser+"','"+OIDC.issuer()+"','other','ACTIVE')");
        sql("INSERT INTO memberships(id,tenant_id,user_id,role,scope_mode) VALUES (gen_random_uuid(),'"+TENANT+"','"+otherUser+"','OWNER','ALL_REPOSITORIES')");
        var prepared=integration.prepare(subject,TENANT);
        var wrong=new AuthenticatedSubject(otherUser,OIDC.issuer(),"other");
        assertThatThrownBy(()->integration.bind(wrong,TENANT,prepared.state(),prepared.verifier(),"code",101)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->integration.bind(subject,TENANT,prepared.state(),prepared.verifier(),"code",999)).isInstanceOf(RuntimeException.class);
        assertThat(count("github_installation_routes")).isZero();assertThat(count("github_installations")).isZero();assertThat(count("job_queue")).isZero();
    }
    @Test void processingIntentFailureRollsBackReceiptAndRedeliveryRecovers() throws Exception {
        bind();byte[] raw=pr(7);UUID delivery=UUID.randomUUID();
        // Fault injection only: deny the runtime job INSERT, without changing assertions or migrations.
        sql("REVOKE INSERT ON job_queue FROM guidein_app");
        try {
            assertThat(post(raw,"pull_request",delivery,sign(raw)).statusCode()).isBetween(400,599);
            assertThat(count("github_deliveries")).isEqualTo(1);assertThat(count("job_queue")).isEqualTo(1);
            assertThat(count("changes")).isZero();assertThat(count("provenance_records")).isZero();
        } finally {sql("GRANT INSERT ON job_queue TO guidein_app");}
        assertThat(post(raw,"pull_request",delivery,sign(raw)).statusCode()).isEqualTo(200);
        integration.processNext(TENANT);assertThat(count("changes")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM github_deliveries WHERE status<>'PROCESSED'")).isZero();
    }
    @Test void providerServerErrorsRemainDurableWithBoundedRetryAndNoPartialState() throws Exception {
        bind();
        for(int status:List.of(500,502,503)) {
            GITHUB.failures.put("/repos/org/repo/commits/"+LocalGitHub.A,status);
            byte[] raw=pr(status);assertThat(post(raw,"pull_request",UUID.randomUUID(),sign(raw)).statusCode()).isEqualTo(200);
            assertThat(integration.processNext(TENANT)).isTrue();
            assertThat(count("changes")).isZero();assertThat(count("change_files")).isZero();assertThat(count("provenance_records")).isZero();assertThat(count("ci_observations")).isZero();
        }
        assertThat(number("SELECT count(*) FROM job_queue WHERE status='READY' AND attempt_count=1 AND available_at>clock_timestamp() AND max_attempts=5")).isEqualTo(3);
        assertThat(number("SELECT count(*) FROM github_deliveries WHERE status='RETRYABLE_FAILURE'")).isEqualTo(3);
    }
    @Test void repositoryRenamePreservesIdentity() throws Exception {
        bind();String repository=text("SELECT id FROM repositories");GITHUB.repositoryName="renamed";
        byte[] raw=pr(7);assertThat(post(raw,"pull_request",UUID.randomUUID(),sign(raw)).statusCode()).isEqualTo(200);
        integration.processNext(TENANT);assertThat(count("changes")).isEqualTo(1);
        assertThat(count("repositories")).isEqualTo(1);assertThat(text("SELECT id FROM repositories")).isEqualTo(repository);
        assertThat(text("SELECT name FROM repositories")).isEqualTo("renamed");
    }
    @Test void removedRepositoryCannotHydratePendingWork() throws Exception {
        bind();byte[] raw=pr(7);post(raw,"pull_request",UUID.randomUUID(),sign(raw));integration.processNext(TENANT);
        post(pr(8),"pull_request",UUID.randomUUID(),sign(pr(8)));
        GITHUB.access=false;
        byte[] removal=mapper.writeValueAsBytes(Map.of("installation",Map.of("id",101),"action","removed"));
        assertThat(post(removal,"installation_repositories",UUID.randomUUID(),sign(removal)).statusCode()).isEqualTo(200);
        int before=GITHUB.requests.get();integration.processNext(TENANT);assertThat(GITHUB.requests).hasValue(before);
        assertThat(count("changes")).isEqualTo(1);assertThat(count("provenance_records")).isEqualTo(1);
        assertThat(text("SELECT status FROM github_installation_repositories")).isEqualTo("ACCESS_REMOVED");
        integration.processNext(TENANT);assertThat(count("changes")).isEqualTo(1);
        assertThat(text("SELECT status FROM github_installation_repositories")).isEqualTo("ACCESS_REMOVED");
    }
    @Test void suspensionUnsuspensionAndDeletionRequireCanonicalAuthority() throws Exception {
        bind();byte[] pr=pr(7);post(pr,"pull_request",UUID.randomUUID(),sign(pr));integration.processNext(TENANT);
        GITHUB.suspended=true;
        byte[] suspended=mapper.writeValueAsBytes(Map.of("installation",Map.of("id",101),"action","suspend"));
        post(suspended,"installation",UUID.randomUUID(),sign(suspended));integration.processNext(TENANT);
        assertThat(text("SELECT status FROM github_installations")).isEqualTo("SUSPENDED");
        GITHUB.suspended=false;
        byte[] active=mapper.writeValueAsBytes(Map.of("installation",Map.of("id",101),"action","unsuspend"));
        post(active,"installation",UUID.randomUUID(),sign(active));integration.processNext(TENANT);
        assertThat(text("SELECT status FROM github_installations")).isEqualTo("ACTIVE");
        assertThat(GITHUB.tokenRequests.get()).isGreaterThanOrEqualTo(2);
        GITHUB.deleted=true;
        byte[] deleted=mapper.writeValueAsBytes(Map.of("installation",Map.of("id",101),"action","deleted"));
        post(deleted,"installation",UUID.randomUUID(),sign(deleted));integration.processNext(TENANT);
        int requests=GITHUB.requests.get(),tokens=GITHUB.tokenRequests.get();
        post(pr(8),"pull_request",UUID.randomUUID(),sign(pr(8)));integration.processNext(TENANT);
        assertThat(GITHUB.requests).hasValue(requests);assertThat(GITHUB.tokenRequests).hasValue(tokens);
        assertThat(text("SELECT status FROM github_installations")).isEqualTo("DELETED");
        assertThat(count("changes")).isEqualTo(1);assertThat(count("provenance_records")).isEqualTo(1);
    }
    @Test void firstUnauthorizedResponseRefreshesTokenOnceAndSecondFails() throws Exception {
        bind();int minted=GITHUB.tokenRequests.get();
        GITHUB.scriptedStatuses.put("/repositories/501",new java.util.concurrent.ConcurrentLinkedQueue<>(List.of(401,200)));
        assertThat(provider.get(101,1,"/repositories/501").json().path("id").asLong()).isEqualTo(501);
        assertThat(GITHUB.tokenRequests).hasValue(minted+1);
        GITHUB.scriptedStatuses.put("/repositories/501",new java.util.concurrent.ConcurrentLinkedQueue<>(List.of(401,401,200)));
        int before=Collections.frequency(GITHUB.paths,"/repositories/501");
        assertThatThrownBy(()->provider.get(101,1,"/repositories/501"))
                .isInstanceOf(io.guidein.github.infrastructure.client.ProviderFailure.class)
                .hasMessage("GITHUB_AUTHENTICATION");
        assertThat(Collections.frequency(GITHUB.paths,"/repositories/501")-before).isEqualTo(2);
        assertThat(GITHUB.tokenRequests).hasValue(minted+2);
    }
    @Test void malformedSuccessfulProviderResponseCannotCommitPartialNormalization() throws Exception {
        bind();GITHUB.malformed.add("/repos/org/repo/commits/"+LocalGitHub.A);
        byte[] raw=pr(7);post(raw,"pull_request",UUID.randomUUID(),sign(raw));integration.processNext(TENANT);
        assertThat(count("changes")).isZero();assertThat(count("change_files")).isZero();assertThat(count("provenance_records")).isZero();assertThat(count("ci_observations")).isZero();
        assertThat(number("SELECT count(*) FROM github_deliveries WHERE status='RETRYABLE_FAILURE' AND failure_code='GITHUB_DEPENDENCY_UNAVAILABLE'")).isEqualTo(1);
    }
    @Test void providerReadTimeoutIsBoundedAndAcceptedReceiptSurvives() throws Exception {
        bind();GITHUB.delays.put("/repositories/501",10000L);
        byte[] raw=pr(7);post(raw,"pull_request",UUID.randomUUID(),sign(raw));long start=System.nanoTime();integration.processNext(TENANT);
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(9));
        assertThat(count("changes")).isZero();assertThat(number("SELECT count(*) FROM github_deliveries WHERE status='RETRYABLE_FAILURE'")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM job_queue WHERE status='READY' AND attempt_count=1 AND available_at>clock_timestamp()")).isEqualTo(1);
    }
    @Test void providerConnectionResetPreservesAcceptedReceipt() throws Exception {
        bind();GITHUB.resets.add("/repositories/501");byte[] raw=pr(7);
        assertThat(post(raw,"pull_request",UUID.randomUUID(),sign(raw)).statusCode()).isEqualTo(200);integration.processNext(TENANT);
        assertThat(count("changes")).isZero();assertThat(number("SELECT count(*) FROM github_deliveries WHERE status='RETRYABLE_FAILURE'")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM job_queue WHERE status='READY' AND attempt_count=1 AND available_at>clock_timestamp()")).isEqualTo(1);
    }
    @Test void commitVerificationReasonsAreFactsNotEligibilityDecisions() throws Exception {
        bind();int number=10;
        for(String reason:List.of("valid","unsigned","bad_signature","expired_key","future_reason")) {
            GITHUB.signatureReason=reason;byte[] raw=pr(number++);post(raw,"pull_request",UUID.randomUUID(),sign(raw));integration.processNext(TENANT);
            assertThat(number("SELECT count(*) FROM provenance_records WHERE signature_reason='"+reason+"' AND signature_verified="+reason.equals("valid"))).isEqualTo(1);
        }
        assertThat(count("changes")).isEqualTo(5);assertThat(count("provenance_records")).isEqualTo(5);
    }
    @Test void actorKindsNeverBecomeAuthenticatedGuideInUsers() throws Exception {
        bind();int number=10;
        for(String kind:List.of("User","Bot","App","System","Unknown")) {
            GITHUB.actorKind=kind;byte[] raw=pr(number++);post(raw,"pull_request",UUID.randomUUID(),sign(raw));integration.processNext(TENANT);
            assertThat(number("SELECT count(*) FROM provenance_records WHERE actor_type='"+kind.toUpperCase(Locale.ROOT)+"' AND actor_external_id='42'")).isEqualTo(1);
        }
        GITHUB.actorKind="User";GITHUB.actor="ghost";byte[] raw=pr(number);post(raw,"pull_request",UUID.randomUUID(),sign(raw));integration.processNext(TENANT);
        assertThat(number("SELECT count(*) FROM provenance_records WHERE actor_type='GHOST'")).isEqualTo(1);
        assertThat(count("users")).isEqualTo(1);assertThat(number("SELECT count(*) FROM audit_events WHERE actor_id='"+USER+"'")).isEqualTo(1);
    }
    @Test void fileKindsRetainRenameAndUnknownBinaryMetadata() throws Exception {
        bind();GITHUB.mixedFiles=true;GITHUB.fileCount=5;byte[] raw=pr(7);post(raw,"pull_request",UUID.randomUUID(),sign(raw));integration.processNext(TENANT);
        assertThat(count("change_files")).isEqualTo(5);
        for(String status:List.of("added","modified","removed","renamed"))assertThat(number("SELECT count(*) FROM change_files WHERE status='"+status+"'")).isPositive();
        assertThat(text("SELECT previous_path FROM change_files WHERE status='renamed'")).isEqualTo("old/renamed.java");
        assertThat(number("SELECT count(*) FROM change_files WHERE path='asset.bin' AND additions IS NULL AND deletions IS NULL AND blob_after_sha IS NULL")).isEqualTo(1);
        assertThat(text("SELECT file_set_status FROM changes")).isEqualTo("COMPLETE");
    }
    @Test void ciSourcesAndExactShasRemainIndependentAndCanonical() throws Exception {
        bind();GITHUB.twoCheckSources=true;
        for(String sha:List.of(LocalGitHub.A,LocalGitHub.B)) {
            byte[] raw=mapper.writeValueAsBytes(Map.of("installation",Map.of("id",101),"repository",Map.of("id",501),"sha",sha,"state","pending"));
            post(raw,"status",UUID.randomUUID(),sign(raw));integration.processNext(TENANT);
            assertThat(number("SELECT count(*) FROM ci_observations WHERE head_sha='"+sha+"'")).isEqualTo(3);
            assertThat(number("SELECT count(DISTINCT source_app_id) FROM ci_observations WHERE head_sha='"+sha+"' AND name='unit-tests'")).isEqualTo(2);
        }
        byte[] stale=mapper.writeValueAsBytes(Map.of("installation",Map.of("id",101),"repository",Map.of("id",501),"action","created","check_run",Map.of("head_sha",LocalGitHub.A,"status","in_progress")));
        post(stale,"check_run",UUID.randomUUID(),sign(stale));integration.processNext(TENANT);
        assertThat(number("SELECT count(*) FROM ci_observations WHERE source_kind='CHECK_RUN' AND status<>'completed'")).isZero();
        assertThat(count("ci_observations")).isEqualTo(6);
    }
    @Test void wrongShaProviderCheckCannotBeBoundToRequestedRevision() throws Exception {
        bind();GITHUB.checkSha=LocalGitHub.B;byte[] raw=pr(7);post(raw,"pull_request",UUID.randomUUID(),sign(raw));integration.processNext(TENANT);
        assertThat(count("ci_observations")).isZero();assertThat(count("changes")).isZero();assertThat(count("provenance_records")).isZero();
        assertThat(number("SELECT count(*) FROM github_deliveries WHERE failure_code='GITHUB_STALE_SOURCE'")).isEqualTo(1);
    }
    @Test void crossTenantExternalIdSubstitutionFailsBeforeProviderReadsAndRlsProtectsWrites() throws Exception {
        bind();UUID installation=UUID.randomUUID(),repository=UUID.randomUUID();
        sql("INSERT INTO github_installation_routes(installation_external_id,tenant_id,installation_id) VALUES (202,'"+OTHER+"','"+installation+"')");
        sql("INSERT INTO github_installations(id,tenant_id,installation_external_id,account_external_id,account_login,permissions_json,status) SELECT '"+installation+"','"+OTHER+"',202,22,'other',permissions_json,'ACTIVE' FROM github_installations WHERE tenant_id='"+TENANT+"'");
        sql("INSERT INTO repositories(id,tenant_id,provider,external_id,owner,name,status) VALUES ('"+repository+"','"+OTHER+"','GITHUB','502','other','private','ACTIVE')");
        sql("INSERT INTO github_installation_repositories(tenant_id,installation_id,repository_id,repository_external_id,status,verified_at) VALUES ('"+OTHER+"','"+installation+"','"+repository+"',502,'ACTIVE',clock_timestamp())");
        int requests=GITHUB.requests.get();
        for(long[] substitution:List.of(new long[]{101,502},new long[]{202,501})) {
            byte[] raw=mapper.writeValueAsBytes(Map.of("installation",Map.of("id",substitution[0]),"repository",Map.of("id",substitution[1]),"number",999,"action","opened"));
            post(raw,"pull_request",UUID.randomUUID(),sign(raw));
        }
        integration.processNext(TENANT);assertThat(integration.processNext(TENANT)).isFalse();integration.processNext(OTHER);
        assertThat(GITHUB.requests).hasValue(requests);assertThat(count("changes")).isZero();assertThat(count("provenance_records")).isZero();assertThat(count("ci_observations")).isZero();
        byte[] legitimate=pr(7);post(legitimate,"pull_request",UUID.randomUUID(),sign(legitimate));integration.processNext(TENANT);
        assertThat(count("changes")).isEqualTo(1);
        try(var connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"guidein_app","guidein-app-test")) {
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement()) {
                statement.execute("SELECT set_config('guidein.tenant_id','"+TENANT+"',true)");
                try(var rows=statement.executeQuery("SELECT count(*) FROM github_installations WHERE tenant_id='"+OTHER+"'")){rows.next();assertThat(rows.getInt(1)).isZero();}
                assertThatThrownBy(()->statement.execute("INSERT INTO changes(id,tenant_id,repository_id,provider,provider_change_id,change_type,head_sha,state,fetched_file_count,file_set_status,normalized_at) VALUES (gen_random_uuid(),'"+TENANT+"','"+repository+"','GITHUB','pr:1','PULL_REQUEST','"+LocalGitHub.A+"','open',0,'UNKNOWN',clock_timestamp())")).isInstanceOf(SQLException.class);
            } finally {connection.rollback();}
            for(String table:List.of("github_installations","github_installation_repositories","changes","change_files","change_current_revisions","provenance_records","ci_observations")) {
                try(var statement=connection.createStatement()) {
                    assertThatThrownBy(()->statement.executeQuery("SELECT count(*) FROM "+table))
                            .as("missing context: "+table).isInstanceOfSatisfying(SQLException.class,error->assertThat(error.getSQLState()).isEqualTo("28000"));
                }
                connection.rollback();
            }
        }
    }
    @Test void acceptedDeliverySurvivesActualApplicationProcessDeathAndRestart() throws Exception {
        bind();byte[] raw=pr(7);UUID delivery=UUID.randomUUID();
        List<java.nio.file.Path> processLogs=new ArrayList<>();
        try(var ingress=new ForkedGuideIn(POSTGRES.getJdbcUrl(),GITHUB,OIDC,false)) {
            processLogs.add(ingress.log);
            assertThat(postAt(ingress.port,raw,"pull_request",delivery,sign(raw)).statusCode()).isEqualTo(200);
            assertThat(number("SELECT count(*) FROM github_deliveries WHERE external_delivery_id='"+delivery+"' AND status='QUEUED'")).isEqualTo(1);
            assertThat(number("SELECT count(*) FROM job_queue WHERE status='READY'")).isEqualTo(1);assertThat(count("changes")).isZero();
            ingress.crash();
        }
        try(var restarted=new ForkedGuideIn(POSTGRES.getJdbcUrl(),GITHUB,OIDC,true)) {
            processLogs.add(restarted.log);
            long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            while(count("changes")==0 && System.nanoTime()<deadline)Thread.sleep(100);
            assertThat(count("changes")).isEqualTo(1);
            assertThat(number("SELECT count(*) FROM github_deliveries WHERE external_delivery_id='"+delivery+"' AND status='PROCESSED'")).isEqualTo(1);
            assertThat(number("SELECT count(*) FROM job_queue WHERE status<>'SUCCEEDED'")).isZero();
            assertThat(postAt(restarted.port,raw,"pull_request",delivery,sign(raw)).statusCode()).isEqualTo(200);
            assertThat(count("changes")).isEqualTo(1);assertThat(count("job_queue")).isEqualTo(2);
            var error=postAt(restarted.port,raw,"pull_request",UUID.randomUUID(),"sha256="+"0".repeat(64));
            assertThat(error.statusCode()).isEqualTo(401);
            Thread.sleep(500); // Let the proof exporter flush the completed HTTP/worker spans.
            String logs=error.body();for(var file:processLogs)logs+=java.nio.file.Files.readString(file);
            assertThat(logs).contains("LoggingSpanExporter","GitHubHydrator.processNext","github_delivery_processed");
            List<String> secrets=new ArrayList<>(List.of(SECRET,"CLIENT_SECRET_CANARY","GITHUB_USER_TOKEN_CANARY","-----BEGIN PRIVATE KEY-----",Base64.getEncoder().encodeToString(GITHUB.key.getPrivate().getEncoded()).substring(0,64)));
            for(String authorization:GITHUB.credentials){secrets.add(authorization);secrets.add(authorization.substring(authorization.indexOf(' ')+1));}
            for(String secret:secrets)assertThat(logs.contains(secret)).as("process logs, error response and exported spans must exclude credentials").isFalse();
            ProofEvidence.write("phase2-secret-process",Map.of("exported_spans_scanned",true,"error_responses_scanned",true,"secret_leaks",0));
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void primaryAndSecondary403ScheduleDurableRetryWithoutBusyWorker(boolean secondary) throws Exception {
        bind();GITHUB.failures.put("/repositories/501",403);GITHUB.secondaryRateLimit=secondary;GITHUB.rateReset=Instant.now().plusSeconds(150);
        byte[] raw=pr(7);post(raw,"pull_request",UUID.randomUUID(),sign(raw));Instant before=Instant.now();integration.processNext(TENANT);
        Instant next=Instant.ofEpochMilli((long)(Double.parseDouble(text("SELECT extract(epoch FROM available_at) FROM job_queue WHERE status='READY'"))*1000));
        assertThat(next).isAfter(before.plusSeconds(secondary?59:145));
        assertThat(next).isBefore(before.plusSeconds(secondary?70:160));
        int requests=GITHUB.requests.get();assertThat(integration.processNext(TENANT)).isFalse();assertThat(GITHUB.requests).hasValue(requests);
        assertThat(count("changes")).isZero();
        ProofEvidence.write("phase2-rate-"+(secondary?"secondary":"primary"),Map.of("observed_at",before.toString(),"available_at",next.toString(),"busy_retries",0));
    }
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void workflowTelemetryConnectsReceiptJobProviderAndNormalizationWithoutSecrets(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        bind();byte[] raw=pr(7);UUID external=UUID.randomUUID();var response=post(raw,"pull_request",external,sign(raw));
        assertThat(response.statusCode()).isEqualTo(200);integration.processNext(TENANT);
        String receipt=text("SELECT id FROM github_deliveries WHERE external_delivery_id='"+external+"'");
        String correlation=response.headers().firstValue("X-Correlation-Id").orElseThrow();
        assertThat(number("SELECT count(*) FROM job_queue WHERE correlation_id='"+correlation+"' AND causation_id='"+receipt+"' AND status='SUCCEEDED'")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM outbox_events WHERE correlation_id='"+correlation+"' AND causation_id='"+receipt+"' AND event_type IN ('change.normalized','provenance.resolved')")).isEqualTo(2);
        assertThat(GITHUB.authenticationViolations).hasValue(0);
        String logs=output.getAll();
        assertThat(logs.lines().anyMatch(line->line.contains("http_request_completed") && line.contains(correlation) && line.contains("trace_id"))).isTrue();
        assertThat(logs.lines().anyMatch(line->line.contains("github_delivery_processed") && line.contains(correlation) && line.contains(receipt) && line.contains("trace_id"))).isTrue();
        String durable=text("SELECT coalesce(jsonb_agg(to_jsonb(a))::text,'[]') FROM audit_events a")
                +text("SELECT coalesce(jsonb_agg(to_jsonb(o))::text,'[]') FROM outbox_events o")
                +text("SELECT coalesce(jsonb_agg(to_jsonb(j))::text,'[]') FROM job_queue j");
        List<String> secrets=new ArrayList<>(List.of(SECRET,"CLIENT_SECRET_CANARY","GITHUB_USER_TOKEN_CANARY","-----BEGIN PRIVATE KEY-----",Base64.getEncoder().encodeToString(GITHUB.key.getPrivate().getEncoded()).substring(0,64)));
        for(String authorization:GITHUB.credentials){secrets.add(authorization);secrets.add(authorization.substring(authorization.indexOf(' ')+1));}
        for(String secret:secrets)assertThat((logs+durable).contains(secret)).as("credential must not reach logs or durable diagnostics").isFalse();
        ProofEvidence.write("phase2-observability",Map.of("correlation_id",correlation,"causation_id",receipt,"secret_leaks",0));
    }
}
