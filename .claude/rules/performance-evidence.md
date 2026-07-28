---
description: JMH, allocation, latency and benchmark-claim policy for CQEngine.
alwaysApply: false
---

# Performance evidence

- CQEngine is not allocation-free. Retrieval, iteration and compound result composition may allocate.
- Keep query construction, result-set creation, first result, full iteration, `size`, mutation, persistence and concurrency workloads separate.
- Close every measured `ResultSet` in the measured lifecycle.
- Publish numbers only with exact commit, JDK/JVM flags, host identity, OS/virtualization, dataset, query, index, cardinality, units, forks, warmup, measurement and raw evidence.
- Machine approval is explicit and machine-specific. Results remain host-specific rather than universal production capacity claims.
- Every approved host characteristic is compared against a live observation. Never let a wrapper, environment variable or approval record supply the value that the same record is checked against.
- `cpuModel` is what the benchmark host reported. Where a hypervisor masks it, record the underlying processor as `declaredPhysicalCpuModel` with `declaredCpuModelEvidence=operator-declared`, keep it out of every comparison, and label it as unmeasured wherever it is published.
- A qualification that skips gates records which ones. Do not let a run that skipped gates present itself as equivalent to one that ran them all.
- Use repeated baselines to establish variance before introducing regression thresholds.
- Do not compare unlike consumption lifecycles or use single-shot profiler allocation as per-operation allocation evidence.
