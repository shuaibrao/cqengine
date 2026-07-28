---
name: docs-lookup
description: Look up current primary documentation for CQEngine's Gradle, Java, parser, persistence, serialization, benchmark, testing, static-analysis, and packaging dependencies. Use before changing or describing a third-party API, plugin, configuration, or recommended practice.
---

# Dependency documentation

Use `npx -y ctx7` to locate and read current documentation. Prefer the dependency project's official documentation and release material. If documentation does not answer an implementation question, use the `opensrc` skill to inspect the exact pinned source.

## Workflow

1. Read the version from `gradle/libs.versions.toml` or `gradle-wrapper.properties`.
2. Locate the library when its Context7 ID is unknown:

   ```bash
   npx -y ctx7 library <library> "<topic>"
   ```

3. Query the selected primary documentation:

   ```bash
   npx -y ctx7 docs <library-id> "<specific question>"
   ```

4. Cross-check version-sensitive behavior against the pinned release notes or source.
5. Cite the authoritative page when the answer or change depends on it.

## Project dependency areas

- Gradle dependency verification, locking, publishing and Kotlin DSL
- ANTLR parser generation/runtime
- Kryo serialization and Objenesis
- sqlite-jdbc and bundled SQLite
- Javassist supported class definition
- Concurrent Trees indexes
- JMH and its Gradle plugin
- JUnit, Mockito, EqualsVerifier and JaCoCo
- Bnd, SpotBugs/FindSecBugs, CycloneDX and Dependency-Check

Do not assume that the newest documentation describes the pinned version. Do not invent a Context7 ID; search when uncertain.
