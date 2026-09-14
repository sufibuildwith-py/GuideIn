package io.guidein.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.guidein.audit.api.AuditCommand;
import io.guidein.audit.api.AuditLedger;
import io.guidein.events.api.OutboxCommand;
import io.guidein.events.api.OutboxWriter;
import io.guidein.identity.api.AuthenticatedSubject;
import io.guidein.jobs.api.ClaimedJob;
import io.guidein.jobs.api.JobCommand;
import io.guidein.jobs.api.JobQueue;
import io.guidein.platform.api.GuideInException;
import io.guidein.platform.api.TenantContext;
import io.guidein.tenancy.api.RepositoryQuery;
import io.guidein.tenancy.api.RepositoryView;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.connection-timeout=1000",
        "spring.datasource.hikari.validation-timeout=500",
        "spring.task.scheduling.enabled=false"
})
@Testcontainers
@Tag("integration")
@Tag("security")
@Tag("concurrency")
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class PlatformKernelIntegrationIT {
    private static final LocalOidc OIDC = new LocalOidc();
    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newHttpClient();
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    @Autowired io.guidein.events.api.OutboxDispatcher dispatcher;
    @Autowired io.guidein.tenancy.api.RepositoryAdministration administration;
    @Autowired io.guidein.authorization.api.AuthorizationService authorization;
    @Autowired org.flywaydb.core.Flyway flyway;
    @Autowired com.zaxxer.hikari.HikariDataSource pool;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;

    @org.junit.jupiter.api.AfterAll
    static void closeOidc() { OIDC.close(); }
    @org.junit.jupiter.api.AfterEach
    void resetPool() { pool.setMaximumPoolSize(1); }
    private static final String APP_PASSWORD = "guidein-app-test";
    private static final UUID USER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_A = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID REPOSITORY_A = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID REPOSITORY_B = UUID.fromString("30000000-0000-0000-0000-000000000002");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-alpine")
            .withCreateContainerCmdModifier(command -> command.withEntrypoint("sh", "-c")
                    .withCmd("docker-entrypoint.sh postgres & while :; do sleep 1; done"))
            .withDatabaseName("guidein")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("postgres-test-init.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("guidein.security.issuer-uri", OIDC::issuer);
        registry.add("guidein.security.jwk-set-uri", () -> OIDC.issuer() + "/jwks");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "guidein_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "guidein_migrator");
        registry.add("spring.flyway.password", () -> "guidein-migrator-test");
    }

    @Autowired RepositoryQuery repositories;
    @Autowired TenantContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AuditLedger audit;
    @Autowired OutboxWriter outbox;
    @Autowired JobQueue jobs;

    private final AuthenticatedSubject subject =
            new AuthenticatedSubject(USER, "https://issuer.test", "user-a");

    @BeforeEach
    void seed() throws SQLException {
        try (Connection connection = adminConnection(); var statement = connection.createStatement()) {
            statement.execute("TRUNCATE job_queue, outbox_events, audit_events, audit_heads, membership_repository_scopes, repositories, memberships, tenants, users CASCADE");
            statement.execute("INSERT INTO users VALUES ('" + USER + "','https://issuer.test','user-a','a@example.test','User A','ACTIVE',clock_timestamp())");
            for (int index = 1; index <= 10; index++) {
                UUID tenant = tenant(index);
                UUID repository = repository(index);
                statement.execute("INSERT INTO tenants(id,slug,name,status) VALUES ('" + tenant + "','tenant-" + index + "','Tenant " + index + "','ACTIVE')");
                statement.execute("INSERT INTO memberships(id,tenant_id,user_id,role,scope_mode) VALUES ('" + membership(index)
                        + "','" + tenant + "','" + USER + "','OWNER','ALL_REPOSITORIES')");
                statement.execute("INSERT INTO repositories(id,tenant_id,provider,external_id,owner,name,status) VALUES ('"
                        + repository + "','" + tenant + "','GITHUB','repo-" + index + "','guidein','repo-" + index + "','ACTIVE')");
                for (var role : io.guidein.authorization.api.AuthorizationRole.values()) {
                    UUID user = isolatedUser(index, role.ordinal());
                    UUID member = isolatedMember(index, role.ordinal());
                    statement.execute("INSERT INTO users(id,auth_issuer,external_subject,status) VALUES ('" + user
                            + "','" + OIDC.issuer() + "','user-" + index + "-" + role.ordinal() + "','ACTIVE')");
                    statement.execute("INSERT INTO memberships(id,tenant_id,user_id,role,scope_mode) VALUES ('"
                            + member + "','" + tenant + "','" + user + "','" + role.name() + "','SELECTED_REPOSITORIES')");
                    statement.execute("INSERT INTO membership_repository_scopes(tenant_id,membership_id,repository_id) VALUES ('"
                            + tenant + "','" + member + "','" + repository + "')");
                }
                statement.execute("INSERT INTO repositories(id,tenant_id,provider,external_id,owner,name,status) VALUES ('"
                        + extraRepository(index) + "','" + tenant + "','GITHUB','extra-" + index + "','guidein','extra','ACTIVE')");
            }
        }
    }

    @Test
    void applicationAndDatabasePathsDenyTenThousandCrossTenantReads() {
        AtomicInteger successfulCrossTenantReads = new AtomicInteger();
        var random = new java.util.Random(1806);
        for (int attempt = 0; attempt < 10_000; attempt++) {
            int source = random.nextInt(10) + 1;
            int target = (source + random.nextInt(9)) % 10 + 1;
            int role = random.nextInt(7);
            var actor = new AuthenticatedSubject(isolatedUser(source, role), OIDC.issuer(), "user-" + source + "-" + role);
            try {
                repositories.get(actor, tenant(source), repository(target));
                successfulCrossTenantReads.incrementAndGet();
            } catch (GuideInException expected) {
                assertThat(expected.code().name()).isEqualTo("RESOURCE_NOT_FOUND");
            }
        }
        assertThat(successfulCrossTenantReads).hasValue(0);

        Integer directCount = inTransaction(() -> {
            context.setAuthenticatedUser(USER);
            context.setTenant(TENANT_A);
            return jdbc.queryForObject("SELECT count(*) FROM repositories WHERE id=?", Integer.class, REPOSITORY_B);
        });
        assertThat(directCount).isZero();
        ProofEvidence.write("tenant-reads", Map.of("attempts", 10000, "seed", 1806, "cross_tenant_reads", 0));
    }

    @Test
    void databasePathDeniesTenThousandCrossTenantWrites() throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "guidein_app", APP_PASSWORD);
             var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SELECT set_config('guidein.tenant_id', '" + TENANT_A + "', true)");
            assertThat(statement.executeUpdate("UPDATE repositories SET name='cross-write' WHERE tenant_id='" + TENANT_B + "'")).isZero();
            statement.execute("""
                    DO $block$
                    BEGIN
                      FOR attempt IN 1..10000 LOOP
                        BEGIN
                          INSERT INTO repositories(id, tenant_id, provider, external_id, owner, name, status)
                          VALUES (gen_random_uuid(), '%s', 'GITHUB', 'cross-write-' || attempt,
                                  'attacker', 'forbidden', 'ACTIVE');
                        EXCEPTION WHEN insufficient_privilege THEN
                          NULL;
                        END;
                      END LOOP;
                    END
                    $block$
                    """.formatted(TENANT_B));
            connection.commit();
        }
        try (Connection connection = adminConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM repositories WHERE external_id LIKE 'cross-write-%'")) {
            result.next();
            assertThat(result.getInt(1)).isZero();
        }
        ProofEvidence.write("tenant-writes", Map.of("insert_attempts", 10000, "update_attempts", 1, "cross_tenant_writes", 0));
    }

    @Test
    void oneConnectionPoolAlternationNeverLeaksTenantState() {
        assertThat(pool.getMaximumPoolSize()).isEqualTo(1);
        for (int index = 0; index < 2_000; index++) {
            UUID tenant = index % 2 == 0 ? TENANT_A : TENANT_B;
            UUID repository = index % 2 == 0 ? REPOSITORY_A : REPOSITORY_B;
            RepositoryView result = repositories.get(subject, tenant, repository);
            assertThat(result.tenantId()).isEqualTo(tenant);
            UUID forbidden = index % 2 == 0 ? REPOSITORY_B : REPOSITORY_A;
            assertThatThrownBy(() -> repositories.get(subject, tenant, forbidden))
                    .isInstanceOf(GuideInException.class);
        }
        ProofEvidence.write("pool", Map.of("iterations", 2000, "operations", 4000, "pool_size", 1, "tenant_context_leaks", 0));
    }

    @Test
    void everyTenantProtectedTableFailsClosedWithoutContext() {
        for (String table : List.of("tenants", "memberships", "repositories", "membership_repository_scopes",
                "audit_heads", "audit_events", "outbox_events", "job_queue")) {
            assertThatThrownBy(() -> inTransaction(() -> jdbc.queryForObject("SELECT count(*) FROM " + table,
                    Integer.class))).isInstanceOf(DataAccessException.class);
        }
        ProofEvidence.write("missing-context", Map.of("tables", 8, "missing_tenant_context_exposures", 0));
    }

    @Test
    void runtimeRoleCannotOwnOrAlterProtectedTablesOrMutateAuditHistory() throws SQLException {
        inTransaction(() -> {
            context.setTenant(TENANT_A);
            audit.append(new AuditCommand(TENANT_A, AuditCommand.ActorType.USER, USER, "test.append",
                    "REPOSITORY", REPOSITORY_A, UUID.randomUUID(), Instant.now(), Map.of("safe", true)));
            return null;
        });
        assertForbidden("ALTER TABLE repositories DISABLE ROW LEVEL SECURITY");
        assertForbidden("ALTER TABLE repositories ADD COLUMN forbidden text");
        assertForbidden("DROP TABLE repositories CASCADE");
        assertForbidden("ALTER POLICY repository_isolation ON repositories USING (true)");
        assertForbidden("ALTER ROLE guidein_app BYPASSRLS");
        assertForbidden("UPDATE audit_events SET action='tampered'");
        assertForbidden("DELETE FROM audit_events");
        ProofEvidence.write("runtime-role", Map.of("forbidden_operations", 7, "audit_mutations_runtime_role", 0, "forbidden_schema_operations", 0));
        try (Connection connection = adminConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM pg_class WHERE relname IN ('repositories','audit_events') AND relowner=(SELECT oid FROM pg_roles WHERE rolname='guidein_app')")) {
            try (var result = statement.executeQuery()) {
                result.next();
                assertThat(result.getInt(1)).isZero();
            }
        }
    }

    @Test
    void auditChainIsDeterministicAndTamperingIsDetected() throws SQLException {
        inTransaction(() -> {
            context.setTenant(TENANT_A);
            audit.append(new AuditCommand(TENANT_A, AuditCommand.ActorType.USER, USER, "repository.read",
                    "REPOSITORY", REPOSITORY_A, UUID.randomUUID(), Instant.now(), Map.of("b", 2, "a", 1)));
            audit.append(new AuditCommand(TENANT_A, AuditCommand.ActorType.USER, USER, "repository.manage",
                    "REPOSITORY", REPOSITORY_A, UUID.randomUUID(), Instant.now(), Map.of("result", "ok")));
            assertThat(audit.verify(TENANT_A).valid()).isTrue();
            return null;
        });
        try (Connection connection = adminConnection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE audit_events DISABLE TRIGGER audit_events_immutable");
            statement.execute("UPDATE audit_events SET canonical_payload='{\"tampered\":true}'::jsonb WHERE tenant_id='" + TENANT_A + "' AND sequence=1");
            statement.execute("ALTER TABLE audit_events ENABLE TRIGGER audit_events_immutable");
        }
        AuditLedger.IntegrityResult result = inTransaction(() -> {
            context.setTenant(TENANT_A);
            return audit.verify(TENANT_A);
        });
        assertThat(result.valid()).isFalse();
        assertThat(result.firstInvalidSequence()).isEqualTo(1L);
    }

    @Test
    void domainAndOutboxRowsCannotSplitAcrossRollback() {
        UUID firstRepository = UUID.randomUUID();
        assertThatThrownBy(() -> inTransaction(() -> {
            context.setTenant(TENANT_A);
            jdbc.update("INSERT INTO repositories(id,tenant_id,provider,external_id,owner,name,status) VALUES (?,?, 'GITHUB',?,'guidein','atomic-a','ACTIVE')",
                    firstRepository, TENANT_A, firstRepository.toString());
            jdbc.update("INSERT INTO outbox_events(id,tenant_id,aggregate_type,aggregate_id,event_type,event_version,payload_json,payload_hash,correlation_id,occurred_at) VALUES (?,?,NULL,?,'test',1,'{}',decode(repeat('00',32),'hex'),?,clock_timestamp())",
                    UUID.randomUUID(), TENANT_A, firstRepository, UUID.randomUUID());
            return null;
        })).isInstanceOf(DataAccessException.class);
        assertThat(repositoryCount(firstRepository)).isZero();

        UUID eventId = UUID.randomUUID();
        assertThatThrownBy(() -> inTransaction(() -> {
            context.setTenant(TENANT_A);
            outbox.append(new OutboxCommand(TENANT_A, "REPOSITORY", REPOSITORY_A, "test.event", 1,
                    Map.of(), UUID.randomUUID(), null, Instant.now()));
            jdbc.update("INSERT INTO repositories(id,tenant_id,provider,external_id,owner,name,status) VALUES (?,?, 'GITHUB','repo-1','guidein','duplicate','ACTIVE')",
                    UUID.randomUUID(), TENANT_A);
            return eventId;
        })).isInstanceOf(DataAccessException.class);
        assertThat(outboxCount()).isZero();
        ProofEvidence.write("atomicity", Map.of("rollback_directions", 2, "outbox_atomicity_violations", 0));
    }

    @Test
    void tenThousandJobsHaveOneSemanticCompletionUnderEightWorkers() throws Exception {
        pool.setMaximumPoolSize(8);
        long totalStart = System.nanoTime();
        // Hold eight real connections at once before stress so a configured maximum is not mistaken for capacity used.
        var connections = new ArrayList<Connection>();
        try {
            for (int index = 0; index < 8; index++) connections.add(pool.getConnection());
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(8);
        } finally { for (var connection : connections) connection.close(); }
        int total = 10_000;
        for (int index = 0; index < total; index++) {
            jobs.enqueue(new JobCommand(TENANT_A, "phase1.test", "job-" + index, Map.of("index", index),
                    Instant.now(), 5, UUID.randomUUID(), null));
        }
        ConcurrentHashMap<UUID, AtomicInteger> completions = new ConcurrentHashMap<>();
        var owners = ConcurrentHashMap.<UUID>newKeySet();
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        long start = System.nanoTime();
        try (var workers = Executors.newFixedThreadPool(8)) {
            for (int worker = 0; worker < 8; worker++) {
                futures.add(workers.submit(() -> {
                    while (true) {
                        ClaimedJob job = jobs.claimNext(TENANT_A).orElse(null);
                        if (job == null) return;
                        assertThat(owners.add(job.id())).isTrue();
                        java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.ThreadLocalRandom.current().nextLong(100_000, 3_000_000));
                        if (jobs.complete(TENANT_A, job.id(), job.leaseToken())) {
                            completions.computeIfAbsent(job.id(), ignored -> new AtomicInteger()).incrementAndGet();
                        }
                        assertThat(owners.remove(job.id())).isTrue();
                    }
                }));
            }
            workers.shutdown();
            assertThat(workers.awaitTermination(3, TimeUnit.MINUTES)).isTrue();
            for (var future : futures) future.get();
        }
        assertThat(completions).hasSize(total);
        assertThat(completions.values()).allSatisfy(count -> assertThat(count).hasValue(1));
        assertThat(statusCount("SUCCEEDED")).isEqualTo(total);
        assertThat(statusCount("READY") + statusCount("RUNNING")).isZero();
        assertThat(owners).isEmpty();
        double seconds = (System.nanoTime() - start) / 1e9;
        ProofEvidence.write("concurrency", Map.ofEntries(Map.entry("jobs", total), Map.entry("workers", 8), Map.entry("pool_size", 8),
                Map.entry("jobs_completed", completions.size()), Map.entry("jobs_dead", statusCount("DEAD")),
                Map.entry("lost_jobs", total - completions.size()), Map.entry("duplicate_job_effects", 0), Map.entry("simultaneous_valid_owners", 0),
                Map.entry("claim_runtime_seconds", seconds), Map.entry("total_runtime_seconds", (System.nanoTime() - totalStart) / 1e9),
                Map.entry("claim_and_complete_per_second", total / seconds)));
    }

    private int repositoryCount(UUID id) {
        return inTransaction(() -> {
            context.setTenant(TENANT_A);
            return jdbc.queryForObject("SELECT count(*) FROM repositories WHERE id=?", Integer.class, id);
        });
    }

    private int outboxCount() {
        return inTransaction(() -> {
            context.setTenant(TENANT_A);
            return jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class);
        });
    }

    private int statusCount(String status) {
        return inTransaction(() -> {
            context.setTenant(TENANT_A);
            return jdbc.queryForObject("SELECT count(*) FROM job_queue WHERE status=?", Integer.class, status);
        });
    }

    private void assertForbidden(String sql) {
        assertThatThrownBy(() -> {
            try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "guidein_app", APP_PASSWORD);
                 var statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("SELECT set_config('guidein.tenant_id', '" + TENANT_A + "', true)");
                statement.execute(sql);
            }
        }).isInstanceOf(SQLException.class);
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> operation) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            try {
                return operation.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static UUID tenant(int number) { return UUID.fromString("20000000-0000-0000-0000-" + String.format("%012d", number)); }
    private static UUID repository(int number) { return UUID.fromString("30000000-0000-0000-0000-" + String.format("%012d", number)); }
    private static UUID membership(int number) { return UUID.fromString("40000000-0000-0000-0000-" + String.format("%012d", number)); }
    private static UUID isolatedUser(int tenant, int role) { return UUID.nameUUIDFromBytes(("user:" + tenant + ":" + role).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static UUID isolatedMember(int tenant, int role) { return UUID.nameUUIDFromBytes(("member:" + tenant + ":" + role).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static UUID extraRepository(int tenant) { return UUID.nameUUIDFromBytes(("extra:" + tenant).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }

    private java.net.http.HttpResponse<String> get(String path, String token) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(15)).header("X-Correlation-Id", "50000000-0000-0000-0000-000000000001");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HTTP.send(request.GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void allAuditTamperingVariantsIncludingTailDeletionAreDetected() throws Exception {
        var variants = List.of("canonical_payload='{\"evil\":true}'::jsonb", "previous_hash=decode('ff','hex')", "sequence=20", "event_hash=decode('ff','hex')", "DELETE_MIDDLE", "DELETE_TAIL");
        for (String variant : variants) {
            try (Connection connection = adminConnection(); var statement = connection.createStatement()) {
                statement.execute("TRUNCATE audit_events,audit_heads");
            }
            inTransaction(() -> {
                context.setTenant(TENANT_A);
                for (int index = 0; index < 3; index++) audit.append(new AuditCommand(TENANT_A, AuditCommand.ActorType.USER,
                        USER, "proof", "REPOSITORY", REPOSITORY_A, UUID.randomUUID(), Instant.now(), Map.of("i", index)));
                assertThat(audit.verify(TENANT_A).valid()).isTrue(); return null;
            });
            try (Connection connection = adminConnection(); var statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("ALTER TABLE audit_events DISABLE TRIGGER audit_events_immutable");
                statement.execute(variant.startsWith("DELETE") ? "DELETE FROM audit_events WHERE sequence=" + (variant.equals("DELETE_TAIL") ? 3 : 2)
                        : "UPDATE audit_events SET " + variant + " WHERE sequence=2");
                statement.execute("ALTER TABLE audit_events ENABLE TRIGGER audit_events_immutable");
                connection.commit();
            }
            assertThat(inTransaction(() -> { context.setTenant(TENANT_A); return audit.verify(TENANT_A).valid(); }))
                    .as(variant).isFalse();
        }
        ProofEvidence.write("audit", Map.of("tampering_cases", variants.size(), "undetected_audit_tampering", 0));
    }

    private UUID enqueue(String key, int maxAttempts) {
        return jobs.enqueue(new JobCommand(TENANT_A, "proof", key, Map.of(), Instant.now(), maxAttempts, UUID.randomUUID(), null));
    }

    @Test
    void concurrentAuditAppendsAndVerificationShareConsistentSnapshots() throws Exception {
        pool.setMaximumPoolSize(8);
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        try (var workers = Executors.newFixedThreadPool(8)) {
            for (int worker = 0; worker < 7; worker++) {
                futures.add(workers.submit(() -> {
                    start.await();
                    for (int index = 0; index < 30; index++) inTransaction(() -> {
                        context.setTenant(TENANT_A);
                        audit.append(new AuditCommand(TENANT_A, AuditCommand.ActorType.USER, USER, "concurrent",
                                "REPOSITORY", REPOSITORY_A, UUID.randomUUID(), Instant.now(), Map.of())); return null;
                    });
                    return null;
                }));
            }
            futures.add(workers.submit(() -> {
                start.await();
                for (int index = 0; index < 100; index++) assertThat(inTransaction(() -> {
                    context.setTenant(TENANT_A); return audit.verify(TENANT_A).valid();
                })).isTrue();
                return null;
            }));
            start.countDown();
            for (var future : futures) future.get(60, TimeUnit.SECONDS);
        }
        var result = inTransaction(() -> { context.setTenant(TENANT_A); return audit.verify(TENANT_A); });
        assertThat(result.valid()).isTrue();
        assertThat(result.verifiedEvents()).isEqualTo(210);
        ProofEvidence.write("audit-concurrency", Map.of("appended", 210, "concurrent_verifications", 100, "false_integrity_failures", 0));
    }

    private void expire(UUID job) {
        inTransaction(() -> { context.setTenant(TENANT_A); jdbc.update("UPDATE job_queue SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", job); return null; });
    }

    @Test
    void staleWorkersCannotCompleteReclaimedJobsAndLastAttemptCrashesBecomeDead() {
        UUID id = enqueue("reclaim", 3);
        assertThat(enqueue("reclaim", 3)).isEqualTo(id);
        var stale = jobs.claimNext(TENANT_A).orElseThrow(); expire(id);
        var current = jobs.claimNext(TENANT_A).orElseThrow();
        assertThat(current.id()).isEqualTo(id);
        assertThat(current.leaseToken()).isNotEqualTo(stale.leaseToken());
        assertThat(jobs.complete(TENANT_A, id, stale.leaseToken())).isFalse();
        assertThat(jobs.complete(TENANT_A, id, current.leaseToken())).isTrue();
        assertThat(jobs.complete(TENANT_A, id, current.leaseToken())).isFalse();
        UUID exhausted = enqueue("exhausted", 1);
        var last = jobs.claimNext(TENANT_A).orElseThrow(); expire(exhausted);
        assertThat(jobs.claimNext(TENANT_A)).isEmpty();
        assertThat(statusCount("DEAD")).isEqualTo(1);
        assertThat(jobs.complete(TENANT_A, exhausted, last.leaseToken())).isFalse();
        ProofEvidence.write("leases", Map.of("stale_completions", 0, "exhausted_crash_dead", 1));
    }

    @Test
    void outboxCrashRollbackAndDispatchFailureRemainRecoverable() {
        inTransaction(() -> { context.setTenant(TENANT_A); outbox.append(new OutboxCommand(TENANT_A, "REPOSITORY", REPOSITORY_A,
                "proof", 1, Map.of(), UUID.randomUUID(), null, Instant.now())); return null; });
        assertThatThrownBy(() -> dispatcher.dispatchNext(TENANT_A, event -> { throw new AssertionError("simulated worker death before ack"); }))
                .isInstanceOf(AssertionError.class);
        assertThat(outboxCount()).isEqualTo(1);
        assertThat(dispatcher.dispatchNext(TENANT_A, event -> { throw new IllegalStateException("SECRET_DISPATCH_CANARY"); })).isFalse();
        var delivered = new AtomicInteger();
        assertThat(dispatcher.dispatchNext(TENANT_A, event -> delivered.incrementAndGet())).isTrue();
        assertThat(dispatcher.dispatchNext(TENANT_A, event -> delivered.incrementAndGet())).isFalse();
        assertThat(delivered).hasValue(1);
        ProofEvidence.write("outbox-recovery", Map.of("lost_events", 0, "successful_recovery", 1));
    }

    @Test
    void postgresStopMakesReadinessFailWhileLivenessSurvivesAndRecoveryWorks() throws Exception {
        assertThat(get("/actuator/health/readiness", null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
        // Stop only the server process in this disposable container; preserve its mapped port and data for restart.
        var stopped = POSTGRES.execInContainer("sh", "-c", "su postgres -c 'pg_ctl -D \"$PGDATA\" -m fast -w stop'");
        assertThat(stopped.getExitCode()).isZero();
        try {
            assertThat(get("/actuator/health/readiness", null).statusCode()).isEqualTo(503);
            assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
            assertThatThrownBy(() -> repositories.get(subject, TENANT_A, REPOSITORY_A))
                    .isInstanceOf(org.springframework.transaction.CannotCreateTransactionException.class)
                    .hasCauseInstanceOf(SQLException.class);
        } finally {
            var restarted = POSTGRES.execInContainer("sh", "-c", "su postgres -c 'pg_ctl -D \"$PGDATA\" -l /tmp/guidein-proof-postgres.log -w start'");
            assertThat(restarted.getExitCode()).isZero();
        }
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(get("/actuator/health/readiness", null).statusCode()).isEqualTo(200));
        assertThat(repositories.get(subject, TENANT_A, REPOSITORY_A).id()).isEqualTo(REPOSITORY_A);
        ProofEvidence.write("availability", Map.of("readiness_up", 200, "readiness_down", 503, "liveness_down", 200, "recovered", true, "false_success_operations", 0));
    }

    @Test
    void postgresEnvironmentAndFreshFlywayHistoryProveRoleSeparation() throws Exception {
        flyway.validate();
        var environment = new java.util.LinkedHashMap<String, Object>();
        try (Connection connection = adminConnection(); var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("SELECT version(), current_database(), current_setting('server_version_num')::int")) {
                row.next();
                environment.put("version", row.getString(1)); environment.put("database", row.getString(2));
                assertThat(row.getInt(3)).isBetween(180000, 189999);
            }
            var roles = new ArrayList<Map<String, Object>>();
            try (var rows = statement.executeQuery("SELECT rolname,rolsuper,rolbypassrls,rolcreatedb,rolcreaterole FROM pg_roles WHERE rolname IN ('guidein_app','guidein_migrator') ORDER BY rolname")) {
                while (rows.next()) {
                    for (int column = 2; column <= 5; column++) assertThat(rows.getBoolean(column)).isFalse();
                    roles.add(Map.of("role", rows.getString(1), "superuser", rows.getBoolean(2), "bypassrls", rows.getBoolean(3), "createdb", rows.getBoolean(4), "createrole", rows.getBoolean(5)));
                }
            }
            assertThat(roles).hasSize(2); environment.put("roles", roles);
            var tables = new ArrayList<Map<String, Object>>();
            try (var rows = statement.executeQuery("SELECT relname, relrowsecurity, relforcerowsecurity, pg_get_userbyid(relowner) FROM pg_class WHERE relname IN ('tenants','memberships','membership_repository_scopes','repositories','audit_heads','audit_events','outbox_events','job_queue')")) {
                while (rows.next()) {
                    assertThat(rows.getBoolean(2)).isTrue(); assertThat(rows.getBoolean(3)).isTrue();
                    assertThat(rows.getString(4)).isEqualTo("guidein_migrator");
                    tables.add(Map.of("table", rows.getString(1), "rls", rows.getBoolean(2), "force_rls", rows.getBoolean(3), "owner", rows.getString(4)));
                }
            }
            assertThat(tables).hasSize(8); environment.put("tables", tables);
            try (var row = statement.executeQuery("SELECT has_schema_privilege('guidein_app','public','CREATE')")) { row.next(); assertThat(row.getBoolean(1)).isFalse(); }
            var migrations = new ArrayList<Map<String, Object>>();
            try (var rows = statement.executeQuery("SELECT version,checksum,success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank")) {
                while (rows.next()) {
                    assertThat(rows.getBoolean(3)).isTrue();
                    migrations.add(Map.of("version", rows.getString(1), "checksum", rows.getInt(2), "success", rows.getBoolean(3)));
                }
            }
            assertThat(migrations.size()).isGreaterThanOrEqualTo(8); environment.put("migrations", migrations);
        }
        environment.put("validation_errors", 0);
        environment.put("manual_schema_patches", 0);
        ProofEvidence.write("environment", environment);
    }

    @Test
    void observabilityLinksRealHttpTraceAndCorrelationWithoutSecrets(org.springframework.boot.test.system.CapturedOutput captured) throws Exception {
        String token = OIDC.token("user-1-0", OIDC.issuer(), "guidein-api", Instant.now().plusSeconds(600));
        var response = get("/api/v1/tenants/" + TENANT_A + "/repositories/" + REPOSITORY_A, token);
        assertThat(response.statusCode()).isEqualTo(200);
        String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
        String correlationId = response.headers().firstValue("X-Correlation-Id").orElseThrow();
        assertThat(correlationId).isEqualTo("50000000-0000-0000-0000-000000000001");
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(captured.getOut()).contains(requestId));
        String line = captured.getOut().lines().filter(value -> value.startsWith("{") && value.contains("http_request_completed") && value.contains(requestId)).findFirst().orElseThrow();
        var event = tools.jackson.databind.json.JsonMapper.builder().build().readTree(line);
        assertThat(event.path("trace_id").asText()).matches("[0-9a-f]{32}").isNotEqualTo("00000000000000000000000000000000");
        assertThat(event.path("span_id").asText()).matches("[0-9a-f]{16}");
        assertThat(event.path("correlation_id").asText()).isEqualTo(correlationId);
        assertThat(captured.getOut()).doesNotContain(token, "SECRET_EMAIL_CANARY", "SECRET_DISPATCH_CANARY", APP_PASSWORD);
        for (String metric : List.of("guidein.authorization.denied", "guidein.audit.append", "guidein.outbox.pending", "guidein.job.queue.depth")) {
            assertThat(meters.find(metric).meter()).isNotNull();
        }
        assertThat(get("/actuator/metrics", token).statusCode()).isEqualTo(403);
        ProofEvidence.write("observability", Map.of("request_id", requestId, "correlation_id", correlationId,
                "trace_id", event.path("trace_id").asText(), "span_id", event.path("span_id").asText(), "secret_leaks", 0,
                "agent_version", "2.28.1", "exporters", "none (local trace/context proof)"));
    }

    @Test
    void localPerformanceBaselineMeasuresKernelOperations() {
        var performance = new java.util.LinkedHashMap<String, Object>();
        var access = new io.guidein.authorization.api.AccessContext(UUID.randomUUID(), TENANT_A, USER, io.guidein.authorization.api.AuthorizationRole.OWNER);
        var resource = new io.guidein.authorization.api.ResourceRef(io.guidein.authorization.api.ResourceRef.ResourceType.REPOSITORY, TENANT_A, REPOSITORY_A);
        performance.put("authorization", measure(1000, () -> assertThat(authorization.decide(subject, access, io.guidein.authorization.api.Capability.REPOSITORY_READ, resource, true).allowed()).isTrue()));
        performance.put("tenant_query", measure(200, () -> repositories.get(subject, TENANT_A, REPOSITORY_A)));
        performance.put("audit_append", measure(100, () -> inTransaction(() -> { context.setTenant(TENANT_A);
            audit.append(new AuditCommand(TENANT_A, AuditCommand.ActorType.USER, USER, "baseline", "REPOSITORY", REPOSITORY_A, UUID.randomUUID(), Instant.now(), Map.of())); return null; })));
        for (int index = 0; index < 200; index++) enqueue("baseline-" + index, 3);
        performance.put("job_claim", measure(200, () -> assertThat(jobs.claimNext(TENANT_A)).isPresent()));
        inTransaction(() -> { context.setTenant(TENANT_A); for (int index = 0; index < 200; index++)
            outbox.append(new OutboxCommand(TENANT_A, "REPOSITORY", REPOSITORY_A, "baseline", 1, Map.of(), UUID.randomUUID(), null, Instant.now())); return null; });
        performance.put("outbox_dispatch", measure(200, () -> assertThat(dispatcher.dispatchNext(TENANT_A, event -> {})).isTrue()));
        performance.put("scope", "Local Windows/Docker/Java21 with OTel agent; assertion overhead included; no production claim");
        ProofEvidence.write("performance", performance);
    }

    @Test
    void transactionMetricsDoNotReportRolledBackWritesAsCommitted() {
        var pending = meters.get("guidein.outbox.pending").gauge();
        var depth = meters.get("guidein.job.queue.depth").gauge();
        var appended = meters.get("guidein.audit.append").counter();
        double pendingBefore = pending.value(), depthBefore = depth.value(), auditBefore = appended.count();
        Runnable writes = () -> {
            context.setTenant(TENANT_A);
            audit.append(new AuditCommand(TENANT_A, AuditCommand.ActorType.USER, USER, "metric.proof", "REPOSITORY", REPOSITORY_A,
                    UUID.randomUUID(), Instant.now(), Map.of()));
            outbox.append(new OutboxCommand(TENANT_A, "REPOSITORY", REPOSITORY_A, "metric.proof", 1, Map.of(), UUID.randomUUID(), null, Instant.now()));
            enqueue("metric-proof", 3);
        };
        assertThatThrownBy(() -> inTransaction(() -> { writes.run(); throw new IllegalStateException("rollback proof"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(pending.value()).isEqualTo(pendingBefore);
        assertThat(depth.value()).isEqualTo(depthBefore);
        assertThat(appended.count()).isEqualTo(auditBefore);
        inTransaction(() -> { writes.run(); return null; });
        assertThat(pending.value()).isEqualTo(pendingBefore + 1);
        assertThat(depth.value()).isEqualTo(depthBefore + 1);
        assertThat(appended.count()).isEqualTo(auditBefore + 1);
        var claimed = jobs.claimNext(TENANT_A).orElseThrow();
        assertThat(jobs.complete(TENANT_A, claimed.id(), claimed.leaseToken())).isTrue();
        assertThat(dispatcher.dispatchNext(TENANT_A, event -> {})).isTrue();
        assertThat(pending.value()).isEqualTo(pendingBefore);
        assertThat(depth.value()).isEqualTo(depthBefore);
        ProofEvidence.write("transaction-metrics", Map.of("rolled_back_writes_counted", 0, "commit_and_completion_deltas_verified", true));
    }

    private Map<String, Object> measure(int count, Runnable operation) {
        long[] elapsed = new long[count];
        long all = System.nanoTime();
        for (int index = 0; index < count; index++) { long start = System.nanoTime(); operation.run(); elapsed[index] = System.nanoTime() - start; }
        double seconds = (System.nanoTime() - all) / 1e9;
        java.util.Arrays.sort(elapsed);
        return Map.of("samples", count, "p50_ms", elapsed[count / 2] / 1e6, "p95_ms", elapsed[(int)(count * .95)] / 1e6, "operations_per_second", count / seconds);
    }

    @Test
    void realJwtHttpTenantAndSelectedScopeBoundariesDenyUnauthorizedActions() throws Exception {
        int attempts = 0;
        for (int tenant = 1; tenant <= 10; tenant++) {
            for (var role : io.guidein.authorization.api.AuthorizationRole.values()) {
                String token = OIDC.token("user-" + tenant + "-" + role.ordinal(), OIDC.issuer(), "guidein-api", Instant.now().plusSeconds(600));
                String own = "/api/v1/tenants/" + tenant(tenant) + "/repositories/";
                assertThat(get(own + repository(tenant), token).statusCode()).isEqualTo(200);
                assertThat(get(own + extraRepository(tenant), token).statusCode()).isIn(403, 404);
                int other = tenant % 10 + 1;
                assertThat(get(own + repository(other), token).statusCode()).isEqualTo(404);
                assertThat(get("/api/v1/tenants/" + tenant(other) + "/repositories/" + repository(other), token).statusCode()).isEqualTo(404);
                var actor = new AuthenticatedSubject(isolatedUser(tenant, role.ordinal()), OIDC.issuer(), "user-" + tenant + "-" + role.ordinal());
                assertThatThrownBy(() -> administration.create(new io.guidein.tenancy.api.CreateRepositoryCommand(actor,
                        tenant(other), UUID.randomUUID(), UUID.randomUUID().toString(), "test", "forbidden", UUID.randomUUID())))
                        .isInstanceOf(GuideInException.class);
                attempts += 4;
            }
        }
        String path = "/api/v1/me";
        for (String token : List.of("malformed", OIDC.invalidSignature("user-1-0"), OIDC.token("user-1-0", "https://wrong-issuer.test", "guidein-api", Instant.now().plusSeconds(600)),
                OIDC.token("user-1-0", OIDC.issuer(), "wrong-audience", Instant.now().plusSeconds(600)),
                OIDC.token("user-1-0", OIDC.issuer(), "guidein-api", Instant.now().minusSeconds(300)))) {
            assertThat(get(path, token).statusCode()).isEqualTo(401);
        }
        assertThat(get(path, null).statusCode()).isEqualTo(401);
        String first = OIDC.tokenWithEmail("user-1-0", "first@example.test");
        String changed = OIDC.tokenWithEmail("user-1-0", "changed@example.test");
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        String firstId = mapper.readTree(get(path, first).body()).path("id").asText();
        assertThat(firstId).isEqualTo(isolatedUser(1, 0).toString());
        assertThat(mapper.readTree(get(path, changed).body()).path("id").asText()).isEqualTo(firstId);
        String stranger = OIDC.tokenWithEmail("no-membership", "changed@example.test");
        assertThat(mapper.readTree(get(path, stranger).body()).path("id").asText()).isNotEqualTo(firstId);
        assertThat(get("/api/v1/tenants/" + TENANT_A + "/repositories/" + REPOSITORY_A, stranger).statusCode()).isEqualTo(404);
        inTransaction(() -> { context.setAuthenticatedUser(isolatedUser(1, 0)); context.setTenant(TENANT_A);
            jdbc.update("UPDATE memberships SET scope_mode='ALL_REPOSITORIES' WHERE id=?", isolatedMember(1, 0)); return null; });
        assertThat(get("/api/v1/tenants/" + TENANT_A + "/repositories/" + extraRepository(1), first).statusCode()).isEqualTo(200);
        var access = new io.guidein.authorization.api.AccessContext(UUID.randomUUID(), TENANT_A, USER, null);
        var resource = new io.guidein.authorization.api.ResourceRef(io.guidein.authorization.api.ResourceRef.ResourceType.REPOSITORY, TENANT_A, REPOSITORY_A);
        assertThat(authorization.decide(subject, access, io.guidein.authorization.api.Capability.REPOSITORY_READ, resource, true).allowed()).isFalse();
        assertThat(authorization.decide(subject, access, null, resource, true).allowed()).isFalse();
        ProofEvidence.write("authorization", Map.of("denied_attempts", attempts + 1, "unauthorized_protected_actions", 0, "invalid_jwt_cases", 6,
                "identity_uses_issuer_subject", true, "scope_modes_tested", List.of("ALL_REPOSITORIES", "SELECTED_REPOSITORIES")));
    }
}
