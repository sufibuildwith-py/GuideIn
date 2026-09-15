# Phase 2 proof defect journal

Phase 2 remains NOT READY until all requested proofs, restart/replay lab, and final clean suite execute successfully. Failed intermediate results are retained here.

Final disposition, 2026-09-15: the fresh clean repository suite passed 133/133 tests with zero skips, including all regressions below, the 10K process-crash replay and all 18 Phase-1 database proofs. The historical NOT READY statement above describes the earlier checkpoint. No known P0/P1 defect remains unresolved; final measurements are in `evaluation/phase2-results.json`.

## P2-PROOF-001 — Provider actor kinds collapsed into USER

- **Symptom:** `GitHubIngestionIT.actorKindsNeverBecomeAuthenticatedGuideInUsers` failed in the real PostgreSQL provenance run (26 tests: 25 passed, 1 failed, 0 skipped). Expected one APP observation; actual count was zero.
- **Root cause:** The classifier treated every positive provider actor ID as USER after only checking Bot and ghost.
- **Impact:** APP, SYSTEM, and unknown/future actor types could be recorded as human provenance. They were not authenticated as GuideIn users, but the recorded actor fact was incorrect.
- **Fix:** Explicit type mapping for User, Bot, App, and System; unknown types remain UNKNOWN. Ghost stays explicit. External IDs never create or resolve GuideIn identities.
- **Regression test:** The unchanged failing method covers USER, BOT, APP, SYSTEM, GHOST, UNKNOWN and verifies no new internal user is created.
- **Evidence:** `.m2/phase2-provenance-proof.log`, 2026-09-15. Actor regression passed in `.m2/phase2-actor-fix-proof.log`; that run separately exposed the test expectation issue below.

## P2-PROOF-002 — New missing-context test expected empty rows rather than established rejection

- **Symptom:** The cross-tenant test errored with PostgreSQL `28000: tenant context is missing` after its substitution assertions passed.
- **Root cause:** The new test assumed RLS would silently hide rows. Immutable Phase-1 migration V1 intentionally raises authorization error 28000 for missing tenant context.
- **Impact:** Incorrect proof expectation only; no exposure occurred and no implementation change was needed.
- **Fix:** Seed legitimate normalized records so every protected table is populated, then require the established 28000 rejection from each context-free query.
- **Regression test:** `crossTenantExternalIdSubstitutionFailsBeforeProviderReadsAndRlsProtectsWrites`; no Phase-1 assertion or migration changed.
- **Evidence:** `.m2/phase2-actor-fix-proof.log` (27 integration cases: 26 passed, 1 errored, 0 skipped).

## P2-PROOF-003 — Worker trace correlation was missing

- **Symptom:** The workflow observability test passed durable receipt/job/outbox linkage but failed to find a trace-linked worker completion log.
- **Root cause:** The worker fetched and normalized data without restoring its durable correlation/causation identifiers into the logging context or defining a worker trace boundary.
- **Impact:** A workflow could not be reconstructed across ingress and asynchronous processing using telemetry alone.
- **Fix:** Scoped worker MDC for tenant/job/correlation/causation, safe provider-fetch and post-commit completion records, and the existing Java agent's method instrumentation for `GitHubHydrator.processNext`. No payload, credential, URL, or raw provider exception logging is added.
- **Regression test:** `workflowTelemetryConnectsReceiptJobProviderAndNormalizationWithoutSecrets` remains unchanged.
- **Evidence:** `.m2/phase2-observability-proof.log` (3 integration cases: 2 passed, 1 failed, 0 skipped). The unchanged regression passed in `.m2/phase2-final-targeted.log` (31/31 integration cases, 0 skipped), including separately exported process spans and credential/error-response scanning.

## Proof harness corrections

These were test compilation/schema-assumption corrections, without changes to security invariants or production architecture: a compound `var` declaration was split; the post-replay audit Boolean was assigned explicitly to avoid an overloaded AssertJ inference ambiguity; outbox recovery queries use its established `published_at`/lease columns rather than an invented status column. The strict missing-context rejection above is retained and strengthened by populated protected tables.
