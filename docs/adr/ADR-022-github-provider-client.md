# ADR-022: Provider HTTP client strategy

Status: accepted design for Phase 2; implementation proof pending.

## Context and sources

REF-JDK-HTTP / REF-SPRING-REST / REF-HUB4J; see [Phase 2 research](../research/PHASE_2_RESEARCH.md), accessed 2026-09-15. Preserve Phase-1 primitives and migrations.

## Decision

Use a thin JDK HttpClient adapter with a trusted configured origin, no redirects, bounded response subscriber, total request timeout, validated pagination links and central API headers. GitHub JSON remains in the github module.

## Alternatives, tradeoffs and security boundary

Convenience SDK models do not cross module APIs. Provider text URLs are display-only. Local HTTP is restricted to explicit test configuration.

## Required proof / revisit trigger

Exercise status codes, pagination, oversized/slow responses, malformed JSON, redirect/host injection and headers. Revisit if deterministic proof exposes a correctness defect or measured limits require a narrower design.
