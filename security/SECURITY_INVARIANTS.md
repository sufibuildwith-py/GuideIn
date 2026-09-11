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

