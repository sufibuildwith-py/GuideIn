# ADR-024: Installation to tenant binding

Status: accepted design for Phase 2; implementation proof pending.

## Context and sources

REF-GH-SETUP-URL; see [Phase 2 research](../research/PHASE_2_RESEARCH.md), accessed 2026-09-15. Preserve Phase-1 primitives and migrations.

## Decision

Require integration.write and tenant-wide scope to prepare. Persist random state hash, PKCE challenge, GuideIn user/tenant, expiry and consumption. Completion uses OAuth code exchange, user installation membership and app installation lookup before atomic binding.

## Alternatives, tradeoffs and security boundary

User GitHub token is transient and never returned. Browser state or installation ID alone cannot bind. Concurrent callbacks compete on one locked state.

## Required proof / revisit trigger

Reject wrong user/tenant, spoofed ID, expired/reused state, missing GitHub association and deleted installation. Revisit if deterministic proof exposes a correctness defect or measured limits require a narrower design.
