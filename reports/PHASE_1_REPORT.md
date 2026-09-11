# GuideIn Phase 1 — Platform Kernel Report

Evaluation date: 2026-09-11 (Asia/Calcutta)

## 1–3. Executive result, verdict, and exact revision

**FAIL — Phase 2 readiness is NOT READY.** The kernel implementation exists and the build plus 62 non-container tests pass, but all eight PostgreSQL 18 integration tests were skipped because Testcontainers found no usable Docker engine. The zero-tolerance database, RLS, role, outbox, audit, pool-isolation, and job-concurrency gates are therefore **unmeasured**, not zero.

- Implementation commit: `c8b5cffcf108984163aec689bcc9e695fde65f5e`
- V1 preservation tag: `v1-career-mentor` at `df8ba52e0051ec245c69f03c688f58a2d5a8a724`
- Machine-readable evidence: `evaluation/phase1-results.json`

## 4–5. Architecture and module map

Java 21 / Spring Boot 4.1.1 modular monolith, enforced by Spring Modulith verification. Cross-module use goes through named `api` packages.

| Module | Responsibility |
|---|---|
| `platform` | Stable errors, canonical JSON, request identity, tenant-context port, security/configuration |
| `identity` | OIDC issuer/subject to internal-user resolution |
| `authorization` | Explicit role/capability/resource decisions, deny by default |
| `tenancy` | Membership bootstrap, repository scope, transaction-local DB context |
| `audit` | Per-tenant append-only hash ledger and verifier |
| `events` | Transactional outbox contract, writer, leased dispatcher |
| `jobs` | Durable deduplicated queue, leases, retries, guarded terminal outcomes |
| `observability` | Bounded Micrometer counters and operational gauges |
| `web` | Thin authenticated API adapters |

## 6. Database schema summary

PostgreSQL tables: `users`, `tenants`, `memberships`, `membership_repository_scopes`, `repositories`, `audit_heads`, `audit_events`, `outbox_events`, and `job_queue`. UUID keys, foreign keys, state checks, partial unique active-job deduplication, tenant-aware indexes, RLS policies, and runtime grants are migration-owned.

## 7. Authentication design

Spring Security OAuth2 Resource Server validates JWT signature, lifetime, issuer, and audience. Identity is keyed by `(auth_issuer, external_subject)`; email/display name are mutable profile data. The API is stateless and has no password login, homemade token, or development bypass.

## 8. Authorization design

One service evaluates `AuthenticatedSubject + AccessContext + Capability + ResourceRef -> AccessDecision`. Seven roles and seven capabilities form 49 explicit cells; the parameterized suite covers all 49 plus seven cross-tenant mismatches. Protected operations default to deny and hostile UUIDs receive non-enumerating not-found behavior where appropriate.

## 9–10. RLS and tenant-context lifecycle

All tenant tables use `ENABLE ROW LEVEL SECURITY`, `FORCE ROW LEVEL SECURITY`, `USING`, and `WITH CHECK`. `guidein_app` is neither owner nor `BYPASSRLS`. Strict `guidein_current_tenant()` fails with SQLSTATE `28000` when context is absent. The safe sequence is: validate JWT, set authenticated user transaction-locally, read only that user's membership, authorize, set tenant with `set_config(..., true)`, then access protected rows. Transaction-local settings cannot persist into pooled reuse.

## 11. Audit ledger

Audit appends lock a per-tenant head, assign a monotonic sequence, RFC 8785-canonicalize the payload, hash an explicit length-framed field sequence plus previous hash, insert the event, and update the head in one transaction. Runtime update/delete is denied and a trigger rejects mutation. Verification recomputes the chain. This is tamper-evident, not externally anchored or signed.

## 12. Transactional outbox

Domain writes and outbox insertion use the same JDBC transaction. Envelopes include event/tenant/aggregate IDs, type/version, canonical payload/hash, correlation/causation IDs, and occurrence time. Claims use ordered `FOR UPDATE SKIP LOCKED`, lock tokens, expiry, attempts, and guarded acknowledgement. Delivery is at-least-once; consumers must be idempotent.

## 13. Durable job queue

States are `READY`, `RUNNING`, `SUCCEEDED`, `FAILED`, and `DEAD`. Claims are ordered and skip locked rows; worker ownership is a bounded lease token. Completion requires a current unexpired token. Retry categories drive fixed bounded backoff. A partial unique index prevents simultaneous active duplicates, and `INSERT ... ON CONFLICT ... DO NOTHING RETURNING` avoids PostgreSQL's aborted-transaction trap.

## 14. Error contract

RFC 9457 `application/problem+json` responses use stable URN type, title, HTTP status, error code, retryability, request ID, and correlation ID. Validation and unknown exceptions do not expose stack traces or raw exception messages.

## 15. Observability

Structured JSON console logging is enabled. Request and correlation IDs are accepted only when valid UUIDs, otherwise generated. Metrics cover authorization denials, cross-tenant denials, audit append/integrity failure, outbox failure/pending, and job claims/retries/dead/depth without tenant-cardinality labels. The OpenTelemetry Java agent is the deployment choice; no OTel starter or SDK coupling was introduced.

## 16. Health/readiness

Only liveness/readiness/health are anonymous; other actuator paths are denied. Liveness includes only application liveness state. Readiness includes readiness state and PostgreSQL `db`, because safe reads/writes require the database. Details are hidden.

## 17–19. Research, adopted patterns, and rejected patterns

The full research record is `docs/research/PHASE_1_RESEARCH.md`. Adopted: modular verification, OIDC resource server, RFC 9457, PostgreSQL RLS and transaction-local context, RFC 8785, hash-chain audit, transactional outbox, leased PostgreSQL queue, Flyway, Testcontainers, Actuator semantics, and agent-first OTel. Deliberately rejected/deferred: microservices, Kafka, RabbitMQ, Redis, Neo4j, Elasticsearch, Kubernetes, Temporal, SpiceDB runtime, Debezium runtime, JPA/Hibernate, custom auth, session tenant variables, and external dependencies in liveness.

## 20. Dependencies added

| Dependency | Version | License | Reason |
|---|---:|---|---|
| Spring Boot starters (web, security, OAuth2 RS, JDBC, validation, actuator) | 4.1.1 | Apache-2.0 | Supported Java platform and kernel facilities |
| Spring Modulith core/test | 2.1.1 | Apache-2.0 | Module boundary verification |
| Flyway core + PostgreSQL module | 12.4.0 | Apache-2.0 core / Redgate terms | Versioned PostgreSQL 18 migrations |
| PostgreSQL JDBC | 42.7.13 | BSD-2-Clause | Native database access |
| Java JSON Canonicalization | 1.1 | Apache-2.0 | RFC 8785-compatible audit/event hashing |
| Testcontainers JUnit/PostgreSQL | 2.0.5 | MIT | Real PostgreSQL integration tests |

Spring/Java/PostgreSQL supplied the rest. No runtime broker, cache, graph store, ORM, or telemetry SDK was added.

## 21. Migrations

1. Runtime role and strict context functions
2. Identity and tenancy
3. Repository resource and selected scopes
4. RLS policies
5. Audit ledger, immutability trigger, grants
6. Transactional outbox
7. Durable job queue and active dedupe index
8. Supporting indexes, grants, and schema hardening

## 22–23. Tests and counts

Final `mvn clean verify` discovery: **70 total, 62 passed, 0 failed, 8 skipped**.

- 1 architecture verification
- 56 authorization cases (49 matrix + 7 tenant mismatch)
- 2 RFC 8785 vectors
- 2 request/correlation identity cases
- 1 secret-canary/error-sanitization case
- 8 PostgreSQL integration/security/concurrency cases, all skipped without Docker

The integration class covers 10,000 cross-tenant reads, 10,000 cross-tenant writes, pool size 1 alternation, missing context on every protected table, runtime-role restrictions, audit tamper detection, outbox rollback in both directions, and 10,000 jobs under eight workers.

## 24–25. Security and concurrency evaluation

| Gate | Actual result | Status |
|---|---:|---|
| Architecture module violations | 0 | PASS |
| Secret leakage unit failures | 0 | PASS (unit scope) |
| Unhandled authorization matrix cells | 0 | PASS |
| Cross-tenant successful reads/writes | not measured | BLOCKED |
| Unauthorized protected actions | not measured | BLOCKED |
| Runtime audit mutations | not measured | BLOCKED |
| Missing-context exposures | not measured | BLOCKED |
| Pool tenant leaks | not measured | BLOCKED |
| Outbox atomicity violations | not measured | BLOCKED |
| Duplicate semantic job completions | not measured | BLOCKED |
| Flyway validation errors | not measured | BLOCKED |

No blocked row is represented as zero. Container tests were skipped rather than replaced with H2 or mocks.

## 26. Performance baseline

Not executed. Authorization p50/p95, tenant query p50/p95, audit append p50/p95, job claim throughput, and outbox claim throughput remain `null`. Database-free numbers would not satisfy the requested kernel baseline, and no production claims are made.

## 27–28. Failures encountered and resolutions

| Failure | Resolution |
|---|---|
| Original repository was V1 desktop code with tracked build artifacts | Tagged V1, preserved it under `legacy/desktop-v1`, removed generated artifacts, and established the V2 reactor |
| Maven defaulted to an unusable `C:\.m2` location | Used repository-local `.m2/repository` and ignored it |
| Boot 4 uses Jackson 3 packages | Adapted API code to Boot-managed Jackson 3 rather than pinning older Jackson |
| Modulith test initially saw stale deleted classes | Verified from a clean build |
| JCS test fixture initially used a non-normative expectation | Replaced it with RFC 8785 vectors |
| Job dedupe originally caught a uniqueness exception inside a transaction | Replaced with partial-index-aware `ON CONFLICT ... DO NOTHING RETURNING` |
| Lease check allowed a single orphan lease field | Tightened the database check to require both fields or neither |
| Docker/Testcontainers unavailable | Kept real PG18 tests, recorded eight skips and blocked readiness |

## 29–31. Compromises, limitations, and accepted debt

- Phase 1 source is implemented, but database-backed proof, live OIDC acceptance, the 26-step demo, failure interruption/recovery, and performance measurement remain incomplete.
- Pending/depth gauges are process-local operational hints and reset at restart. A future safe aggregate mechanism must not bypass tenant isolation.
- Audit integrity is a hash chain without external anchoring/signatures.
- Outbox delivery is at-least-once.
- OTel agent compatibility needs a deployment smoke test on the chosen runtime image.
- Testcontainers 2.0.5 reports a deprecation warning for its typed PostgreSQL container API; it does not fail compilation.

## 32. Threat-model changes

`security/THREAT_MODEL.md` now covers hostile JWTs and UUIDs, tenant-header spoofing, pool leakage, runtime credential compromise, audit mutation, concurrent ledger forks, state/event split brain, stale workers, secret disclosure, and management exposure. `security/SECURITY_INVARIANTS.md` records 15 non-negotiable controls.

## 33. ADRs created

ADR-001, 004, 005, and 013–020 cover modular monolith, outbox, RLS, JDBC, tenant resolution, authentication, capability authorization, canonical audit, PostgreSQL jobs, OTel agent, and DB-role separation.

## 34. Exact exit-gate evidence

- `mvn clean verify`: build PASS.
- Surefire: 62 passed, 0 failed, 0 skipped.
- Failsafe: 8 discovered, 0 failed, 8 skipped because no valid Docker environment was found.
- PostgreSQL 18 image declared: `postgres:18-alpine` in Testcontainers and Compose.
- Zero-tolerance database metrics: `null` / `NOT_EXECUTED` in the evaluation JSON.
- Result: Phase 1 FAIL; Phase 2 NOT READY.

## 35. Phase 2 must account for

Do not begin Phase 2. First run Docker/PostgreSQL 18, execute and fix all integration gates, add a real OIDC issuer/JWK acceptance fixture, capture structured logs/traces and health transition evidence, run failure injection, and record local performance baselines. Expand the adversarial fixture to multiple users, every role, and selected scopes before accepting the 10,000-attempt result.

## 36. Recommended plan assumption changes

| Plan assumption | Observed problem | Evidence | Proposed change | Future impact |
|---|---|---|---|---|
| Global queue-depth and outbox-pending gauges are simple under strict RLS | Runtime role cannot safely aggregate all tenants without broadening privilege | RLS design and non-owner role | Treat current gauges as process-local; design a constrained stats projection or per-tenant operational view | Phase 2 operations must avoid privileged global queries |
| A complete local acceptance run is always available | This host has neither a running Docker engine nor local PostgreSQL | Testcontainers discovery failure in final verify | Make container runtime a preflight hard prerequisite and run the same suite in CI | Readiness cannot be inferred from compilation/build success |

## 37. Phase-2 readiness verdict

**NOT READY.** The implementation is a credible kernel candidate, but Phase 1 success requires executed PostgreSQL/OIDC/failure/health evidence. Those gates are not waived.

## Phase-0 prerequisites completed during Phase 1 preflight

- Captured the locked plan as `PLAN.md`.
- Tagged the V1 lineage as `v1-career-mentor`.
- Preserved V1 under `legacy/desktop-v1`.
- Established the root/backend Maven reactor, `.gitignore`, safe environment template, PostgreSQL Compose definition, CI workflow, and documentation structure.

## Research appendix

| Source | Problem studied | Borrowed | Rejected | Why | License/status |
|---|---|---|---|---|---|
| Spring Boot / Actuator | Java compatibility and availability | Boot 4.1.1, probes | External deps in liveness | Supported Java 21 platform | Apache-2.0 |
| Spring Modulith + upstream issues | Boundaries and event recovery | Verification | Publication registry as outbox | Explicit multi-instance ownership needed | Apache-2.0 |
| PostgreSQL 18 docs | RLS, context, locks | FORCE RLS, local settings, SKIP LOCKED | App predicates/session settings alone | DB-enforced isolation and queue semantics | PostgreSQL License |
| AWS SaaS RLS guidance | Pooled tenancy | Shared schema + RLS | DB/schema per tenant | Matches Phase 1 scale/scope | Documentation |
| Zanzibar / SpiceDB | Permission semantics | Stable subject-capability-resource vocabulary | Distributed ReBAC service | Preserve migration path without service sprawl | Paper / Apache-2.0 |
| OWASP Authorization | Access-control failure modes | Deny default, every request, IDOR tests | Scattered role checks | Central reviewable policy | CC BY-SA 4.0 docs |
| Spring Security | JWT validation | Signature/time/issuer/audience | Custom/password auth | Standard stateless OIDC | Apache-2.0 |
| Stripe request/idempotency docs | Retry identity | Request/correlation IDs, dedupe concepts | HTTP response cache | Kernel primitives only | Proprietary docs; concepts only |
| RFC 9457 | API errors | Problem Details | Raw exception echo | Stable safe machine contract | IETF standard |
| CloudTrail integrity model | Audit tamper evidence | Previous-hash chain | AWS signing architecture | Local verifiable baseline | Documentation; concepts only |
| RFC 8785 / JCS | Deterministic hashing | Canonical JSON library | Homegrown numeric formatting | Standards-compatible bytes | IETF / Apache-2.0 |
| Debezium outbox docs | Atomic events | Envelope and transaction pattern | Debezium/Kafka runtime | Broker-neutral Phase 1 | Apache-2.0 |
| OpenTelemetry docs/issues | Instrumentation | Java agent + bounded metrics | Starter/SDK coupling | Broad coverage, verify Boot 4 | Apache-2.0 |
| Flyway docs | PostgreSQL 18 migrations | Immutable ordered migrations | Edited migrations | Repeatable schema provenance | Apache-2.0 core / Redgate terms |
| Testcontainers docs | Real DB tests | PG18 container suite | H2 security substitute | PostgreSQL behavior is the subject | MIT |

All links, access dates, edge cases, classifications, and expanded rationale are retained in `docs/research/PHASE_1_RESEARCH.md`.
