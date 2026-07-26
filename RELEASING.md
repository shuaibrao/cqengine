# Releasing CQEngine

CQEngine releases are produced from committed source by one authoritative qualification wrapper. The wrapper creates
the Maven-format repository, release artifacts and independently retained evidence; running individual Gradle tasks
does not create an equivalent release result.

## Prerequisites

Before qualifying a release:

- select the version in tracked root `gradle.properties`, which is the sole permitted version source;
- update `documentation/ReleaseNotes.md` and all user-facing compatibility, migration and security documentation;
- use a clean, non-shallow Git checkout with no replacement objects or legacy grafts;
- install Java 21 and Java 25 toolchains and set an absolute `JAVA_HOME`;
- export `NVD_API_KEY` in the process environment, never as a Gradle property or command-line argument;
- export `CQENGINE_JMH_MACHINE_LABEL` using 3–64 letters, digits, dots, underscores or hyphens, starting with a
  letter or digit; and
- provide network access for Maven Central, the Gradle Plugin Portal and the live vulnerability feeds.

The benchmark label identifies a host; it does not by itself approve that host or make its measurements publishable.
Numerical results may be published only when the performance evidence policy accepts the complete host and run
metadata.

## Authoritative command

Run this command from the repository checkout:

```bash
scripts/qualify-candidate.sh
```

Do not invoke `releaseCheck` directly. The wrapper first invalidates retained output, verifies its tools and the Git
source identity, materializes the exact committed tree into a detached checkout, performs a no-execution benchmark
selection and stress-test dependency preflight, and then runs the complete release graph from a second detached
checkout and empty Gradle user home. It disables build and configuration caches, parallel execution, ambient Gradle
options and dependency-verification write mode for qualification.

The wrapper is intentionally long-running. Use focused tasks during development and reserve this command for a
reviewed release commit.

## Qualification contract

A successful run proves these outcomes for the exact source commit and version:

- Java 21 bytecode compiles cleanly and the Java 21/25 unit, functional and persistence matrices pass;
- JDK-usage, source/binary API and exported-package compatibility gates pass;
- SpotBugs and FindSecBugs analyze every production class, with no unreviewed high-confidence finding and an exact
  match to the reviewed full inventory;
- NVD and OSV vulnerability scans, CycloneDX SBOM generation and runtime licence policy complete against their exact
  expected inventories;
- thin, `all`, sources and Javadocs JARs satisfy their manifest, content, legal, relocation,
  native-library and class-inventory contracts;
- Gradle-metadata, POM-only, classpath and module-path consumers resolve and execute the staged artifacts on both
  supported JDKs;
- two builds from different absolute paths and isolated Gradle homes produce byte-identical publication artifacts,
  attachments, metadata and deterministic evidence; and
- OpenJDK JCStress 0.16 reports no interesting, forbidden or error outcomes for the indexed-collection invariants on
  Java 21 and Java 25, and the fixed 15-minute concurrent read/write soak terminates cleanly with exact post-quiescence
  collection, hash-index and navigable-index agreement; and
- JMH discovery, smoke and full Java 21/25 evidence complete with a labelled environment and strong-hash inventory.

The full JMH gate also generates sanitized CSV, Markdown tables and SVG charts under
`benchmarks/build/reports/jmh-publication/`. These views are derived from the same validated 208-result inventory and
are included in the readiness manifest.

Live vulnerability reports and qualification timestamps are deliberately time-sensitive. Release artifacts,
publication metadata and normalized release evidence are reproducible; a stale scan is never substituted for a new
one.

## Outputs

The verified local Maven repository is written to:

```text
build/local-repository/
```

It contains only conventional Maven repository content: the thin, `all`, sources and Javadocs JARs, POM, Gradle
module metadata, Maven metadata and checksum sidecars.

Publishable evidence is kept outside the Maven coordinate at:

```text
build/local-release-evidence/publishable/
```

It contains artifact-bound CycloneDX JSON/XML pairs, the runtime licence inventory, legal material, source/build
provenance and SHA-256/SHA-512 manifests. Time-sensitive qualification evidence and the complete retained log are
under:

```text
build/local-release-evidence/qualification/
build/reports/
benchmarks/build/reports/
```

The root concurrency reports retained by the readiness manifest are under `build/reports/concurrency/`. They contain
the complete JCStress console classifications for both runtimes and the soak configuration, deterministic seed,
operation counts and final-state digest.

The authoritative result is
`build/local-release-evidence/qualification/local-readiness-manifest.txt`. It binds the coordinate, source commit and
tree, toolchains, platform, command and every required report by SHA-256 and SHA-512. A missing, stale or mismatched
manifest is a failed qualification even if an individual task is green.

## Release review

After the wrapper succeeds:

1. Read the retained qualification log and readiness manifest; confirm the coordinate, commit and tree are the ones
   selected for release.
2. Review vulnerability, licence, static-analysis, compatibility and benchmark summaries rather than relying only on
   the aggregate exit code.
3. Verify the exact repository and evidence inventories and retain them together.
4. Publish only the byte-identical contents of the verified local repository, accompanied by the corresponding
   publishable evidence.
5. Tag and announce the same source identity, including compatibility or migration notes and any performance limits.

Re-run qualification after any source, dependency, version, shaded-content, legal or build change. Never combine
outputs from different qualification runs.

After reviewing a successful run, copy its generated benchmark views into the tracked results tree with:

```bash
./gradlew syncBenchmarkDocumentation
```

This explicit step keeps qualification read-only with respect to committed source. Review and commit the generated
result directory separately; its metadata binds the measured commit and executable hashes, so a documentation-only
commit does not misrepresent the measured library bytes.

## Publishing through GitHub (release-bundle workflow)

Signing and the Central Portal hand-off run on a GitHub-hosted runner via `.github/workflows/release-bundle.yml`.
The workflow never re-runs the multi-hour qualification; it proves the runner's rebuild reproduces the qualified
bytes by hashing the rebuilt publication inventory against the committed readiness manifest, and refuses to sign
on any mismatch.

1. Qualify the release commit locally (`scripts/qualify-candidate.sh`, above). The commit qualified is the commit
   whose bytes are published.
2. Commit the qualification evidence so the workflow can verify it:

   ```bash
   mkdir -p release-evidence/<version>
   cp build/local-release-evidence/qualification/wrapper-completion.properties \
      build/local-release-evidence/qualification/local-readiness-manifest.txt \
      release-evidence/<version>/
   git add release-evidence/<version> && git commit
   ```

   Evidence-only commits after the qualified commit are safe: the workflow checks out and rebuilds the qualified
   `sourceCommit` itself (which must be an ancestor of the tag), so the published bytes are still the qualified ones.
3. Confirm `io.github.shuaibrao:cqengine:<version>` is absent from Maven Central, then create and push the signed
   release tag — this is the release decision:

   ```bash
   git tag -s v<version> -m 'CQEngine <version>'
   git push origin v<version>
   ```

4. Dispatch the workflow from the tag and approve the `maven-central` environment run when prompted:

   ```bash
   gh workflow run release-bundle.yml --ref v<version> -f version=<version> -f centralPublishing=user-managed
   ```

   `centralPublishing=skip` produces the signed bundle artifact only; `user-managed` uploads and waits for
   `VALIDATED`, leaving the final publish to the Portal UI; `automatic` publishes as soon as validation passes and
   cannot be undone. Central releases are immutable — a bad release is superseded by a new version, never replaced.
5. After Central lists the artifacts, verify the public bytes against the retained bundle artifact, create the
   GitHub release from the tag attaching the evidence artifacts, and bump `gradle.properties` to the next
   `-SNAPSHOT`.
