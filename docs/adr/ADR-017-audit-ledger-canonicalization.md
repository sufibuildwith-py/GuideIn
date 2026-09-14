# ADR-017: Audit Ledger Canonicalization

## Context
Hash chains are not reproducible when logically equal JSON serializes differently.

## Options considered
Jackson key sorting, custom canonical JSON, RFC 8785 JCS, external append-only service.

## Decision
Use `io.github.erdtman:java-json-canonicalization:1.1` (Apache-2.0) and official RFC 8785 vectors. Serialize per-tenant appends behind a locked head row and SHA-256 chain.

## Why
JCS covers key ordering, escaping, and ECMAScript number formatting that a casual implementation would miss.

## Tradeoffs
The small dependency is old and must be monitored; input is restricted to I-JSON.

## Security consequences
Database permissions and a trigger prevent runtime mutation; a verifier detects privileged tampering/deletion.

The proof pass found that continuity alone accepts a truncated valid prefix. Verification now compares its final sequence/hash to `audit_heads`; the regression suite includes middle and tail deletion plus payload, previous-hash, sequence, and event-hash replacement.

## Revisit trigger
Dependency health fails review or certificates require externally anchored/signed audit roots.
