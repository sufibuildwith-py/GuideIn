# GuideIn Phase 1 Threat Model

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
