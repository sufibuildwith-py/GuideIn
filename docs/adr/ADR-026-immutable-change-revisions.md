# ADR-026: Immutable PR revisions

Status: accepted design for Phase 2; implementation proof pending.

## Context and sources

REF-GH-PULLS; see [Phase 2 research](../research/PHASE_2_RESEARCH.md), accessed 2026-09-15. Preserve Phase-1 primitives and migrations.

## Decision

Use tenant/repository/provider-change/head-SHA uniqueness. Existing revision facts are not overwritten on a new head. Current projection uses canonical refetch, serialized installation processing and a head recheck after fetching files.

## Alternatives, tradeoffs and security boundary

Direct push identity uses ref plus before/after digests; zero SHA deletion is explicit, not a commit fetch. File completeness is an independent field.

## Required proof / revisit trigger

Prove A then B history, delayed A, duplicate normalization, head changes during hydration and giant filesets. Revisit if deterministic proof exposes a correctness defect or measured limits require a narrower design.
