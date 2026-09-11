# ADR-015: OIDC Resource Server Authentication

## Context
GuideIn must authenticate humans without becoming an identity provider.

## Options considered
Passwords, opaque custom tokens, server sessions, OIDC JWT resource server.

## Decision
Use Spring Security OAuth2 Resource Server. Validate JWT signature, issuer, time claims, and audience; map identity by `(issuer, subject)`.

## Why
It uses mature protocol and cryptographic implementations and keeps email mutable.

## Tradeoffs
Operations depend on an external issuer/JWK lifecycle; accepted tokens are stateless until expiry.

## Security consequences
No development bypass exists. Production profile requires explicit issuer, JWK URI, audience, and DB secrets.

## Revisit trigger
Browser UI requirements justify a backend-for-frontend session while retaining OIDC identity.

