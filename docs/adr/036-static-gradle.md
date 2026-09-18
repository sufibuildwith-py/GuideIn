# Static Gradle analysis

Status: Accepted; Phase 3 proof complete

Date: 2026-09-16

## Decision

Recognize only literal module includes and dependency declarations. Never invoke Gradle, Tooling API, wrappers, plugins or repository code. Dynamic build expressions produce explicit gaps.

## Consequences and proof obligations

Completeness is deliberately constrained; no guessed coordinates or execution to fill missing topology.

Reference: [Phase-3 research](../research/PHASE_3_RESEARCH.md).
