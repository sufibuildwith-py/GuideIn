# OpenAPI reference security

Status: Accepted for Phase 3 implementation; proof pending

Date: 2026-09-16

## Decision

Use bounded local JSON/YAML documents. Resolve only repository-relative references within the already staged material; reject URI schemes, absolute paths and traversal. Bound visited references and report cycles without network resolution.

## Consequences and proof obligations

Do not add the full Swagger resolver: V1 needs declarative paths/schema metadata, not remote resolution or validation execution.

Reference: [Phase-3 research](../research/PHASE_3_RESEARCH.md).
