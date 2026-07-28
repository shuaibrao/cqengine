---
description: Dependency locking, checksum/signature verification, source-attachment exception, scanner and update policy.
alwaysApply: false
---

# Dependency integrity

- Pin versions in `gradle/libs.versions.toml`; reject dynamic, range, changing and snapshot dependencies in released graphs.
- Keep every resolvable graph locked and every executable artifact, plugin, POM and module metadata SHA-256 verified.
- Review new signing keys, ignored keys and checksums against an authoritative repository before committing them.
- The permanent trusted-artifact rules may match only Maven `*-sources.jar` and Gradle `gradle-*-src.zip` navigation attachments. Do not extend them to binaries, Javadocs, POMs, Gradle metadata or arbitrary ZIPs.
- Dependency-verification write mode records observations; it is never passing evidence. Rebuild in strict mode from a fresh home afterward.
- Run authenticated NVD plus OSV, SBOM and licence checks for runtime dependency changes.
- Update locks, verification metadata, vulnerability evidence and the relevant sections of `CONTRIBUTING.md`,
  `SECURITY.md`, `RELEASING.md` and `documentation/ReleaseNotes.md` together.
