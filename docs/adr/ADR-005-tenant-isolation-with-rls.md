# ADR-005: Tenant Isolation with PostgreSQL RLS

## Context
Application predicates alone cannot be the final defense against cross-tenant access.

## Options considered
Database-per-tenant, schema-per-tenant, pooled tables with predicates only, pooled tables with RLS.

## Decision
Use shared tables with `tenant_id`, application authorization, and PostgreSQL `ENABLE` plus `FORCE ROW LEVEL SECURITY`, `USING`, and `WITH CHECK` policies.

## Why
This provides two independent enforcement layers without premature operational partitioning.

## Tradeoffs
RLS requires strict transaction/context discipline and careful query/test design; noisy-neighbor risks remain.

## Security consequences
The runtime role is neither owner nor `BYPASSRLS`; missing context raises and fails closed.

## Revisit trigger
Regulatory or measured isolation requirements demand separate schemas/databases.

