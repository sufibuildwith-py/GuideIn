# Phase 3 research — System Graph V1

Status: research and measurement in progress. No Phase-3 quality gate has yet executed.
Accessed: 2026-09-15. Sources below are upstream documentation or repositories. No upstream implementation code is copied.

## Baseline and scope

The local source of truth is main at efc47c3, with Phase-2 implementation 1adc1bf and evidence fd33842 after the README push rebased history. Original recorded proof references remain 71ad5e2a3a7ba72f69b61c770212d011c9ee2d10 and c51d837e446529ad5422d77e7b2e53b4c08350a4. Existing Phase-1/2 evidence records 133 passing tests. New results must come from executed Phase-3 tests.

The graph describes an exact source revision. It is a topology evidence product with immutable published snapshots, deterministic keys, explicit gaps, and source locations. Graph extraction never evaluates repository build scripts.

## Reference registry

Each row uses the access date above. Decisions concern Phase 3; benchmark-dependent choices remain provisional.

| Reference / source | Problem and design principle | Decision and borrowed concept | Not borrowed | License / security implications |
|---|---|---|---|---|
| REF-CODEQL — [database creation](https://docs.github.com/en/code-security/reference/code-scanning/codeql/codeql-cli-manual/database-create) | Source-root binding and queryable extracted models | ADAPT revision-bound extraction followed by deterministic queries | DEFER CLI/runtime and compiled build extraction | CLI has GitHub CodeQL terms; no dependency or code copied; repository compilation could execute hostile builds |
| REF-OPENREWRITE — [LST](https://docs.openrewrite.org/concepts-and-explanations/lossless-semantic-trees), [type attribution](https://docs.openrewrite.org/reference/type-attribution), [upstream](https://github.com/openrewrite/rewrite) | Syntax alone cannot identify semantic targets; source sets and classpaths matter | EVALUATE parsing and type attribution in isolated benchmark; ADAPT distinction between syntax and resolution | No recipes, source rewriting, build-plugin invocation or uncontrolled classpath resolution | Apache-2.0 core; parse supplied bounded strings only; measure memory and transitive footprint |
| REF-JAVAPARSER — [upstream](https://github.com/javaparser/javaparser) | Java AST, declarations/imports and optional symbol solver | EVALUATE core against OpenRewrite; candidate for narrow Java-21 syntax extraction | No blindly enabled JavaParserTypeSolver/JarTypeSolver/ReflectionTypeSolver; no fetching dependencies or loading repository classes | Upstream offers Apache-2.0 licensing; use that option. Core and solver are distinct dependencies; measure failure and classpath behavior |
| REF-SCIP — [protocol](https://github.com/scip-code/scip) | Document-relative locations, symbols and occurrences | ADAPT stable qualified symbol naming and definition/reference distinction | DEFER SCIP import/export and indexer/server dependencies | Apache-2.0 protocol; no runtime dependency; paths remain repository-relative |
| REF-JQASSISTANT — [upstream](https://github.com/jqassistant/jqassistant) | Scan structural facts, query architecture | CONCEPTUAL REFERENCE ONLY | No code copying, GPL runtime or Neo4j | GPL-3.0 core; no dependency added |
| REF-BACKSTAGE — [system model](https://backstage.io/docs/features/software-catalog/system-model/) | Components, APIs, resources, owners and systems have distinct meanings | ADAPT typed graph vocabulary | No wholesale mutable catalog schema | Apache-2.0 project; repository declarations still require schema validation and revision binding |
| REF-SEMGREP — [upstream](https://github.com/semgrep/semgrep) | Language-aware patterns and bounded structural matching | DEFER runtime; borrow explicit rule scope and negative fixtures | No broad language scan, rule download or security verdicts | LGPL-2.1 community engine; proprietary extensions/rule licenses separate; no dependency |
| REF-TREESITTER — [documentation](https://tree-sitter.github.io/tree-sitter/) | Incremental multi-language syntax trees | DEFER broad language extraction | No native grammars or JNI runtime | MIT core, grammar licenses vary; Java-specific benchmark takes priority |
| REF-MAVEN — [Model Builder](https://maven.apache.org/ref/3.9.11/maven-model-builder/) | Effective models need inheritance, interpolation, profiles and BOM resolution | ADAPT explicit versus effective distinction; bounded local parent handling and gaps | No plugins, lifecycle, network resolution, environment interpolation or claim of full effective model | Apache-2.0; JDK XML parser can handle raw declarations with external entities/DTDs disabled |
| REF-GRADLE — [Tooling API](https://docs.gradle.org/current/userguide/tooling_api.html) | Models may require Gradle daemon/configuration execution | DEFER Tooling API; ADAPT narrow static declarations with unresolved dynamic gaps | No Gradle invocation, Groovy evaluation or Kotlin compilation | Apache-2.0; control-plane execution boundary is decisive |
| REF-NPM — [lockfile model](https://docs.npmjs.com/cli/v11/configuring-npm/package-lock-json/) | Workspace links and dependency declarations differ from installed resolution | ADAPT declared dependency categories and exact workspace identity | No npm install, lifecycle execution or registry traversal | npm CLI Artistic-2.0; JSON parsing needs size/depth limits and no scripts execution |
| REF-OPENAPI — [Swagger Parser](https://github.com/swagger-api/swagger-parser) | OpenAPI parsing and optional reference resolution | EVALUATE; ADAPT schema/version checks and local references | No arbitrary remote references or path escape; no implicit network-enabled resolver | Apache-2.0; a resolver can cause SSRF, so repository content must never supply an outbound URL |
| REF-COMPOSE — [services specification](https://docs.docker.com/reference/compose-file/services/) | Declarative services and explicit depends_on | ADAPT exact dependencies and source paths | No Compose startup, environment interpolation or network-sharing inference | Compose specification Apache-2.0; YAML aliases/depth/size require bounds |
| REF-OTEL — [service identity](https://opentelemetry.io/docs/specs/semconv/resource/service/) | Service name/namespace/instance distinguish logical and runtime identity | ADAPT logical identity versus process instance distinction | DEFER runtime topology enrichment | Apache-2.0 spec; no source bodies or high-cardinality metric labels |
| REF-GH-TREES — [Git trees](https://docs.github.com/en/rest/git/trees) | Trees bind paths/modes/blob IDs to immutable Git objects; recursive responses may truncate | ADAPT exact commit-to-tree/blob retrieval using existing provider client and Contents READ | No clone, archives, mutable branch HEAD or provider-supplied URL fetch | GitHub documentation; enforce limits before decoding/staging, reject symlink modes and record truncated-tree gaps |

## Provisional acquisition decision

Use Git object endpoints via the existing GitHub client. Resolve a validated commit SHA to its tree, retrieve bounded entries, and fetch relevant blobs by object ID. Recheck tenant repository access before every request and before publication. An in-memory content-addressed staging map is sufficient for bounded V1 inputs and avoids writing attacker-selected filesystem paths. No archives are accepted in this design.

## Parser decision and bounded YAML parsing — 2026-09-16

The two-test standalone comparison passed against independent labels. Each parser matched all 180 repeated cases; malformed syntax was rejected. JavaParser core 3.28.2 is selected by ADR 035. It adds one 1,493,312-byte runtime jar with no runtime transitives. The benchmark-only solver and OpenRewrite stack stay outside the backend. See [actual comparison](../../evaluation/java-parser-comparison.json) for timings, resolution checks and measurement limitations.

SnakeYAML 2.6 is already in the Spring Boot dependency graph and is now declared explicitly for graph YAML. JDK/Jackson alone does not provide YAML parsing. Use its SafeConstructor, forbid duplicate keys and collection aliases, and cap nesting/code points and input bytes. No arbitrary object construction or URL resolution is enabled. The [upstream LoaderOptions source](https://github.com/snakeyaml/snakeyaml/blob/master/src/main/java/org/yaml/snakeyaml/LoaderOptions.java) documents these controls. License: Apache-2.0; no new runtime transitives. The adapter can be replaced independently of extractors.

Swagger Parser's full resolver is not adopted: Phase 3 extracts bounded declarative operations and local references, with no need for remote resolution. Local document parsing and explicit reference validation keep that network boundary inspectable.

## Measurements still required

Complete oracle quality, full-corpus reproducibility, PostgreSQL scale/security and final clean regression results remain unmeasured at this checkpoint. Foundation tests are not a substitute for those exit gates.
