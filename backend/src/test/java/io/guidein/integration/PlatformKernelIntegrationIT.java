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

@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.task.scheduling.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
@Tag("security")
@Tag("concurrency")
class PlatformKernelIntegrationIT {
    private static final String APP_PASSWORD = "guidein-app-test";
    private static final UUID USER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_A = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID REPOSITORY_A = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID REPOSITORY_B = UUID.fromString("30000000-0000-0000-0000-000000000002");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("guidein")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("postgres-test-init.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "guidein_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
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
            }
        }
    }

    @Test
    void applicationAndDatabasePathsDenyTenThousandCrossTenantReads() {
        AtomicInteger successfulCrossTenantReads = new AtomicInteger();
        for (int attempt = 0; attempt < 10_000; attempt++) {
            int source = (attempt % 10) + 1;
            int target = ((attempt + 1) % 10) + 1;
            try {
                repositories.get(subject, tenant(source), repository(target));
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
    }

    @Test
    void databasePathDeniesTenThousandCrossTenantWrites() throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "guidein_app", APP_PASSWORD);
             var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SELECT set_config('guidein.tenant_id', '" + TENANT_A + "', true)");
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
    }

    @Test
    void oneConnectionPoolAlternationNeverLeaksTenantState() {
        for (int index = 0; index < 2_000; index++) {
            UUID tenant = index % 2 == 0 ? TENANT_A : TENANT_B;
            UUID repository = index % 2 == 0 ? REPOSITORY_A : REPOSITORY_B;
            RepositoryView result = repositories.get(subject, tenant, repository);
            assertThat(result.tenantId()).isEqualTo(tenant);
            UUID forbidden = index % 2 == 0 ? REPOSITORY_B : REPOSITORY_A;
            assertThatThrownBy(() -> repositories.get(subject, tenant, forbidden))
                    .isInstanceOf(GuideInException.class);
        }
    }

    @Test
    void everyTenantProtectedTableFailsClosedWithoutContext() {
        for (String table : List.of("tenants", "memberships", "repositories", "membership_repository_scopes",
                "audit_heads", "audit_events", "outbox_events", "job_queue")) {
            assertThatThrownBy(() -> inTransaction(() -> jdbc.queryForObject("SELECT count(*) FROM " + table,
                    Integer.class))).isInstanceOf(DataAccessException.class);
        }
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
        assertForbidden("ALTER ROLE guidein_app BYPASSRLS");
        assertForbidden("UPDATE audit_events SET action='tampered'");
        assertForbidden("DELETE FROM audit_events");
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
    }

    @Test
    void tenThousandJobsHaveOneSemanticCompletionUnderEightWorkers() throws Exception {
        int total = 10_000;
        for (int index = 0; index < total; index++) {
            jobs.enqueue(new JobCommand(TENANT_A, "phase1.test", "job-" + index, Map.of("index", index),
                    Instant.now(), 5, UUID.randomUUID(), null));
        }
        ConcurrentHashMap<UUID, AtomicInteger> completions = new ConcurrentHashMap<>();
        try (var workers = Executors.newFixedThreadPool(8)) {
            for (int worker = 0; worker < 8; worker++) {
                workers.submit(() -> {
                    while (true) {
                        ClaimedJob job = jobs.claimNext(TENANT_A).orElse(null);
                        if (job == null) return;
                        if (jobs.complete(TENANT_A, job.id(), job.leaseToken())) {
                            completions.computeIfAbsent(job.id(), ignored -> new AtomicInteger()).incrementAndGet();
                        }
                    }
                });
            }
            workers.shutdown();
            assertThat(workers.awaitTermination(3, TimeUnit.MINUTES)).isTrue();
        }
        assertThat(completions).hasSize(total);
        assertThat(completions.values()).allSatisfy(count -> assertThat(count).hasValue(1));
        assertThat(statusCount("SUCCEEDED")).isEqualTo(total);
        assertThat(statusCount("READY") + statusCount("RUNNING")).isZero();
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
}
