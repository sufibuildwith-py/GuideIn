# Phase 2 operations

Phase 2 deterministic PostgreSQL proof completed on 2026-09-15 (133/133 tests, zero skips). See [the final evidence report](../../reports/PHASE_2_REPORT.md); operational configuration and live deployment acceptance remain separate from that proof.

## Trace boundary

Keep the established OpenTelemetry Java agent deployment. Configure:

```text
OTEL_INSTRUMENTATION_METHODS_INCLUDE=io.guidein.github.application.GitHubHydrator[processNext]
```

This creates a worker trace around claiming, provider calls, and committed normalization without coupling application modules to an SDK. Correlation and causation IDs link that worker trace to the original HTTP trace through durable receipt/job/outbox records and scoped logs. The asynchronous trace is not falsely represented as the original HTTP span.

Use an approved collector in deployment; do not capture Authorization headers, bodies, private keys, tokens, or webhook secrets. Method arguments are not recorded. The proof configuration enables the same method instrumentation.

Reference: [OpenTelemetry Java agent method instrumentation](https://opentelemetry.io/docs/zero-code/java/agent/annotations/#creating-spans-around-methods-with-otelinstrumentationmethodsinclude).

## GitHub configuration

Enable ingress with `guidein.github.enabled=true`; enable the durable poller separately with `guidein.github.worker-enabled=true`. Supply client ID, client secret, app slug, callback URL, webhook secret and private-key path through the deployment secret facility. The RSA key file must be PKCS#8 (`BEGIN PRIVATE KEY`) with at least 2048 bits; convert a GitHub PKCS#1 download before deployment and protect the file. Do not paste keys into configuration committed to the repository.

Grant only Metadata, Contents, Pull requests, Checks and Commit statuses READ. The client requests that same reduced permission set when minting installation tokens. REST requests use the single version constant `2026-03-10`. Production origins are GitHub HTTPS; loopback is permitted only with the explicit test option and active test profile.

## Installation and recovery

An OWNER with tenant-wide repository scope prepares a binding. Complete the OAuth authorization URL with the saved PKCE verifier and the same signed-in GuideIn identity. State is hashed, expires after ten minutes and is consumed once before provider exchange. Failed exchanges require a new preparation. Browser installation IDs do not establish authority.

Successful webhook acceptance follows the receipt/job/outbox transaction commit. A process can stop after acceptance; PostgreSQL retains the processing intent. Keep the database when restarting the worker. Suspensions, deletion and repository removals restrict authority immediately; reconciliation is required to restore access. Historical revisions and provenance remain immutable.

Rate-limited work uses `job_queue.available_at` and installation cooldown, with Retry-After taking precedence over exhausted reset metadata and a one-minute secondary floor. Provider failures use the existing bounded job-attempt policy. Inspect stable failure codes and correlated logs. Authorized redrive uses the tenant endpoint for dead or retryable deliveries and records an audit event; it does not bypass installation authority.

## Interpreting source facts

PR revisions retain their exact head SHA and a separate provider-current projection. Capped file sets are explicit and missing patches are not fabricated. CI observations use repository, SHA, source kind, external check/status ID and reporting app/actor ID. Signature verification reasons and actor kinds are provenance facts; Phase 2 does not grant release eligibility or verify build attestations.
