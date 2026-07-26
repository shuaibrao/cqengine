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
- Use repeated baselines to establish variance before introducing regression thresholds.
- Do not compare unlike consumption lifecycles or use single-shot profiler allocation as per-operation allocation evidence.
