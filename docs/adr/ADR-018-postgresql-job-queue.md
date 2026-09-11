# ADR-018: PostgreSQL Durable Job Queue

## Context
Phase 1 needs recoverable background work without adding broker infrastructure.

## Options considered
In-memory executor, Redis/RabbitMQ/Kafka/SQS, PostgreSQL queue.

## Decision
Use ordered `FOR UPDATE SKIP LOCKED`, active dedupe uniqueness, expiring leases, attempt bounds, stable failure categories, and lease-token-guarded completion.

## Why
It preserves durability and transaction semantics using existing infrastructure.

## Tradeoffs
Polling and write contention limit scale; queue views are intentionally inconsistent during claims.

## Security consequences
RLS isolates jobs; stale workers cannot complete reclaimed work; payloads confer no authority.

## Revisit trigger
Measured queue load, isolation, or delivery topology exceeds PostgreSQL targets.

