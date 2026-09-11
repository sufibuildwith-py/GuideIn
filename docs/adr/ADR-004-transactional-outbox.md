# ADR-004: Explicit Transactional Outbox

## Context
A domain mutation and the event describing it must never diverge.

## Options considered
Direct publish, Spring Modulith publication registry, custom PostgreSQL outbox, Kafka/Debezium.

## Decision
Insert a versioned GuideIn event envelope into `outbox_events` in the domain transaction. Claim with a lease; deliver at least once; require idempotent consumers.

## Why
GuideIn needs tenant identity, aggregate identity, correlation, causation, payload hashes, deterministic leases, and future broker-neutral externalization.

## Tradeoffs
GuideIn owns dispatcher/recovery logic and accepts duplicate delivery after an external side effect followed by rollback.

## Security consequences
RLS isolates rows; payloads are canonicalized; consumer authority is not implied by event content.

## Revisit trigger
Measured throughput or integration requirements justify CDC/broker externalization.

