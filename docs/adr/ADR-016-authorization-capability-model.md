# ADR-016: Capability and Resource Authorization

## Context
Role conditionals scattered through endpoints become incomplete and untestable.

## Options considered
Controller role checks, policy engine, SpiceDB/ReBAC, central in-process capability service.

## Decision
Use `AuthenticatedSubject + AccessContext + Capability + ResourceRef + scope -> AccessDecision`; enumerate every role/capability cell.

## Why
The contract remains deny-by-default and can evolve toward ABAC/ReBAC without a distributed service today.

## Tradeoffs
The matrix is code-versioned and requires deliberate updates.

## Security consequences
Tenant identity and selected-repository scope are checked independently of role capability.

## Revisit trigger
Relationships become too dynamic or numerous for a tested in-process matrix.

