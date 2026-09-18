# Canonical snapshot digest

Status: Accepted; Phase 3 proof complete

Date: 2026-09-16

## Decision

Canonicalize sorted semantic nodes, edges, evidence and gaps through the existing RFC 8785 service, then SHA-256. Stable keys define sorting; exclude generated IDs, timings and arrival order.

## Consequences and proof obligations

Reproducibility is a measured gate across randomized discovery order, not a claim based solely on sorting.

Reference: [Phase-3 research](../research/PHASE_3_RESEARCH.md).
