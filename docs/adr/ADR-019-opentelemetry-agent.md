# ADR-019: OpenTelemetry Java Agent First

## Context
GuideIn needs broad HTTP/JDBC/executor traces without coupling its kernel to an instrumentation SDK.

## Options considered
Manual SDK, Spring Boot OTel starter, Java agent.

## Decision
Deploy the OpenTelemetry Java agent and configure it through environment/JVM settings. Application code emits bounded Micrometer domain metrics.

The proof suite attaches pinned agent 2.28.1 to the Failsafe JVM. Its real HTTP test asserts agent-generated `trace_id` and `span_id` alongside application request/correlation IDs in structured logs. Exporters are disabled for this local test; remote collector delivery is a deployment concern and is not claimed as tested. No telemetry SDK is bundled into the application.

## Why
Upstream recommends the agent for the broadest Spring instrumentation; recent Boot 4 changes make smoke verification mandatory.

## Tradeoffs
The agent is a deployment artifact and custom spans need the OTel API if later added.

## Security consequences
No secrets, JWTs, raw SQL parameters, source, emails, or unbounded tenant labels are permitted in telemetry.

## Revisit trigger
Native images, agent conflicts, startup overhead, or dynamic exporter credentials create a measured need for the starter.
