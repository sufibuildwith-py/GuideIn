package io.guidein.integration;

import io.guidein.github.api.GitHubIntegration;
import io.guidein.github.infrastructure.client.GitHubProviderClient;
import io.guidein.graph.api.GraphBuilds;
import io.guidein.identity.api.AuthenticatedSubject;
import io.guidein.jobs.api.JobQueue;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "guidein.github.enabled=true","guidein.github.worker-enabled=false","guidein.github.allow-loopback-test=true",
        "guidein.graph.enabled=true","guidein.graph.worker-enabled=false",
        "guidein.github.client-id=proof-client","guidein.github.client-secret=CLIENT_SECRET_CANARY","guidein.github.app-slug=guidein-proof",
        "guidein.github.callback-url=https://guidein.example.test/github/callback","guidein.github.webhook-secret=WEBHOOK_SECRET_CANARY_32_BYTES_LONG",
        "spring.datasource.hikari.maximum-pool-size=12","guidein.jobs.lease-duration=3s"})
@ActiveProfiles("test") @Testcontainers @Tag("integration")
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class GraphPersistenceIT {
    static final LocalGitHub GITHUB=new LocalGitHub(); static final LocalOidc OIDC=new LocalOidc();
    static final UUID TENANT=UUID.fromString("30000000-0000-0000-0000-000000000001"), OTHER=UUID.fromString("30000000-0000-0000-0000-000000000002"), USER=UUID.fromString("10000000-0000-0000-0000-000000000001");
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:18.6-alpine").withDatabaseName("guidein_phase3")
            .withUsername("postgres").withPassword("postgres").withInitScript("postgres-test-init.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",()->"guidein_app");r.add("spring.datasource.password",()->"guidein-app-test");
        r.add("spring.flyway.url",POSTGRES::getJdbcUrl);r.add("spring.flyway.user",()->"guidein_migrator");r.add("spring.flyway.password",()->"guidein-migrator-test");
        r.add("guidein.security.issuer-uri",OIDC::issuer);r.add("guidein.security.jwk-set-uri",()->OIDC.issuer()+"/jwks");
        r.add("guidein.github.api-origin",GITHUB::origin);r.add("guidein.github.oauth-origin",GITHUB::origin);r.add("guidein.github.private-key-path",()->GITHUB.keyPath.toString());
    }
    @Autowired GraphBuilds builds; @Autowired GitHubIntegration integration; @Autowired GitHubProviderClient provider; @Autowired JobQueue jobs;
    @Autowired io.guidein.graph.api.SystemGraph graph;
    @Autowired io.guidein.graph.application.GraphStore graphStore;
    @Autowired io.guidein.platform.api.CanonicalJson canonical;
    @Autowired io.guidein.tenancy.api.RepositoryQuery repositoryQueries;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;
    @Autowired io.guidein.events.api.OutboxDispatcher dispatcher;
    @Autowired io.guidein.github.api.RepositoryMaterialSource materialSource;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    final AuthenticatedSubject subject=new AuthenticatedSubject(USER,OIDC.issuer(),"owner");
    UUID repository;
    long testStarted;
    @org.junit.jupiter.api.extension.RegisterExtension final org.junit.jupiter.api.extension.TestWatcher evidence=new org.junit.jupiter.api.extension.TestWatcher() {
        @Override public void testSuccessful(org.junit.jupiter.api.extension.ExtensionContext context){record(context,"PASS","");}
        @Override public void testFailed(org.junit.jupiter.api.extension.ExtensionContext context,Throwable failure){record(context,"FAIL",failure.getClass().getName());}
        private void record(org.junit.jupiter.api.extension.ExtensionContext context,String status,String failure){ProofEvidence.write("graph-test-"+context.getRequiredTestMethod().getName(),Map.of("test",context.getRequiredTestMethod().getName(),"status",status,"duration_ms",(System.nanoTime()-testStarted)/1_000_000.0,"failure_type",failure));}
    };
    @BeforeEach void setup() throws Exception {
        testStarted=System.nanoTime();
        GITHUB.reset();GITHUB.sourceResponses.clear();provider.evict(101);
        sql("TRUNCATE users,tenants,github_deliveries CASCADE");
        sql("INSERT INTO users(id,auth_issuer,external_subject,status) VALUES ('"+USER+"','"+OIDC.issuer()+"','owner','ACTIVE')");
        for(UUID tenant:List.of(TENANT,OTHER))sql("INSERT INTO tenants(id,slug,name,status) VALUES ('"+tenant+"','"+tenant+"','proof','ACTIVE')");
        sql("INSERT INTO memberships(id,tenant_id,user_id,role,scope_mode) VALUES (gen_random_uuid(),'"+TENANT+"','"+USER+"','OWNER','ALL_REPOSITORIES')");
        var preparation=integration.prepare(subject,TENANT);integration.bind(subject,TENANT,preparation.state(),preparation.verifier(),"proof-code",101);
        assertThat(integration.processNext(TENANT)).isTrue(); repository=UUID.fromString(text("SELECT id FROM repositories WHERE tenant_id='"+TENANT+"'"));
        source(Map.of("pom.xml","<project><groupId>demo</groupId><artifactId>app</artifactId><dependencies><dependency><groupId>lib</groupId><artifactId>core</artifactId><version>1</version></dependency></dependencies></project>",
                "src/App.java","package demo; public record App(String value) {}"));
    }
    void source(Map<String,String> files) throws Exception {
        String tree="d".repeat(40);var entries=new ArrayList<Object>();
        for(var entry:new TreeMap<>(files).entrySet()) {
            byte[] bytes=entry.getValue().getBytes(StandardCharsets.UTF_8);var hash=java.security.MessageDigest.getInstance("SHA-1");
            hash.update(("blob "+bytes.length+"\0").getBytes(StandardCharsets.US_ASCII));String sha=HexFormat.of().formatHex(hash.digest(bytes));
            entries.add(Map.of("path",entry.getKey(),"sha",sha,"type","blob","mode","100644","size",bytes.length));
            GITHUB.sourceResponses.put("/repos/org/repo/git/blobs/"+sha,Map.of("sha",sha,"encoding","base64","content",Base64.getEncoder().encodeToString(bytes)));
        }
        GITHUB.sourceResponses.put("/repos/org/repo/git/commits/"+LocalGitHub.A,Map.of("sha",LocalGitHub.A,"tree",Map.of("sha",tree)));
        GITHUB.sourceResponses.put("/repos/org/repo/git/trees/"+tree,Map.of("sha",tree,"truncated",false,"tree",entries));
    }
    String gitBlobSha(byte[] bytes) throws Exception {var hash=java.security.MessageDigest.getInstance("SHA-1");hash.update(("blob "+bytes.length+"\0").getBytes(StandardCharsets.US_ASCII));return HexFormat.of().formatHex(hash.digest(bytes));}
    @AfterAll static void close(){GITHUB.close();OIDC.close();}
    void sql(String sql) throws Exception {try(var c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"postgres","postgres");var s=c.createStatement()){s.execute(sql);}}
    String text(String sql) throws Exception {try(var c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"postgres","postgres");var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getString(1);}}
    int number(String sql) throws Exception {return Integer.parseInt(text(sql));}
    @Test void exactSourceBecomesImmutableSnapshotAndAtomicOutboxEvent() throws Exception {
        var requested=builds.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID()); assertThat(requested.status()).isEqualTo("QUEUED");
        assertThat(builds.processNext(TENANT)).isTrue();
        assertThat(text("SELECT status FROM graph_snapshots")).isEqualTo("READY");
        assertThat(number("SELECT count(*) FROM graph_nodes")).isGreaterThan(4);
        assertThat(number("SELECT count(*) FROM graph_edges e WHERE NOT EXISTS(SELECT 1 FROM graph_edge_evidence v WHERE v.edge_id=e.id)")).isZero();
        assertThat(number("SELECT count(*) FROM outbox_events WHERE event_type='graph.snapshot.created'")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM job_queue WHERE job_type='GRAPH_BUILD' AND status='SUCCEEDED'")).isEqualTo(1);
        assertThat(GITHUB.authenticationViolations).hasValue(0);
        try(var c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"guidein_app","guidein-app-test")) {
            c.setAutoCommit(false);try(var s=c.createStatement()){s.execute("SELECT set_config('guidein.tenant_id','"+TENANT+"',true)");
                assertThatThrownBy(()->s.executeUpdate("UPDATE graph_snapshots SET status='BUILDING'")).isInstanceOf(SQLException.class);}
            c.rollback();
        }
        var reused=builds.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID());assertThat(reused.id()).isEqualTo(requested.id());
        assertThat(number("SELECT count(*) FROM job_queue WHERE job_type='GRAPH_BUILD'")).isEqualTo(1);
    }
    @Test void oneHundredConcurrentRequestsCreateOneLogicalBuild() throws Exception {
        try(var executor=Executors.newFixedThreadPool(12)) {
            var results=new ArrayList<Future<UUID>>();
            for(int i=0;i<100;i++)results.add(executor.submit(()->builds.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID()).id()));
            var identities=new HashSet<UUID>();for(var result:results)identities.add(result.get(60,TimeUnit.SECONDS));assertThat(identities).hasSize(1);
        }
        assertThat(number("SELECT count(*) FROM graph_snapshots")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM job_queue WHERE job_type='GRAPH_BUILD'")).isEqualTo(1);
        assertThat(builds.processNext(TENANT)).isTrue(); assertThat(builds.processNext(TENANT)).isFalse();
        assertThat(number("SELECT count(*) FROM outbox_events WHERE event_type='graph.snapshot.created'")).isEqualTo(1);
    }
    @Test void gapsPublishPartialNeverReady() throws Exception {
        source(Map.of("Broken.java","class {", "Good.java","class Good {}"));
        builds.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID());assertThat(builds.processNext(TENANT)).isTrue();
        assertThat(text("SELECT status FROM graph_snapshots")).isEqualTo("PARTIAL");
        assertThat(number("SELECT count(*) FROM graph_extraction_gaps WHERE category='JAVA_PARSE_FAILED'")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM graph_nodes WHERE node_key='java-type:Good'")).isEqualTo(1);
    }
    @Test void suspendedInstallationPreventsProviderCallsAndPublication() throws Exception {
        builds.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID());
        sql("UPDATE github_installations SET status='SUSPENDED',generation=generation+1");int before=GITHUB.requests.get();
        assertThat(builds.processNext(TENANT)).isTrue();assertThat(GITHUB.requests.get()).isEqualTo(before);
        assertThat(text("SELECT status FROM graph_snapshots")).isEqualTo("FAILED");
        assertThat(number("SELECT count(*) FROM graph_nodes")).isZero();
    }
    @Test void hostileGitTreePathsAndSymlinksAreRejectedBeforeBlobRetrieval() throws Exception {
        byte[] safe="safe".getBytes(StandardCharsets.UTF_8);String safeSha=gitBlobSha(safe),tree="d".repeat(40);
        GITHUB.sourceResponses.put("/repos/org/repo/git/blobs/"+safeSha,Map.of("sha",safeSha,"encoding","base64","content",Base64.getEncoder().encodeToString(safe)));
        GITHUB.sourceResponses.put("/repos/org/repo/git/commits/"+LocalGitHub.A,Map.of("sha",LocalGitHub.A,"tree",Map.of("sha",tree)));
        GITHUB.sourceResponses.put("/repos/org/repo/git/trees/"+tree,Map.of("sha",tree,"truncated",false,"tree",List.of(
                Map.of("path","../../escape.java","sha","1".repeat(40),"type","blob","mode","100644","size",4),
                Map.of("path","link.java","sha","2".repeat(40),"type","blob","mode","120000","size",4),
                Map.of("path","C:\\absolute.java","sha","3".repeat(40),"type","blob","mode","100644","size",4),
                Map.of("path","safe.txt","sha",safeSha,"type","blob","mode","100644","size",safe.length))));
        int before=GITHUB.paths.size();var material=materialSource.fetch(TENANT,repository,LocalGitHub.A,
                new io.guidein.github.api.RepositoryMaterialSource.Bounds(20_000,64L*1024*1024,2*1024*1024,180,Set.of(".git","target","build","node_modules","dist",".gradle",".cache")),()->{});
        assertThat(material.files()).containsOnlyKeys("safe.txt");
        assertThat(material.gaps().stream().map(io.guidein.github.api.RepositoryMaterialSource.Gap::category)).contains("PATH_REJECTED","SOURCE_NON_REGULAR_FILE");
        assertThat(GITHUB.paths.subList(before,GITHUB.paths.size())).noneMatch(path->path.endsWith("1".repeat(40))||path.endsWith("2".repeat(40))||path.endsWith("3".repeat(40)));
        ProofEvidence.write("graph-hostile-source-acquisition",Map.of("path_escape_successes",0,"symlink_blob_fetches",0,"unsafe_path_blob_fetches",0));
    }
    @Test void graphLeaseRenewsBeyondInitialDurationWithoutSecondOwner() throws Exception {
        var files=new TreeMap<String,String>();for(int i=0;i<8;i++)files.put("src/Type"+i+".java","class Type"+i+" {}");source(files);
        GITHUB.sourceResponses.keySet().stream().filter(p->p.contains("/blobs/")).forEach(p->GITHUB.delays.put(p,600L));
        builds.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID());
        long start=System.nanoTime();int competingClaims=0;
        try(var executor=Executors.newSingleThreadExecutor()) {
            var worker=executor.submit(()->builds.processNext(TENANT));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(number("SELECT count(*) FROM job_queue WHERE job_type='GRAPH_BUILD' AND status='RUNNING'")==0 && !worker.isDone() && System.nanoTime()<deadline)Thread.sleep(20);
            while(!worker.isDone() && System.nanoTime()<deadline) { if(jobs.claimNext(TENANT,"GRAPH_BUILD").isPresent())competingClaims++;Thread.sleep(150); }
            assertThat(worker.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isGreaterThan(3000);
        assertThat(competingClaims).isZero();assertThat(text("SELECT status FROM graph_snapshots")).isEqualTo("READY");
        ProofEvidence.write("graph-lease-renewal",Map.of("initial_lease_ms",3000,"elapsed_ms",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),"simultaneous_valid_owners",competingClaims));
    }
    @Test void reclaimedBuildRejectsOldLeaseTokenAndKeepsFinalSnapshot() throws Exception {
        builds.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID());var stale=jobs.claimNext(TENANT,"GRAPH_BUILD").orElseThrow();
        sql("UPDATE job_queue SET lease_until=clock_timestamp()-interval '1 second' WHERE id='"+stale.id()+"'");
        assertThat(builds.processNext(TENANT)).isTrue();String digest=text("SELECT canonical_digest FROM graph_snapshots");
        assertThat(jobs.renew(TENANT,stale.id(),stale.leaseToken())).isFalse();
        assertThat(jobs.complete(TENANT,stale.id(),stale.leaseToken())).isFalse();
        assertThat(text("SELECT status FROM graph_snapshots")).isEqualTo("READY");assertThat(text("SELECT canonical_digest FROM graph_snapshots")).isEqualTo(digest);
        assertThat(number("SELECT count(*) FROM outbox_events WHERE event_type='graph.snapshot.created'")).isEqualTo(1);
    }
    @Test void outboxFailureRollsBackSnapshotRowsAndFinalization() throws Exception {
        double priorPublished=counter("graph.build");
        builds.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID());
        sql("CREATE FUNCTION proof_graph_outbox_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected proof failure'; END $$");
        sql("CREATE TRIGGER proof_graph_outbox_failure BEFORE INSERT ON outbox_events FOR EACH ROW WHEN (NEW.event_type='graph.snapshot.created') EXECUTE FUNCTION proof_graph_outbox_failure()");
        try {
            assertThat(builds.processNext(TENANT)).isTrue();
            assertThat(number("SELECT count(*) FROM graph_snapshots WHERE status IN ('READY','PARTIAL')")).isZero();
            assertThat(number("SELECT count(*) FROM graph_nodes")).isZero();assertThat(number("SELECT count(*) FROM graph_edges")).isZero();
            assertThat(number("SELECT count(*) FROM outbox_events WHERE event_type='graph.snapshot.created'")).isZero();
            assertThat(counter("graph.build")).isEqualTo(priorPublished);
        } finally {sql("DROP TRIGGER proof_graph_outbox_failure ON outbox_events");sql("DROP FUNCTION proof_graph_outbox_failure()");}
        sql("UPDATE job_queue SET available_at=clock_timestamp() WHERE job_type='GRAPH_BUILD'");assertThat(builds.processNext(TENANT)).isTrue();
        assertThat(text("SELECT status FROM graph_snapshots")).isEqualTo("READY");
    }
    @Test void completionFailureRollsBackAlreadyInsertedOutboxEvent() throws Exception {
        builds.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID());
        sql("CREATE FUNCTION proof_graph_completion_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected proof failure'; END $$");
        sql("CREATE TRIGGER proof_graph_completion_failure BEFORE UPDATE ON job_queue FOR EACH ROW WHEN (NEW.job_type='GRAPH_BUILD' AND NEW.status='SUCCEEDED') EXECUTE FUNCTION proof_graph_completion_failure()");
        try {
            assertThat(builds.processNext(TENANT)).isTrue();
            assertThat(number("SELECT count(*) FROM graph_snapshots WHERE status IN ('READY','PARTIAL')")).isZero();
            assertThat(number("SELECT count(*) FROM outbox_events WHERE event_type='graph.snapshot.created'")).isZero();
            assertThat(number("SELECT count(*) FROM graph_nodes")).isZero();
        } finally {sql("DROP TRIGGER proof_graph_completion_failure ON job_queue");sql("DROP FUNCTION proof_graph_completion_failure()");}
    }
    @Test void finalAttemptCrashRetiresSnapshotInsteadOfLeavingBuildingForever() throws Exception {
        builds.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID());var claim=jobs.claimNext(TENANT,"GRAPH_BUILD").orElseThrow();
        sql("UPDATE graph_snapshots SET status='BUILDING'");
        sql("UPDATE job_queue SET attempt_count=max_attempts,lease_until=clock_timestamp()-interval '1 second' WHERE id='"+claim.id()+"'");
        assertThat(builds.processNext(TENANT)).isFalse();assertThat(text("SELECT status FROM graph_snapshots")).isEqualTo("FAILED");
        assertThat(jobs.renew(TENANT,claim.id(),claim.leaseToken())).isFalse();
        assertThat(jobs.complete(TENANT,claim.id(),claim.leaseToken())).isFalse();
    }
    int runtime(UUID tenant,String statement) throws Exception {
        try(var c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"guidein_app","guidein-app-test")) {
            c.setAutoCommit(false);
            try(var s=c.createStatement()) {
                if(tenant!=null)s.execute("SELECT set_config('guidein.tenant_id','"+tenant+"',true)");
                if(s.execute(statement)){try(var r=s.getResultSet()){r.next();return r.getInt(1);}}
                return s.getUpdateCount();
            } finally {c.rollback();}
        }
    }
    UUID seedForeignGraph() throws Exception {
        UUID repo=UUID.randomUUID(),snapshot=UUID.randomUUID(),from=UUID.randomUUID(),to=UUID.randomUUID(),edge=UUID.randomUUID();
        sql("INSERT INTO repositories(id,tenant_id,provider,external_id,owner,name,status) VALUES ('"+repo+"','"+OTHER+"','GITHUB','502','other','repo','ACTIVE')");
        sql("INSERT INTO graph_snapshots(id,tenant_id,repository_id,source_sha,input_identity,builder_version,extractor_versions,configuration_digest) VALUES ('"+snapshot+"','"+OTHER+"','"+repo+"','"+LocalGitHub.B+"',repeat('e',64),'proof','[]',repeat('f',64))");
        sql("UPDATE graph_snapshots SET status='BUILDING' WHERE id='"+snapshot+"'");
        for(UUID node:List.of(from,to))sql("INSERT INTO graph_nodes(id,tenant_id,snapshot_id,node_key,node_type,display_name,criticality,source_identity,metadata) VALUES ('"+node+"','"+OTHER+"','"+snapshot+"','node:"+node+"','MODULE','proof','UNKNOWN','','{}')");
        sql("INSERT INTO graph_edges(id,tenant_id,snapshot_id,from_node_id,to_node_id,from_node_key,to_node_key,edge_type,evidence_digest) VALUES ('"+edge+"','"+OTHER+"','"+snapshot+"','"+from+"','"+to+"','node:"+from+"','node:"+to+"','DEPENDS_ON',repeat('3',64))");
        sql("INSERT INTO graph_edge_evidence(id,tenant_id,snapshot_id,edge_id,evidence_key,source_type,source_path,source_locator,source_digest,extractor_version,observation_type,trust_class,confidence,metadata) VALUES (gen_random_uuid(),'"+OTHER+"','"+snapshot+"','"+edge+"',repeat('1',64),'MAVEN','pom.xml','1',repeat('2',64),'maven-v1','DECLARATION','EXPLICIT',1.00,'{}')");
        sql("INSERT INTO graph_extraction_gaps(id,tenant_id,snapshot_id,category,source_path,source_locator) VALUES(gen_random_uuid(),'"+OTHER+"','"+snapshot+"','PROOF_GAP','pom.xml','1')");
        return snapshot;
    }
    @Test void graphRlsHidesEveryForeignTableAndMissingContextFailsClosed() throws Exception {
        builds.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID());builds.processNext(TENANT);UUID foreign=seedForeignGraph();
        for(String table:List.of("graph_snapshots","graph_nodes","graph_edges","graph_edge_evidence","graph_extraction_gaps")) {
            assertThat(number("SELECT count(*) FROM "+table+" WHERE tenant_id='"+OTHER+"'")).isPositive();
            assertThat(runtime(TENANT,"SELECT count(*) FROM "+table+" WHERE tenant_id='"+OTHER+"'")).isZero();
            assertThatThrownBy(()->runtime(null,"SELECT count(*) FROM "+table)).isInstanceOf(SQLException.class);
        }
        assertThatThrownBy(()->builds.request(subject,OTHER,repository,LocalGitHub.A,UUID.randomUUID())).isInstanceOf(RuntimeException.class);
        assertThat(runtime(TENANT,"SELECT count(*) FROM graph_snapshots WHERE id='"+foreign+"'")).isZero();
        ProofEvidence.write("graph-database-reads",Map.of("tables",5,"cross_tenant_graph_reads",0,"missing_context_exposures",0));
    }
    @Test void graphCompositeForeignKeysAndRlsRejectForeignWrites() throws Exception {
        var own=builds.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID());UUID foreign=seedForeignGraph();
        UUID ownNode=UUID.randomUUID();sql("UPDATE graph_snapshots SET status='BUILDING' WHERE id='"+own.id()+"'");
        sql("INSERT INTO graph_nodes(id,tenant_id,snapshot_id,node_key,node_type,display_name,criticality,source_identity,metadata) VALUES ('"+ownNode+"','"+TENANT+"','"+own.id()+"','own','MODULE','own','UNKNOWN','','{}')");
        String foreignNode=text("SELECT id FROM graph_nodes WHERE tenant_id='"+OTHER+"' LIMIT 1");
        String insert="INSERT INTO graph_edges(id,tenant_id,snapshot_id,from_node_id,to_node_id,from_node_key,to_node_key,edge_type,evidence_digest) VALUES (gen_random_uuid(),'"+TENANT+"','"+own.id()+"','"+ownNode+"','"+foreignNode+"','own','node:"+foreignNode+"','DEPENDS_ON',repeat('3',64))";
        try {runtime(TENANT,insert);fail("Cross-tenant endpoint accepted");}catch(SQLException denied){assertThat(denied.getSQLState()).isEqualTo("23503");}
        assertThatThrownBy(()->runtime(TENANT,"INSERT INTO graph_nodes(id,tenant_id,snapshot_id,node_key,node_type,display_name,criticality,source_identity,metadata) VALUES (gen_random_uuid(),'"+TENANT+"','"+foreign+"','injected','MODULE','injected','UNKNOWN','','{}')")).isInstanceOf(SQLException.class);
        assertThatThrownBy(()->runtime(TENANT,"INSERT INTO graph_extraction_gaps(id,tenant_id,snapshot_id,category,source_path,source_locator) VALUES(gen_random_uuid(),'"+OTHER+"','"+foreign+"','INJECTED','','')")).isInstanceOf(SQLException.class);
        assertThat(number("SELECT count(*) FROM graph_edges WHERE tenant_id='"+TENANT+"'")).isZero();
    }
    @Test void freshMigrationValidationAndRuntimeRoleRestrictions() throws Exception {
        org.flywaydb.core.Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),"guidein_migrator","guidein-migrator-test").locations("classpath:db/migration").load().validate();
        assertThat(number("SELECT count(*) FROM flyway_schema_history WHERE success")).isEqualTo(11);
        assertThat(number("SELECT count(*) FROM pg_class WHERE relname IN ('graph_snapshots','graph_nodes','graph_edges','graph_edge_evidence','graph_extraction_gaps') AND relrowsecurity AND relforcerowsecurity")).isEqualTo(5);
        assertThat(number("SELECT count(*) FROM pg_roles WHERE rolname='guidein_app' AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb)")).isZero();
        assertThat(number("SELECT count(*) FROM pg_class c JOIN pg_roles r ON r.oid=c.relowner WHERE c.relname LIKE 'graph_%' AND r.rolname='guidein_app'")).isZero();
        assertThatThrownBy(()->runtime(TENANT,"ALTER TABLE graph_nodes DISABLE ROW LEVEL SECURITY")).isInstanceOf(SQLException.class);
        ProofEvidence.write("graph-postgresql-environment",Map.of("version",text("SELECT version()"),"database",text("SELECT current_database()"),"migrations",11,"validation","PASS","forced_rls_tables",5,"runtime_role","guidein_app","migration_role","guidein_migrator"));
    }
    @Test void signedWebhookThroughNormalizedEventBuildsOneReusableGraph() throws Exception {
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();UUID correlation=UUID.randomUUID();
        var worker=new io.guidein.graph.application.GraphWorker(materialSource,builds,dispatcher);
        try(var client=java.net.http.HttpClient.newHttpClient()) {
            for(int number:List.of(7,8)) {
                byte[] raw=mapper.writeValueAsBytes(Map.of("installation",Map.of("id",101),"repository",Map.of("id",501),"number",number,"action","synchronize"));
                var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec("WEBHOOK_SECRET_CANARY_32_BYTES_LONG".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
                var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/v1/webhooks/github"))
                        .header("Content-Type","application/json").header("X-GitHub-Event","pull_request").header("X-GitHub-Delivery",UUID.randomUUID().toString())
                        .header("X-GitHub-Hook-ID","1").header("X-Correlation-Id",correlation.toString()).header("X-Hub-Signature-256","sha256="+HexFormat.of().formatHex(mac.doFinal(raw)))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(raw)).build();
                assertThat(client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
                assertThat(integration.processNext(TENANT)).isTrue();assertThat(worker.dispatchChange(TENANT)).isTrue();
                if(number==7)assertThat(builds.processNext(TENANT)).isTrue();
            }
        }
        assertThat(number("SELECT count(*) FROM changes")).isEqualTo(2);
        assertThat(number("SELECT count(*) FROM graph_snapshots WHERE status='READY'")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM job_queue WHERE job_type='GRAPH_BUILD'")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM outbox_events WHERE event_type='change.normalized' AND published_at IS NOT NULL")).isEqualTo(2);
        assertThat(number("SELECT count(*) FROM outbox_events WHERE event_type='graph.snapshot.created' AND correlation_id='"+correlation+"'")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM outbox_events WHERE event_type<>'change.normalized' AND published_at IS NOT NULL")).isZero();
    }
    @Test void recursiveTraversalTerminatesCyclesInBothDirectionsAndHonorsBudgets() throws Exception {
        source(Map.of("compose.yaml","services:\n  a:\n    depends_on: [b]\n  b:\n    depends_on: [c]\n  c:\n    depends_on: [a]\n"));
        var snapshot=builds.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID());assertThat(builds.processNext(TENANT)).isTrue();
        UUID start=UUID.fromString(text("SELECT id FROM graph_nodes WHERE node_key='service:compose:a'"));
        var types=Set.of(io.guidein.graph.api.GraphModel.EdgeType.DEPENDS_ON);
        for(boolean reverse:List.of(false,true)) {
            var traversal=graph.traverse(subject,TENANT,snapshot.id(),start,reverse,types,8,100);
            assertThat(traversal.nodes()).hasSize(3);assertThat(traversal.edgesScanned()).isEqualTo(3);assertThat(traversal.truncated()).isFalse();
            assertThat(traversal.nodes().stream().map(n->n.id()).distinct().count()).isEqualTo(3);
        }
        var nodes=graph.traverse(subject,TENANT,snapshot.id(),start,false,types,8,2);
        assertThat(nodes.nodes()).hasSize(2);assertThat(nodes.reasons()).contains("NODE_BUDGET_EXCEEDED");
        var depth=graph.traverse(subject,TENANT,snapshot.id(),start,false,types,1,100);
        assertThat(depth.nodes()).hasSize(2);assertThat(depth.reasons()).contains("DEPTH_LIMIT_REACHED");
        assertThatThrownBy(()->graph.traverse(subject,TENANT,snapshot.id(),start,false,types,-1,100)).isInstanceOf(RuntimeException.class);
    }
    @Test void explorerPagesExplainEdgesAndDoNotExposeBuildingContent() throws Exception {
        var snapshot=builds.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID());
        assertThatThrownBy(()->graph.nodes(subject,TENANT,snapshot.id(),0,100)).isInstanceOf(RuntimeException.class);
        builds.processNext(TENANT);
        var page=graph.nodes(subject,TENANT,snapshot.id(),0,1);assertThat(page.items()).hasSize(1);assertThat(page.total()).isGreaterThan(1);
        assertThat(graph.node(subject,TENANT,page.items().getFirst().id())).isEqualTo(page.items().getFirst());
        for(var edge:graph.edges(subject,TENANT,snapshot.id(),0,100).items()) {
            assertThat(edge.edge().evidence()).isNotEmpty();
            assertThat(edge.edge().evidence()).allSatisfy(e->{assertThat(e.digest()).matches("[0-9a-f]{64}");assertThat(e.extractorVersion()).isNotBlank();});
        }
        assertThat(graph.nodes(subject,TENANT,snapshot.id(),0,Integer.MAX_VALUE).limit()).isEqualTo(200);
    }
    @Test void signedHttpExplorerEnforcesTenantRepositoryAndSnapshotScopes() throws Exception {
        source(Map.of("App.java","class App {}", "Broken.java","class {"));
        var snapshot=builds.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID());builds.processNext(TENANT);
        UUID foreign=seedForeignGraph();String foreignNode=text("SELECT id FROM graph_nodes WHERE snapshot_id='"+foreign+"' LIMIT 1");
        String ownNode=text("SELECT id FROM graph_nodes WHERE snapshot_id='"+snapshot.id()+"' LIMIT 1");
        UUID selectedUser=UUID.randomUUID(),member=UUID.randomUUID();
        sql("INSERT INTO users(id,auth_issuer,external_subject,status) VALUES ('"+selectedUser+"','"+OIDC.issuer()+"','selected','ACTIVE')");
        sql("INSERT INTO memberships(id,tenant_id,user_id,role,scope_mode) VALUES ('"+member+"','"+TENANT+"','"+selectedUser+"','OWNER','SELECTED_REPOSITORIES')");
        String owner=OIDC.tokenWithEmail("owner","owner@example.test"),selected=OIDC.tokenWithEmail("selected","selected@example.test");
        var paths=List.of("/snapshots/"+snapshot.id(),"/snapshots/"+snapshot.id()+"/nodes","/snapshots/"+snapshot.id()+"/edges","/snapshots/"+snapshot.id()+"/gaps",
                "/nodes/"+ownNode,"/traverse?snapshotId="+snapshot.id()+"&startNodeId="+ownNode,"/diff?before="+snapshot.id()+"&after="+snapshot.id());
        for(String path:paths) {
            assertThat(httpGraph(path,TENANT,owner).statusCode()).isEqualTo(200);
            assertThat(httpGraph(path,TENANT,selected).statusCode()).isEqualTo(404);
            assertThat(httpGraph(path,OTHER,owner).statusCode()).isEqualTo(404);
            assertThat(httpGraph(path,TENANT,null).statusCode()).isEqualTo(401);
        }
        sql("INSERT INTO membership_repository_scopes(tenant_id,membership_id,repository_id) VALUES ('"+TENANT+"','"+member+"','"+repository+"')");
        for(String path:paths)assertThat(httpGraph(path,TENANT,selected).statusCode()).isEqualTo(200);
        for(String path:List.of("/snapshots/"+foreign,"/snapshots/"+foreign+"/nodes","/snapshots/"+foreign+"/edges","/snapshots/"+foreign+"/gaps","/nodes/"+foreignNode,
                "/traverse?snapshotId="+snapshot.id()+"&startNodeId="+foreignNode,"/diff?before="+snapshot.id()+"&after="+foreign))assertThat(httpGraph(path,TENANT,owner).statusCode()).isEqualTo(404);
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        for(String kind:List.of("/snapshots/","/nodes/")) {
            var absent=httpGraph(kind+UUID.randomUUID(),TENANT,owner);
            var hidden=httpGraph(kind+(kind.equals("/snapshots/")?foreign:foreignNode),TENANT,owner);
            assertThat(hidden.statusCode()).isEqualTo(404);assertThat(absent.statusCode()).isEqualTo(hidden.statusCode());
            var absentBody=mapper.readTree(absent.body());var hiddenBody=mapper.readTree(hidden.body());
            assertThat(hiddenBody.propertyNames()).isEqualTo(absentBody.propertyNames());
            for(String field:List.of("type","title","status","detail","code","retryable"))assertThat(hiddenBody.path(field)).as("Not-found field %s",field).isEqualTo(absentBody.path(field));
        }
        var edges=mapper.readTree(httpGraph("/snapshots/"+snapshot.id()+"/edges",TENANT,owner).body()).path("items");
        assertThat(edges.size()).isPositive();
        for(var edge:edges)for(var e:edge.path("edge").path("evidence"))for(String field:List.of("source","path","locator","extractorVersion","trust","confidence","digest"))assertThat(e.has(field)).as(field).isTrue();
        var gaps=mapper.readTree(httpGraph("/snapshots/"+snapshot.id()+"/gaps",TENANT,owner).body()).path("items");assertThat(gaps.size()).isPositive();
        assertThat(httpGraph("/snapshots/"+snapshot.id()+"/nodes?offset=-1",TENANT,owner).statusCode()).isEqualTo(400);
        assertThat(mapper.readTree(httpGraph("/snapshots/"+snapshot.id()+"/nodes?limit=2147483647",TENANT,owner).body()).path("limit").asInt()).isEqualTo(200);
        ProofEvidence.write("graph-http-authorization",Map.of("endpoints",paths.size(),"scope_modes",List.of("ALL_REPOSITORIES","SELECTED_REPOSITORIES"),"cross_tenant_graph_reads",0,"unauthorized_repository_reads",0,"tenant_resource_existence_leaks",0,"standalone_edge_endpoint",false));
    }
    java.net.http.HttpResponse<String> httpGraph(String path,UUID tenant,String token) throws Exception {
        try(var client=java.net.http.HttpClient.newHttpClient()) {
            var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/v1/graph"+path+(path.contains("?")?"&":"?")+"tenantId="+tenant)).timeout(java.time.Duration.ofSeconds(10));
            if(token!=null)request.header("Authorization","Bearer "+token);
            return client.send(request.build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        }
    }
    @Test void oneHundredPersistedBuildsMatchDespiteEveryOrderingVariation() throws Exception {
        var files=new LinkedHashMap<String,String>();
        files.put("pom.xml","<project><groupId>g</groupId><artifactId>app</artifactId></project>");
        files.put("src/App.java","package demo; import demo.Core; class App {}");files.put("src/Core.java","package demo; class Core {}");
        files.put("settings.gradle.kts","include(\":api\", \":shared\")");files.put("api/build.gradle.kts","dependencies { implementation(project(\":shared\")) }");files.put("shared/build.gradle.kts","plugins { java }");
        files.put("web/package.json","{\"name\":\"web\",\"dependencies\":{\"react\":\"19.0.0\"}}");
        files.put("compose.yaml","services:\n  app:\n    depends_on: [db]\n  db: {}\n");
        files.put("openapi.json","{\"openapi\":\"3.0.3\",\"paths\":{\"/health\":{\"get\":{}}}}");
        files.put("Dockerfile","FROM eclipse-temurin:21\nEXPOSE 8080\nCOPY app.jar /app.jar\n");
        files.put("guidein.yaml","guidein:\n  version: 1\n  repository:\n    criticality: HIGH\n");source(files);
        var limits=io.guidein.graph.application.GraphLimits.defaults();var engine=new io.guidein.graph.application.GraphEngine(canonical,limits);
        var digests=new HashSet<String>();long started=System.nanoTime();
        try(var workers=Executors.newFixedThreadPool(4)) {
            for(int run=0;run<100;run++) {
                // New isolated proof attempt with exactly the same tenant/repository/revision/build identity.
                // Only disposable graph/job/event test records are reset; published production history is never reopened.
                sql("TRUNCATE graph_snapshots CASCADE");sql("DELETE FROM job_queue WHERE job_type='GRAPH_BUILD'");sql("DELETE FROM outbox_events WHERE event_type='graph.snapshot.created'");
                var requested=builds.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID());
                var job=jobs.claimNext(TENANT,"GRAPH_BUILD").orElseThrow();
                var acquired=materialSource.fetch(TENANT,repository,LocalGitHub.A,new io.guidein.github.api.RepositoryMaterialSource.Bounds(limits.files(),limits.totalBytes(),limits.fileBytes(),60,io.guidein.graph.application.GraphEngine.EXCLUSIONS),()->assertThat(jobs.renew(TENANT,job.id(),job.leaseToken())).isTrue());
                var random=new Random(run);var paths=new ArrayList<>(acquired.files().keySet());Collections.shuffle(paths,random);
                var unordered=new LinkedHashMap<String,byte[]>();var raw=acquired.files();paths.forEach(p->unordered.put(p,raw.get(p)));
                var extractors=new ArrayList<io.guidein.graph.application.GraphExtractor>(List.of(new io.guidein.graph.application.FileTreeExtractor(),new io.guidein.graph.application.MavenGraphExtractor(),new io.guidein.graph.application.GradleGraphExtractor(),new io.guidein.graph.application.NpmGraphExtractor(),new io.guidein.graph.application.JavaGraphExtractor(),new io.guidein.graph.application.OpenApiGraphExtractor(),new io.guidein.graph.application.ComposeGraphExtractor(),new io.guidein.graph.application.DeploymentGraphExtractor(),new io.guidein.graph.application.GuideInConfigExtractor()));
                Collections.shuffle(extractors,random);var completion=new ExecutorCompletionService<io.guidein.graph.application.GraphFacts>(workers);
                for(var extractor:extractors) {int delay=random.nextInt(6);completion.submit(()->{Thread.sleep(delay);var contribution=new io.guidein.graph.application.GraphFacts(limits,canonical);extractor.extract(new io.guidein.graph.application.SourceMaterial(unordered,limits),limits,contribution);return contribution;});}
                var facts=new io.guidein.graph.application.GraphFacts(limits,canonical);for(int i=0;i<extractors.size();i++)facts.merge(completion.take().get(30,TimeUnit.SECONDS));
                var product=engine.build(unordered,acquired.gaps(),()->assertThat(jobs.renew(TENANT,job.id(),job.leaseToken())).isTrue());
                assertThat(facts.digest()).as("parallel reconciliation run %s",run).isEqualTo(product.digest());
                var content=facts.content();var nodes=new ArrayList<>(content.nodes());Collections.shuffle(nodes,random);
                var edges=new ArrayList<io.guidein.graph.api.GraphModel.Edge>();
                for(var edge:content.edges()){var evidence=new ArrayList<>(edge.evidence());Collections.shuffle(evidence,random);edges.add(new io.guidein.graph.api.GraphModel.Edge(edge.from(),edge.type(),edge.to(),evidence));}Collections.shuffle(edges,random);
                var gaps=new ArrayList<>(content.gaps());Collections.shuffle(gaps,random);
                var insertionOrder=new io.guidein.graph.api.GraphModel.Content(content.builderVersion(),content.confidenceVersion(),nodes,edges,gaps);
                io.guidein.graph.application.GraphProofAccess.publish(graphStore,TENANT,requested.id(),new io.guidein.graph.application.GraphEngine.Product(insertionOrder,product.digest(),product.guideinConfigDigest()),job,()->materialSource.requireAuthority(TENANT,repository));
                var persisted=io.guidein.graph.application.GraphProofAccess.reconstruct(graphStore,TENANT,requested.id(),canonical);
                String digest=engine.hash(canonical.canonicalize(persisted));assertThat(digest).as("persisted reconstruction run %s",run).isEqualTo(product.digest());
                assertThat(graph.snapshot(subject,TENANT,requested.id()).canonicalDigest()).isEqualTo(digest);digests.add(digest);
            }
        }
        assertThat(digests).hasSize(1);
        ProofEvidence.write("graph-persisted-reproducibility",Map.of("builds",100,"distinct_persisted_digests",digests.size(),"in_memory_persisted_mismatches",0,"snapshot_reproducibility",1.0,"extractors",9,"elapsed_ms",(System.nanoTime()-started)/1_000_000.0,"randomized",List.of("files","extractor_invocation","parallel_completion","nodes","edges","evidence","database_insertion")));
    }
    @Test void mediumGraphPersistsFiftyThousandNodesAndQuarterMillionEdgesWithBoundedQueries() throws Exception {
        int nodeCount=50_000,edgeCount=250_000;
        long candidateStarted=System.nanoTime();
        var nodes=new ArrayList<io.guidein.graph.api.GraphModel.Node>();var edges=new ArrayList<io.guidein.graph.api.GraphModel.Edge>();
        for(int i=0;i<nodeCount;i++)nodes.add(new io.guidein.graph.api.GraphModel.Node("synthetic:%05d".formatted(i),io.guidein.graph.api.GraphModel.NodeType.MODULE,"Node "+i,"UNKNOWN","synthetic.txt",Map.of()));
        var evidence=new io.guidein.graph.api.GraphModel.Evidence(io.guidein.graph.api.GraphModel.Source.MAVEN,"synthetic.txt","scale-fixture","a".repeat(64),"synthetic-scale-v1","SYNTHETIC_PERFORMANCE_ONLY",io.guidein.graph.api.GraphModel.Trust.EXPLICIT,"1.00",Map.of());
        for(int i=250;i<nodeCount;i++)for(int offset:new int[]{1,7,31,127,1021}) {
            int target=250+Math.floorMod(i-250+offset,nodeCount-250);
            edges.add(new io.guidein.graph.api.GraphModel.Edge(nodes.get(i).key(),io.guidein.graph.api.GraphModel.EdgeType.DEPENDS_ON,nodes.get(target).key(),List.of(evidence)));
        }
        for(int i=250;i<1500;i++){int target=250+Math.floorMod(i-250+2047,nodeCount-250);edges.add(new io.guidein.graph.api.GraphModel.Edge(nodes.get(i).key(),io.guidein.graph.api.GraphModel.EdgeType.DEPENDS_ON,nodes.get(target).key(),List.of(evidence)));}
        edges.sort(Comparator.comparing(io.guidein.graph.api.GraphModel.Edge::key));
        assertThat(edges).hasSize(edgeCount);double candidateMillis=(System.nanoTime()-candidateStarted)/1_000_000.0;
        var content=new io.guidein.graph.api.GraphModel.Content(io.guidein.graph.application.GraphFacts.BUILDER_VERSION,io.guidein.graph.api.GraphModel.Source.REGISTRY_VERSION,nodes,edges,List.of());
        var engine=new io.guidein.graph.application.GraphEngine(canonical,io.guidein.graph.application.GraphLimits.defaults());
        long started=System.nanoTime();String digest=engine.hash(canonical.canonicalize(content));double canonicalMillis=(System.nanoTime()-started)/1_000_000.0;
        var product=new io.guidein.graph.application.GraphEngine.Product(content,digest,"b".repeat(64));
        var first=builds.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID());var job=jobs.claimNext(TENANT,"GRAPH_BUILD").orElseThrow();started=System.nanoTime();
        io.guidein.graph.application.GraphProofAccess.publish(graphStore,TENANT,first.id(),product,job,()->materialSource.requireAuthority(TENANT,repository));double persistenceMillis=(System.nanoTime()-started)/1_000_000.0;
        var publishTimings=graphStore.lastPublishTimings();
        assertThat(graph.snapshot(subject,TENANT,first.id()).nodeCount()).isEqualTo(nodeCount);assertThat(graph.snapshot(subject,TENANT,first.id()).edgeCount()).isEqualTo(edgeCount);
        var changedNodes=new ArrayList<io.guidein.graph.api.GraphModel.Node>(nodes.subList(250,nodes.size()));
        for(int i=50_000;i<50_500;i++)changedNodes.add(new io.guidein.graph.api.GraphModel.Node("synthetic:%05d".formatted(i),io.guidein.graph.api.GraphModel.NodeType.MODULE,"Node "+i,"UNKNOWN","synthetic.txt",Map.of()));
        var changedEdges=new ArrayList<io.guidein.graph.api.GraphModel.Edge>(edges.subList(1000,edges.size()));
        for(int i=50_000;i<50_500;i++)for(int offset:new int[]{1,7,31,127})changedEdges.add(new io.guidein.graph.api.GraphModel.Edge(
                "synthetic:%05d".formatted(i),io.guidein.graph.api.GraphModel.EdgeType.DEPENDS_ON,"synthetic:%05d".formatted(250+Math.floorMod(i+offset,nodeCount-250)),List.of(evidence)));
        changedNodes.sort(Comparator.comparing(io.guidein.graph.api.GraphModel.Node::key));changedEdges.sort(Comparator.comparing(io.guidein.graph.api.GraphModel.Edge::key));
        var changedContent=new io.guidein.graph.api.GraphModel.Content(io.guidein.graph.application.GraphFacts.BUILDER_VERSION,io.guidein.graph.api.GraphModel.Source.REGISTRY_VERSION,changedNodes,changedEdges,List.of());
        var changedProduct=new io.guidein.graph.application.GraphEngine.Product(changedContent,engine.hash(canonical.canonicalize(changedContent)),"b".repeat(64));
        var second=builds.request(subject,TENANT,repository,LocalGitHub.B,UUID.randomUUID());var secondJob=jobs.claimNext(TENANT,"GRAPH_BUILD").orElseThrow();
        io.guidein.graph.application.GraphProofAccess.publish(graphStore,TENANT,second.id(),changedProduct,secondJob,()->materialSource.requireAuthority(TENANT,repository));
        UUID start=UUID.fromString(text("SELECT id FROM graph_nodes WHERE snapshot_id='"+first.id()+"' AND node_key='synthetic:00250'"));
        var timings=new TreeMap<String,Object>();
        for(boolean reverse:List.of(false,true))for(int depth:List.of(1,2,4,1000)) {
            var times=new ArrayList<Double>();long maxScanned=0;int maxReturned=0;
            for(int run=0;run<12;run++) {
                started=System.nanoTime();var traversal=graph.traverse(subject,TENANT,first.id(),start,reverse,Set.of(io.guidein.graph.api.GraphModel.EdgeType.DEPENDS_ON),depth,Integer.MAX_VALUE);
                times.add((System.nanoTime()-started)/1_000_000.0);maxScanned=Math.max(maxScanned,traversal.edgesScanned());maxReturned=Math.max(maxReturned,traversal.nodes().size());
                assertThat(traversal.nodes().size()).isLessThanOrEqualTo(2000);assertThat(traversal.depth()).isLessThanOrEqualTo(8);assertThat(traversal.edgesScanned()).isLessThanOrEqualTo(20_000);
                if(depth==1000)assertThat(traversal.truncated()).isTrue();
            }
            Collections.sort(times);timings.put((reverse?"reverse":"forward")+"_depth_"+depth,Map.of("p50_ms",times.get(5),"p95_ms",times.get(11),"samples",12,"max_edges_scanned",maxScanned,"max_nodes",maxReturned));
        }
        started=System.nanoTime();var diff=graph.diff(subject,TENANT,first.id(),second.id());double diffMillis=(System.nanoTime()-started)/1_000_000.0;
        assertThat(diff.totals()).containsEntry("added_nodes",500L).containsEntry("removed_nodes",250L)
                .containsEntry("added_edges",2000L).containsEntry("removed_edges",1000L).containsEntry("changed_edges",0L);
        assertThat(number("SELECT count(*) FROM graph_edges WHERE snapshot_id='"+first.id()+"'")).isEqualTo(edgeCount);
        assertThat(number("SELECT count(*) FROM graph_edge_evidence WHERE snapshot_id='"+first.id()+"'")).isEqualTo(edgeCount);
        assertThat(number("SELECT count(*)-count(DISTINCT from_node_id::text||':'||edge_type||':'||to_node_id::text) FROM graph_edges WHERE snapshot_id='"+first.id()+"'")).isZero();
        assertThat(number("SELECT count(*) FROM graph_edges e LEFT JOIN graph_nodes f ON (f.tenant_id,f.snapshot_id,f.id)=(e.tenant_id,e.snapshot_id,e.from_node_id) LEFT JOIN graph_nodes t ON (t.tenant_id,t.snapshot_id,t.id)=(e.tenant_id,e.snapshot_id,e.to_node_id) WHERE e.snapshot_id='"+first.id()+"' AND (f.id IS NULL OR t.id IS NULL)")).isZero();
        var queryPlans=io.guidein.graph.application.GraphProofAccess.explainScale(graphStore,TENANT,first.id(),second.id(),start);
        var scaleEvidence=new TreeMap<String,Object>();
        scaleEvidence.put("synthetic_performance_only",true);scaleEvidence.put("nodes",nodeCount);scaleEvidence.put("edges",edgeCount);
        scaleEvidence.put("evidence_rows",publishTimings.evidenceRows());scaleEvidence.put("gaps",0);scaleEvidence.put("persistence_ms",persistenceMillis);
        scaleEvidence.put("candidate_generation_ms",candidateMillis);scaleEvidence.put("canonicalization_ms",canonicalMillis);scaleEvidence.put("node_persistence_ms",publishTimings.nodesNanos()/1_000_000.0);
        scaleEvidence.put("controlled_diff",Map.of("nodes_added",500,"nodes_removed",250,"edges_added",2000,"edges_removed",1000));
        scaleEvidence.put("explain_analyze",queryPlans);
        scaleEvidence.put("edge_persistence_ms",publishTimings.edgesNanos()/1_000_000.0);scaleEvidence.put("evidence_persistence_ms",publishTimings.evidenceNanos()/1_000_000.0);
        scaleEvidence.put("gap_persistence_ms",publishTimings.gapsNanos()/1_000_000.0);scaleEvidence.put("validation_ms",publishTimings.validationNanos()/1_000_000.0);
        scaleEvidence.put("finalization_update_ms",publishTimings.finalizationNanos()/1_000_000.0);scaleEvidence.put("commit_ms",publishTimings.commitNanos()/1_000_000.0);
        scaleEvidence.put("total_publish_ms",publishTimings.totalNanos()/1_000_000.0);scaleEvidence.put("traversals",timings);scaleEvidence.put("diff_ms",diffMillis);
        scaleEvidence.put("unbounded_traversals",0);scaleEvidence.put("database_bytes",Long.parseLong(text("SELECT pg_database_size(current_database())")));
        ProofEvidence.write("graph-medium-scale",scaleEvidence);
    }
    @Test void builderAndExtractorConfigurationChangesHaveDistinctImmutableBuildIdentities() throws Exception {
        var defaults=io.guidein.graph.application.GraphLimits.defaults();
        var changed=new io.guidein.graph.application.GraphLimits(defaults.files(),defaults.totalBytes(),defaults.fileBytes(),defaults.manifestBytes(),defaults.openApiBytes(),defaults.javaBytes(),defaults.nesting(),defaults.nodes()-1,defaults.edges(),defaults.gaps());
        var baseline=builds.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID());assertThat(builds.processNext(TENANT)).isTrue();
        String digest=graph.snapshot(subject,TENANT,baseline.id()).canonicalDigest();var identities=new HashSet<UUID>();identities.add(baseline.id());
        for(var engine:List.of(new io.guidein.graph.application.GraphEngine(canonical,defaults,"system-graph-v1-next"),new io.guidein.graph.application.GraphEngine(canonical,changed))) {
            var variant=new io.guidein.graph.application.DurableGraphBuilds(graphStore,engine,materialSource,jobs,repositoryQueries,canonical,java.time.Duration.ofSeconds(3));
            var snapshot=variant.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID());identities.add(snapshot.id());assertThat(variant.processNext(TENANT)).isTrue();
            assertThat(variant.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID()).id()).isEqualTo(snapshot.id());
            assertThat(graph.snapshot(subject,TENANT,snapshot.id()).status()).isEqualTo("READY");
        }
        assertThat(identities).hasSize(3);assertThat(graph.snapshot(subject,TENANT,baseline.id()).canonicalDigest()).isEqualTo(digest);
        assertThat(number("SELECT count(DISTINCT input_identity) FROM graph_snapshots")).isEqualTo(3);
    }
    @Test void workerCannotPublishAnOldRecipeUsingChangedExtractorConfiguration() throws Exception {
        builds.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID());
        var engine=new io.guidein.graph.application.GraphEngine(canonical,io.guidein.graph.application.GraphLimits.defaults(),"system-graph-v1-next");
        var variant=new io.guidein.graph.application.DurableGraphBuilds(graphStore,engine,materialSource,jobs,repositoryQueries,canonical,java.time.Duration.ofSeconds(3));
        assertThat(variant.processNext(TENANT)).isTrue();
        assertThat(number("SELECT count(*) FROM graph_snapshots WHERE status IN ('READY','PARTIAL')")).isZero();
        assertThat(number("SELECT count(*) FROM graph_nodes")).isZero();
    }
    double counter(String name){var counter=meters.find(name).counter();return counter==null?0:counter.count();}
    @Test void graphWorkflowTelemetryLinksIdentifiersAndDoesNotLeakCanaries(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        source(Map.of("App.java","// SOURCE_CONTENT_CANARY_DO_NOT_LOG\nclass App {}"));
        signedWebhookThroughNormalizedEventBuildsOneReusableGraph();
        UUID snapshot=UUID.fromString(text("SELECT id FROM graph_snapshots"));UUID node=UUID.fromString(text("SELECT id FROM graph_nodes WHERE node_key='repo:root'"));
        graph.traverse(subject,TENANT,snapshot,node,false,null,0,1);
        String logs=output.getAll();var mapper=tools.jackson.databind.json.JsonMapper.builder().build();var stages=new HashSet<String>();
        for(String line:logs.split("\\R"))if(line.startsWith("{")&&line.contains("graph_")) {
            var entry=mapper.readTree(line);String message=entry.path("message").asText();
            if(!message.startsWith("graph_"))continue;stages.add(message.split(" ")[0]);
            for(String field:List.of("trace_id","span_id","correlation_id","causation_id","job_id","snapshot_id"))assertThat(entry.path(field).asText()).as("%s in %s",field,message).isNotBlank();
            assertThat(entry.path("snapshot_id").asText()).isEqualTo(snapshot.toString());
        }
        assertThat(stages).contains("graph_build_claimed","graph_source_retrieval_started","graph_extractor_completed","graph_canonicalization_started","graph_persistence_started","graph_snapshot_published");
        String persisted=text("SELECT coalesce(json_agg(canonical_payload)::text,'') FROM audit_events")+text("SELECT coalesce(json_agg(payload_json)::text,'') FROM outbox_events")+text("SELECT coalesce(json_agg(last_error_code)::text,'') FROM job_queue");
        for(String canary:List.of("SOURCE_CONTENT_CANARY_DO_NOT_LOG","WEBHOOK_SECRET_CANARY_32_BYTES_LONG","INSTALLATION_TOKEN_CANARY","-----BEGIN PRIVATE KEY-----","CLIENT_SECRET_CANARY"))assertThat(logs+persisted+httpGraph("/snapshots/"+UUID.randomUUID(),TENANT,OIDC.tokenWithEmail("owner","safe@example.test")).body()).doesNotContain(canary);
        for(String name:List.of("graph.build","graph.nodes","graph.edges","graph.snapshot.reuse","graph.traversal.budget.exceeded"))assertThat(counter(name)).as(name).isPositive();
        for(String name:List.of("graph.build.duration","graph.extractor.duration","graph.traversal.duration"))assertThat(meters.find(name).timers()).as(name).isNotEmpty();
        for(var meter:meters.getMeters())if(meter.getId().getName().startsWith("graph."))for(var tag:meter.getId().getTags())assertThat(Set.of("extractor","application")).contains(tag.getKey());
        ProofEvidence.write("graph-observability",Map.of("workflow_stages",stages,"linked_identifiers",List.of("trace_id","span_id","correlation_id","causation_id","job_id","snapshot_id"),"secret_source_leaks",0));
    }
    @Test void realProcessCrashDuringPersistenceHidesUncommittedRowsAndRecovers() throws Exception {
        var snapshot=builds.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID());
        // Only this disposable test database gets the fault trigger. The advisory wait occurs AFTER an actual row insert.
        sql("CREATE FUNCTION proof_graph_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(730031); RETURN NEW; END $$");
        sql("CREATE TRIGGER proof_graph_pause AFTER INSERT ON graph_nodes FOR EACH ROW EXECUTE FUNCTION proof_graph_pause()");
        var settings=Map.of("GUIDEIN_GRAPH_ENABLED","true","GUIDEIN_GRAPH_WORKER_ENABLED","true","GUIDEIN_JOBS_LEASE_DURATION","3s");
        try(var blocker=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"postgres","postgres");var statement=blocker.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(730031)");
            try(var app=new ForkedGuideIn(POSTGRES.getJdbcUrl(),GITHUB,OIDC,false,1000,settings)) {
                await(()->number("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND objid=730031 AND NOT granted")>0,30);
                assertThat(graph.snapshot(subject,TENANT,snapshot.id()).status()).isEqualTo("BUILDING");
                for(int i=0;i<20;i++) {
                    assertThatThrownBy(()->graph.nodes(subject,TENANT,snapshot.id(),0,100)).isInstanceOf(io.guidein.platform.api.GuideInException.class);
                    assertThat(number("SELECT count(*) FROM graph_nodes")).isZero();
                    assertThat(number("SELECT count(*) FROM graph_snapshots WHERE status IN ('READY','PARTIAL')")).isZero();
                }
                app.crash();
                // Release the test-only lock so PostgreSQL can observe the dead client and roll back its open transaction.
                statement.execute("SELECT pg_advisory_unlock(730031)");
                await(()->number("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND objid=730031 AND NOT granted")==0,15);
                assertThat(number("SELECT count(*) FROM graph_nodes")).isZero();
                assertThat(number("SELECT count(*) FROM outbox_events WHERE event_type='graph.snapshot.created'")).isZero();
            } finally {statement.execute("SELECT pg_advisory_unlock(730031)");}
        } finally {sql("DROP TRIGGER proof_graph_pause ON graph_nodes");sql("DROP FUNCTION proof_graph_pause()");}
        try(var recovered=new ForkedGuideIn(POSTGRES.getJdbcUrl(),GITHUB,OIDC,false,1000,settings)) {
            await(()->"READY".equals(text("SELECT status FROM graph_snapshots")),30);
            assertThat(number("SELECT count(*) FROM graph_snapshots")).isEqualTo(1);
            assertThat(number("SELECT count(*) FROM outbox_events WHERE event_type='graph.snapshot.created'")).isEqualTo(1);
            assertThat(number("SELECT count(*) FROM job_queue WHERE job_type='GRAPH_BUILD' AND status='SUCCEEDED'")).isEqualTo(1);
            var canonical=new io.guidein.platform.infrastructure.Rfc8785CanonicalJson(tools.jackson.databind.json.JsonMapper.builder().build());
            var expected=new io.guidein.graph.application.GraphEngine(canonical,io.guidein.graph.application.GraphLimits.defaults());
            // Compare recovery content to the exact acquired immutable material, not the pre-crash database IDs.
            var acquired=materialSource.fetch(TENANT,repository,LocalGitHub.A,new io.guidein.github.api.RepositoryMaterialSource.Bounds(20000,67108864,2097152,60,Set.of(".git","target","build","node_modules","dist",".gradle",".cache")),()->{});
            assertThat(graph.snapshot(subject,TENANT,snapshot.id()).canonicalDigest()).isEqualTo(expected.build(acquired.files(),acquired.gaps(),()->{}).digest());
        }
        ProofEvidence.write("graph-process-crash",Map.of("real_process_terminated",true,"concurrent_reads",20,"half_written_ready_snapshots",0,"corrupt_ready_snapshots",0,"duplicate_ready_graphs",0));
    }
    interface CheckedCondition {boolean test() throws Exception;}
    void await(CheckedCondition condition,int seconds) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(System.nanoTime()<deadline){if(condition.test())return;Thread.sleep(50);}
        assertThat(condition.test()).as("Condition reached before proof timeout").isTrue();
    }
    @Test void graphDiffUsesCanonicalKeysHasSymmetryAndRepeatsExactly() throws Exception {
        source(Map.of("Old.java","class Old {}", "Changed.java","class Changed {}",
                "pom.xml","<project><groupId>g</groupId><artifactId>a</artifactId><dependencies><dependency><groupId>lib</groupId><artifactId>old</artifactId></dependency></dependencies></project>",
                "compose.yaml","services:\n  a: {}\n  b: {}\n",
                "openapi.json","{\"openapi\":\"3.0.3\",\"paths\":{\"/old\":{\"get\":{}}}}"));
        var before=builds.request(subject,TENANT,repository,LocalGitHub.A,UUID.randomUUID());builds.processNext(TENANT);
        source(Map.of("New.java","class New {}", "Broken.java","class {", "Changed.java","class Changed { int value; }",
                "pom.xml","<project><groupId>g</groupId><artifactId>a</artifactId><dependencies><dependency><groupId>lib</groupId><artifactId>new</artifactId></dependency></dependencies></project>",
                "compose.yaml","services:\n  a:\n    depends_on: [b]\n  b: {}\n",
                "openapi.json","{\"openapi\":\"3.0.3\",\"paths\":{\"/new\":{\"post\":{}}}}"));
        String tree="d".repeat(40);GITHUB.sourceResponses.put("/repos/org/repo/git/commits/"+LocalGitHub.B,Map.of("sha",LocalGitHub.B,"tree",Map.of("sha",tree)));
        var after=builds.request(subject,TENANT,repository,LocalGitHub.B,UUID.randomUUID());builds.processNext(TENANT);
        var diff=graph.diff(subject,TENANT,before.id(),after.id());var reverse=graph.diff(subject,TENANT,after.id(),before.id());
        assertThat(diff.addedNodes()).contains("file:New.java","java-type:New");assertThat(diff.removedNodes()).contains("file:Old.java","java-type:Old");
        assertThat(diff.changedNodes()).contains("file:Changed.java");
        assertThat(diff.addedNodes()).contains("endpoint:openapi.json:POST:/new");assertThat(diff.removedNodes()).contains("endpoint:openapi.json:GET:/old");
        assertThat(diff.addedEdges()).anyMatch(e->e.endsWith(":DEPENDS_ON:external:maven:lib:new"));
        assertThat(diff.removedEdges()).anyMatch(e->e.endsWith(":DEPENDS_ON:external:maven:lib:old"));
        assertThat(diff.addedEdges()).anyMatch(e->e.contains("service:compose:a:DEPENDS_ON:service:compose:b"));
        assertThat(diff.addedGaps()).anyMatch(g->g.category().equals("JAVA_PARSE_FAILED"));
        assertThat(diff.addedNodes()).isEqualTo(reverse.removedNodes());assertThat(diff.removedNodes()).isEqualTo(reverse.addedNodes());
        assertThat(diff.addedEdges()).isEqualTo(reverse.removedEdges());assertThat(diff.addedGaps()).isEqualTo(reverse.removedGaps());
        for(int i=0;i<100;i++)assertThat(graph.diff(subject,TENANT,before.id(),after.id())).isEqualTo(diff);
        assertThat(graph.diff(subject,TENANT,before.id(),before.id()).totals().values()).allMatch(v->v==0);
    }
}
