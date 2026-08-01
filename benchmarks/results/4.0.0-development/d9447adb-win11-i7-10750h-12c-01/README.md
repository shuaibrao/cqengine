# CQEngine development benchmark evidence

This directory is a deterministic, sanitized report derived from one completed full JMH qualification. It is
an approved machine-specific development baseline. It has no performance thresholds.

- Measured commit: `d9447adb9bc407b5e1169e4887e0cdb641830d8f`
- Measured tree: `8a56daa37b14d826c74e4d48929ddfbec3566596`
- Report publication coordinate: `io.github.shuaibrao:cqengine:4.0.0`
- Capture status: `verified-machine-baseline` (`machineApproval=approved`)
- Benchmark host: `win11-i7-10750h-12c-01`
- Host characteristics: Windows 11 10.0, QEMU Virtual CPU version 2.5+ (measured), 12 logical processors, NTFS storage; underlying physical CPU Intel(R) Core(TM) i7-10750H CPU @ 2.60GHz (operator-declared, not measured)
- Approval validation: the approval captured with the run was revalidated against `config/benchmark-hosts/win11-i7-10750h-12c-01.properties` (`sha256:ce403bcf9818de14e123d32ac837f41ee8973df364c8d35c6f8671a52345cb8a`).
- Qualification evidence: Java 21 and Java 25, 104 unique results each
- Published tables and charts: Java 25
- Thresholds: none

The host approval checks the captured operating system, kernel, architecture, CPU, processor count and
filesystems against a reviewed record. `cpuModel` is always the model the benchmark host reported; where a
record also carries `declaredPhysicalCpuModel`, that value identifies the hypervisor's underlying processor
and is an operator declaration that no part of this build measured or verified.
The report preserves the measured source commit, source tree, input
hashes and every value from both supported runtimes. Its public views use Java 25 so the charts describe
the current runtime without turning runtime-version differences into a performance claim.

Results are suitable for machine-specific inspection, but they do not establish cross-host guarantees,
regression thresholds or universal CQEngine performance claims.

## Contents

- `results.csv` contains all 208 unique JMH result rows from exactly 14 full-run JSON inputs.
- `representative-results.md` presents selected query, latency and allocation views.
- `query-scenarios.svg` is the current indexed-query overview suitable for the project README.
- `query-lifecycle.svg`, `sampled-latency.svg` and `allocation.svg` provide focused generated charts.
- `environment.properties` contains allowlisted, sanitized capture metadata.
- `source-inputs.sha256` identifies the 14 local JSON inputs without exposing absolute paths.
- `SHA256SUMS` authenticates every generated file in this directory except itself.

The raw JMH JSON and human-readable reports remain in the local build output and are not copied here,
because they contain absolute executable and temporary-directory paths. Regenerate this report from
those inputs with:

```shell
python3 scripts/generate-benchmark-report.py \
  --input benchmarks/build/reports \
  --output <destination> \
  --approval-record <repository-relative-host-properties> \
  --display-host <approved-machine-label>
```

Generation rejects missing or partial lanes, unexpected run settings, duplicate identities, non-finite
metrics, incorrect capture metadata, and any output containing known local or internal path forms.
