# GuideIn — PLAN.md

> **Locked direction:** GuideIn becomes a **Change Intelligence Control Plane** for software delivery.
>
> **Core promise:** *Every change enters production with proof.*
>
> **Primary job:** Understand a software change, map its true blast radius, determine what evidence is required, govern whether it may ship, observe what actually happened after deployment, and turn failures into permanent regression defenses.

---

## 0. Document Control

| Field | Value |
|---|---|
| Project | GuideIn |
| Architecture generation | V2 |
| Product category | Change Intelligence / Software Delivery Control Plane |
| Status | **LOCKED FOR IMPLEMENTATION** |
| Plan baseline date | 2026-09-11 |
| Backend language | Java 21 LTS |
| Backend framework baseline | Spring Boot 4.1.x |
| Database baseline | PostgreSQL 18.x |
| Web baseline | Next.js 16.3.x Active LTS |
| Architecture style | Modular monolith + durable event backbone |
| AI role | Advisory / interpretive only |
| Decision authority | Deterministic policy + verified evidence |
| Initial source control integration | GitHub |
| Initial CI integration | GitHub Actions |
| Initial runtime integration | OpenTelemetry-compatible telemetry |
| Initial deployment target | Docker Compose locally; containerized production deployment |
| Security stance | Zero-trust toward repository content, external payloads, model output, and executable artifacts |

### Non-negotiable statement

GuideIn is **not** an AI code reviewer, another coding agent, another CI dashboard, another SonarQube clone, or an LLM that guesses whether code looks safe.

GuideIn is the layer that reconciles:

- **what changed**
- **why it changed**
- **what depends on it**
- **what historically broke around it**
- **what evidence exists**
- **what verification is still missing**
- **who is qualified to review the risk**
- **what policy allows**
- **what happened after deployment**
- **what the organization should permanently learn from that outcome**

The system must remain useful when the LLM is disabled.

---

# 1. Product Thesis

Software organizations are increasingly able to generate code faster than they can safely understand, review, verify, and operate it.

Individual tools know fragments of the truth:

- GitHub knows commits, pull requests, reviews, ownership metadata, and diffs.
- CI knows which workflows ran.
- test frameworks know which tests passed.
- security scanners know specific findings.
- deployment systems know what was released.
- observability systems know runtime behavior.
- incident systems know what failed.
- humans know tribal context.
- AI assistants know how to summarize or propose.

The missing layer is a system that **reconciles those fragments into an evidence-backed decision about a software change**.

GuideIn will build that layer.

---

# 2. Locked Product Loop

Every GuideIn-managed change follows this state machine:

```text
CHANGE RECEIVED
      |
      v
IDENTITY + PROVENANCE VERIFIED
      |
      v
CHANGE NORMALIZED
      |
      v
SYSTEM GRAPH SNAPSHOT RESOLVED
      |
      v
BLAST RADIUS COMPUTED
      |
      v
HISTORICAL + SECURITY CONTEXT ATTACHED
      |
      v
VERIFICATION PLAN GENERATED
      |
      v
EVIDENCE COLLECTED
      |
      v
POLICY EVALUATED
      |
      +-------> BLOCK
      |
      +-------> HUMAN REVIEW REQUIRED
      |
      +-------> ELIGIBLE TO SHIP
                     |
                     v
             RELEASE CERTIFICATE
                     |
                     v
                 DEPLOYMENT
                     |
                     v
             RUNTIME OBSERVATION
                     |
                     v
             OUTCOME RECONCILIATION
                     |
             +-------+--------+
             |                |
             v                v
          HEALTHY          DIVERGED
                              |
                              v
                         INCIDENT INPUT
                              |
                              v
                     REGRESSION ANTIBODY
                              |
                              v
                    FUTURE CHANGES HARDER
                         TO BREAK AGAIN
```

A change cannot skip states merely because an AI claims it is safe.

---

# 3. Product Surface

GuideIn V2 is composed of six signature systems and seven supporting systems.

## 3.1 Signature systems

### A. System Graph

A living, versioned graph of:

```text
repository
  -> module
  -> file
  -> symbol
  -> service
  -> endpoint
  -> event/topic
  -> database/table
  -> external provider
  -> deployment
  -> team
  -> owner
  -> incident
  -> invariant
```

The graph is the structural memory of the software organization.

### B. Change Passport

The canonical evidence object for one proposed change.

A Change Passport records:

- change identity
- immutable source SHA
- author/provenance
- files/symbols changed
- intent
- affected components
- blast radius
- historical analogues
- security-sensitive surfaces
- required verification
- observed verification
- reviewer requirements
- deterministic risk score
- policy decision
- model-generated explanation, clearly labeled advisory
- certificate reference when eligible

### C. Blast Radius Engine

Deterministically maps a change through the System Graph and returns:

- directly affected components
- transitively affected components
- critical infrastructure touched
- runtime/customer surfaces potentially affected
- historical incidents connected to the touched paths
- uncertainty/missing topology

### D. Verification Planner

Transforms change facts into a concrete evidence contract.

Examples:

- auth change -> authorization boundary tests + session tests + security review
- schema migration -> compatibility + rollback + migration dry run
- payment retry code -> idempotency + duplicate-event + timeout + reconciliation tests
- frontend copy change -> lint + targeted UI checks

The planner must be rule-driven and versioned. LLM suggestions may propose extra checks but cannot remove deterministic requirements.

### E. Regression Antibodies

Production failures become permanent machine-readable safeguards.

An antibody contains:

- incident identity
- root cause
- scope matcher
- invariant
- verification requirement
- severity
- owning team
- evidence that established the rule
- effective date
- version
- status
- exceptions with expiry

A future change matching an antibody must satisfy it before being eligible to ship.

### F. Proof of Safe Change

An immutable, signed release certificate representing the evidence GuideIn had at decision time.

It does **not** claim software can be proven perfectly safe.

It proves:

> “Under policy version X, using evidence set Y, for source digest Z, GuideIn evaluated the defined gates and reached decision D.”

That distinction is critical.

---

# 4. Supporting Systems

## 4.1 Provenance Engine

Determines where a change came from and which assertions can be trusted.

Tracks:

- GitHub actor
- commit signer status
- commit SHA
- branch
- PR
- GitHub App delivery
- bot/human attribution
- AI-generated-code declarations when available
- workflow provenance
- build artifact digest
- deployment artifact digest

Unknown provenance increases risk; it never silently becomes trusted.

## 4.2 Evidence Store

Every assertion must have evidence.

Evidence sources receive a trust class:

| Class | Example | Default trust |
|---|---|---:|
| A | Signed provider webhook, Git commit SHA, CI conclusion fetched from provider | High |
| B | GuideIn deterministic computation | High |
| C | Runtime telemetry from authenticated collector | High |
| D | Human approval from authenticated authorized reviewer | Medium-high |
| E | Repository documentation | Medium |
| F | LLM inference | Advisory only |
| G | Unauthenticated/external free text | Untrusted |

Trust class is not the final decision by itself. Freshness, completeness, and cryptographic identity are also evaluated.

## 4.3 Policy Governor

Final eligibility is deterministic.

Outputs exactly one:

```text
ALLOW
REVIEW_REQUIRED
DENY
INCOMPLETE
```

`INCOMPLETE` is fail-closed for protected release gates.

## 4.4 Reviewer Expertise Router

Ranks reviewers based on evidence instead of only static CODEOWNERS.

Signals:

- relevant code ownership
- commits to affected subsystem
- previous reviews
- incident participation
- approved domain tags
- current review load
- availability
- separation-of-duties constraints

GuideIn recommends reviewers; organizations decide whether assignment is automatic.

## 4.5 Deployment Observer

Links release certificates to actual deployments and runtime telemetry.

## 4.6 Reconciliation Engine

Compares:

```text
predicted / expected
vs
observed
```

Outputs:

- matched
- mild divergence
- major divergence
- unknown due to insufficient telemetry

## 4.7 Explanation Layer

LLM-based explanation and summarization only.

The model may:

- summarize diffs
- explain risk facts
- summarize graph paths
- turn policy output into human language
- propose non-binding tests
- draft incident antibodies for human review

The model may **not**:

- authenticate users
- authorize actions
- mark checks passed
- create trusted evidence
- override policy
- alter risk inputs
- sign certificates
- directly execute repository code
- access arbitrary network resources
- retrieve tenant secrets
- move a release from DENY/INCOMPLETE to ALLOW

---

# 5. Non-Goals for V2

GuideIn V2 will not initially:

1. replace GitHub;
2. replace CI providers;
3. replace Kubernetes;
4. become an IDE;
5. generate entire applications;
6. autonomously merge arbitrary PRs;
7. autonomously deploy to production;
8. execute untrusted repository code inside the control-plane process;
9. ingest every SaaS platform before the GitHub path is correct;
10. introduce microservices just to look “enterprise”;
11. introduce Kafka, Redis, Neo4j, Elasticsearch, or Kubernetes without a demonstrated need;
12. claim probabilistic model output is proof;
13. train on customer source code by default;
14. make safety decisions on natural-language prompts alone.

---

# 6. Engineering Principles

## 6.1 Deterministic core, probabilistic edge

Core decisions must be reproducible.

```text
same source digest
+ same evidence snapshot
+ same graph snapshot
+ same policy version
= same decision
```

The LLM may explain the decision differently but cannot alter it.

## 6.2 Fail closed on authority

If GuideIn cannot verify required evidence:

```text
UNKNOWN != PASS
```

Unknown becomes `INCOMPLETE`, and protected releases do not receive an ALLOW certificate.

## 6.3 Immutable source identity

Every assessment binds to:

- repository ID
- commit SHA
- pull request head SHA
- graph snapshot
- evidence snapshot
- policy version

If head SHA changes, the prior decision is stale.

## 6.4 Evidence before confidence

No score can compensate for a missing mandatory gate.

Example:

```text
risk score = 4/100
security invariant = FAIL
=> DENY
```

## 6.5 Keep the control plane out of the execution plane

Untrusted builds/tests run elsewhere.

## 6.6 Every external action is idempotent

Webhook delivery replays, worker retries, and duplicate provider events must not create duplicate semantic outcomes.

## 6.7 Every mutable policy is versioned

Past decisions must remain explainable after future policy changes.

## 6.8 Tenant isolation is enforced twice

Application authorization **and** database-level row security.

## 6.9 Security-sensitive defaults are restrictive

No wildcard provider access, no default outbound internet from sandboxes, no silent admin role, no unbounded model context.

## 6.10 No hidden safety bypass

Any exception must be:

- explicit
- authorized
- reasoned
- time-bounded
- auditable

---

# 7. Architecture Decision: Modular Monolith First

GuideIn will begin as a **modular monolith** implemented with Spring Boot.

This is deliberate.

Microservices would immediately create:

- distributed transactions
- service discovery
- distributed tracing complexity
- duplicated authentication
- deployment coordination
- network failure modes
- version skew
- higher local-development cost

None of those improve the first version of blast-radius reasoning.

Instead:

```text
                  +----------------------+
                  |      Next.js UI      |
                  +----------+-----------+
                             |
                           HTTPS
                             |
                  +----------v-----------+
                  |   Spring Boot API    |
                  |  Modular Monolith    |
                  +----------+-----------+
                             |
        +--------------------+--------------------+
        |                    |                    |
        v                    v                    v
   PostgreSQL          Object Storage       Model Provider
   + pgvector          content-addressed     abstraction
        |
        v
 Durable Outbox / Work Queue
```

Modules communicate through typed application interfaces and domain events.

The database is shared initially, but modules own their schemas/tables conceptually.

### Extraction rule

A module may become a service only when at least one is true:

1. independent scaling differs by >5x from the API tier;
2. strong security isolation is required;
3. execution requires a different runtime;
4. failure isolation is materially valuable;
5. deployment cadence becomes independently constrained.

Likely first extraction: **sandbox execution worker**, because it handles hostile workloads.

---

# 8. Target Repository Layout

Before restructuring, create a protected git tag:

```text
v1-career-mentor
```

Target structure:

```text
GuideIn/
├── backend/
│   ├── pom.xml
│   └── src/
│       ├── main/java/io/guidein/
│       │   ├── GuideInApplication.java
│       │   ├── identity/
│       │   ├── tenancy/
│       │   ├── github/
│       │   ├── ingestion/
│       │   ├── provenance/
│       │   ├── change/
│       │   ├── graph/
│       │   ├── evidence/
│       │   ├── blast/
│       │   ├── verification/
│       │   ├── policy/
│       │   ├── antibodies/
│       │   ├── reviewers/
│       │   ├── certificates/
│       │   ├── deployments/
│       │   ├── reconciliation/
│       │   ├── ai/
│       │   ├── audit/
│       │   └── platform/
│       ├── main/resources/
│       │   ├── application.yml
│       │   └── db/migration/
│       └── test/
├── web/
│   ├── package.json
│   ├── package-lock.json
│   └── src/
├── contracts/
│   ├── openapi/
│   ├── events/
│   └── json-schema/
├── infra/
│   ├── docker/
│   ├── compose/
│   └── deployment/
├── security/
│   ├── THREAT_MODEL.md
│   ├── SECURITY_INVARIANTS.md
│   └── abuse-cases/
├── evaluation/
│   ├── scenarios/
│   ├── fixtures/
│   └── oracle/
├── docs/
│   ├── architecture/
│   ├── adr/
│   └── runbooks/
├── legacy/
│   └── desktop-v1/
├── .github/
│   └── workflows/
├── PLAN.md
├── README.md
└── docker-compose.yml
```

The legacy UI remains preserved for project lineage, not as the control-plane runtime.

---

# 9. Technology Baseline

## Backend

- Java 21 LTS
- Spring Boot 4.1.x
- Spring Security
- Spring Modulith
- Spring Data / JDBC/JPA where appropriate
- Flyway
- Jackson
- Bean Validation
- Micrometer
- OpenTelemetry
- Testcontainers
- JUnit 5
- ArchUnit

## Data

- PostgreSQL 18.x
- pgvector for semantic retrieval only
- PostgreSQL recursive CTEs for initial graph traversal
- PostgreSQL advisory locks only for narrow serialization cases
- PostgreSQL `SKIP LOCKED` queue for durable background jobs
- no Redis requirement in the first stable architecture

## Frontend

- Next.js 16.3.x Active LTS
- TypeScript
- React
- server-side auth session handling
- generated API types from OpenAPI
- strict CSP-compatible UI
- no dangerous raw HTML rendering for diffs/logs

## Object storage

S3-compatible API.

Local:

- MinIO

Production:

- managed encrypted object storage

Large payloads stored by SHA-256 content address:

```text
sha256/ab/cd/<full-digest>
```

## AI

Model-provider abstraction:

```java
interface ReasoningProvider {
    StructuredModelResponse reason(ModelRequest request);
}
```

Providers are replaceable.

No product decision depends on one model vendor.

---

# 10. Internal Module Contracts

Each module exposes:

```text
api/
application/
domain/
infrastructure/
```

Example:

```text
graph/
├── api/
│   ├── SystemGraphQuery.java
│   └── GraphSnapshotRef.java
├── application/
│   └── GraphBuildService.java
├── domain/
│   ├── GraphNode.java
│   ├── GraphEdge.java
│   └── EdgeType.java
└── infrastructure/
    ├── GraphRepository.java
    └── GithubGraphExtractor.java
```

Forbidden:

```text
blast -> directly querying github tables
policy -> directly reading UI tables
ai -> directly updating decisions
web controller -> direct SQL
```

Use ArchUnit tests to enforce package boundaries in CI.

---

# 11. Core Data Model

Every tenant-owned table contains:

```sql
tenant_id UUID NOT NULL
```

Every primary domain object also contains:

```text
id
created_at
updated_at where mutable
version where optimistic locking matters
```

## 11.1 Identity and tenancy

### tenants

```text
id
slug
name
status
created_at
```

### users

```text
id
external_subject
email_normalized
display_name
status
created_at
```

### memberships

```text
tenant_id
user_id
role
repository_scope
created_at
```

Roles:

```text
OWNER
ADMIN
SECURITY
RELEASE_MANAGER
ENGINEER
AUDITOR
VIEWER
```

## 11.2 GitHub integration

### github_installations

```text
id
tenant_id
installation_external_id
account_login
permissions_json
status
last_verified_at
```

### repositories

```text
id
tenant_id
provider
external_id
owner
name
default_branch
visibility
criticality
status
```

## 11.3 Changes

### changes

```text
id
tenant_id
repository_id
provider_change_id
change_type
base_sha
head_sha
state
title
author_external_id
opened_at
updated_at
normalized_at
```

Unique:

```text
tenant_id + repository_id + provider_change_id + head_sha
```

### change_files

```text
id
tenant_id
change_id
path
old_path
status
additions
deletions
blob_before_sha
blob_after_sha
language
```

## 11.4 Provenance

### provenance_records

```text
id
tenant_id
change_id
subject_type
subject_digest
source
actor
signature_status
attestation_type
attestation_digest
trust_class
observed_at
```

## 11.5 Graph

### graph_snapshots

```text
id
tenant_id
repository_id
source_sha
builder_version
status
node_count
edge_count
created_at
```

### graph_nodes

```text
id
tenant_id
snapshot_id
node_key
node_type
name
metadata_json
criticality
```

Node types begin with:

```text
REPOSITORY
MODULE
FILE
SYMBOL
SERVICE
ENDPOINT
EVENT
DATABASE
TABLE
EXTERNAL_PROVIDER
TEAM
PERSON
INCIDENT
INVARIANT
DEPLOYMENT
```

### graph_edges

```text
id
tenant_id
snapshot_id
from_node_id
to_node_id
edge_type
confidence
source
metadata_json
```

Edge types begin with:

```text
CONTAINS
IMPORTS
CALLS
EXPOSES
PUBLISHES
CONSUMES
READS
WRITES
DEPENDS_ON
DEPLOYED_AS
OWNED_BY
REVIEWED_BY
CAUSED
AFFECTED
PROTECTED_BY
```

No arbitrary LLM-created edge becomes trusted without supporting evidence.

## 11.6 Evidence

### evidence_items

```text
id
tenant_id
change_id
type
source
source_external_id
trust_class
status
payload_digest
artifact_ref
observed_at
expires_at
metadata_json
```

Status:

```text
PASS
FAIL
UNKNOWN
STALE
NOT_APPLICABLE
```

## 11.7 Verification

### verification_plans

```text
id
tenant_id
change_id
planner_version
policy_version
status
created_at
```

### verification_requirements

```text
id
tenant_id
plan_id
requirement_key
category
severity
mandatory
matcher_reason
satisfied_by_evidence_id
status
```

## 11.8 Policy

### policy_sets

```text
id
tenant_id
name
active_version_id
```

### policy_versions

```text
id
tenant_id
policy_set_id
version_number
canonical_definition
definition_hash
created_by
created_at
```

### decisions

```text
id
tenant_id
change_id
head_sha
graph_snapshot_id
policy_version_id
evidence_snapshot_hash
risk_model_version
risk_score
decision
reason_codes
created_at
```

Decision is immutable.

## 11.9 Antibodies

### incidents

```text
id
tenant_id
external_id
title
severity
started_at
resolved_at
root_cause_summary
evidence_digest
```

### regression_antibodies

```text
id
tenant_id
antibody_key
version
severity
scope_definition
invariant_definition
verification_definition
incident_id
status
effective_at
expires_at
created_by
```

## 11.10 Review routing

### reviewer_profiles

```text
tenant_id
user_id
domain_tags
current_load
availability
last_recomputed_at
```

### expertise_evidence

```text
id
tenant_id
user_id
node_id
evidence_type
weight
source
observed_at
```

## 11.11 Certificates

### release_certificates

```text
id
tenant_id
change_id
head_sha
decision_id
certificate_payload
payload_hash
signature_algorithm
key_id
signature
issued_at
revoked_at
revoke_reason
```

## 11.12 Deployment and reconciliation

### deployments

```text
id
tenant_id
repository_id
environment
artifact_digest
certificate_id
provider_deployment_id
started_at
completed_at
status
```

### observations

```text
id
tenant_id
deployment_id
metric_key
window_start
window_end
expected_value
observed_value
unit
status
source
```

### reconciliations

```text
id
tenant_id
deployment_id
version
result
divergence_score
evidence_hash
created_at
```

## 11.13 Platform reliability

### webhook_deliveries

```text
provider
external_delivery_id
tenant_id
signature_verified
payload_hash
status
received_at
processed_at
```

Unique external delivery ID prevents replay side effects.

### outbox_events

```text
id
tenant_id
aggregate_type
aggregate_id
event_type
event_version
payload_json
occurred_at
published_at
attempt_count
```

### job_queue

```text
id
tenant_id
job_type
dedupe_key
payload_json
status
available_at
lease_until
attempt_count
max_attempts
last_error_code
```

### idempotency_keys

```text
tenant_id
scope
key
request_hash
result_ref
expires_at
```

## 11.14 Audit

### audit_events

Append-only.

```text
id
tenant_id
sequence
actor_type
actor_id
action
resource_type
resource_id
correlation_id
occurred_at
canonical_payload_hash
previous_hash
event_hash
```

Database permissions must deny UPDATE and DELETE for the application role.

---

# 12. System Graph Design

## 12.1 Why PostgreSQL first

Do not introduce Neo4j simply because the word “graph” appears in the product.

Initial GuideIn graph operations are:

- bounded dependency traversal
- reverse dependency traversal
- ownership lookup
- incident linkage
- service-to-data relationships
- path explanations

PostgreSQL can support these through indexed adjacency tables and recursive CTEs.

A dedicated graph database becomes justified only if measured traversal latency, graph size, or graph analytics demands it.

## 12.2 Graph source precedence

```text
1. Explicit repository configuration
2. Build manifests
3. parsed code relationships
4. API specifications
5. deployment manifests
6. runtime telemetry
7. repository metadata
8. human annotations
9. model inference
```

LLM inference is never silently promoted to a trusted structural edge.

## 12.3 Graph confidence

Each edge contains a confidence in `[0, 1]`, but confidence is based on source class, not model vibes.

Example:

```text
explicit OpenAPI reference       1.00
declared Maven dependency        1.00
observed OTel service call       0.95
static import/call analysis      0.90
human annotation                0.85
documentation extraction        0.60
LLM hypothesis                  0.25 / advisory
```

## 12.4 Snapshots

Blast-radius calculations always bind to a snapshot.

Never mutate the graph under a previously issued decision.

---

# 13. Blast Radius Engine

## 13.1 Inputs

```text
change_id
head_sha
graph_snapshot_id
criticality configuration
historical incident links
```

## 13.2 Outputs

```text
direct_nodes[]
transitive_nodes[]
critical_paths[]
incident_matches[]
unknown_edges[]
coverage_score
blast_score
reason_codes[]
```

## 13.3 Traversal

Traversal is typed and bounded.

Example propagation policy:

```text
FILE -> SYMBOL            depth 1
SYMBOL -> SERVICE         depth 2
SERVICE -> ENDPOINT       depth 3
SERVICE -> EVENT          depth 3
SERVICE -> DATABASE       depth 3
SERVICE -> SERVICE        depth 4
SERVICE -> EXTERNAL       depth 4
NODE -> INCIDENT          contextual, no propagation
NODE -> OWNER             contextual, no propagation
```

A maximum traversal budget prevents runaway queries.

## 13.4 Underreporting protection

If graph coverage is below a required threshold for a critical repository:

```text
blast result = INCOMPLETE
```

Never return “low blast radius” because topology is missing.

---

# 14. Deterministic Risk Model

Risk dimensions are normalized `[0, 100]`.

```text
blast_radius          22%
component_criticality 18%
change_surface        12%
historical_failure    12%
security_sensitivity  14%
contract_schema_risk  10%
provenance_risk        5%
verification_gap       7%
--------------------------
TOTAL                100%
```

Formula:

```text
risk =
0.22 * blast_radius
+ 0.18 * component_criticality
+ 0.12 * change_surface
+ 0.12 * historical_failure
+ 0.14 * security_sensitivity
+ 0.10 * contract_schema_risk
+ 0.05 * provenance_risk
+ 0.07 * verification_gap
```

Then clamp:

```text
0 <= risk <= 100
```

Suggested display classes:

```text
0-19   LOW
20-39  MODERATE
40-59  HIGH
60-79  VERY_HIGH
80-100 CRITICAL
```

These classes **do not override hard gates**.

## 14.1 Versioning

Risk model has an immutable version:

```text
guidein-risk-v1
```

Every coefficient and mapping is tested against labeled scenarios.

Changing a coefficient creates `v2`, never silently rewrites `v1`.

---

# 15. Hard Safety Gates

The following examples are independent of numeric risk:

```text
REQUIRED evidence FAIL       -> DENY
REQUIRED evidence UNKNOWN    -> INCOMPLETE
known critical antibody FAIL -> DENY
head SHA mismatch            -> INCOMPLETE
invalid provenance           -> REVIEW_REQUIRED or DENY by policy
unsigned required artifact   -> INCOMPLETE
expired approval             -> INCOMPLETE
cross-environment mismatch   -> DENY
certificate/source mismatch  -> DENY
policy evaluation error      -> INCOMPLETE
```

No “overall score” can average these away.

---

# 16. Verification Planner

The planner consumes **facts**, not prose.

Example facts:

```json
{
  "touches_authentication": true,
  "touches_database_schema": false,
  "touches_payment_effect": true,
  "has_public_api_change": false,
  "blast_class": "HIGH",
  "matched_antibodies": ["PAYMENTS-017"]
}
```

Rules return requirements.

Example:

```text
WHEN touches_payment_effect = true
REQUIRE:
  payment.idempotency
  payment.duplicate_event
  payment.timeout
  payment.reconciliation

WHEN matched_antibody = PAYMENTS-017
REQUIRE:
  invariant.PAYMENTS-017
```

Requirement IDs are stable and machine-readable.

## 16.1 Requirement satisfaction

A requirement is not satisfied by test name similarity.

It must map to an evidence adapter with a declared contract.

Example:

```text
requirement:
payment.idempotency

accepted evidence:
- GuideIn invariant runner result
- trusted CI check with provider check key `guidein/payment-idempotency`
```

---

# 17. Regression Antibody Engine

## 17.1 Creation workflow

```text
incident imported
    ->
root cause reviewed
    ->
candidate antibody drafted
    ->
human approval
    ->
simulation against historical changes
    ->
activation
```

No LLM-generated antibody becomes active automatically.

## 17.2 Antibody structure

Example:

```yaml
key: PAYMENTS-017
version: 1
severity: CRITICAL

scope:
  any:
    - path: "payments/**"
    - graph_node_type: "PAYMENT_EXECUTION"

invariant:
  code: EXACTLY_ONCE_FINANCIAL_EFFECT
  description: >
    Retry behavior must not create more than one financial effect
    for the same idempotency identity.

verification:
  required:
    - payment.idempotency
    - payment.duplicate_event
    - payment.reconciliation
```

## 17.3 Expiry and exceptions

Exceptions:

- require authorized approver;
- require reason;
- require expiry;
- appear in the certificate;
- appear in audit;
- cannot disable unrelated invariants.

---

# 18. Change Passport

A passport is a projection over immutable records.

Minimum passport:

```text
Identity
Provenance
Intent
Source digest
Graph snapshot
Affected components
Blast radius
Historical incident matches
Security-sensitive surfaces
Verification requirements
Evidence status
Reviewer requirements
Risk score
Policy result
Exceptions
Explanation
Decision timestamp
```

## 18.1 Passport states

```text
BUILDING
WAITING_FOR_EVIDENCE
READY_FOR_DECISION
REVIEW_REQUIRED
BLOCKED
ELIGIBLE
STALE
SUPERSEDED
```

Any head-SHA update moves previous passport to `STALE`.

---

# 19. Proof of Safe Change Certificate

Canonical JSON is created from:

```text
tenant
repository
change
head_sha
artifact_digest
graph_snapshot
evidence_snapshot_hash
policy_version_hash
risk_model_version
decision
requirements
approvals
exceptions
issued_at
```

Steps:

```text
canonicalize JSON
     ->
SHA-256
     ->
sign digest using Ed25519 key from KMS/HSM-backed key provider
     ->
store payload + digest + signature + key ID
```

Local development may use a generated development key.

Production signing keys must not live in repository files or environment files committed to source.

## 19.1 Verification endpoint

```text
GET /api/v1/certificates/{id}/verify
```

Returns:

```text
signature_valid
source_digest_match
policy_hash_match
revocation_status
```

---

# 20. GitHub Integration

Use a **GitHub App**, not personal access tokens.

Initial permissions must be minimal and reviewed.

GuideIn needs only the permissions required for:

- repository metadata
- pull requests
- commit/check metadata
- requested checks/status posting if enabled

Write access is opt-in.

## 20.1 Webhook pipeline

```text
POST /api/v1/webhooks/github
```

Processing order:

1. enforce request-size limit;
2. read raw bytes once;
3. verify GitHub HMAC signature;
4. validate known event name;
5. extract delivery ID;
6. reject/reconcile replay;
7. persist raw payload digest and minimal required payload;
8. enqueue durable normalization job;
9. return success quickly.

Webhook HTTP handling must never perform full graph analysis synchronously.

## 20.2 Replay protection

Unique:

```text
provider + delivery_id
```

A duplicate accepted delivery returns idempotent success but does not create duplicate effects.

---

# 21. API Surface

All internal APIs use `/api/v1`.

## Identity

```text
GET  /api/v1/me
GET  /api/v1/tenants/{tenantId}
GET  /api/v1/tenants/{tenantId}/members
```

## Repositories

```text
GET  /api/v1/repositories
GET  /api/v1/repositories/{repositoryId}
POST /api/v1/repositories/{repositoryId}/rescan
```

## Changes

```text
GET /api/v1/changes
GET /api/v1/changes/{changeId}
GET /api/v1/changes/{changeId}/passport
GET /api/v1/changes/{changeId}/blast-radius
GET /api/v1/changes/{changeId}/evidence
```

## Graph

```text
GET /api/v1/graph/snapshots/{snapshotId}
GET /api/v1/graph/nodes/{nodeId}
GET /api/v1/graph/impact?changeId={id}
```

## Verification

```text
GET /api/v1/verification-plans/{planId}
GET /api/v1/verification-requirements/{requirementId}
```

## Antibodies

```text
GET  /api/v1/antibodies
POST /api/v1/antibodies
GET  /api/v1/antibodies/{id}
POST /api/v1/antibodies/{id}/simulate
POST /api/v1/antibodies/{id}/activate
```

## Policy

```text
GET  /api/v1/policies
POST /api/v1/policies/{id}/versions
POST /api/v1/changes/{changeId}/evaluate
```

## Certificates

```text
GET /api/v1/certificates/{id}
GET /api/v1/certificates/{id}/verify
```

## Deployments

```text
POST /api/v1/deployments/events
GET  /api/v1/deployments/{id}
GET  /api/v1/deployments/{id}/reconciliation
```

## Audit

```text
GET /api/v1/audit
```

Audit endpoint is restricted.

---

# 22. Event Envelope

Internal event schema:

```json
{
  "event_id": "uuid",
  "event_type": "change.normalized",
  "event_version": 1,
  "tenant_id": "uuid",
  "aggregate_type": "CHANGE",
  "aggregate_id": "uuid",
  "correlation_id": "uuid",
  "causation_id": "uuid-or-null",
  "occurred_at": "timestamp",
  "payload_hash": "sha256",
  "payload": {}
}
```

Core events:

```text
github.delivery.accepted
change.normalized
provenance.resolved
graph.snapshot.created
blast.computed
antibodies.matched
verification.plan.created
evidence.received
evidence.expired
decision.created
certificate.issued
deployment.started
deployment.completed
runtime.observation.received
reconciliation.completed
incident.imported
antibody.activated
```

Event schemas live under `/contracts/events` and are versioned.

---

# 23. Transaction and Messaging Reliability

GuideIn uses a transactional outbox.

Within one DB transaction:

```text
domain state change
+
outbox event insert
COMMIT
```

A worker publishes/dispatches the event after commit.

This prevents:

```text
database committed
but event disappeared
```

and:

```text
event emitted
but database rolled back
```

Consumers are idempotent.

Delivery semantics:

```text
at least once
+
idempotent consumers
=
effectively once at the domain boundary
```

We do not pretend distributed exactly-once delivery exists.

---

# 24. Job Queue

Initial queue uses PostgreSQL.

Worker claim:

```sql
SELECT ...
FOR UPDATE SKIP LOCKED
LIMIT ...
```

Each job has:

- dedupe key
- lease
- timeout
- attempt count
- maximum attempts
- backoff
- terminal dead-letter state

Backoff:

```text
attempt 1: 5s
attempt 2: 30s
attempt 3: 2m
attempt 4: 10m
attempt 5: dead-letter
```

Adjust per job type.

Permanent errors do not retry endlessly.

---

# 25. Security Architecture

There is no such thing as zero risk.

The goal is **negligible avoidable weakness** through explicit trust boundaries, least privilege, deterministic authority, repeatable testing, and aggressive failure isolation.

## 25.1 Trust zones

```text
UNTRUSTED INTERNET
   |
   v
Edge / TLS / rate limits
   |
   v
Webhook + API validation boundary
   |
   v
Authenticated control plane
   |
   +------> PostgreSQL
   |
   +------> Object storage
   |
   +------> Provider adapters
   |
   +------> LLM adapter (sanitized context only)
   |
   +------> Sandbox runner boundary
                    |
                    v
              HOSTILE CODE ZONE
```

Everything arriving from a repository is untrusted, even when the repository belongs to the customer.

---

# 26. Authentication

## Human users

Use OIDC/OAuth through a trusted identity provider.

Session properties:

- HTTP-only
- Secure
- SameSite appropriate to architecture
- short-lived access session
- rotation
- CSRF protection for state-changing browser flows

Do not store raw OAuth tokens in browser localStorage.

## Machine integrations

Use provider-native signed webhooks, installation credentials, and narrowly scoped service identity.

## GitHub

Use GitHub App installation tokens generated server-side.

Tokens:

- encrypted at rest if persistence is unavoidable;
- preferably generated just in time;
- never returned to browser;
- never added to model context;
- redacted from logs.

---

# 27. Authorization

Use RBAC plus resource scope.

A request is authorized against:

```text
authenticated subject
AND
tenant membership
AND
role
AND
repository scope
AND
requested action
```

Critical actions require explicit capabilities:

```text
policy.write
policy.activate
antibody.activate
certificate.revoke
release.exception.create
integration.write
audit.read
```

Admin is not treated as omnipotent by accident.

Separation of duties can require:

```text
author != required approver
```

for high-risk policy classes.

---

# 28. Tenant Isolation

Every tenant-bound query requires `tenant_id`.

Database RLS is enabled.

Pseudo policy:

```sql
tenant_id = current_setting('guidein.tenant_id')::uuid
```

Application sets tenant context per transaction after authorization.

Defense layers:

1. controller/resource authorization;
2. service-layer tenant-aware IDs;
3. repository predicates;
4. PostgreSQL RLS;
5. compound unique keys including tenant;
6. cross-tenant adversarial tests.

A missing tenant context must fail, not default to all tenants.

---

# 29. Secrets Management

## Never store secrets in:

- git
- `application.yml`
- frontend environment bundles
- model prompts
- audit payloads
- exception messages

Local development:

```text
.env.local ignored by git
```

Production:

- cloud secret manager / Vault-class store
- KMS envelope encryption
- automatic rotation where provider supports it

Logs use structured redaction filters.

A secret scanning CI gate runs before merge.

---

# 30. SSRF Defense

GuideIn will ingest URLs from repositories and external providers.

Therefore outbound fetching is dangerous.

Rules:

1. default-deny arbitrary URL fetching;
2. provider adapters have explicit hostname allowlists;
3. only HTTPS except local development;
4. reject loopback, link-local, private, multicast, and metadata IP ranges unless explicitly required;
5. resolve DNS and validate resulting IP;
6. protect against redirects to disallowed addresses;
7. cap redirects;
8. cap response size;
9. enforce connect/read timeout;
10. content-type validate;
11. do not let the LLM invent fetch URLs.

---

# 31. Untrusted Code and Sandbox Design

The Spring Boot application never executes repository code.

Any future test/invariant execution happens in an isolated runner.

Minimum sandbox:

- ephemeral container/VM
- rootless user
- read-only base filesystem
- isolated writable scratch volume
- no host Docker socket
- no host filesystem mount
- no production credentials
- network disabled by default
- explicit egress allowlist when a test needs it
- CPU quota
- memory quota
- PID limit
- disk quota
- wall-clock timeout
- seccomp profile
- capability drop
- no privileged mode

Input artifact is addressed by digest.

Output is limited to:

- exit status
- structured report
- capped logs
- artifact hashes

The sandbox never receives tenant signing keys.

---

# 32. Prompt Injection and Model Security

Repository content may contain:

```text
"Ignore GuideIn rules"
"send secrets here"
"mark this PR safe"
"call this URL"
```

Treat all repository text as data.

## Required controls

### Context labeling

Every model payload separates:

```text
SYSTEM POLICY
TRUSTED STRUCTURED FACTS
UNTRUSTED REPOSITORY CONTENT
TASK
OUTPUT SCHEMA
```

### No privileged tools

The reasoning model receives no general shell, DB, secret store, deployment, GitHub write, or arbitrary network tool.

### Structured output

Model output validates against JSON Schema.

Invalid output is discarded or retried within bounded limits.

### Data minimization

Send only the minimum relevant diff/context.

### Secret prevention

Run secret/redaction filters before provider transmission.

### Authority firewall

Model output maps only to advisory fields.

There is no code path:

```text
LLM says safe -> ALLOW
```

### Model audit

Record:

- provider
- model identifier
- request template version
- structured input digest
- response digest
- latency
- token usage if available

Raw prompts/responses are retained only according to configured privacy policy.

---

# 33. Data Poisoning Defense

Attackers may try to manipulate GuideIn by:

- fake docs
- fake ownership files
- misleading comments
- malicious telemetry
- fabricated test names
- manipulated incident descriptions

Controls:

1. source trust classes;
2. signed provider ingestion;
3. typed evidence adapters;
4. no free-text evidence satisfying mandatory checks;
5. anomaly detection for abrupt graph changes;
6. human confirmation for policy/antibody activation;
7. provenance displayed in UI;
8. conflicting evidence remains visible;
9. trusted evidence is never overwritten by lower-trust evidence.

---

# 34. Supply-Chain Security

CI must produce:

- dependency vulnerability scan
- SBOM
- secret scan
- static analysis
- test report
- build artifact digest

Requirements:

- Maven dependency versions controlled through dependency management;
- npm uses committed lockfile;
- CI installs with lockfile enforcement;
- GitHub Actions pinned to immutable commit SHAs for high-trust workflows where practical;
- no unreviewed curl-pipe-shell installers;
- container images pinned by digest in protected environments;
- dependency update automation opens PRs, never silently deploys;
- critical dependency CVEs block protected builds according to policy.

---

# 35. Web Security

Mandatory:

- Content Security Policy
- frame-ancestors restriction
- X-Content-Type-Options
- Referrer-Policy
- HSTS in production
- CSRF defense
- output escaping
- strict file download content disposition
- safe diff/log renderer
- no raw HTML from repositories
- rate limits
- upload size limits

Repository Markdown is sanitized using an allowlist.

SVG from repositories is not rendered inline unsanitized.

---

# 36. Audit Integrity

Audit is append-only.

Event hash:

```text
event_hash =
SHA256(
    tenant_id
  + sequence
  + occurred_at
  + canonical_payload_hash
  + previous_hash
)
```

A scheduled integrity job verifies the chain.

Critical actions additionally emit security telemetry.

Audit does not store secrets.

---

# 37. Certificate Signing

Production private keys:

- KMS/HSM-backed when possible;
- inaccessible to frontend;
- inaccessible to LLM;
- inaccessible to sandbox;
- accessed only by certificate service path;
- usage audited.

Key rotation retains old public keys so historical certificates remain verifiable.

---

# 38. Reliability Model

## Failure categories

```text
TRANSIENT_PROVIDER
RATE_LIMIT
TIMEOUT
INVALID_INPUT
AUTHENTICATION
AUTHORIZATION
CONFLICT
STALE_SOURCE
POLICY_ERROR
INTERNAL
DEPENDENCY_UNAVAILABLE
```

Retries only occur for retryable categories.

## Timeouts

Every external call has:

- connect timeout
- request timeout
- total operation deadline

No infinite waits.

## Circuit breakers

Provider adapters use bounded circuit breaking for repeated failures.

A provider outage should degrade GuideIn to:

```text
INCOMPLETE / delayed evidence
```

not false PASS.

---

# 39. Idempotency

Operations requiring idempotency:

- webhook ingestion
- graph build request
- evidence ingestion
- decision evaluation
- certificate issuance
- deployment event ingestion
- runtime observation ingestion
- antibody activation

Certificate unique constraint:

```text
tenant_id + change_id + head_sha + decision_id
```

A retry returns the same logical certificate.

---

# 40. Concurrency

Use optimistic locking for mutable administrative entities.

Decision objects are immutable.

Race example:

```text
PR head SHA = A
evaluation starts
PR updated to SHA B
evaluation finishes for A
```

GuideIn must store result for A but never attach it as current for B.

Current eligibility query verifies exact head SHA.

---

# 41. Consistency Boundaries

Strong consistency required for:

- authorization
- policy activation
- certificate issuance
- exception creation
- source SHA binding

Eventual consistency acceptable for:

- reviewer expertise score
- semantic search embeddings
- UI aggregate counts
- historical similarity suggestions

---

# 42. Backpressure

Provider event storms must not take down API.

Controls:

- fast webhook persistence
- bounded worker pools
- queue depth metrics
- tenant fairness
- per-provider rate limits
- large-repository throttling
- max concurrent graph builds per tenant
- dead-letter queue

Low-priority semantic enrichment yields to decision-critical work.

---

# 43. Observability

OpenTelemetry from day one.

Every request/job/event carries:

```text
trace_id
correlation_id
tenant_id (non-secret internal attribute)
change_id when applicable
```

Metrics:

```text
webhook_ingest_latency
webhook_invalid_signature_total
job_queue_depth
job_retry_total
graph_build_duration
graph_node_count
blast_compute_duration
passport_time_to_ready
evidence_pending_count
policy_decision_total{decision}
certificate_issue_total
reconciliation_divergence_total
llm_failure_total
cross_tenant_denial_total
```

Logs are structured JSON.

Sensitive payloads are not logged by default.

---

# 44. Initial SLOs

After beta stabilization:

| SLI | Target |
|---|---:|
| API availability | >= 99.9% |
| Webhook accepted/persisted p95 | < 500 ms |
| Change normalization p95 | < 5 s |
| Typical passport initial build p95 | < 15 s |
| Existing graph blast query p95 | < 1 s |
| Decision read p95 | < 300 ms |
| Duplicate webhook semantic effects | 0 |
| Cross-tenant data disclosures | 0 |
| Invalid signatures accepted | 0 |
| Policy hard-stop bypasses | 0 |
| LLM-authorized releases | 0 |

Targets are measured; they are not README claims until proven.

---

# 45. Backup and Disaster Recovery

PostgreSQL:

- managed continuous backup or WAL archival;
- daily backup verification;
- point-in-time recovery;
- quarterly restore drill during mature phase.

Object storage:

- versioning where available;
- encryption;
- lifecycle rules;
- backup for irreplaceable artifacts.

Targets for first production-grade release:

```text
RPO <= 5 minutes
RTO <= 60 minutes
```

Certificate verification material must survive application database restoration.

---

# 46. Privacy and Retention

Tenant-configurable retention categories:

- raw webhook payloads
- diffs
- model inputs
- model outputs
- telemetry
- audit
- certificates

Certificates and audit may require longer retention than raw code context.

Deletion workflows must distinguish:

```text
customer-deletable operational data
vs
legally/security-required audit retention
```

No training on customer data by default.

---

# 47. AI Architecture

Use one reasoning layer, not a fashionable swarm of agents.

```text
Structured facts
      |
      v
Prompt builder
      |
      v
Provider abstraction
      |
      v
Schema validator
      |
      v
Advisory explanation
```

The AI module is stateless from the authority perspective.

## AI use cases

Allowed:

- diff summary
- intent classification suggestion
- incident summary
- historical similarity explanation
- test suggestion
- graph-path explanation
- reviewer-facing executive summary

Not allowed:

- trusted graph construction without corroboration
- PASS evidence creation
- policy decision
- certificate signature
- role assignment
- secrets access
- direct write to provider

---

# 48. Historical Similarity

pgvector supports retrieval of historical changes/incidents.

Similarity is a **context feature**, not a decision by itself.

Pipeline:

```text
change representation
   ->
embedding
   ->
tenant-scoped vector search
   ->
candidate historical changes
   ->
deterministic filters
   ->
human-readable similarity context
```

Vector queries must include tenant restriction at the database level.

---

# 49. Reviewer Expertise Routing

Score candidate reviewer `r` for change `c`:

```text
expertise =
0.35 * subsystem_history
+ 0.20 * prior_review_relevance
+ 0.20 * incident_experience
+ 0.15 * declared_domain
+ 0.10 * ownership_signal
```

Then apply penalties:

```text
review_load
unavailability
separation_of_duties
conflict_of_interest
```

Do not expose a misleading “human quality score.”

The score represents **relevance for this change**, not employee performance.

---

# 50. Deployment Reconciliation

A deployment must bind:

```text
certificate_id
artifact_digest
environment
deployment_id
```

If deployed artifact digest differs from certified artifact:

```text
CERTIFICATE_MISMATCH
=> critical alert
```

## Observation windows

Initial configurable windows:

```text
5 minutes
15 minutes
60 minutes
24 hours
```

Metrics may include:

- error rate
- latency
- saturation
- queue depth
- business metric
- specific invariant metric

---

# 51. Counterfactual / Expected Impact

Do **not** begin with grand claims that GuideIn predicts production perfectly.

V1 expected-impact engine is conservative.

It can produce:

- known touched metrics
- historical baseline ranges
- similar-change outcomes
- graph-derived risk surfaces

Only after enough labeled data should GuideIn introduce calibrated probabilistic predictions.

Every prediction stores:

```text
prediction
confidence interval
model/rule version
features
timestamp
actual outcome later
```

Calibration is continuously measured.

---

# 52. Evaluation Harness

GuideIn must have a first-class deterministic evaluation suite similar in seriousness to a financial safety system.

Target before public “production-grade” claims:

```text
>= 1,000 deterministic scenarios
>= 40 scenario categories
fixed seeds where randomness exists
machine-readable oracle
```

Categories include:

1. safe documentation-only change;
2. low-risk UI change;
3. public API breaking change;
4. database destructive migration;
5. backward-compatible migration;
6. auth permission widening;
7. auth permission narrowing;
8. payment idempotency regression;
9. duplicate event handling;
10. queue retry semantics;
11. secret introduced into diff;
12. unsigned commit policy;
13. stale CI evidence;
14. failing required check;
15. missing required check;
16. unknown graph coverage;
17. dependency criticality;
18. cross-service blast;
19. incident antibody match;
20. expired antibody exception;
21. valid exception;
22. unauthorized exception;
23. policy version mismatch;
24. head SHA race;
25. replayed webhook;
26. forged webhook;
27. huge PR;
28. deleted service;
29. renamed file;
30. monorepo ownership;
31. bot-authored PR;
32. model unavailable;
33. GitHub unavailable;
34. telemetry unavailable;
35. sandbox timeout;
36. sandbox resource exhaustion;
37. malicious repository prompt injection;
38. poisoned documentation;
39. certificate tampering;
40. cross-tenant access attempt.

---

# 53. Zero-Tolerance Evaluation Gates

Release candidate fails if any scenario produces:

```text
invalid webhook accepted > 0
cross-tenant data read > 0
policy hard-stop bypass > 0
LLM creates trusted PASS > 0
certificate/source digest mismatch accepted > 0
duplicate event semantic effect > 0
unauthorized exception activation > 0
stale SHA treated as current > 0
audit mutation allowed > 0
sandbox receives production secret > 0
sandbox unrestricted network by default > 0
```

These are release blockers.

---

# 54. Testing Strategy

## Unit

Pure domain logic:

- risk scoring
- policy predicates
- graph traversal rules
- requirement matching
- antibody matchers
- certificate canonicalization
- hash chain

Target high coverage of critical logic, but coverage percentage alone is not the goal.

## Property-based

Examples:

```text
risk is always 0..100
reordering evidence cannot alter evidence-set hash after canonicalization
duplicate events do not duplicate domain effects
tenant A identifiers never authorize tenant B
```

## Integration

Use Testcontainers for:

- PostgreSQL
- MinIO
- provider stub

## Contract

Provider webhook fixtures and OpenAPI schemas.

## Architecture

ArchUnit module-boundary tests.

## Mutation

Run mutation testing on policy/risk/antibody core to prove tests catch altered logic.

## Fuzz

Fuzz:

- webhook parser
- diff parser
- policy parser
- JSON schemas
- Markdown sanitizer
- certificate verifier

## Security

- SAST
- dependency scan
- secret scan
- DAST against staging
- authorization matrix tests
- RLS tests

## Chaos

Later phases:

- DB connection interruption
- provider timeout
- worker crash
- duplicate event burst
- model outage
- object storage outage

---

# 55. Security Test Matrix

Mandatory automated tests include:

```text
IDOR attempts
role escalation
tenant ID substitution
repository scope bypass
CSRF
XSS via code/diff/Markdown
SQL injection
SSRF
webhook forgery
webhook replay
JWT/session misuse
stale authorization
malicious ZIP/archive
path traversal
oversized payload
decompression bomb
Unicode spoofing display cases
prompt injection
model schema escape
secret leakage into model prompt
signed-certificate tampering
audit-chain tampering
sandbox breakout assumptions
```

True sandbox escape testing requires an appropriate controlled environment and must not endanger developer machines.

---

# 56. Stable Implementation Definition

A phase is **not complete because the UI works**.

A stable phase requires:

1. migration committed;
2. domain invariants documented;
3. unit tests;
4. integration tests;
5. negative tests;
6. security tests where relevant;
7. structured metrics;
8. failure behavior defined;
9. runbook;
10. rollback path;
11. evaluation scenario coverage;
12. no unresolved P0/P1 defects.

---

# 57. Implementation Phases

---

## PHASE 0 — Preserve V1 and Establish the Engineering Contract

### Goal

Protect GuideIn history and remove ambiguity before rebuilding.

### Work

- tag current repository `v1-career-mentor`;
- archive current desktop source under `legacy/desktop-v1` only after tag;
- remove accidental build artifacts such as committed `target/`;
- add `.gitignore`;
- inventory dependencies;
- document the old API integration;
- create ADR structure;
- create `SECURITY_INVARIANTS.md`;
- create threat-model skeleton;
- create backend/web folders;
- set code formatting;
- establish CI.

### CI baseline

```text
compile
unit test
architecture test
secret scan
dependency scan
SBOM
```

### Exit gate

- clean build from fresh clone;
- no secrets in history currently referenced by runtime;
- V1 recoverable from tag;
- new skeleton starts without desktop dependencies;
- CI green.

---

## PHASE 1 — Platform Kernel

### Goal

Build the foundation before product features.

### Implement

- Spring Boot application;
- PostgreSQL/Flyway;
- tenant model;
- user/membership model;
- authentication;
- RBAC/resource scopes;
- RLS;
- audit ledger;
- transactional outbox;
- job queue;
- error model;
- correlation IDs;
- OpenTelemetry;
- health/readiness endpoints.

### Required tests

- every role/action pair;
- missing tenant context;
- cross-tenant repository IDs;
- audit update/delete denied;
- outbox commit atomicity;
- duplicate job claim safety.

### Exit gate

```text
cross-tenant successful reads = 0
unauthorized protected actions = 0
audit mutation from app role = 0
```

Do not proceed if tenant isolation is weak.

---

## PHASE 2 — GitHub Trustworthy Ingestion

### Goal

Receive GitHub change data without creating an attack surface.

### Implement

- GitHub App configuration;
- installation lifecycle;
- signed webhook endpoint;
- replay protection;
- provider client;
- PR/commit normalization;
- file metadata;
- CI check ingestion;
- provenance records;
- rate limit awareness;
- job-based processing.

### Adversarial tests

- forged signature;
- empty signature;
- correct signature wrong body;
- duplicate delivery ID;
- oversized payload;
- unknown event;
- provider timeout;
- revoked installation.

### Scale test

Replay at least 10,000 deliveries with duplicates.

### Exit gate

```text
invalid signatures accepted = 0
duplicate semantic effects = 0
lost accepted deliveries = 0 in controlled test
```

---

## PHASE 3 — System Graph V1

**Status: COMPLETE / PROVEN (2026-09-17).** Final evidence: 189/189 tests, 20/20 labeled fixtures, 100% explicit-relationship recall, zero false trusted edges, 100/100 persisted reproducibility, and a successful 50,000-node / 250,000-edge PostgreSQL proof. See `reports/PHASE_3_REPORT.md` and `evaluation/phase3-results.json`.

### Goal

Create an explainable topology graph.

### Sources V1

- repository file tree;
- Maven/Gradle/npm manifests;
- Java imports/packages;
- OpenAPI if present;
- Docker/Compose;
- basic deployment manifests;
- explicit GuideIn config file.

### Implement

- graph snapshots;
- node/edge model;
- source confidence;
- graph build worker;
- recursive traversal;
- graph explorer API;
- graph diff between snapshots.

### Do not implement yet

- broad language support;
- AI-generated graph edges as authoritative;
- Neo4j.

### Exit gate

For labeled fixtures:

```text
known explicit dependency recall >= 98%
false trusted edges <= 1%
snapshot reproducibility = 100%
```

Unknown topology is surfaced, not hidden.

---

## PHASE 4 — Change Passport V1

### Goal

Create the central product object.

### Implement

- Change Passport projection;
- provenance section;
- source diff summary;
- affected graph nodes;
- evidence status;
- status machine;
- stale-head handling;
- web passport page.

### AI

Allow optional advisory diff explanation behind provider interface.

Test with AI completely disabled.

### Exit gate

Every passport field can trace to:

```text
trusted evidence
or
deterministic computation
or
clearly labeled advisory inference
```

No unlabeled inference.

---

## PHASE 5 — Blast Radius Engine

### Goal

Map source change to true operational surface.

### Implement

- typed traversal rules;
- path explanations;
- criticality;
- graph coverage;
- incident links;
- blast score;
- incomplete-state behavior.

### Fixtures

Build representative systems:

- monolith;
- microservice;
- event-driven;
- shared DB;
- API gateway;
- package library;
- monorepo.

### Exit gate

On labeled critical fixtures:

```text
critical affected-node recall >= 95%
known critical dependency underreporting = 0
```

If precision is lower initially, false positives are safer than silent critical false negatives, but noise must be measured.

---

## PHASE 6 — Verification Planner

### Goal

Turn risk into required evidence.

### Implement

- typed fact extractor;
- requirement registry;
- rule registry;
- plan versioning;
- requirement/evidence binding;
- mandatory vs advisory checks;
- GitHub check status projection.

### Initial requirement packs

```text
general
security
authentication
database
public_api
event_processing
payments
deployment
```

### Exit gate

All labeled change categories generate the oracle-required mandatory checks.

```text
missing oracle mandatory requirement = 0
```

---

## PHASE 7 — Regression Antibodies

### Goal

Make failures permanently useful.

### Implement

- incident import model;
- antibody DSL;
- draft/simulate/activate lifecycle;
- historical simulation;
- exception handling;
- expiry;
- matching in verification planning.

### Safety

Only authorized humans activate an antibody.

### Exit gate

- active antibody deterministically matches all positive fixtures;
- no unrelated fixture is incorrectly hard-blocked beyond agreed tolerance;
- bypass attempts fail.

---

## PHASE 8 — Policy Governor + Risk + Certificates

### Goal

Introduce actual governance.

### Implement

- risk model v1;
- hard gates;
- policy sets/versions;
- decision snapshots;
- ALLOW/REVIEW_REQUIRED/DENY/INCOMPLETE;
- certificate canonicalization;
- signing;
- verification;
- revocation;
- GitHub status/check publishing, initially non-blocking.

### Shadow mode first

GuideIn observes and computes decisions without blocking merges.

Compare GuideIn decisions with known expected outcomes.

### Exit gate

Run >= 1,000 deterministic scenarios.

All zero-tolerance gates pass.

Only then allow optional protected-branch enforcement.

---

## PHASE 9 — Reviewer Expertise Routing

### Goal

Reduce reviewer overload without replacing human judgment.

### Implement

- expertise evidence;
- domain tags;
- current load;
- reviewer suggestions;
- separation of duties;
- recommendation explanation.

### Privacy

Do not present this as employee performance ranking.

### Exit gate

On a labeled reviewer dataset:

- correct domain reviewer appears in top 3 >= 90%;
- no unauthorized private activity used;
- workload penalty prevents pathological over-routing.

---

## PHASE 10 — Deployment Observer + Reconciliation

### Goal

Close the loop between pre-merge confidence and production reality.

### Implement

- deployment ingestion;
- artifact digest binding;
- certificate binding;
- OTel observation adapter;
- expected ranges;
- observation windows;
- divergence engine;
- rollout state model;
- incident candidate generation.

### Initial action

Alert only.

Do not auto-rollback in first implementation.

### Exit gate

- wrong artifact/certificate binding detected 100%;
- synthetic runtime regressions detected according to configured thresholds;
- missing telemetry becomes UNKNOWN, never healthy.

---

## PHASE 11 — Security and Failure Hardening

### Goal

Attack GuideIn itself.

### Work

- full STRIDE review;
- OWASP web/API tests;
- authorization fuzzing;
- RLS red team;
- prompt injection suite;
- SSRF suite;
- malicious repository fixture corpus;
- dependency poisoning simulations;
- certificate tampering;
- audit tampering;
- queue storm;
- database failover drill;
- provider outage drill;
- sandbox abuse tests in isolated test infra.

### Exit gate

No unresolved:

```text
P0 critical
P1 high
```

All security invariants automated where possible.

---

## PHASE 12 — Production Beta

### Goal

Prove the system on real repositories before broad claims.

### Deployment progression

```text
local
   ->
single test repository
   ->
multiple repositories / one tenant
   ->
shadow production usage
   ->
non-blocking recommendations
   ->
selected protected-branch enforcement
```

### Beta evidence required

Track:

- false blocks;
- missed risky changes;
- time to passport;
- graph coverage;
- verification noise;
- reviewer acceptance;
- provider failures;
- operational cost.

Do not market accuracy numbers without this dataset.

---

# 58. UI Information Architecture

GuideIn should not look like a generic chat app.

Main navigation:

```text
Overview
Changes
System Graph
Verification
Antibodies
Deployments
Policies
Audit
Settings
```

## Change page

Header:

```text
PR #8241
Risk: HIGH
Decision: REVIEW REQUIRED
Evidence: 14 / 16
Head SHA: ...
```

Sections:

1. Summary
2. Blast Radius
3. Required Verification
4. Evidence
5. History / Similar Incidents
6. Reviewers
7. Policy Decision
8. Certificate
9. Deployment Outcome

## System Graph

Visual graph is useful, but table/path views are mandatory.

Pretty graph visualization must never be the only way to inspect an impact path.

---

# 59. GuideIn Configuration File

Repository-level optional config:

```yaml
guidein:
  version: 1

  repository:
    criticality: HIGH

  components:
    - name: payment-service
      paths:
        - "services/payments/**"
      criticality: CRITICAL

  protections:
    - match:
        paths:
          - "services/payments/**"
      require:
        - payment.idempotency
        - payment.reconciliation

  owners:
    - domain: payments
      team: payment-platform
```

Configuration is schema-validated.

Unknown dangerous keys fail validation instead of being ignored silently.

---

# 60. Error Contract

API error:

```json
{
  "code": "STALE_CHANGE_HEAD",
  "message": "The change head SHA no longer matches the evaluated passport.",
  "correlation_id": "...",
  "retryable": false
}
```

Never leak:

- stack trace
- SQL
- credentials
- internal token
- raw provider secret
- unrestricted filesystem path

---

# 61. Migration Strategy from Current GuideIn

Current GuideIn is a Java desktop AI career mentor.

The rewrite is conceptually large enough that pretending it is an incremental UI feature would produce a bad architecture.

Migration:

```text
1. Tag current main as v1-career-mentor.
2. Preserve old README/history.
3. Move V1 desktop code to legacy only after tag.
4. Create new backend skeleton.
5. Reuse generic HTTP/JSON learning where useful, not old career-domain classes.
6. Replace direct Gemini coupling with ReasoningProvider abstraction.
7. Build web control plane separately.
8. Update README only when Phase 1+ foundation is real.
```

Do not drag old `Candidate`, career-profile, or chat-domain models into the new core.

The lineage is historical, not a requirement for bad reuse.

---

# 62. CI/CD for GuideIn Itself

Pull request pipeline:

```text
format
compile
unit
architecture
integration
evaluation subset
SAST
dependency scan
secret scan
SBOM
frontend typecheck
frontend test
container build
```

Protected main:

```text
all mandatory checks
review
signed/verified merge according to repository policy
```

Release pipeline:

```text
full evaluation suite
security suite
container scan
SBOM/provenance
image digest
staging deploy
smoke
migration check
manual production approval initially
```

Production deployment is progressive.

---

# 63. Database Migration Rules

Flyway only.

Rules:

1. migrations immutable after release;
2. additive schema change preferred;
3. destructive changes use expand/migrate/contract;
4. index creation considered for lock impact;
5. large backfills run separately;
6. rollback is operationally planned even when DB migration itself is forward-only.

GuideIn must practice the release discipline it recommends to others.

---

# 64. Dependency Discipline

Every new dependency must answer:

```text
What problem does it solve?
Can the JDK/Spring/Postgres already solve it?
What is its security/support posture?
What is the maintenance cost?
Can it be removed?
```

Avoid framework collection.

A strong project is not measured by dependency count.

---

# 65. Performance Test Plan

Datasets:

```text
small:
  1k graph nodes
  5k edges

medium:
  50k nodes
  250k edges

large target:
  250k nodes
  1.5m edges
```

Measure:

- graph build
- reverse traversal
- passport composition
- policy evaluation
- queue throughput
- UI API response

Load tests include multiple tenants.

Tenant fairness must prevent one large repository from starving smaller workloads.

---

# 66. Cost Controls

Track per tenant/change:

- external API calls
- model tokens
- embedding calls
- object storage
- graph processing time
- sandbox compute

LLM enrichment is skipped when deterministic data already answers the question.

Set:

- per-tenant quotas
- bounded context
- caching by digest
- embedding dedupe
- model timeout
- fallback behavior

Cost failure must not become safety failure.

---

# 67. Threat Model — High-Risk Abuse Cases

## T1: Forged GitHub webhook

**Impact:** fake PASS evidence / fake change.

**Control:** HMAC verification, replay ID, provider refetch for critical facts.

## T2: Malicious PR contains prompt injection

**Impact:** attempts to influence model.

**Control:** untrusted context isolation, no privileged tools, schema validation, advisory-only AI.

## T3: Cross-tenant object ID substitution

**Impact:** source/code/evidence leak.

**Control:** tenant-aware authorization + RLS + adversarial test suite.

## T4: Compromised CI sends fake check

**Impact:** false evidence.

**Control:** source provenance, provider identity, policy may require independent GuideIn checks for critical invariants.

## T5: Repository code attempts sandbox escape

**Impact:** infrastructure compromise.

**Control:** isolated runner, no host socket, no secrets, resource/network limits.

## T6: Attacker modifies policy

**Impact:** bypass.

**Control:** capability authorization, immutable versions, audit, optional two-person approval for critical policy.

## T7: Certificate tampering

**Impact:** fake release proof.

**Control:** canonical hash + asymmetric signature + verification + revocation.

## T8: Stale PR evaluation reused

**Impact:** unreviewed code ships.

**Control:** exact head SHA binding.

## T9: Graph incompleteness creates false low risk

**Impact:** missed blast radius.

**Control:** coverage score and INCOMPLETE state.

## T10: Model provider outage

**Impact:** explanations missing.

**Control:** core remains operational; no safety authority depends on model.

## T11: LLM/data exfiltration

**Impact:** IP leak.

**Control:** minimum context, redaction, provider policy, tenant configuration, local/provider abstraction.

## T12: Insider creates permanent exception

**Impact:** long-term bypass.

**Control:** exception scope + expiry + audit + capability + optional dual approval.

---

# 68. Security Invariants

These should become executable tests.

```text
SEC-001  Invalid GitHub signatures never mutate domain state.
SEC-002  Duplicate webhook delivery never duplicates semantic state.
SEC-003  Tenant A cannot read Tenant B rows through any supported API.
SEC-004  Tenant A cannot infer Tenant B existence through object IDs.
SEC-005  LLM output cannot create trusted evidence.
SEC-006  LLM output cannot change decision enum.
SEC-007  LLM has no access to signing key.
SEC-008  Sandbox has no production secret.
SEC-009  Sandbox has no default internet egress.
SEC-010  Certificate binds exact source SHA.
SEC-011  Certificate binds exact policy version.
SEC-012  Stale evidence cannot satisfy fresh mandatory requirement.
SEC-013  Policy evaluation failure never produces ALLOW.
SEC-014  Missing tenant context fails closed.
SEC-015  Audit events cannot be updated/deleted by application role.
SEC-016  Exception requires authorized actor and expiry.
SEC-017  Protected decision cannot be created without evidence snapshot hash.
SEC-018  Unknown graph coverage cannot be represented as known-low risk.
SEC-019  Browser never receives GitHub App private credentials.
SEC-020  Secret-looking content is redacted before model transmission.
```

---

# 69. Weak-Point Register

| Weak point | Why dangerous | Primary mitigation | Validation |
|---|---|---|---|
| Incomplete System Graph | False low blast radius | Coverage score + fail incomplete | Labeled graph fixtures |
| Noisy blast radius | Alert fatigue | Typed bounded traversal | Precision/recall benchmark |
| LLM hallucination | Fake confidence | Advisory-only authority firewall | Injection/evaluation tests |
| Stale PR state | Wrong source approved | SHA-bound passports | Race tests |
| Webhook replay | Duplicate state/actions | Delivery-id idempotency | 10k replay test |
| Multi-tenant leak | Critical security failure | RLS + app auth | Cross-tenant matrix |
| CI compromise | Fake evidence | provenance + independent invariants | forged evidence fixtures |
| Policy complexity | Hidden bypass | typed rules + versioning | mutation/property tests |
| Sandbox breakout | Control-plane compromise | hard isolation | isolated red-team tests |
| Certificate key theft | Fake proof | KMS/HSM + least privilege | key access audit |
| Excessive dependencies | Supply-chain risk | dependency discipline | SBOM + review |
| Queue overload | delayed decisions/outage | backpressure + fairness | burst load test |
| External provider outage | incomplete state | durable retry + fail closed | chaos test |
| AI cost blowout | unsustainable operation | digest cache + limits | per-tenant cost metrics |
| Reviewer scoring misuse | workplace harm/noise | relevance-only framing | product/privacy review |
| Antibody overreach | false blocks forever | simulation + version + expiry | historical replay |
| Policy exception abuse | silent permanent bypass | scope/expiry/audit | authorization tests |
| Raw logs leak secrets | credential exposure | structured redaction | canary secret tests |
| Graph DB premature complexity | operational fragility | PostgreSQL first | benchmark before migration |
| Microservices premature split | distributed failure surface | modular monolith | architecture boundaries |
| Runtime telemetry poisoning | false reconciliation | authenticated source + trust | spoof fixtures |
| Model provider data exposure | code/IP leak | minimization + configurable provider | data-flow review |
| Huge PRs | time/memory exhaustion | limits + chunked parsing | >100k-line fixtures |
| Monorepos | graph explosion | component boundaries + incremental graph | large dataset load test |
| Ambiguous ownership | wrong reviewers | evidence-based routing | labeled reviewer evaluation |

---

# 70. Decisions We Intentionally Defer

Do not decide until measurements justify them:

- Neo4j
- Kafka
- Redis
- Kubernetes
- service mesh
- autonomous rollback
- autonomous merge
- multi-model voting
- model fine-tuning
- custom graph ML
- eBPF deep runtime topology
- additional source hosts beyond GitHub
- Jira/Linear/Datadog integrations

A deferred decision is not missing architecture; it is controlled scope.

---

# 71. ADRs Required Before Major Implementation

Create at minimum:

```text
ADR-001 Modular Monolith First
ADR-002 PostgreSQL as Primary Graph Store
ADR-003 Deterministic Authority / Advisory AI
ADR-004 Transactional Outbox
ADR-005 Tenant Isolation with RLS
ADR-006 GitHub App Authentication
ADR-007 Evidence Trust Classification
ADR-008 Certificate Signing
ADR-009 Sandbox Isolation
ADR-010 Policy Versioning
ADR-011 Change SHA Immutability
ADR-012 Runtime Reconciliation Model
```

---

# 72. Definition of Done for V2 Core

GuideIn V2 core is complete only when a demo can prove this entire path using real code and deterministic evidence:

```text
1. GitHub PR opened.
2. Signed webhook verified.
3. PR normalized.
4. System Graph snapshot selected/built.
5. Blast radius explains affected service/database/API.
6. Historical incident antibody matches.
7. Verification Planner requires the correct tests.
8. One required test initially missing -> INCOMPLETE.
9. Evidence arrives.
10. Deterministic policy reevaluates.
11. Qualified reviewer requirement is satisfied.
12. Release becomes ALLOW.
13. Signed certificate is produced.
14. Exact certified artifact is deployed.
15. Runtime telemetry is observed.
16. Reconciliation detects healthy or divergent behavior.
17. A synthetic incident can be converted to a new antibody.
18. A future similar PR is automatically challenged by that antibody.
```

That loop is the product.

Anything that does not strengthen this loop is secondary.

---

# 73. Demo Scenario We Should Build Toward

Create a deliberately realistic demo repository with:

```text
checkout-api
payment-orchestrator
retry-worker
PostgreSQL
Kafka-like event contract
provider-adapter
```

Historical incident:

```text
INC-193
Duplicate payment effect caused by retry after acknowledgement timeout.
```

Antibody:

```text
PAYMENTS-017
Retry operations must preserve one financial effect per idempotency identity.
```

Demo PR:

```text
"Improve retry latency"
```

The code subtly weakens idempotency.

GuideIn shows:

```text
Risk: HIGH
Blast radius:
- payment-orchestrator
- retry-worker
- provider-adapter
- transaction table

Historical match:
INC-193

Required:
payment.idempotency
payment.duplicate_event
payment.reconciliation

Result:
payment.idempotency FAILED

Decision:
DENY
```

Then fix the code.

Checks pass.

GuideIn issues certificate.

Deploy.

Observe telemetry.

Reconcile.

This is far stronger than a slideshow of AI text.

---

# 74. README Claim Discipline

Never claim:

```text
"prevents all outages"
"guarantees safe releases"
"100% secure"
"AI understands the whole codebase"
```

Claims must be evidence-backed.

Preferred language:

```text
"deterministically enforces configured release invariants"
"binds release decisions to immutable source and evidence snapshots"
"maps known dependency blast radius"
"fails closed when mandatory evidence is unavailable"
```

---

# 75. Immediate Build Order

Exact first implementation order:

```text
01  Tag V1
02  Clean repository
03  Add architecture/security docs
04  Scaffold backend
05  PostgreSQL + Flyway
06  Tenant/user/membership
07  Spring Security/OIDC dev path
08  PostgreSQL RLS
09  Audit ledger
10  Outbox
11  Job queue
12  Observability
13  GitHub App webhook verification
14  GitHub installation/repository model
15  Change normalization
16  Provenance
17  Graph schema
18  Graph snapshot builder
19  Change Passport
20  Blast Radius
21  Verification rule engine
22  Evidence adapters
23  Regression Antibodies
24  Risk model
25  Policy Governor
26  Certificate signer/verifier
27  GitHub status projection
28  Reviewer router
29  Deployment ingestion
30  Runtime observation
31  Reconciliation
32  Full evaluation harness
33  Security hardening
34  Shadow beta
35  Optional enforcement
```

Do not jump to step 23 because it demos well if steps 4-16 are unstable.

---

# 76. First Commit Sequence

Suggested first commits:

```text
chore: snapshot GuideIn v1 lineage
chore: establish v2 repository structure
docs: add architecture decisions and security invariants
feat(platform): bootstrap Spring Boot control plane
feat(tenancy): add tenant-aware identity model and RLS
feat(audit): add append-only tamper-evident audit ledger
feat(platform): add transactional outbox and durable jobs
feat(github): verify and persist GitHub deliveries
feat(change): normalize pull request revisions by head SHA
feat(provenance): add source and workflow provenance model
feat(graph): add versioned system graph foundation
```

Small coherent commits make later debugging and review much easier.

---

# 77. Final Architecture Summary

```text
                         +-------------------+
                         |      GitHub       |
                         +---------+---------+
                                   |
                             signed webhook
                                   |
                                   v
+-------------------------------------------------------------------+
|                       GUIDEIN CONTROL PLANE                        |
|                                                                   |
|  Identity/Tenancy                                                 |
|       |                                                           |
|       v                                                           |
|  Trusted Ingestion --> Provenance --> Change Model                 |
|                                   |                               |
|                                   v                               |
|                            System Graph                            |
|                                   |                               |
|                         +---------+---------+                      |
|                         |                   |                      |
|                         v                   v                      |
|                    Blast Radius       Antibody Match               |
|                         |                   |                      |
|                         +---------+---------+                      |
|                                   v                               |
|                         Verification Planner                      |
|                                   |                               |
|                                   v                               |
|                              Evidence                             |
|                                   |                               |
|                                   v                               |
|                          Deterministic Policy                      |
|                          /        |        \                       |
|                       DENY      REVIEW      ALLOW                  |
|                                               |                   |
|                                               v                   |
|                                      Signed Certificate           |
|                                               |                   |
+-----------------------------------------------+-------------------+
                                                |
                                                v
                                         Deployment System
                                                |
                                                v
                                      Runtime / OpenTelemetry
                                                |
                                                v
+-------------------------------------------------------------------+
|                         GUIDEIN RECONCILIATION                     |
|            Expected vs observed -> incident -> antibody            |
+-------------------------------------------------------------------+

LLM = explanation layer around facts.
LLM != authority.
```

---

# 78. The Standard We Hold GuideIn To

GuideIn should surpass Sentinel not by containing more screens or more AI calls, but by having a stronger engineering claim:

> **Sentinel governed dangerous financial recovery actions. GuideIn governs whether software changes have enough evidence to deserve production.**

The project is successful when:

- its architecture is understandable;
- failure modes are explicit;
- security boundaries are testable;
- decisions are reproducible;
- integrations are idempotent;
- evidence is traceable;
- AI is useful without becoming authority;
- unknowns fail safely;
- historical failures become permanent protections;
- every issued release certificate can explain exactly why it existed.

The foundation comes before spectacle.

The stable implementation comes before scale.

The evidence comes before the claim.

**Every change enters production with proof.**
