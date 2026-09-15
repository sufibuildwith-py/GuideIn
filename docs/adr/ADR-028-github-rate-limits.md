# ADR-028: Durable rate-limit strategy

Status: accepted design for Phase 2; implementation proof pending.

## Context and sources

REF-GH-RATE-LIMITS / REF-RESILIENCE4J; see [Phase 2 research](../research/PHASE_2_RESEARCH.md), accessed 2026-09-15. Preserve Phase-1 primitives and migrations.

## Decision

Capture rate headers and request ID. Use Retry-After, exhausted reset, or >=60 second secondary backoff. A durable installation cooldown stops sibling jobs hammering the provider. Queue available_at owns retries.

## Alternatives, tradeoffs and security boundary

One 401 refresh for idempotent GET is the sole HTTP retry. No sleeping workers, extra queue, or stacked retry libraries.

## Required proof / revisit trigger

Prove 403 access versus rate limit, 429, reset precedence, cooldown and bounded attempts. Revisit if deterministic proof exposes a correctness defect or measured limits require a narrower design.
