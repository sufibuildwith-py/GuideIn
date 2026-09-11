# ADR-001: Modular Monolith First

## Context
Phase 1 establishes security and transaction boundaries that later product modules must reuse.

## Options considered
One Spring application with enforced modules; microservices; an unstructured monolith.

## Decision
Use one Spring Boot deployment with top-level modules, public `api` named interfaces, and `ApplicationModules.verify()` in CI.

## Why
One process and database preserve local transactions while executable boundaries prevent accidental coupling.

## Tradeoffs
Modules cannot scale or deploy independently. Database ownership remains conceptual within one schema.

## Security consequences
Fewer network trust boundaries exist, while forbidden internal imports fail tests.

## Revisit trigger
A module needs materially different scaling, runtime, security isolation, failure isolation, or deployment cadence.

