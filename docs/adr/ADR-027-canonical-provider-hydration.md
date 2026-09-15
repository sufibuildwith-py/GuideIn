# ADR-027: Provider fetch as canonical hydration

Status: accepted design for Phase 2; implementation proof pending.

## Context and sources

REF-GH-PULLS / REF-GH-COMMITS; see [Phase 2 research](../research/PHASE_2_RESEARCH.md), accessed 2026-09-15. Preserve Phase-1 primitives and migrations.

## Decision

Treat webhook selectors as notifications. Fetch canonical PR/commit/repository facts with installation credentials; convert to provider-neutral candidates. Recheck access generation and job token in the normalized write transaction.

## Alternatives, tradeoffs and security boundary

Signed free text and arbitrary URLs do not become authority. Provider failures cannot partially commit facts.

## Required proof / revisit trigger

Prove poisoned selectors/URLs, provider errors, stale worker, access changes and atomic domain/outbox writes. Revisit if deterministic proof exposes a correctness defect or measured limits require a narrower design.
