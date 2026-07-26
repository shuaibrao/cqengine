# CQEngine concurrency qualification

This non-published Gradle project checks concurrency correctness. It is separate from JMH because throughput and
latency measurements cannot classify Java Memory Model outcomes or prove collection/index consistency.

## JCStress

The project uses the official [OpenJDK JCStress](https://openjdk.org/projects/code-tools/jcstress/) 0.16 harness. Its
three tests cover:

- publication of an added object to a concurrent reader and final hash-index consistency;
- distinct concurrent writers reaching the collection, hash index and navigable index; and
- add/remove races for the same object through `ObjectLockingIndexedCollection`.

Every documented valid outcome is `ACCEPTABLE`; an unlisted outcome is explicitly `FORBIDDEN`. The Gradle task then
requires exactly three accepted tests and zero interesting, failed or error classifications. A harness exit code of
zero without those exact summaries still fails the build.

`./gradlew :stress-tests:jcstressSmoke` uses Java 25 sanity mode for discovery and wiring. The authoritative
`:stress-tests:jcstress` task uses default mode, three forks and both Java 21 and Java 25. It is intentionally part of
the long candidate qualification, not the normal developer `check` task.

## Concurrent read/write soak

The soak starts from one immutable record per ID, gives each writer a disjoint ID partition, and exercises hash and
navigable indexes while readers issue ID and group queries. Active result sets are weakly consistent, so an active
read may encounter successive versions of one logical ID; every returned object must still satisfy its query and an
individual object must not be repeated.

After the stop signal, every worker must terminate within a bounded deadline. Worker exceptions stop all lanes and
are propagated. The final check requires exactly one expected record per ID and exact agreement between the backing
collection, every ID lookup and every group lookup. A successful report records the seed, requested and elapsed
duration, lane sizes, operation counts and a SHA-256 digest of final state.

Use the short smoke during development:

```bash
./gradlew :stress-tests:soakSmoke
./gradlew :stress-tests:soakSmoke -Pcqengine.soak.smokeMillis=5000
```

The configurable report-only lane accepts `cqengine.soak.durationMillis`, `cqengine.soak.seed`,
`cqengine.soak.writers`, `cqengine.soak.readers`, `cqengine.soak.keySpace` and `cqengine.soak.groups`:

```bash
./gradlew :stress-tests:soak \
  -Pcqengine.soak.durationMillis=120000 \
  -Pcqengine.soak.seed=7640891576956012809 \
  -Pcqengine.soak.writers=4 \
  -Pcqengine.soak.readers=8 \
  -Pcqengine.soak.keySpace=4096 \
  -Pcqengine.soak.groups=64
```

Candidate qualification does not use those overrides. It runs `soakQualification` with a fixed 900,000 ms duration
and retains its report beside the dual-JDK JCStress reports under `build/reports/concurrency/`.

JCStress and its JNA/JOpt Simple dependencies are build-only, locked and strictly verified. They are not published in
the CQEngine POM, thin JAR, `all` JAR or runtime SBOM.
