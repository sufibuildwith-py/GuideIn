# GuideIn V2

GuideIn is an evidence-driven Change Intelligence Control Plane for software delivery.

> Every change enters production with proof.

The repository is being rebuilt as a Java 21 / Spring Boot modular monolith. Phase 1 contains only the security-critical platform kernel: identity, tenancy, authorization, PostgreSQL row-level security, audit integrity, transactional events, durable jobs, API errors, observability, and health semantics.

The original desktop career mentor is preserved at `v1-career-mentor` and under `legacy/desktop-v1`.

## Local prerequisites

- Java 21
- Maven 3.9+
- Docker with Compose (for PostgreSQL 18 and security integration tests)

Copy `.env.example` to `.env` and use development-only passwords, then run:

```text
docker compose up -d postgres
mvn -Dmaven.repo.local=.m2/repository verify
```

The API requires a JWT from the configured OIDC issuer. No password or development bypass authentication is implemented.
