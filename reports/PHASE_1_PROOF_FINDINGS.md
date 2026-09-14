# Phase 1 proof findings

Implementation baseline: `c8b5cffcf108984163aec689bcc9e695fde65f5e`.
Earlier incomplete evidence: `0d2a81bd9c21347d8a130f23eed605410b607e07`.

These defects were found while executing the real PostgreSQL proof. The earlier 62-pass/8-skipped result remains in Git history and in the Phase 1 report. No accepted migration was edited during this proof pass.

| Finding | Expected / actual | Root cause and impact | Implementation fix | Regression proof |
|---|---|---|---|---|
| Transaction proxy startup | Expected application startup; actual `Cannot subclass final class JdbcAuditLedger` | Boot defaults to class proxies, but transactional implementations were final. No application transaction could start. | Production uses JDK interface proxies (`spring.aop.proxy-target-class=false`); module callers use APIs. | Every PostgreSQL integration test starts the real Spring application. |
| Flyway activation | Expected migrations before service access; Boot 4 migration auto-configuration was absent | Flyway core alone did not supply the Boot 4 integration module. | Use `spring-boot-starter-flyway` and retain the PostgreSQL Flyway module. | Fresh database, migration-owner assertions, and `flyway.validate()` in the environment proof. |
| JWT handler binding | Valid signed JWT expected HTTP 200; actual HTTP 500 | Unannotated `Jwt` handler arguments were treated as MVC binding input instead of the authenticated principal. | Add `@AuthenticationPrincipal` to both handlers. | Real RSA/JWK HTTP suite, identity/email separation, and both scope modes. |
| Audit tail deletion | Expected detection; actual verifier returned valid after `DELETE_TAIL` | The verifier checked only remaining rows, so a valid prefix passed. | Compare final sequence/hash with persisted audit head. | Payload, previous hash, sequence, event hash, middle deletion, and tail deletion variants. |
| Audit snapshot consistency (review finding) | Head and events must describe one committed snapshot | Separate queries can see an append between them when verification joins a caller's READ COMMITTED transaction; an inner repeatable-read annotation cannot upgrade that transaction. | Read head and events in one SQL statement, preserving one snapshot even in a caller-owned transaction. | Seven concurrent appenders plus a verifier: 210 appends and 100 concurrent integrity checks. |
| Last-attempt worker death | Expected deterministic terminal state; actual DEAD count 0 and row stayed RUNNING | Reclaim filtered exhausted attempts but no worker remained to call `fail()`. | Retire exhausted expired RUNNING leases to DEAD with `LEASE_EXHAUSTED` during the next production claim. | Final-attempt crash and stale-token completion rejection. |
| Unknown role | Expected deny; immutable matrix lookup could throw for null role | Invalid/unknown authorization inputs needed validation before matrix lookup. | Deny null role alongside absent subject/capability/resource. Unknown external strings cannot become enum permissions. | Integration authorization proof asserts null/unknown role and capability deny. |
| Interruption exception assumption (test correction) | Expected failed operation; actual failure was correctly raised at transaction start | Spring wrapped a SQL connection exception whose deepest cause could be EOF. The original test asserted the wrong wrapper/root type. No successful operation occurred. | Keep production outage behavior. Test requires transaction-start failure caused by SQLException; it no longer requires SQLException to be the deepest cause. | PostgreSQL stopped, readiness 503, liveness 200, protected operation fails, restart and successful protected read. |
| Docker stopped between sessions (environment) | Expected engine available; resumed containers could not start | Docker Desktop had stopped overnight. No gate assertions ran in these failed starts. | Start the existing installation; add Docker preflight to the individual-run script. No test skip is allowed. | Individual and final executions require Testcontainers startup. |

## Transactional metrics review finding

Audit/queue success counters and depth gauges changed before commit and survived rollback. Durable-success metrics now use transaction after-commit callbacks; denial/failure counters remain immediate. `transactionMetricsDoNotReportRolledBackWritesAsCommitted` checks exact deltas after real PostgreSQL rollback, commit, and completion. The gauges remain process-local rather than durable global totals.

## Evidence interpretation

- An individual pass covers one proof method in a disposable PostgreSQL instance. The subsequent clean full suite covers their interaction against another newly initialized instance.
- The queue proof uses eight workers and eight database connections, records each acknowledged semantic completion, checks overlapping owners, and adds randomized processing delays. It does not claim exactly-once effects at an external service.
- Worker death in the outbox proof is injected as an unrecoverable error before acknowledgement, causing the production transaction to roll back. Ordinary dispatch exceptions are tested separately, followed by successful retry.
- PostgreSQL outage injection stops the actual server with `pg_ctl` inside a disposable supervised container. The same server/data/port are restarted to exercise the application's reconnect behavior.
- OTel proof uses a pinned Java agent and inspects actual structured HTTP log events for trace/span/request/correlation IDs. Exporters are disabled; remote collector delivery is not included.
