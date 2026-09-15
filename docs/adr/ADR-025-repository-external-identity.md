# ADR-025: Repository external identity

Status: accepted design for Phase 2; implementation proof pending.

## Context and sources

REF-GH-INSTALLATION-LIFECYCLE; see [Phase 2 research](../research/PHASE_2_RESEARCH.md), accessed 2026-09-15. Preserve Phase-1 primitives and migrations.

## Decision

Map GitHub numeric repository ID to one tenant repository identity. Rename/transfer updates display coordinates. Access is separately tracked per installation and rechecked before provider calls and normalized commit.

## Alternatives, tradeoffs and security boundary

Historical changes survive access removal. No unauthenticated fallback. Foreign-key identity includes tenant.

## Required proof / revisit trigger

Prove rename, transfer, removal, cross-tenant ID substitution and in-flight revocation. Revisit if deterministic proof exposes a correctness defect or measured limits require a narrower design.
