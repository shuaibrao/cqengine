---
name: opensrc
description: Fetch and inspect the exact source implementation of CQEngine dependencies when documentation is insufficient. Use for allocation behavior, serialization formats, parser internals, SQLite/JDBC behavior, Gradle plugin quirks, or any request to read how a dependency works internally.
---

# Dependency source inspection

Read the pinned version from `gradle/libs.versions.toml` first. Prefer an already resolved source attachment or the dependency's official tagged repository. `opensrc` supports GitHub repositories but not Maven coordinates:

```bash
opensrc path <owner>/<repository>@<tag>
# or without a global installation
npx -y opensrc path <owner>/<repository>@<tag>
```

Relevant upstream repositories include:

- `antlr/antlr4`
- `EsotericSoftware/kryo`
- `xerial/sqlite-jdbc`
- `jboss-javassist/javassist`
- `npgall/concurrent-trees`
- `openjdk/jmh`
- `raphw/byte-buddy`
- `gradle/gradle`

Tag schemes differ. Verify that the selected checkout identifies the exact pinned release; do not silently inspect `main`. Search locally with `rg`, record the file/method that supports the conclusion, and clearly distinguish source evidence from inference.

Use `docs-lookup` first for ordinary API usage. Use this skill when behavior, ownership, allocation, native interaction, or a vendor quirk requires implementation evidence.
