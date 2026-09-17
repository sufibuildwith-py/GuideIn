# Java parser comparison

Run from the repository root:

```text
mvn -f evaluation/tools/java-parser-benchmark/pom.xml test
```

Use a repository-local Maven cache if the host default cache is unavailable.

The standalone project compares nine independently labeled Java fixture files over twenty rounds and checks local-source versus missing-classpath resolution. It does not execute fixture code or builds. The benchmark-only symbol solver and OpenRewrite dependencies are not backend dependencies. Backend extraction uses only JavaParser core.

Results are written by the test to `target/results.json` and `target/resolution.json`. The recorded run is [java-parser-comparison.json](../../java-parser-comparison.json). Shared-JVM heap observations are approximate; they are not isolated process memory measurements. Invalid syntax must remain invalid for both parsers.
