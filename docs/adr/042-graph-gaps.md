# Explicit graph gaps

Status: Accepted for Phase 3 implementation; proof pending

Date: 2026-09-16

## Decision

Represent unsupported constructs, parse failures, unsafe inputs, unresolved symbols and exhausted budgets as deterministic typed records. Preserve independent valid facts and publish PARTIAL; no valid product means FAILED.

## Consequences and proof obligations

Use bounded stable reason codes and locations, never raw parser messages containing repository secrets.

Reference: [Phase-3 research](../research/PHASE_3_RESEARCH.md).
