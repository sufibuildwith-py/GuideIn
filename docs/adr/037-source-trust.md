# Source confidence and trust

Status: Accepted for Phase 3 implementation; proof pending

Date: 2026-09-16

## Decision

Version a closed evidence registry. EXPLICIT and RESOLVED are trusted classes, independent of decimal confidence. Config precedes build, parsed code, API specs, deployment and repository metadata. No model output can populate trusted facts.

## Consequences and proof obligations

Candidate validation rejects arbitrary evidence kinds and missing provenance. Preserve conflicting evidence and report disagreement.

Reference: [Phase-3 research](../research/PHASE_3_RESEARCH.md).
