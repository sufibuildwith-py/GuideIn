# ADR-020: Database Role Separation

## Context
RLS is ineffective if the runtime connection owns tables or can bypass policies.

## Options considered
One database user; application-owned schema; separate migrator/runtime credentials.

## Decision
`guidein_migrator` owns and evolves schema; `guidein_app` has only runtime DML, is non-owner, non-superuser, `NOCREATEROLE`, `NOCREATEDB`, `NOINHERIT`, and `NOBYPASSRLS`.

## Why
Privilege separation turns RLS and audit immutability into database-enforced boundaries.

## Tradeoffs
Local setup and secret rotation require two credentials; migrations cannot run through the application user.

## Security consequences
Runtime compromise cannot alter policies/schema or mutate audit history with ordinary application credentials.

## Revisit trigger
Managed-database constraints require a different but equivalently tested ownership arrangement.
