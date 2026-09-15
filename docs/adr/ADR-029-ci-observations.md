# ADR-029: Provider-neutral CI observations

Status: accepted design for Phase 2; implementation proof pending.

## Context and sources

REF-GH-CHECKS / REF-GH-ATTESTATIONS; see [Phase 2 research](../research/PHASE_2_RESEARCH.md), accessed 2026-09-15. Preserve Phase-1 primitives and migrations.

## Decision

Store checks and legacy statuses by repository and exact SHA with external identity and reporting app/actor. Keep source kind, timestamps, conclusion and display URL. Refresh provider-current state rather than trusting arrival order.

## Alternatives, tradeoffs and security boundary

Name alone is not identity, empty PR associations do not justify guessing, and CI success is not a release decision.

## Required proof / revisit trigger

Prove same-name apps, distinct SHAs, fork relationship uncertainty, out-of-order updates and legacy statuses. Revisit if deterministic proof exposes a correctness defect or measured limits require a narrower design.
