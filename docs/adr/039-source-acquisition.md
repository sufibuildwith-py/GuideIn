# Exact revision source acquisition

Status: Accepted for Phase 3 implementation; proof pending

Date: 2026-09-16

## Decision

Adapt the existing guarded GitHub provider boundary to fetch exact commit/tree/blob objects. Validate immutable object identity and installation/repository authority throughout acquisition. Stage bounded bytes in memory rather than extract archives or clone executable repositories.

## Consequences and proof obligations

Reject unsafe paths, symlinks and submodules with explicit gaps. No process execution, archive expansion, arbitrary origins or unauthenticated fallback.

Reference: [Phase-3 research](../research/PHASE_3_RESEARCH.md).
