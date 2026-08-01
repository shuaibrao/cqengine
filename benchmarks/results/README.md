# CQEngine benchmark results

Each directory under this tree contains deterministic tables, full-precision CSV data and SVG charts derived from
one complete, validated JMH qualification run. Directories are named `<commit>-<host label>` inside a
`<version>-development` line, and their contents are bound to the measured source commit, benchmark JAR, JDKs and
host characteristics. Results are machine-specific evidence, not universal latency, capacity or allocation
guarantees.

## Available baselines

| Baseline | Measured commit | Host | Start here |
|---|---|---|---|
| [`4.0.0-development/d9447adb-win11-i7-10750h-12c-01`](4.0.0-development/d9447adb-win11-i7-10750h-12c-01/README.md) | `d9447adb` | Windows 11, 12 logical processors (QEMU guest) | [Representative results](4.0.0-development/d9447adb-win11-i7-10750h-12c-01/representative-results.md) |

No benchmark values are ever copied forward between baselines; each directory is regenerated in full from its own
qualification run.

## The benchmark data

Every lane measures the same deterministic synthetic record, `BenchmarkRecord(id, manufacturer, model, price)`,
generated as:

- `id` runs from 0 to N−1;
- `manufacturer`/`model` cycle over four fixed pairs — Ford Focus, Honda Civic, Toyota Prius, BMW M6 — selected by
  `id & 3`, so each value matches exactly a quarter of the dataset; and
- `price` cycles through `2500 + id % 7500`.

Dataset sizes are 10,000 records for the query, persistence and sampled-latency lanes and 1,000 records for
steady-state mutation allocation. Because generation is deterministic, every query's match count is exact, and each
benchmark's setup recomputes the expected matching IDs from the dataset and verifies the retrieved result set
against them before any measurement starts.

## What is measured

Seven lanes keep distinct workloads separate, so a query number is never blended with a mutation or persistence
number:

- **Query lifecycle** — one compound query (`HashIndex` on manufacturer plus `NavigableIndex` on price, matching
  751 records) measured at each stage of the `ResultSet` lifecycle: construct the query object, create/close,
  first-result/close, full-iteration/close and `size()`/close, plus an unindexed full-scan control.
- **Indexed query scenarios** — eleven query shapes, one per index family: unique, hash and compound equality,
  navigable range, a precomputed standing query, radix prefix, reversed-radix suffix, inverted-radix longest
  prefix, suffix-tree contains, and an unindexed fallback scan as the control. Setup additionally proves each
  indexed case used its declared index and only the control used the fallback scan.
- **Mutation** — single-shot index-build and update latency on prebuilt independent collections.
- **Mutation allocation** — one steady-state `update(...)` replacing a single indexed record, measured for both
  time and heap allocation across millions of operations.
- **Persistence** — the identical primary-key point lookup, secondary-index iteration, `size()` and record
  replacement against three storage engines: on-heap, off-heap SQLite (`OffHeapPersistence`) and an on-disk SQLite
  file in WAL mode (`DiskPersistence`).
- **Concurrency** — read-only, 3-reader/1-writer and 1-reader/3-writer throughput with post-trial consistency
  checks; separate JCStress and soak qualification cover memory-model invariants.
- **Sampled latency** — percentile distributions (p50 through p100) for representative query and persistence
  operations from JMH's sample-time mode.

## How it is measured

- JMH drives a headless benchmark-runner JAR on both supported runtimes — Java 21 and Java 25 launchers resolved
  through Gradle toolchains — producing 104 validated results per JDK, 208 in total, from 14 raw JSON inputs.
- Each lane runs two independent JVM forks with declared warmup iterations followed by timed measurement
  iterations; every `results.csv` row records its exact fork, warmup and measurement settings alongside the score,
  error and confidence bounds.
- Average-time lanes report ns/op or µs/op; the sampled lane reports percentiles; the JMH GC profiler supplies
  `gc.alloc.rate.norm` — B/op, heap bytes allocated per operation — wherever allocation is claimed.
- Every measured operation closes its `ResultSet` inside the measured lifecycle, so the numbers include the
  deterministic-close cost the library requires of real callers.
- The validation gate rejects wrong modes, units, JDKs or JVM options, missing or duplicate results, non-finite
  scores, negative allocation and non-monotonic percentiles before anything is published.

## How a run becomes committed evidence

`./gradlew qualifyLocally` runs the full baseline as part of release qualification. The machine label must name a
reviewed record under `config/benchmark-hosts/`, and every characteristic in that record — operating system,
kernel, architecture, virtualization, CPU model, logical processors, filesystems — is compared against a live
observation of the machine. The generator then converts the exact validated results into the sanitized bundle each
baseline directory contains: `results.csv`, `representative-results.md` (selected Java 25 views with how-to-read
notes), four SVG charts, capture metadata, raw-input hashes and a `SHA256SUMS` manifest.

`./gradlew syncBenchmarkDocumentation` copies a reviewed bundle into this tree as an explicit, separate step;
nothing here is edited by hand.

See [benchmarks/README.md](../README.md) for the complete per-lane validation contract and
[documentation/Benchmark.md](../../documentation/Benchmark.md) for the methodology, machine boundary and
interpretation rules.
