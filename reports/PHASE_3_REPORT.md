# Phase 3 — System Graph V1

## Final evidence closeout — 2026-09-17

GuideIn Phase 3 is complete and proven. The final clean `mvn clean verify` run completed with `BUILD SUCCESS` in 31:52: 115 unit/architecture tests and 74 integration tests, 189/189 passed, zero failures, errors or skips. The integration layer preserved all Phase-1 and Phase-2 proofs and added 24 PostgreSQL-backed graph proof methods, which completed in 509.8 seconds.

The proof ran PostgreSQL 18.6 from disposable empty databases, applied all 11 migrations from zero using `guidein_migrator`, validated Flyway state, and exercised the non-superuser `guidein_app` role. No manual schema patch was used.

## Proof history

Phase 3 was not declared complete when implementation or unit tests first became green. PostgreSQL persistence, HTTP authorization, reproducibility, crash recovery, graph diff and medium-scale finalization remained explicit open gates. The first 50,000-node / 250,000-edge attempt exposed a genuine finalization-scale defect; subsequent measurement exposed a separate graph-diff scale defect. Both failures and every earlier correctness/security defect remain in [the defect journal](PHASE_3_DEFECT_JOURNAL.md).

The final implementation baseline is `cf1898d871a6fddd8f33829a2e62610c6bc33e18`. Evidence closeout is intentionally separate from that implementation commit.

## What is proven

GuideIn can reconstruct a structural graph for an immutable source revision, attach deterministic evidence to trusted relationships, surface unresolved topology as explicit gaps, and reproduce the same persisted graph independently of discovery, extractor completion and insertion order. Publication is transactional: immutable graph rows, snapshot state, job completion and outbox intent succeed or roll back together. Real process termination exposed no half-written published graph and recovered to one valid product.

The graph remains tenant- and repository-scoped through signed HTTP authorization and PostgreSQL FORCE RLS. Static extraction does not execute repository code, does not use an LLM as graph authority, does not resolve remote OpenAPI references, and rejects hostile Git paths/symlinks before blob retrieval. Traversal terminates cycles and enforces depth, node, edge and page budgets. Snapshot diff uses canonical structural identity and exact evidence-set digests.

## Final test accounting

| Layer | Passed | Failed | Errors | Skipped |
|---|---:|---:|---:|---:|
| Unit + architecture | 115 | 0 | 0 | 0 |
| Phase-1 PostgreSQL | 18 | 0 | 0 | 0 |
| Phase-2 integration/adversarial | 31 | 0 | 0 | 0 |
| Phase-2 10K replay | 1 | 0 | 0 | 0 |
| Phase-3 PostgreSQL graph | 24 | 0 | 0 | 0 |
| **Total** | **189** | **0** | **0** | **0** |

Skipped Phase-3 proof tests: **0**. Phase-1 regressions: **0**. Phase-2 regressions: **0**.

## Graph quality

The final oracle evaluated 20 labeled fixtures. It found all 2,132 required trusted relationships, missed none, emitted zero false trusted edges, and produced the expected 10 gaps with zero unexpected gaps.

| Metric | Result |
|---|---:|
| Known explicit dependency recall | 100% |
| False trusted edge rate | 0% |
| Required relationships | 2,132 |
| Required relationships found | 2,132 |
| False trusted edges | 0 |
| Expected / unexpected gaps | 10 / 0 |
| Heuristic edges | 0 |

The file-tree fixture deliberately contributes 2,062 of the 2,132 required relationships, so extractor-level results are reported separately rather than presenting the aggregate as language/dependency coverage.

| Extractor/source | Required trusted | Found | Missed | False trusted | Gaps |
|---|---:|---:|---:|---:|---:|
| GuideIn config | 5 | 5 | 0 | 0 | 0 |
| Maven | 9 | 9 | 0 | 0 | 1 |
| Gradle static | 3 | 3 | 0 | 0 | 2 |
| npm | 6 | 6 | 0 | 0 | 1 |
| Java | 27 | 27 | 0 | 0 | 3 |
| OpenAPI | 4 | 4 | 0 | 0 | 1 |
| Docker Compose | 13 | 13 | 0 | 0 | 1 |
| Deployment manifests | 3 | 3 | 0 | 0 | 0 |
| File tree | 2,062 | 2,062 | 0 | 0 | 1 |

Gaps are successful fail-closed observations: dynamic/unsupported configuration, parse failures, unresolved symbols, blocked references or rejected paths remain visible instead of becoming trusted edges.

## Reproducibility

The persisted proof executed 100 independent builds across nine extractors while randomizing file order, extractor invocation, parallel completion, node order, edge order, evidence order and database insertion order.

- Runs: 100
- Identical PostgreSQL reconstructed digests: 100
- Distinct persisted digests: 1
- In-memory versus persisted digest mismatches: 0
- Snapshot reproducibility: 100%
- Elapsed: 99,902.2577 ms

## Security and trust gates

All counters below were exercised by named unit or PostgreSQL/HTTP proof methods; none is a green-by-assumption placeholder.

| Gate | Result | Principal proof |
|---|---:|---|
| Cross-tenant graph reads | 0 | RLS table sweep and signed HTTP explorer |
| Cross-tenant graph writes | 0 | RLS and composite-FK write adversaries |
| Missing-tenant-context exposures | 0 | Runtime-role fail-closed reads |
| Tenant graph-resource existence leaks | 0 | Foreign/nonexistent snapshot and node response equivalence across 7 endpoints |
| Unauthorized repository reads | 0 | ALL/SELECTED repository scope proof |
| Authoritative LLM-created edges | 0 | Model provider not configured; graph engine has no model dependency |
| Repository code executions | 0 | Process counter plus static Gradle/deployment adversaries |
| Remote OpenAPI fetches | 0 | Remote, file, metadata-address and traversal reference adversaries |
| Path escapes | 0 | Source path suite and provider pre-fetch tree validation |
| Unsafe-path / symlink blob fetches | 0 / 0 | Hostile Git tree integration proof |
| Unbounded traversals | 0 | Cyclic and over-budget PostgreSQL traversal proof |
| Incorrect READY partial snapshots | 0 | Material gaps publish `PARTIAL`, never `READY` |
| Stale configuration publications | 0 | Builder/config identity checked before source fetch/write |
| Simultaneous valid graph owners | 0 | 3-second lease-renewal proof |
| Secret/source-content leaks | 0 | Captured log/telemetry/audit/error canary scan |

The graph observability flow linked `trace_id`, `span_id`, `correlation_id`, `causation_id`, `job_id` and `snapshot_id` across source retrieval, extraction, canonicalization, persistence and publication without exposing secrets or source canaries.

## Snapshot semantics and recovery

`READY` means the published snapshot has no material extraction gaps. Any material unknown makes the published product `PARTIAL`; a build that cannot safely publish becomes `FAILED`. Database triggers independently validate final state, counts and evidence coverage. Published graph rows are immutable.

Outbox-insert failure and job-completion failure were injected in both transaction directions; neither left published graph rows or a false event. A killed application process exposed zero half-written/corrupt READY snapshots to 20 concurrent readers, then recovered to one snapshot and one event. Expired/reclaimed leases reject the stale worker's renew/complete operations, and a final-attempt crash deterministically retires the snapshot instead of leaving it BUILDING.

## Medium-scale proof

The final clean synthetic performance fixture contained exactly 50,000 nodes, 250,000 edges, 250,000 evidence rows and zero gaps. It published `READY` with zero duplicate logical edges, orphan edges or trusted edges without evidence, and its canonical digest validated.

| Stage | Final clean time (ms) |
|---|---:|
| Candidate generation | 738.2605 |
| Canonicalization + canonical digest | 4,203.2825 |
| Node persistence | 9,810.8551 |
| Edge persistence | 72,052.1615 |
| Evidence persistence | 52,608.3592 |
| Gap persistence | 0.0367 |
| Set-based validation | 663.6974 |
| Snapshot finalization update/trigger | 410.1466 |
| Transaction commit | 1.4622 |
| Persistence (outer measurement) | 151,774.7806 |
| Total build (candidate + canonicalization/digest + persistence) | **156,716.3236** |

Canonical digest time is the measured canonicalization/hash stage and is included in canonicalization; it is not an additional additive stage. The final database occupied 825,693,887 bytes after both scale snapshots and associated proof data.

### Query-plan evidence

`EXPLAIN (ANALYZE, BUFFERS)` was captured from the populated PostgreSQL 18.6 database using the production SQL shapes:

| Query | Execution (ms) | Relevant plan property |
|---|---:|---|
| Canonical node lookup | 0.252 | canonical unique index scan |
| Forward edge lookup | 0.179 | `idx_graph_edge_forward` |
| Reverse edge lookup | 0.190 | `idx_graph_edge_reverse` |
| Type/page query | 0.204 | bounded index scan |
| Recursive traversal | 54.696 | recursive CTE with bounded indexed expansions |
| Evidence coverage | 87.337 | index-only scan and one aggregate |
| Diff support | 480.270 | canonical hash full join; bounded ranked output |

No validation invariant was deleted. Composite foreign keys enforce that edge endpoints and their persisted canonical keys belong to the same tenant and snapshot. Set-based counts verify nodes, edges, evidence coverage and gaps before an independently enforced finalization trigger.

## Traversal scale

Each row is 12 samples. `visited` and `scanned` are maximum observed counts.

| Direction/depth | p50 ms | p95 ms | Visited | Edges scanned |
|---|---:|---:|---:|---:|
| Forward 1 | 15.6484 | 53.8763 | 7 | 6 |
| Forward 2 | 19.6340 | 42.2107 | 27 | 41 |
| Forward 4 | 22.4443 | 31.4279 | 176 | 429 |
| Reverse 1 | 15.0643 | 20.9032 | 6 | 5 |
| Reverse 2 | 14.7352 | 16.9938 | 21 | 30 |
| Reverse 4 | 18.5436 | 31.3994 | 126 | 280 |
| Forward over-budget request | 63.7604 | 84.0460 | 1,559 | 5,545 |
| Reverse over-budget request | 42.8745 | 73.3644 | 1,125 | 3,660 |

Budget-exceeding requests returned deterministic truncated results; they did not become unbounded. The maximum observed p50/p95 across all traversal shapes was 63.7604/84.0460 ms.

## Graph diff scale

Snapshot B introduced controlled structural changes. Expected and observed counts matched exactly: +500/-250 nodes and +2,000/-1,000 edges. Diff duration was 1,034.1408 ms. Repeating the small canonical/symmetry corpus 100 times returned identical results.

## Defects preserved from proof

### Snapshot finalization scale collapse

The first 50k/250k run reached finalization, ran for roughly 2,170 seconds and eventually lost the database connection. PostgreSQL had statistics for none of the transaction's uncommitted edges, estimated approximately one row and selected a nested-loop anti-join that repeatedly scanned the roughly 250,000-row evidence set.

The fix replaced that query with equivalent set-based evidence coverage, retained explicit timed pre-finalization validation, used smaller bounded persistence batches, and kept lease renewal/fencing. The endpoint anti-join was removed only after measurement showed it duplicated validated composite foreign keys; endpoint integrity was not weakened.

### Graph diff full reconstruction

The initial medium diff exceeded a 5-second timeout and then a 30-second timeout. It rebuilt canonical endpoint keys and aggregated evidence across both snapshots at query time, involving about 500,000 edge rows. Edges now persist constrained canonical endpoint keys and a deterministic evidence-set digest. Composite foreign keys tie every canonical key to the exact node ID, so endpoint integrity remains intact while diff becomes an indexed structural comparison.

### Stale builder/config publication

A worker running a newer builder could claim a job queued under an older builder/config identity, risking a mislabeled snapshot. The worker now verifies the persisted builder version and configuration hash before source retrieval or graph writes. The final regression observed zero stale configuration publications.

### Earlier correctness and security findings

The proof also found and fixed: same-line unresolved imports collapsing into one gap; JavaParser token removal losing evidence positions; legal local OpenAPI `./` references being rejected; fact collection depending on extractor arrival order; HTTP 403/404 behavior leaking graph existence; and a deliberate three-second lease exposing insufficient renewal inside long persistence batches. P3-012 additionally moved hostile Git path/symlink rejection ahead of blob retrieval. Full symptom/root-cause/impact/fix/regression entries are preserved in the defect journal.

## Known limitations

- The scale graph is synthetic performance evidence, not a substitute for the labeled semantic oracle.
- V1 intentionally supports the documented Java/build/OpenAPI/Compose/deployment/config sources; unsupported or dynamic topology becomes an explicit gap.
- OpenAPI references are repository-local. Remote resolution is deliberately prohibited.
- Graph evidence describes structure; it does not yet create Change Passports, blast-radius policy or release decisions.

## Final gate

All Phase-3 hard gates passed: recall 100% (required >=98%), false trusted edge rate 0% (required <=1%), reproducibility 100%, every zero-tolerance security counter zero, no Phase-1 or Phase-2 regression, no skipped Phase-3 proof, and no unresolved P0/P1 Phase-3 defect.

**Phase 4 readiness: READY. Phase 4 has not started.**
