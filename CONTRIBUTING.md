# Contributing to CQEngine

CQEngine is a mature library with a widely used `com.googlecode.cqengine.*` API and persisted data formats.
Changes should be focused, preserve established behavior unless the change is intentional, and include evidence
appropriate to their compatibility, security and performance risk.

Please also read the [code of conduct](CODE_OF_CONDUCT.md); it applies in all project spaces.

## Licensing of contributions

CQEngine is Apache-2.0, and contributions are accepted under the project's
[Individual Contributor License Agreement](CLA.md), which keeps the project's copyright position
consolidated with the maintainer. Agreeing is part of the normal workflow: sign off every commit
with the Developer Certificate of Origin trailer —

```bash
git commit -s
```

— which adds `Signed-off-by: Your Name <your@email>`. Submitting a signed-off pull request
constitutes acceptance of the CLA. Unsigned commits cannot be merged.

## Pull requests

- Fork the repository and make the change on a topic branch; open the pull request against `main`.
- Keep commits focused; one logical change per PR where practical, and no unrelated formatting or
  dependency churn.
- Every commit carries a DCO `Signed-off-by` trailer (see above).
- Run the focused verification for every affected contract (table below) before requesting review;
  state in the PR description which gates were run.
- Explain what changed and why in the PR description; link the issue where one exists.
- Reviews may ask for compatibility, persistence-format or performance evidence — those requests
  follow directly from the change-discipline rules later in this page.

Bug reports and feature requests use the issue forms; suspected vulnerabilities go through the
[security policy](SECURITY.md), never a public issue.

## Development environment

Use the committed Gradle wrapper; do not substitute a system Gradle installation. The build compiles Java 21
bytecode and verifies behavior on Java 21 and Java 25, so both JDKs must be installed and discoverable by Gradle.
Python 3 is also required for the repository's agent-configuration checks.

Start with these focused checks:

```bash
./gradlew formatRatchetCheck
./gradlew test
```

Run the checks which match the code being changed:

| Change | Minimum focused verification |
|---|---|
| Core query or index behavior | Relevant test class, then `./gradlew testJava21 testJava25` |
| Parser or grammar | Parser tests on both JDKs and regenerated ANTLR sources through the normal Gradle tasks |
| Kryo or SQLite persistence | `./gradlew integrationTest` plus the relevant compatibility or failure-path tests |
| Public or protected API | `./gradlew apiCompatibility baseline` |
| Publication shape | `./gradlew verifyPublishedJars verifyPublication consumerTest` |
| Performance-sensitive code | `./gradlew jmhSmoke`; retain workload and environment evidence for any numerical claim |
| Concurrent collection or index behavior | `./gradlew concurrencySmoke`; reserve full JCStress and soak execution for candidate qualification |
| Static-analysis disposition | `./gradlew verifySpotBugsMain verifySpotBugsReview --rerun-tasks` |
| Qualification gates | `./gradlew verifyQualificationInvocation verifyQualificationSource`; the complete run is `./gradlew clean qualifyLocally` |
| GitHub Actions workflow | `actionlint` with `shellcheck` on `PATH`, at the versions `security.yml` pins; CI fails on findings that never appear in a Gradle build |

`./gradlew check` is the developer aggregate. The authoritative release qualification is intentionally separate and
long-running; see [RELEASING.md](RELEASING.md).

## Concurrency qualification

The non-published [`stress-tests`](stress-tests/README.md) project keeps concurrency correctness separate from JMH
performance measurements. `./gradlew concurrencySmoke` checks JCStress discovery and a short read/write soak. During
development, `./gradlew :stress-tests:soak` accepts the documented `cqengine.soak.*` Gradle properties for a longer
local investigation.

Only the qualification wrapper runs the authoritative contract: OpenJDK JCStress 0.16 in default mode with three
forks on both Java 21 and Java 25, followed by the fixed 15-minute deterministic-seed soak. Do not present a smoke or
duration-overridden development run as candidate evidence.

## Test framework migration

Tests run on JUnit Platform 6.0.3 and use Jupiter exclusively. JUnit 3/4, Vintage, legacy runners and rules,
JUnit DataProvider and Guava testlib are not part of the test runtime. The inherited generated collection suites are
represented by Jupiter-native contract matrices, and the functional lane still enforces the exact, gap-free set of
7,654 scenarios on Java 21 and Java 25. `checkNoLegacyJUnit` prevents the retired test stack from being reintroduced.

## Change discipline

- Keep the published library JAR canonical, with declared rather than embedded dependencies.
- Preserve the public API and serialized or persisted formats unless a reviewed compatibility change and migration
  path are part of the change.
- Close locally consumed `ResultSet`, iterator, JDBC and persistence resources deterministically. Use
  try-with-resources where ownership is local.
- Add a focused regression for a defect or failure path whenever practical.
- Do not mass-format inherited source or combine dependency, behavior and formatting changes without a clear reason.
- Treat benchmark results as measurements of a named workload and environment, not as universal performance claims.
- Preserve the Apache-2.0 licence, original attribution and required third-party notices. Do not apply blanket
  copyright-header rewrites.

## Dependency changes

CQEngine uses fixed versions, conflict rejection, dependency locking, SHA-256 checksums and PGP verification in
Gradle strict mode. A dependency update must review the upstream release and vulnerability evidence and explain every
new dependency, signing key, ignored signing key or checksum.

Regenerate verification metadata before touching locks. A new plugin version cannot be resolved until its own
artifacts verify, so `--write-locks` fails first with a verification error when the two are run the other way round:

```bash
./gradlew --write-verification-metadata sha256,pgp dependencies
```

Build-plugin changes must resolve the tasks and metadata variants which actually use the plugins:

```bash
./gradlew clean jar generatePomFileForMavenJavaPublication --write-verification-metadata sha256,pgp
./gradlew tasks cyclonedxDirectBom generateLicenseReport checkLicense --no-parallel --write-verification-metadata sha256,pgp
```

Resolve the isolated benchmark graph separately:

```bash
./gradlew :benchmarks:jmhJar --write-locks --write-verification-metadata sha256,pgp
```

Resolve the non-published concurrency-tool graph separately:

```bash
./gradlew :stress-tests:dependencies --write-locks --write-verification-metadata sha256,pgp
```

Regenerate the locks last, once every graph verifies:

```bash
./gradlew dependencies --write-locks
```

Add `--export-keys` only when the keyring genuinely needs new material, and revert both files if it fails. Gradle
aborts on an unsigned-subpacket key with a `PGPSignature.getHashedSubPackets()` null dereference, and it fails
part-written: one observed run truncated `verification-metadata.xml` and rewrote `verification-keyring.keys`. Omitting
the flag records the same checksums and signatures without touching the keyring.

Review the generated diff before trusting it:

- On Windows, Gradle rewrites these files with CRLF. Normalise them back to LF first, or a few genuine additions
  arrive buried in thousands of phantom line changes.
- Compare parsed entries rather than reading the raw diff. Confirm that no existing checksum changed, since a
  changed checksum for an unchanged coordinate means artifact substitution, not an update.
- Reject proposed trust for any key listed in `ignored-keys`. Key servers that were unreachable when the ignore was
  recorded may answer later, but a key cannot be both trusted and ignored; the ignore wins and the grant is inert.

Then rebuild from a fresh Gradle user home without either write flag. This step is not a formality. Write mode
records what the local cache already held, so a warm home hides artifacts a clean resolution needs — a missing Gradle
module descriptor has reached a reviewed diff this way and only surfaced under a clean-home strict rebuild. Write mode
records newly observed artifacts and is not verification evidence. Routine builds must never use lenient dependency
verification.

Gradle's IDE model downloads optional Maven `*-sources.jar` files and Gradle `gradle-*-src.zip` files for navigation.
Those two filename classes are permanently exempt from checksum and signature verification. They are not compile,
runtime, test, packaging or publication inputs. Do not narrow this policy by adding individual source-attachment
checksums, and never widen it to binary JARs, POMs, Gradle metadata, Javadocs or executable inputs.

See the [security policy](SECURITY.md) and [release procedure](RELEASING.md) for the complete trust and scanning
contracts.

## Agent configuration

`.agent/` is the canonical source for repository rules, skills and hooks. The rule and skill trees under `.cursor/`,
`.claude/` and `.codex/` are generated mirrors and must not be edited directly. After changing a canonical rule or
skill, run:

```bash
python3 scripts/sync-agent-config.py
python3 scripts/test-agent-harness.py
python3 scripts/sync-agent-config.py --check
```

The generated files belong in the same change as their canonical source. `./gradlew checkAgentConfigSync` applies the
same drift check through Gradle.

## Before submitting a change

- Review the diff for unrelated files, generated output and accidental licence changes.
- Run `git diff --check` and the focused verification for every affected contract.
- Update user documentation and release notes when behavior, compatibility, security posture, artifacts or supported
  environments change.
- Record a durable rationale in code or documentation; do not expose temporary implementation tracking as product
  documentation.
