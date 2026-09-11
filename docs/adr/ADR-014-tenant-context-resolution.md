# ADR-014: Tenant Context Resolution

## Context
Membership must be verified before tenant-scoped RLS authority is established.

## Options considered
Trust a tenant header; set session variables; privileged membership bypass; authenticated-user bootstrap policy.

## Decision
Set `guidein.user_id` transaction-locally, allow membership SELECT only for that user, verify membership, then set `guidein.tenant_id` transaction-locally and access protected resources.

## Why
It solves bootstrap without global RLS bypass and pooled-connection state leakage.

## Tradeoffs
All protected access must stay inside an explicit transaction.

## Security consequences
Caller tenant IDs remain untrusted routing input. Missing context raises; resource existence stays hidden.

## Revisit trigger
Authorization storage moves outside PostgreSQL or machine identities require a separately proven bootstrap path.

