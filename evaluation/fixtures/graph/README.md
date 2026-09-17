# Graph oracle corpus

The source fixtures and initial dependency labels preceded extractor implementation. On 2026-09-17, their structural/file/package/declaration expectations were completed by source review, without copying extractor output. Each fixture has explicit expected node keys, all permitted trusted relationships, and required/allowed gap categories. No unspecified emitted edge is exempt from the false-positive denominator.

Edges are classified as REQUIRED_TRUSTED, OPTIONAL, or MUST_NOT_EXIST. Optional lists are currently empty. False-inference traps cover shared Compose networks and ports, duplicate Java names, README claims, common npm namespaces, dynamic Gradle expressions, unsafe paths, unresolved symbols and remote OpenAPI URLs.

Fixture 17 expands the independently specified 2,000-file family `src/File0000.txt` through `src/File1999.txt` with inert `data` contents; its complete node and edge list is checked in. Fixture 18 interprets hostile path strings only as virtual source-map keys and adds `safe.txt`; those strings are never filesystem destinations. Local generated `.gradle` or other excluded build/cache directories are not fixture truth and are never executed.

The evaluator reports both the aggregate (including structural/file-tree edges) and separate source counts. The 50,000-node synthetic database scale graph is a separate performance proof and must never inflate these accuracy scores. JFR process-start recording surrounds all 20 extractor evaluations. The graph engine has no model-provider dependency.
