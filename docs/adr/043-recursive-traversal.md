# Bounded recursive CTE traversal

Status: Accepted; Phase 3 proof complete

Date: 2026-09-16

## Decision

Use tenant/snapshot-filtered PostgreSQL recursive traversal with explicit direction, edge types, visited-node handling and hard depth, node and work budgets plus transaction statement timeout. Do not rely on an outer LIMIT alone.

## Consequences and proof obligations

Report truncation and measured work. Test cycles, dense graphs, reverse traversal and cross-tenant substitutions against real PostgreSQL.

Reference: [Phase-3 research](../research/PHASE_3_RESEARCH.md).
