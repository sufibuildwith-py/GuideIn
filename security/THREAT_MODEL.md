# GuideIn Cumulative Threat Model

## Assets

Tenant data, identity mappings, membership and repository scope, audit history, outbox state, job ownership, database credentials, JWT validation configuration, and telemetry integrity.

## Trust boundaries

```text
untrusted HTTP/JWT/resource IDs
  -> Spring Security signature + issuer + audience validation
  -> issuer/subject internal identity
  -> authenticated-user-scoped membership bootstrap
  -> centralized capability/resource decision
  -> transaction-local tenant context
  -> PostgreSQL RLS under non-owner runtime role
```

Repository data, future provider payloads, and model output are outside Phase 1 and remain untrusted by design.

## Principal threats and controls

| Threat | Control | Verification |
|---|---|---|
| Forged, expired, or wrong-issuer JWT | Spring Security resource server; issuer, lifetime, signature, audience validators | Security configuration and future issuer fixture tests |
| Tenant header spoofing | No tenant header establishes authority | API accepts tenant only as hostile resource routing input |
| Cross-tenant UUID / IDOR | Membership-before-context, indistinguishable not-found, RLS | 10,000-attempt application and direct-DB suite |
| Pool context leakage | `set_config(..., true)` only inside transactions | Single-connection A/B alternation test |
| Runtime credential compromise | Non-owner, no DDL/role administration/BYPASSRLS, FORCE RLS | Forbidden-operation database tests |
| Audit modification or deletion | Privilege revocation, rejection trigger, hash chain | Runtime mutation tests and privileged tamper detection |
| Concurrent audit fork | Locked `audit_heads` row | Concurrent append coverage (requires PostgreSQL) |
| State/event split brain | Domain mutation and outbox insert share transaction | Two-direction rollback injection |
| Duplicate or stale job completion | `SKIP LOCKED`, unique active dedupe key, lease token and expiry guard | 10,000 jobs with eight workers and stale lease tests |
| Secret disclosure | Safe Problem Details, no exception echo, structured-log policy, bounded telemetry labels | Exception canary test passes; full structured-log capture remains an acceptance gate |
| Management endpoint exposure | Anonymous access only to three health endpoints; all other actuator paths denied | HTTP security tests remain required with a running test environment |

## Residual risks / Phase 1 limitations

- The local environment used for the initial implementation has no Docker or PostgreSQL, so database-backed security claims are not yet executed evidence.
- OIDC integration is structurally real but needs an issuer/JWK fixture or local IdP acceptance run.
- The audit chain is tamper-evident, not externally anchored or digitally signed.
- Outbox delivery is at-least-once. Consumers must be idempotent.
- The internal system-worker tenant selection boundary is not exposed over HTTP; later orchestration must not convert it into caller-controlled authority.

The limitations above preserve the historical Phase-1 checkpoint. PostgreSQL, JWT/HTTP, observability, interruption/recovery, and provider boundaries were subsequently executed and recorded in the Phase-1 and Phase-2 reports.

## Phase 3 trust boundary — repository material to immutable graph

```text
installation-scoped provider reads
  -> exact commit/tree/blob verification
  -> bounded canonical repository paths
  -> inert static extractors (no repository execution, no AI authority)
  -> deterministic fact reconciliation + explicit gaps
  -> canonical snapshot digest
  -> one fenced PostgreSQL publication transaction
  -> immutable RLS-protected graph + outbox evidence
```

| Threat | Control | Verification |
|---|---|---|
| Malicious repository executes during analysis | Static parsers only; no build tool, Docker, package-manager or script invocation | JFR process-start proof across 20 source-reviewed fixtures; hostile input suite |
| Path traversal, symlink or unsafe Git tree entry | Canonical relative path validation before blob retrieval; regular blob modes only | Hostile Git tree test records zero unsafe/symlink blob fetches |
| OpenAPI SSRF or filesystem read | Local references only after canonical in-repository resolution | Remote schemes, absolute paths and parent traversal adversarial suite |
| False topology inferred from names/prose/co-location | Closed edge vocabulary and explicit/resolved evidence trust classes | MUST_NOT_EXIST oracle traps and zero false trusted edges |
| Parser ambiguity silently appears complete | Typed material gaps; database enforces READY has zero gaps and PARTIAL has at least one | READY/PARTIAL and hostile parser regressions |
| Extractor or insertion ordering changes meaning | Source precedence, canonical tie-breaks, sorted identities and RFC-8785 digest | 100 shuffled in-memory runs and 100 persisted PostgreSQL builds |
| Half-published or stale-worker graph | One transaction, lease renewal/token fence, immutable final state, atomic outbox | rollback injection, reclaim/fencing and real JVM crash tests |
| Cross-tenant graph UUID substitution / existence oracle | membership and repository scope before graph access, normalized not-found, FORCE RLS and composite tenant keys | signed HTTP explorer and direct runtime-role tests |
| Traversal/cardinality denial of service | file/byte/node/edge/gap/depth/node/work/time caps | hostile cardinality test and 50k-node/250k-edge bounded traversal proof |
| Source or secret leakage through operations data | identifier-only structured logs, bounded metric labels and safe failure categories | canary scan across logs, telemetry, audit, outbox, job errors and HTTP bodies |

## Phase 3 residual limitations

- V1 recognizes the documented Java, Maven, static Gradle, npm, OpenAPI, Compose, basic Kubernetes and Dockerfile subset. Unsupported or dynamic topology remains an explicit gap rather than a guess.
- Graph diff is structurally bounded and paged to 1,000 results per category; larger totals are reported as truncated.
- The graph is evidence for later consumers. It does not calculate blast radius, policy eligibility, release decisions or AI explanations.
