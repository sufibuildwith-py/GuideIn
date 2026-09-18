# Immutable graph snapshots

Status: Accepted; Phase 3 proof complete

Date: 2026-09-16

## Decision

QUEUED and BUILDING are operational states; READY and PARTIAL are published immutable products. A known extraction gap requires PARTIAL. FAILED has no published graph. Publication, evidence and outbox event commit atomically.

## Consequences and proof obligations

New source or extractor versions produce new identities. Expired jobs rebuild safely using existing leased jobs.

Reference: [Phase-3 research](../research/PHASE_3_RESEARCH.md).
