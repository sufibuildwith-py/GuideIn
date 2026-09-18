# Java parser selection

Status: Accepted; Phase 3 proof complete

Date: 2026-09-16

## Decision

Adopt JavaParser core 3.28.2 (Apache-2.0 option), not its general symbol solver, for offline Java 21 AST extraction. Resolve explicit imports only against an exact, unambiguous repository declaration index. Missing classpaths become gaps.

## Consequences and proof obligations

The independent two-test comparison passed: 180/180 facts per parser matched hand labels. JavaParser took 69.2969 ms versus OpenRewrite 11302.2274 ms; shared-JVM heap observations are approximate. Both resolve the supplied local source; absent dependencies cannot be presumed resolved. General attribution and whole-program calls are deferred.

Reference: [Phase-3 research](../research/PHASE_3_RESEARCH.md).
