# Phase-3 defect journal

Phase 3 completed its final clean proof on 2026-09-17. Entries are retained after fixes pass so the evidence does not sanitize the implementation history.

## P3-001 — Unresolved imports on one line collapsed

- Symptom: `wildcardDoesNotFanOutAndMissingImportsRemainGaps` expected two unresolved-import gaps but observed one.
- Root cause: the Java extractor used a line-only locator; distinct imports on the same line shared the gap's deduplication key.
- Impact: unknown topology was underreported and individual explanations were lost.
- Fix: use line and column for Java import and type evidence locations.
- Regression: original two-import assertion passed in the nine-test foundation rerun (2026-09-16); later combined foundation/manifest run passed 18/18.

## Environment — Interrupted Maven cache download

- Symptom: parser benchmark dependency resolution encountered invalid cached POMs after an interrupted execution.
- Root cause: 21 downloaded POM files began with null bytes; no jar in the inspected set had that signature.
- Impact: benchmark could not execute; this was not a graph correctness result.
- Fix: validate paths inside the repository-local cache, quarantine those files as `.null-byte-backup`, and resolve dependencies again.
- Verification: both parser benchmark tests subsequently passed; no assertions were weakened.

## P3-002 — Conditional Gradle declaration could be promoted

- Symptom: code inspection found that a recognized dependency line nested inside unsupported control flow could still emit a trusted edge.
- Root cause: the initial recognizer validated individual lines without validating the containing script structure.
- Impact: a conditional or unreachable declaration could appear unconditional.
- Fix: reject unsupported enclosing control flow before emitting any facts from that script; preserve an explicit dynamic-configuration gap.
- Regression: `gradleConditionalDependencyCannotBecomeUnconditionalTrustedEdge` passed in the 16-method manifest suite on 2026-09-16.

## P3-003 — Repeated source traversal and hashing amplified extraction work

- Symptom: review found the file-tree loop repeatedly copied the entire path list, and each manifest relationship rehashed its full source bytes.
- Root cause: immutable defensive-copy helpers were called inside high-cardinality loops.
- Impact: quadratic list work and repeated large-file hashing could waste build budgets.
- Fix: cache the immutable path list per extraction and content digests per source material.
- Regression: the 2,001-relationship large-file-tree oracle passed, and the final 50,000-node / 250,000-edge PostgreSQL scale proof completed with bounded persistence and query plans.

## P3-004 — Parser token optimization removed evidence locations

- Symptom: six graph foundation methods errored with missing AST source positions after token storage was disabled.
- Root cause: this parser configuration did not retain the positions assumed by evidence creation.
- Impact: valid Java extraction could fail instead of producing explainable facts.
- Fix: retain parser tokens; control memory using bounded file bytes and lexical nesting preflight instead.
- Regression: all 27 foundation/manifest methods passed, followed by repeated 110-test unit/architecture passes on 2026-09-17.

## P3-005 — Legal dot-relative OpenAPI reference rejected

- Symptom: `openApiDotRelativeRefResolvesAndLegalRecursiveSchemaTerminates` expected READY but received PARTIAL.
- Root cause: the general source-path validator correctly rejects literal dot segments, but the reference resolver did not remove legal current-directory URI segments before validation.
- Impact: valid repository-contained schemas were falsely reported as blocked material topology.
- Fix: remove only current-directory segments before canonical path validation; continue rejecting parent traversal, remote schemes, absolute paths, encoded paths and backslashes.
- Regression: legal dot-relative reference plus recursive Node schema passed; all 17 manifest tests passed on 2026-09-17, including remote/escape checks.

## P3-006 — Extractor arrival order discarded facts

- Symptom: `extractorInvocationOrderCannotDiscardRelationshipsOrMetadata` failed with PARTIAL for the order GuideIn config, Java, Maven, Compose, file tree.
- Root cause: edges were rejected before later extractors could supply endpoints; criticality was ignored before the node existed; node metadata was first-writer-wins.
- Impact: ordering could change graph content/digest, lose configured criticality, and hide build-manifest metadata behind file-tree defaults.
- Fix: retain bounded edge candidates and validate endpoints at finalization, collect criticality independently of node arrival, select node candidates by source precedence and canonical tie-break, retain conflicting criticality claims as an explicit source-conflict gap with UNKNOWN resolved criticality.
- Regression: 100 shuffled in-memory builds passed. The final clean persisted proof randomized files, extractor invocation, parallel completion, nodes, edges, evidence and database insertion across 100 nine-extractor builds; all reconstructed one digest and produced zero in-memory/persisted mismatches.

## Proof harness incidents — 2026-09-17

The serialized real-process rerun passed: 20 concurrent reads exposed no unfinished graph, restart produced one READY snapshot and one event, and recovery digest matched exact-source extraction.

- Docker had stopped between sessions; a diff invocation failed during environment initialization, before its method executed. Docker Desktop was restarted; this is not a passing proof.
- The real-process fault trigger intentionally blocked after a node insert. After killing the client, the test must release its own advisory lock so PostgreSQL can observe the disconnect and roll back. All no-publication assertions remain intact.
- An overlapping Maven compilation temporarily removed test classes during the crash test's evidence write (`ProofEvidence` not found). The run is invalid and must be repeated with Maven executions serialized.

## P3-007 — Explorer scope denial distinguished an existing graph UUID

- Symptom: signed HTTP request from an unscoped selected-repository member returned 403 for an existing snapshot, while nonexistent UUIDs return 404.
- Root cause: graph lookup propagated the repository capability denial after locating the snapshot.
- Impact: content remained denied, but graph existence was distinguishable outside repository scope.
- Fix: translate repository authorization denial to the same not-found response at the graph-query boundary; retain underlying Phase-1 authorization unchanged.
- Regression: signed JWT requests across snapshot, node, edge, gap, traversal and diff endpoints passed, before and after explicit repository scope grant. Explicit nonexistent-versus-foreign snapshot/node status, stable error fields and body shape also passed.

## P3-008 — Medium graph persistence exceeded short lease between renewals

- Symptom: the 50,000-node / 250,000-edge proof failed with lease conflict during the edge persistence loop, using the deliberate three-second integration-test lease.
- Root cause: 500-edge batches plus their accumulated evidence could exceed the renewal interval; evidence work had no intermediate renewal.
- Impact: fencing correctly prevented final publication, but a valid large build could roll back and retry unnecessarily.
- Fix: cap node/edge batches at 100 and renew before every evidence sub-batch of at most 100 records. The atomic publication transaction and strict lease checks remain unchanged.
- Regression: the original three-second lease test and 50,000-node / 250,000-edge scale proof pass with renewals between bounded batches; the final scale run persisted exactly 250,000 evidence rows and committed atomically.

## P3-009 — A new worker recipe could publish under an old build identity

- Symptom: `workerCannotPublishAnOldRecipeUsingChangedExtractorConfiguration` expected zero final snapshots but observed one after a worker with a different builder version claimed an old job.
- Root cause: request deduplication included the recipe, but processing did not compare the current worker recipe with the persisted snapshot recipe.
- Impact: a rolling version/configuration change could mislabel a graph as having been built by the old version.
- Fix: under the snapshot lock, compare both builder version and extractor configuration digest before source retrieval or publication; mismatches fail permanently as stale source. A request under the new recipe receives a distinct identity.
- Regression: the worker mismatch test passed individually, in the 24-method combined PostgreSQL class and in the final clean 189-test suite. Stale configuration publications measured zero.

## P3-010 — Snapshot evidence validation became quadratic at scale

- Symptom: 50,000 nodes and 250,000 edges reached finalization, but the trigger's missing-evidence anti-join ran for minutes and the proof connection eventually closed.
- Root cause: a new snapshot's rows are invisible to table statistics until commit. PostgreSQL estimated one edge and selected a nested-loop anti-join which repeatedly scanned the snapshot evidence set.
- Impact: unbounded graph publication latency, lease pressure and production scale risk. The invariant failed closed; no invalid snapshot was published.
- Fix: preserve composite foreign-key endpoint enforcement and replace the evidence anti-join with a linear distinct covered-edge count. The measured endpoint anti-join was redundant because validated composite foreign keys already enforce tenant/snapshot endpoint integrity; it was removed without weakening that constraint. Explicit set-based persisted counts/evidence validation runs before the independently enforced trigger, with per-stage timing, bounded batches and lease renewal retained.
- Regression: the final clean PostgreSQL 18.6 scale proof passed. Validation took 663.6974 ms, finalization update (including trigger validation) 410.1466 ms and commit 1.4622 ms. The failed first attempt remains recorded: the run lasted about 2,170 seconds and ultimately lost its database connection without publishing an invalid snapshot.

## P3-011 — Graph diff reconstructed both large graphs at query time

- Symptom: diffing two 250,000-edge snapshots exceeded first 5 seconds and then 30 seconds even after consolidating three comparisons into one query.
- Root cause: every diff reconstructed canonical endpoint keys through node joins and aggregated all evidence identities before comparing snapshots.
- Impact: medium-graph diff was unusable despite bounded failure behavior.
- Fix: persist canonical endpoint keys and a deterministic evidence-set digest with each edge. Composite foreign keys bind each denormalized key to its exact node ID, and a unique canonical-edge constraint preserves identity. Diff now compares the indexed canonical representation directly.
- Regression: small deterministic/symmetric diff passed 100 repetitions; the final clean controlled medium diff passed with exactly +500/-250 nodes and +2000/-1000 edges in 1034.1408 ms. Endpoint integrity remains enforced by composite foreign keys.

## P3-012 — Unsafe Git tree entries were rejected after blob retrieval

- Symptom: code inspection showed that hostile tree paths and symbolic-link entries reached the blob-fetch loop before `SourceMaterial` rejected them.
- Root cause: canonical path and regular-file checks existed at the material boundary but not at the provider acquisition boundary.
- Impact: invalid content could not become trusted graph state, but the provider client could still fetch attacker-selected blob objects unnecessarily.
- Fix: validate and canonicalize Git tree paths, entry modes and file bounds before any blob request. Reject traversal, absolute/drive paths, backslashes, control or invalid Unicode, empty/dot segments, overlong paths and symbolic links.
- Regression: `hostileGitTreePathsAndSymlinksAreRejectedBeforeBlobRetrieval` fetched only `safe.txt`; unsafe-path blob fetches, symlink blob fetches and path escapes all measured zero in the final clean suite.

## Final closeout

The final clean Maven run completed in 31:52 with 189/189 tests passed and zero failures, errors or skips. The Phase-3 PostgreSQL class passed all 24 proof methods in 509.8 seconds. No unresolved Phase-3 P0/P1 defect remains.
