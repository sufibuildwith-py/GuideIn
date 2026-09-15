# ADR-023: Webhook authentication and replay

Status: accepted design for Phase 2; implementation proof pending.

## Context and sources

REF-GH-WEBHOOK-VERIFY / REF-GH-WEBHOOK-BEST-PRACTICES; see [Phase 2 research](../research/PHASE_2_RESEARCH.md), accessed 2026-09-15. Preserve Phase-1 primitives and migrations.

## Decision

Stream bounded raw bytes; validate SHA256 HMAC against at most current/previous secrets before header dedupe or JSON parsing. Bound work commits receipt, existing queue intent and outbox together. Unique provider/delivery identity suppresses duplicates.

## Alternatives, tradeoffs and security boundary

Signature covers bytes, not GitHub headers. Header classification never establishes domain authority; canonical refetch and semantic unique constraints remain necessary. No timestamp protocol is invented.

## Required proof / revisit trigger

Prove raw-body variants, rotation, oversized streams, 100 simultaneous duplicates, rollback and post-2xx restart. Revisit if deterministic proof exposes a correctness defect or measured limits require a narrower design.
