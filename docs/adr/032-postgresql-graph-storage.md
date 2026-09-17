# PostgreSQL graph storage

Status: Accepted for Phase 3 implementation; proof pending

Date: 2026-09-16

## Decision

Use additive tenant-keyed PostgreSQL tables with composite tenant/snapshot foreign keys, FORCE RLS, and directional edge indexes. Reuse the platform transaction context and runtime role. No separate graph store.

## Consequences and proof obligations

Validate isolation and index plans against real PostgreSQL 18. Never substitute an in-memory database for proof.

Reference: [Phase-3 research](../research/PHASE_3_RESEARCH.md).
