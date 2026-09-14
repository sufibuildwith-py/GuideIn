# ADR-013: JDBC for the Platform Kernel

## Context
RLS context, row locks, queue claims, audit serialization, and privilege behavior require predictable SQL.

## Options considered
JPA/Hibernate, Spring Data repositories, Spring JDBC `JdbcClient`.

## Decision
Use `JdbcClient` and explicit SQL in Phase 1.

The PostgreSQL proof pass established that final implementation classes require interface-based transaction proxies. Production configuration sets `spring.aop.proxy-target-class=false`; callers use the module APIs. Spring Boot 4's Flyway starter activates automatic migrations before JDBC services are used.

## Why
Transactions, `set_config`, `FOR UPDATE`, `SKIP LOCKED`, and guarded updates remain visible and testable.

## Tradeoffs
More mapping code and PostgreSQL-specific SQL are accepted.

## Security consequences
No ORM filter can be mistaken for tenant isolation; bound parameters remain mandatory.

## Revisit trigger
A later non-kernel module demonstrates lower risk and material productivity benefit from an ORM.
