# ADR-021: GitHub App authentication

Status: accepted design for Phase 2; implementation proof pending.

## Context and sources

REF-GH-APP-AUTH / REF-GH-PERMISSIONS; see [Phase 2 research](../research/PHASE_2_RESEARCH.md), accessed 2026-09-15. Preserve Phase-1 primitives and migrations.

## Decision

Use a GitHub App with Metadata, Pull requests, Contents, Checks and Commit statuses READ. Keep app RS256 signing separate from GuideIn OIDC. A configured PEM secret path supplies the development signer; an interface permits a sign-only vault/KMS implementation.

## Alternatives, tradeoffs and security boundary

PATs and repository writes are excluded. Key material never enters database, application configuration values, audit or logs.

## Required proof / revisit trigger

Assert JWT algorithm/claims, read-only token requests, and secret canaries. Revisit if deterministic proof exposes a correctness defect or measured limits require a narrower design.
