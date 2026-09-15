# ADR-030: Unbound transport and machine routing

Status: accepted design for Phase 2; implementation proof pending.

## Context and sources

REF-GH-INSTALLATION-LIFECYCLE / REF-GH-SETUP-URL; see [Phase 2 research](../research/PHASE_2_RESEARCH.md), accessed 2026-09-15. Preserve Phase-1 primitives and migrations.

## Decision

Use an explicitly global minimal transport/routing registry before binding. It contains no tenant domain facts or source. Secure binding associates routing and schedules canonical installation reconciliation plus eligible staged notifications.

## Alternatives, tradeoffs and security boundary

No guessed tenant or RLS bypass role. Worker tenant comes from persisted binding, never caller input. Bound tenant domain tables retain FORCE RLS.

## Required proof / revisit trigger

Prove pre-binding notifications, subsequent reconciliation, tenant-free minimal storage and substitution denial. Revisit if deterministic proof exposes a correctness defect or measured limits require a narrower design.
