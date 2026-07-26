# CQEngine JMH benchmarks

This non-published subproject measures distinct query, mutation, persistence and concurrency lifecycles. Its JMH
runner JAR is build tooling, not a CQEngine library artifact.

Run the short discovery and correctness gate on both supported JDKs:

```bash
./gradlew jmhSmoke
```

The authoritative dual-JDK baseline runs only inside the detached release qualification:

```bash
CQENGINE_JMH_MACHINE_LABEL=host-purpose-id scripts/qualify-candidate.sh
```

Do not invoke `jmhBaseline` directly: it depends on the hermetic release-invocation gate and rejects ordinary Gradle
sessions before any raw benchmark lane runs. `jmhJava21`, `jmhJava25` and the individual `jmhQuery*`, `jmhMutation*`,
`jmhPersistence*`, `jmhConcurrency*` and `jmhLatency*` tasks remain useful direct report-only suites, but none is
release evidence by itself. The wrapper-only aggregate validates exactly 104 results per JDK and 208 overall:

Before any measured fork, `jmhLaneSelectionPreflight` lists the generated runner and checks the exact method set
selected by all 16 JMH tasks. It rejects multiple include/exclude entries because JMH Gradle plugin 0.7.3 serializes
such lists as one comma-containing regex, as well as empty, overly broad, missing and newly uncontracted selections.
`jmhLaneSelectionRegression` exercises those fail-closed cases through the same selector logic on every preflight.
The wrapper runs this preflight once before the expensive qualification graph and `releaseCheck` reruns it after
`clean`; the retained inventory is `benchmarks/build/reports/jmh-selection/inventory.txt`.

| Lane | Results per JDK | Mode | Unit | GC allocation |
|---|---:|---|---|---|
| Query lifecycle | 6 | average time | ns/op | Required |
| Query scenarios | 44 | average time | ns/op | Required |
| Mutation | 4 | single shot | ms/op | Not claimed |
| Mutation allocation | 1 | average time | ns/op | Required |
| Persistence | 12 | average time | us/op | Required |
| Concurrency | 9 | throughput | ops/s | Separate write/busy counters |
| Sampled latency | 28 | sample time | us/op | Not claimed |

Every result must use two forks, the declared warmup/measurement contract and the configured Java launcher. The gate
rejects missing, duplicate or unexpected keys, wrong modes/units/JDKs/JVM options, negative or non-finite primary
scores, negative `gc.alloc.rate.norm`, non-monotonic p50/p90/p95/p99/p99.9 latency and invalid concurrency counters.
It establishes no latency, throughput or allocation threshold.

The Java 25 reports retain the original directory names except for the explicit latency report:

- query lifecycle and allocation: `benchmarks/build/reports/jmh/`;
- indexed/fallback query scenarios: `benchmarks/build/reports/jmh-query-scenarios/`;
- single-shot index/mutation latency: `benchmarks/build/reports/jmh-mutation/`;
- steady reversible update allocation: `benchmarks/build/reports/jmh-mutation-allocation/`;
- equivalent on-heap, off-heap and disk-WAL lifecycles: `benchmarks/build/reports/jmh-persistence/`;
- read-only, 3-reader/1-writer and 1-reader/3-writer throughput:
  `benchmarks/build/reports/jmh-concurrency/`; and
- sampled query and persistence latency: `benchmarks/build/reports/jmh-latency-java25/`.

The corresponding Java 21 directories end in `-java21`, for example
`benchmarks/build/reports/jmh-query-scenarios-java21/`. Each JSON result records the actual JDK. The smoke verifier
additionally requires Java 21 or Java 25 as declared, so Gradle toolchain fallback cannot silently make the comparison
invalid.

The smoke task writes `jmh-smoke-java21/` and `jmh-smoke-java25/`. Each matrix must contain exactly 73 declared
benchmark/parameter results, finite primary scores, no duplicates and no explicit JMH failure markers. Multi-writer
contention is report-only because its useful evidence is throughput plus the separate successful-write and
bounded-busy-failure rates, not one scheduler-dependent smoke invocation. The smoke gate still exercises a single
writer concurrently with readers and every persistence mode. None of these tasks defines a hard performance
regression threshold.

The full gate retains all 28 lane JSON/human files. `jmh-baseline/environment.properties` records source commit/tree
and GAV, the benchmark-runner JAR hash, explicit machine label, operating system, kernel, architecture,
virtualization, CPU,
memory, project and temporary filesystems, both Java launchers, Gradle wrapper and JMH versions, and the sanitized
JVM/build environment.
`summary.txt` records only validation/configuration counts, while `inventory.txt` hashes every raw report plus the
environment and summary with SHA-256 and SHA-512. The local-readiness manifest hashes all three baseline files,
including the inventory itself.

After validation, `generateJmhPublicationReport` converts the exact 208 results into a sanitized CSV, representative
Markdown tables and deterministic SVG charts under `benchmarks/build/reports/jmh-publication/`. The release evidence
manifest hashes that complete generated bundle. `./gradlew syncBenchmarkDocumentation` is the explicit post-review
step which copies a retained bundle into `benchmarks/results/`; qualification itself does not rewrite tracked source.

A machine label is identity, not approval. A matching record under `config/benchmark-hosts/` binds a reviewed host's
stable operating-system, virtualization, CPU and filesystem characteristics. A reviewed host may produce
machine-specific descriptive results, but those results are not a capacity or latency commitment and have no
numerical regression threshold.

The query lifecycle benchmarks keep these costs separate:

- constructing a compound query;
- creating a lazy `ResultSet`, reading its retrieval cost and deterministically closing it;
- reading the first result and closing;
- iterating every result and closing;
- calling `ResultSet.size()` and closing; and
- fully consuming an unindexed fallback query.

`QueryScenarioBenchmark` adds the selection and cardinality matrix below. Setup verifies every returned ID, rejects
duplicates, checks the declared cardinality class and proves that indexed cases did not fall back to a collection scan.

| Scenario | Accelerated query or path | Results at 256 | Results at 10,000 |
|---|---|---:|---:|
| `UNIQUE_ZERO` | unique `id` miss | 0 | 0 |
| `UNIQUE_ONE` | unique `id` hit | 1 | 1 |
| `HASH_LARGE` | hash equality on `manufacturer` | 64 | 2,500 |
| `NAVIGABLE_SMALL` | navigable `price` range | 8 | 16 |
| `COMPOUND_LARGE` | compound `manufacturer` and `model` equality | 64 | 2,500 |
| `STANDING_MEDIUM` | standing query over manufacturer and half the IDs | 32 | 1,250 |
| `RADIX_LARGE` | radix `startsWith` on `model` | 64 | 2,500 |
| `REVERSED_RADIX_LARGE` | reversed-radix `endsWith` on `model` | 64 | 2,500 |
| `INVERTED_RADIX_LARGE` | inverted-radix `longestPrefix` on `model` | 64 | 2,500 |
| `SUFFIX_LARGE` | suffix-tree `contains` on `model` | 64 | 2,500 |
| `FALLBACK_LARGE` | unindexed equality scan on `model` | 64 | 2,500 |

Every scenario separately measures result-set creation/read-cost/close, first-result/close, complete iteration/close
and `size()`/close. The sampled-time tasks record percentile distributions for first-result and complete-iteration
query lifecycles plus persistence point lookup and replacement. Concurrency remains a throughput report because the
successful-write and bounded-busy `@AuxCounters` are not reliably supported in JMH sample-time mode.

The single-shot mutation task prebuilds exactly enough independent collection samples for its configured warmup and
measurement invocations. It is a latency/index-build report, not allocation evidence: profiler and harness allocation
would dominate one operation per iteration. `MutationAllocationBenchmark` instead toggles one indexed replacement in
steady state for millions of measured operations and deliberately includes the two singleton input wrappers at the
public call boundary.

Persistence reads compare a primary-key point lookup, secondary-index iteration and `size()` across on-heap,
off-heap and disk-WAL storage. The reversible replacement keeps cardinality stable. Every measured `ResultSet` closes
in the measured operation, and fixture teardown checks final cardinality before closing native persistence and deleting
disk files.

Concurrency groups update disjoint keys. A concurrent read can legitimately observe the remove/add window of
`ConcurrentIndexedCollection.update()`, so correctness is checked after each trial instead of asserting atomic
cardinality inside the measured read. Only `SQLiteBusyException` is counted as a bounded contention outcome; every
other exception fails the benchmark. The GC profiler is intentionally absent from group runs because process-wide
allocation cannot be attributed defensibly to individual reader and writer methods.

JMH's GC profiler reports JVM heap allocation, not transient SQLite/native allocation or retained index footprint.
Results are meaningful only with the exact commit, JDK, JVM arguments, OS/filesystem, hardware, dataset, index
configuration and result cardinality. See the [benchmark guide](../documentation/Benchmark.md).

JVM allocation results do not replace retained/native-memory accounting, and benchmark timing does not replace
memory-model race testing. Do not present process-RSS snapshots or ordinary timing tests as either form of evidence.

## Java 25 harness boundary

The build pins JMH 1.37, whose `org.openjdk.jmh.util.Utils` still uses
`sun.misc.Unsafe.objectFieldOffset`. Measured Java 25 forks receive the explicit
`--sun-misc-unsafe-memory-access=allow` transition policy. JMH Gradle plugin 0.7.3 does not expose separate JVM
arguments for its launcher process, so that launcher still emits the JDK's terminal-deprecation warning before the
flagged forks begin. This known build-tool warning does not originate in CQEngine code and does not affect published
metadata, library artifacts or consumer JVMs. Remove the exception and warning when an approved JMH/plugin release no
longer uses that mechanism.

Java 21 and Java 25 persistence benchmark forks also receive `--enable-native-access=ALL-UNNAMED`, matching the
documented sqlite-jdbc runtime boundary. This is a native-access permission, not a module-opening workaround.
