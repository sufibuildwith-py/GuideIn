# Canonical graph identity

Status: Accepted; Phase 3 proof complete

Date: 2026-09-16

## Decision

Canonical identities use normalized repository-relative paths, fully qualified symbols and provider-neutral module keys. Snapshot input binds tenant, repository, exact source SHA, builder/extractor versions and configuration digest. Database UUIDs and wall clocks are not graph content.

## Consequences and proof obligations

Duplicate simple names cannot merge. Forward slashes and strict relative path validation apply on every host.

Reference: [Phase-3 research](../research/PHASE_3_RESEARCH.md).
