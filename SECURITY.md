# Security policy

CQEngine treats parser resource use, persistence deserialization, SQLite construction and dependency provenance as
explicit trust boundaries. Security controls reduce risk within those boundaries; they are not a claim that arbitrary
hostile input is safe in the same process.

## Reporting a vulnerability

Private vulnerability-reporting instructions have not yet been published. Before the first public release, enable
GitHub private vulnerability reporting for this repository and publish response expectations. While this placeholder
remains, do not include exploit details, secrets or sensitive deployment information in a public issue; contact the
project privately through GitHub instead.

Include the affected version, configuration, reproduction, impact and any known mitigation in a private report. Do
not test against systems or data you do not own or have explicit permission to assess.

## Supported versions

The latest final release published from this repository is the maintained security line. Release candidates are for
evaluation and qualification, and upstream CQEngine artifacts or artifacts published by other forks are outside this
repository's support boundary. If more than one final line is maintained, its release notes must state the additional
support window explicitly.

The library compiles to Java 21 bytecode and is verified on Java 21 and Java 25. A vulnerability which depends on an
unsupported runtime or on an application bypassing a documented trust boundary may still be assessed, but it is not
automatically a defect in the supported configuration.

## String-query boundary

The SQL and CQN parsers enforce finite query-length, token and grammar-aware nesting limits before constructing a
query. Applications should normally choose limits smaller than the defaults for their observed workload and retain
transport-level request limits.

CQN's compatibility regex policy uses `java.util.regex` and is intended only for trusted patterns and candidate
values. Java regular expressions have no execution deadline and can exhibit excessive backtracking. Disable regex
queries with `RegexPolicy.DISABLED` at an untrusted boundary, or provide an independently reviewed bounded policy.
Parser limits do not impose a deadline on custom value parsers, custom regex policies or query execution.

See [SQL and CQN string queries](documentation/StringQueries.md).

## Kryo persistence boundary

Kryo persistence has two explicit modes:

- `TRUSTED_STORE_COMPATIBILITY` reads historical raw Kryo data and permits class-name resolution for compatibility.
  The database, backups and restore path must therefore be protected from untrusted modification.
- `REGISTERED_TYPES` requires a reviewed concrete-type allowlist and a versioned envelope. The envelope detects
  format/configuration mismatch and truncated or trailing bytes; it is not a signature, MAC or authenticity proof.

Both modes enforce finite blob, graph-depth and string limits. Registered mode also bounds arrays, default common
collections and the JDK immutable `List.of`/`Set.of`/`Map.of` families before backing-container allocation. CQEngine's
JDK wrapper serializers apply the same element limit in both modes. These controls are not a hostile-deserialization
sandbox: other compatibility-mode containers and application or third-party serializers may allocate from their own
encoded lengths. Adversary-influenced storage additionally needs authenticated bytes, reviewed serializers and a
process-level memory/time boundary.

There is no permissive fallback from registered data to historical raw bytes. Migrate by reading a protected old
store in compatibility mode, validating the objects, and rewriting into registered mode while retaining an approved
rollback snapshot or reverse-migration procedure.

See [persistence](documentation/Persistence.md).

## SQLite boundary

SQL values use bind parameters. CQEngine-generated table and index components are validated against a finite ASCII
identifier contract and quoted before use. Current table identities use a domain-separated SHA-256 digest of the
logical attribute and suffix, including unsanitized partial-filter descriptions. Legacy sanitized tables are renamed
transactionally and bound to one V2 identity in migration metadata; ambiguous or inconsistent dual schemas fail
closed. Back up legacy databases and review possible historical sanitizer collisions before the first upgraded open.

Disk and off-heap requests commit only after successful object-store and index work and otherwise roll back.
`CompositePersistence` can coordinate more than one SQLite database but does not implement a distributed transaction
or recovery log; applications requiring atomic durability must keep participating indexes in one persistence
transaction domain.

The SQLite JDBC dependency includes native libraries. Java 25 consumers must grant the documented native access for
their classpath or module-path artifact form; no `--add-opens` workaround is part of the supported contract.

See [persistence](documentation/Persistence.md) and [Java compatibility](documentation/JavaCompatibility.md).

## Dependency and release integrity

Executable dependencies, build plugins, POMs and Gradle metadata use fixed versions and strict SHA-256/PGP
verification. Dependency graphs are locked and version conflicts fail resolution. IDE-only `*-sources.jar` and
`gradle-*-src.zip` attachments are permanently trusted by filename for source navigation; they are not compiled,
executed, packaged or published. The exception must never be widened to binary or metadata inputs.

OpenJDK JCStress and its JNA/JOpt Simple tool dependencies are build-only inputs in the non-published stress project.
They are locked and verified like other executable tooling but are absent from CQEngine's published POM, JARs and
runtime SBOM.

The release security gate produces a runtime CycloneDX SBOM and licence inventory and scans the runtime graph and
shaded artifact with authenticated NVD data and OSV. Missing credentials, feed or analysis errors, inventory drift,
an unapproved licence, a High/Critical NVD finding or any OSV finding fails the gate. A clean result from one feed does
not override a finding from another. Security scans are time-sensitive and must be regenerated after any dependency
or shaded-content change.

The NVD corpus is reused for up to 24 hours rather than re-downloaded per run, because a complete rebuild takes
hours and no disposable runner can finish one. A scan therefore reports advisories as of that corpus, so a
same-day release re-run does not pick up advisories published in the interim. Force a refresh before a release
decision by discarding the cached data directory, and treat the scheduled scan as the mechanism that keeps the
corpus current between releases.

A vulnerability suppression must identify one exact advisory and component, explain why it does not apply, and have
an owner and expiry date. Broad group suppressions and treating a feed outage as a clean scan are not acceptable.

See [contributing](CONTRIBUTING.md), [releasing](RELEASING.md) and
[static analysis](documentation/StaticAnalysis.md).

## Security-sensitive contribution requirements

Changes to parser grammars or limits, regex policy, Kryo configuration or serializers, SQLite naming or transaction
handling, native loading, dependency verification, scanners, artifact assembly or publication must include focused
negative tests and updated trust-boundary documentation. Preserve failure causes and deterministic resource cleanup;
do not replace a fail-closed control with a warning or silent fallback.
