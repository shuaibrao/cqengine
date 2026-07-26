# CQEngine benchmarks

CQEngine uses JMH for current performance evidence. The suite separates query construction, result-set creation,
result consumption, mutation, persistence, concurrency, sampled latency and allocation so that unlike operations are
not collapsed into one headline number.

## Current benchmark evidence

The authoritative release qualification runs the complete benchmark inventory on Java 21 and Java 25 and retains all
208 validated rows in CSV. Public tables and charts present the Java 25 measurements from that same run rather than
turning runtime-version differences into a performance claim.

The next reviewed result package will be linked here after qualification of the current CQEngine 4.0 source. A result
is publishable only when it identifies the measured commit, tree, benchmark JAR, JDKs and approved host record.

## Workloads

| Lane | What it measures |
|---|---|
| Query lifecycle | Query construction and result-set create/read/iterate/size/close operations |
| Indexed query scenarios | Unique, hash, navigable, compound, standing, radix, reversed-radix, inverted-radix and suffix indexes, plus unindexed fallback |
| Sampled latency | p50 and p99 observations for representative query and persistence operations |
| Mutation | Isolated collection/index replacement and update costs |
| Allocation | JMH normalized bytes allocated per representative operation |
| Persistence | On-heap, off-heap and disk lifecycle operations |
| Concurrency | Read/write throughput with writer progress and SQLite busy-failure accounting |

The query-scenario dataset contains 10,000 records. Scenario cardinality deliberately ranges from zero and one match
to large result sets. Full-iteration timings therefore compare equivalent runs of one scenario; they should not be
used to rank indexes whose queries return different numbers of objects.

## Running benchmarks

Candidate evidence is produced only through the authoritative wrapper:

```bash
CQENGINE_JMH_MACHINE_LABEL=<approved-host-label> scripts/qualify-candidate.sh
```

The wrapper runs from committed source in a detached checkout and fresh Gradle home. It validates benchmark discovery,
forks, warmup and measurement settings, result counts, JDK launchers, profiler metrics, concurrency progress and strong
hashes before generating sanitized CSV, Markdown and SVG output.

For development-only feedback, use:

```bash
./gradlew jmhSmoke
```

After reviewing a successful qualification, copy its generated report into the tracked results tree with:

```bash
./gradlew syncBenchmarkDocumentation
```

## Interpreting results

- `ns/op`, `us/op` and `ms/op` are latency measures; lower is better for the same workload.
- `ops/s` is throughput; higher is better for the same workload and thread configuration.
- p50 and p99 describe the observed sample distribution from this run, not service-level guarantees.
- `B/op` is normalized JVM allocation. CQEngine is not allocation-free.
- Results are machine-specific and do not establish capacity, universal latency or numerical regression thresholds.
- Host load, firmware, kernel, JVM, dataset, index selection, result cardinality and consumption depth can materially
  change measurements.

The approved development host is described publicly by its operating system, processor, logical-processor count and
filesystem. Lower-level kernel and virtualization fields remain in the evidence so another run can determine whether
the environments are genuinely comparable.

## Historical material

The original CQEngine benchmark spreadsheets and charts remain under `documentation/documents/benchmark-history/`
and `documentation/images/benchmark-history/` as project history. They used a custom timing harness, Java 6, a
different machine and different workloads, so they are not presented as current CQEngine 4.0 performance evidence.
