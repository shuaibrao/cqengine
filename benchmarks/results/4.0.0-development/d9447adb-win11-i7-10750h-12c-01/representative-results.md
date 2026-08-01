# Representative benchmark results

These Java 25 values are selected views of the complete 208-row `results.csv`, which retains
the validated Java 21 and Java 25 qualification evidence. The public tables and charts focus
on the current Java 25 runtime rather than presenting a cross-JDK comparison.

Measured host: Windows 11 10.0, QEMU Virtual CPU version 2.5+ (measured), 12 logical processors, NTFS storage; underlying physical CPU Intel(R) Core(TM) i7-10750H CPU @ 2.60GHz (operator-declared, not measured). Measured commit: `d9447adb9bc407b5e1169e4887e0cdb641830d8f`.
They are not performance thresholds, cross-machine guarantees or universal CQEngine claims.

## Indexed query scenarios

Dataset size: 10,000. Each value is the average time to run the query, iterate every matching
record and close the result set, so a row's time includes delivering all of its matches. Match
counts are exact for the deterministic dataset and verified by the benchmark setup. Lower is
better at the same match count.

| Scenario | Index | Query | Matches | Java 25 ns/op |
| --- | --- | --- | --- | --- |
| Unique lookup: no match | `UniqueIndex` on id | `equal(ID, <absent id>)` | 0 | 219.6 |
| Unique lookup: one match | `UniqueIndex` on id | `equal(ID, ...)` | 1 | 225.3 |
| Hash equality | `HashIndex` on manufacturer | `equal(MANUFACTURER, "Ford")` | 2,500 | 20,250 |
| Navigable range | `NavigableIndex` on price | `between(PRICE, 2500, 2507)` | 16 | 1,197 |
| Compound lookup | `CompoundIndex` on manufacturer, model | `and(equal("Toyota"), equal("Prius"))` | 2,500 | 20,160 |
| Standing query | `StandingQueryIndex` | precomputed Toyota with id below the midpoint | 1,250 | 8,964 |
| Radix prefix | `RadixTreeIndex` on model | `startsWith(MODEL, "Fo")` | 2,500 | 22,910 |
| Reversed radix suffix | `ReversedRadixTreeIndex` on model | `endsWith(MODEL, "vic")` | 2,500 | 24,850 |
| Inverted radix prefix | `InvertedRadixTreeIndex` on model | `longestPrefix(MODEL, "Prius-Prime")` | 2,500 | 19,870 |
| Suffix contains | `SuffixTreeIndex` on model | `contains(MODEL, "6")` | 2,500 | 23,610 |
| Unindexed fallback | none — full collection scan | `equal(MODEL, "M6")` | 2,500 | 141,900 |

![Indexed query scenarios on Java 25](query-scenarios.svg)

How to read these numbers:

- The unique lookups return at most one record straight from the index; their
  225.3 ns is this run's floor for answering a query, not a typical cost.
- The 2,500-match rows are not slow indexes: delivering 2,500 of the 10,000 records dominates
  the time, which is why the hash, compound and string indexes land in one band. Hash equality
  spends about 8.1 ns per record delivered.
- The standing query answers a compound query from a result set maintained at insert time, so
  retrieval does not evaluate the query's branches at all.
- The unindexed fallback is the control: the same equality shape without an index scans the
  whole collection and takes about 7× as
  long as the indexed hash equality at the same match count. That multiple is what the index
  contributes to this workload.
- These comparisons hold within this run on this host; they are not cross-machine ratios.

## Query lifecycle

Dataset size: 10,000. Every row runs the same compound query — Ford with price 4,000–6,000,
answered by a `HashIndex` and a `NavigableIndex` and matching 751 records — except the
unindexed row, which filters on the model attribute without an index and matches 2,500.
Average-time score is in ns/op; normalized allocation is in B/op, heap bytes allocated per
operation from the JMH GC profiler. Lower is better.

| Operation | Work measured | Results consumed | Java 25 ns/op | Java 25 B/op |
| --- | --- | --- | --- | --- |
| Construct compound query | build the `and(...)` query object; nothing is retrieved | — | 64.44 | 416 |
| Create/read/close | `retrieve(...)`, read the retrieval cost, close | 0 of 751 | 68,900 | 98,700 |
| First result/close | retrieve, take the first record, close | 1 of 751 | 85,930 | 98,990 |
| Full iteration/close | retrieve, iterate every record, close | 751 | 127,300 | 98,870 |
| Size/close | retrieve, count via `size()`, close | 751 counted | 126,000 | 98,910 |
| Unindexed iteration/close | full-scan filter with no usable index, iterate, close | 2,500 | 139,400 | 1,305 |

![Query lifecycle on Java 25](query-lifecycle.svg)

How to read these numbers:

- Query objects are cheap: 416 B and well under a microsecond
  to build, so constructing a query per call is not a meaningful cost.
- Allocation is nearly flat from create-only (98,700 B) to full
  iteration (98,870 B): composing the indexed result set at
  `retrieve(...)` accounts for almost all of it, and consuming the results adds almost
  nothing. B/op here is per result-set lifecycle, not per record delivered.
- Time, not allocation, scales with consumption: full iteration adds about
  78 ns per record on top of creating and
  closing the result set.
- `size()` costs the same as full iteration on this query shape because counting a compound
  result set walks its matches.
- The unindexed row allocates only 1,305 B — a filtering scan
  composes no index result sets — but pays the full-collection scan time no matter how
  many records match.

## Sampled latency

Dataset size: 10,000. Values are microseconds per operation observed by the sample-time
lane; p50 and p99 describe this run's sample distribution, not service guarantees. The
three persistence rows are the identical single-record primary-key lookup against three
storage engines. Lower is better.

| Operation | Storage / index | Results | Java 25 p50 µs | Java 25 p99 µs |
| --- | --- | --- | --- | --- |
| Persistence lookup, on heap | on-heap collection, `UniqueIndex` on id | 1 | 0.2 | 0.5 |
| Persistence lookup, off heap | `OffHeapPersistence` (off-heap SQLite), `OffHeapIndex` | 1 | 61.06 | 121.8 |
| Persistence lookup, disk WAL | `DiskPersistence` (SQLite file, WAL), `DiskIndex` | 1 | 3,178 | 6,255 |
| First result, hash large | `HashIndex` on manufacturer | 1 of 2,500 | 0.5 | 0.9 |
| Full iteration, hash large | `HashIndex` on manufacturer | 2,500 | 18.69 | 38.27 |
| Full iteration, fallback large | none — full collection scan | 2,500 | 129.3 | 243.7 |

![Representative sampled latency](sampled-latency.svg)

How to read these numbers:

- The storage medium dominates the persistence rows: the same lookup costs
  0.2 µs on heap, 61.06 µs in off-heap
  SQLite and 3,178 µs (about 3.2 ms)
  in the disk WAL store at p50. Persistence is chosen for durability and dataset size
  beyond heap capacity, not for lookup speed.
- Results stream lazily: on the same 2,500-match hash query the first record arrives in
  0.5 µs at p50, about 37×
  sooner than iterating all matches (18.69 µs, about
  7.5 ns per record).
- Without an index, the same full iteration takes about
  6.9× longer at p50.

## Mutation allocation

`replaceWithSingletonInputs`, dataset size 1,000: each operation is one `update(...)` call
that atomically replaces one record with another in a `HashIndex`-indexed collection; the
benchmark verifies afterwards that the collection and index are consistent. Average-time
score is in ns/op; allocation is in B/op.

| Runtime | Score ns/op | Normalized allocation B/op |
| --- | --- | --- |
| Java 25 | 510.3 | 804 |

![Normalized allocation](allocation.svg)

A steady-state replace through the collection and its index costs 510.3 ns
and 804 B of allocation per operation on this host.

The complete CSV retains score error, confidence bounds, percentiles and available GC profiler metrics for every row.
