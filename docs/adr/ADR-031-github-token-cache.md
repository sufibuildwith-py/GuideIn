# ADR-031: Token caching and rotation

Status: accepted design for Phase 2; implementation proof pending.

## Context and sources

REF-GH-TOKEN-LIFECYCLE; see [Phase 2 research](../research/PHASE_2_RESEARCH.md), accessed 2026-09-15. Preserve Phase-1 primitives and migrations.

## Decision

Keep opaque installation credentials in a bounded in-memory cache by installation and authorization generation. Refresh before expiry and evict on 401/suspension/deletion/access reduction. Request only read permissions.

## Alternatives, tradeoffs and security boundary

No token format inference. Cache disappearance on restart is safe. Webhook rotation accepts at most two named versions; audits record version identifiers only.

## Required proof / revisit trigger

Prove old/long/arbitrary token fixtures, expiry, single refresh, cache eviction, generation races and no secrets in outputs. Revisit if deterministic proof exposes a correctness defect or measured limits require a narrower design.
