# Phase 1 Security Invariants

1. Missing tenant context cannot expose or mutate tenant rows.
2. Cross-tenant resource identifiers cannot bypass application authorization or PostgreSQL RLS.
3. The runtime database role is not a table owner, superuser, role administrator, or `BYPASSRLS` role.
4. The runtime database role cannot alter schema or RLS policy and cannot update/delete audit history.
5. Authorization defaults to deny; every role/capability cell is explicit and unknown capabilities cannot be represented.
6. Authentication identity is the OIDC issuer and subject pair; email is mutable profile data.
7. Tenant and authenticated-user settings use transaction-local PostgreSQL context and cannot survive pool reuse.
8. Membership bootstrap exposes only the authenticated user's own memberships before tenant context is established.
9. Domain state and its outbox record commit or roll back together.
10. At most one current lease token can complete a job; a stale worker cannot acknowledge reclaimed work.
11. Audit payloads are RFC-8785 canonicalized and form a serialized per-tenant SHA-256 chain.
12. Access tokens, secrets, raw authorization headers, and database credentials never enter logs, error bodies, or telemetry attributes.
13. Actuator exposes only health probes anonymously; other management endpoints are denied on the public HTTP surface.
14. Liveness excludes external dependencies. Readiness includes PostgreSQL because safe authorization and state mutation require it.
15. Production configuration has no fallback password, issuer, JWK endpoint, or audience.

The integration suite must execute these invariants against PostgreSQL 18. A missing container runtime makes the Phase 1 verdict `NOT READY`, not `PASS`.

## Phase 3 — System Graph V1

36. `SEC-036`: Repository material is fetched only through an authenticated installation binding that is rechecked before, during, and after provider reads.
37. `SEC-037`: Repository paths are canonical, relative UTF-8 paths; traversal, absolute, drive-qualified, control-character, collision, and symlink inputs fail closed before blob retrieval.
38. `SEC-038`: Repository content is never executed. Maven, Gradle, npm, Docker and repository scripts remain inert input bytes.
39. `SEC-039`: OpenAPI resolution is repository-local and bounded; remote schemes, absolute references and parent traversal never trigger network or filesystem access.
40. `SEC-040`: Only explicit or mechanically resolved evidence creates authoritative graph edges. Model/LLM output creates zero authoritative edges.
41. `SEC-041`: Every persisted edge has evidence, and every endpoint ID/key pair is constrained to the same tenant and immutable snapshot.
42. `SEC-042`: Material uncertainty produces an explicit extraction gap and a `PARTIAL` snapshot; `READY` cannot contain gaps.
43. `SEC-043`: Snapshot identity includes repository, exact source SHA, builder version and extraction configuration; incompatible recipes cannot reuse or publish an old identity.
44. `SEC-044`: Graph publication is atomic with job completion and its outbox event. Unpublished rows remain unreadable and published rows are immutable.
45. `SEC-045`: Lease tokens fence stale graph workers, including workers that resume after another owner has reclaimed or completed the job.
46. `SEC-046`: Graph reads apply tenant membership, repository scope and PostgreSQL FORCE RLS; foreign and nonexistent graph resources have indistinguishable not-found semantics.
47. `SEC-047`: Traversal, parsing, source acquisition, cardinality and query work have server-side caps; caller input cannot request an unbounded graph walk.
48. `SEC-048`: Graph logs, spans, metrics, audit records, job errors and HTTP failures contain identifiers and bounded categories, never source bodies or provider secrets.

