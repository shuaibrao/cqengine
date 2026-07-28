#!/usr/bin/env python3
"""Generate a deterministic, sanitized report from the full dual-JDK JMH run."""

from __future__ import annotations

import argparse
import csv
import hashlib
import html
import io
import json
import math
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence


EXPECTED_TOTAL_RESULTS = 208
EXPECTED_RESULTS_PER_JDK = 104
HEX_40 = re.compile(r"[0-9a-f]{40}")
HEX_64 = re.compile(r"[0-9a-f]{64}")
MACHINE_LABEL = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{2,63}")
BENCHMARK_NAMESPACE_MARKER = ".cqengine.benchmark."
BENCHMARK_NAMESPACE = "io.github.shuaibrao.cqengine.benchmark."
JAVA_PACKAGE = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*(?:[.][A-Za-z_$][A-Za-z0-9_$]*)*")
BENCHMARK_SUFFIX = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*[.][A-Za-z_$][A-Za-z0-9_$]*")


@dataclass(frozen=True)
class InputSpec:
    lane: str
    jdk_major: int
    relative_path: str
    result_count: int
    mode: str
    score_unit: str
    warmup_iterations: int
    measurement_iterations: int


@dataclass(frozen=True)
class CaptureMetadata:
    environment: Mapping[str, str]
    summary: Mapping[str, str]
    source_commit: str
    source_tree: str
    coordinate: str
    machine_label: str
    machine_approval: str
    status: str
    evidence_use: str
    numeric_readme_claims: str
    approved_numerical_baseline: bool


@dataclass(frozen=True)
class ApprovalDecision:
    approved: bool
    basis: str
    display_host: str
    record_identity: str
    record_sha256: str
    declared_physical_cpu_model: str = "none"
    declared_cpu_model_evidence: str = "none"


INPUT_SPECS = (
    InputSpec("query", 21, "jmh-query-java21/results.json", 6, "avgt", "ns/op", 3, 5),
    InputSpec("query", 25, "jmh/results.json", 6, "avgt", "ns/op", 3, 5),
    InputSpec("scenarios", 21, "jmh-query-scenarios-java21/results.json", 44, "avgt", "ns/op", 3, 5),
    InputSpec("scenarios", 25, "jmh-query-scenarios/results.json", 44, "avgt", "ns/op", 3, 5),
    InputSpec("latency", 21, "jmh-latency-java21/results.json", 28, "sample", "us/op", 3, 5),
    InputSpec("latency", 25, "jmh-latency-java25/results.json", 28, "sample", "us/op", 3, 5),
    InputSpec("mutation", 21, "jmh-mutation-java21/results.json", 4, "ss", "ms/op", 1, 5),
    InputSpec("mutation", 25, "jmh-mutation/results.json", 4, "ss", "ms/op", 1, 5),
    InputSpec(
        "allocation",
        21,
        "jmh-mutation-allocation-java21/results.json",
        1,
        "avgt",
        "ns/op",
        3,
        5,
    ),
    InputSpec(
        "allocation",
        25,
        "jmh-mutation-allocation/results.json",
        1,
        "avgt",
        "ns/op",
        3,
        5,
    ),
    InputSpec("persistence", 21, "jmh-persistence-java21/results.json", 12, "avgt", "us/op", 3, 5),
    InputSpec("persistence", 25, "jmh-persistence/results.json", 12, "avgt", "us/op", 3, 5),
    InputSpec("concurrency", 21, "jmh-concurrency-java21/results.json", 9, "thrpt", "ops/s", 3, 5),
    InputSpec("concurrency", 25, "jmh-concurrency/results.json", 9, "thrpt", "ops/s", 3, 5),
)

LANE_ORDER = {
    "query": 0,
    "scenarios": 1,
    "latency": 2,
    "mutation": 3,
    "allocation": 4,
    "persistence": 5,
    "concurrency": 6,
}

QUERY_SCENARIOS = (
    ("Unique lookup: no match", "UNIQUE_ZERO"),
    ("Unique lookup: one match", "UNIQUE_ONE"),
    ("Hash equality", "HASH_LARGE"),
    ("Navigable range", "NAVIGABLE_SMALL"),
    ("Compound lookup", "COMPOUND_LARGE"),
    ("Standing query", "STANDING_MEDIUM"),
    ("Radix prefix", "RADIX_LARGE"),
    ("Reversed radix suffix", "REVERSED_RADIX_LARGE"),
    ("Inverted radix prefix", "INVERTED_RADIX_LARGE"),
    ("Suffix contains", "SUFFIX_LARGE"),
    ("Unindexed fallback", "FALLBACK_LARGE"),
)

MANAGED_OUTPUTS = (
    "README.md",
    "allocation.svg",
    "environment.properties",
    "query-lifecycle.svg",
    "query-scenarios.svg",
    "representative-results.md",
    "results.csv",
    "sampled-latency.svg",
    "source-inputs.sha256",
)


class ReportError(RuntimeError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate deterministic evidence from the 14-input CQEngine dual-JDK JMH baseline."
    )
    parser.add_argument(
        "--input",
        required=True,
        type=Path,
        help="JMH report root containing jmh-baseline/ and the 14 full results.json inputs",
    )
    parser.add_argument("--output", required=True, type=Path, help="Destination evidence directory")
    parser.add_argument(
        "--approval-record",
        type=Path,
        help="Repository-local benchmark host properties used for characteristic-match approval",
    )
    parser.add_argument(
        "--display-host",
        help="Display label; defaults to the captured label and must equal an approval record label",
    )
    parser.add_argument(
        "--validate-only",
        action="store_true",
        help="Validate that the existing output is byte-identical to a fresh generation",
    )
    return parser.parse_args()


def read_properties(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise ReportError(f"missing metadata file: {path}")
    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue
        if "=" not in line:
            raise ReportError(f"invalid property at {path}:{line_number}")
        key, value = line.split("=", 1)
        if key in values:
            raise ReportError(f"duplicate property {key!r} in {path}")
        values[key] = value
    return values


def require_property(properties: Mapping[str, str], key: str, expected: str | None = None) -> str:
    try:
        value = properties[key]
    except KeyError as exc:
        raise ReportError(f"missing required property: {key}") from exc
    if expected is not None and value != expected:
        raise ReportError(f"property {key!r} is {value!r}, expected {expected!r}")
    return value


def finite_number(value: Any, description: str) -> float:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ReportError(f"{description} is not numeric")
    result = float(value)
    if not math.isfinite(result):
        raise ReportError(f"{description} is not finite")
    return result


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def metric_score(metrics: Mapping[str, Any], name: str) -> float | None:
    metric = metrics.get(name)
    if metric is None:
        return None
    if not isinstance(metric, dict) or "score" not in metric:
        raise ReportError(f"malformed secondary metric {name!r}")
    return finite_number(metric["score"], f"secondary metric {name} score")


def percentile(metric: Mapping[str, Any], key: str) -> float | None:
    percentiles = metric.get("scorePercentiles")
    if percentiles is None:
        return None
    if not isinstance(percentiles, dict) or key not in percentiles:
        raise ReportError(f"primary metric is missing percentile {key}")
    return finite_number(percentiles[key], f"primary percentile {key}")


def normalized_benchmark_name(benchmark: Any, description: str) -> str:
    if not isinstance(benchmark, str) or benchmark.count(BENCHMARK_NAMESPACE_MARKER) != 1:
        raise ReportError(f"{description}: unexpected benchmark name {benchmark!r}")
    package_prefix, suffix = benchmark.split(BENCHMARK_NAMESPACE_MARKER, 1)
    if JAVA_PACKAGE.fullmatch(package_prefix) is None or BENCHMARK_SUFFIX.fullmatch(suffix) is None:
        raise ReportError(f"{description}: malformed benchmark name {benchmark!r}")
    return BENCHMARK_NAMESPACE + suffix


def validate_result(result: Mapping[str, Any], spec: InputSpec, index: int) -> None:
    prefix = f"{spec.relative_path}[{index}]"
    normalized_benchmark_name(result.get("benchmark"), prefix)
    if str(result.get("jdkVersion", "")).split(".", 1)[0] != str(spec.jdk_major):
        raise ReportError(f"{prefix}: expected JDK {spec.jdk_major}, found {result.get('jdkVersion')!r}")
    expected_fields = {
        "jmhVersion": "1.37",
        "mode": spec.mode,
        "forks": 2,
        "warmupIterations": spec.warmup_iterations,
        "warmupTime": "1 s",
        "measurementIterations": spec.measurement_iterations,
        "measurementTime": "1 s",
    }
    for field, expected in expected_fields.items():
        if result.get(field) != expected:
            raise ReportError(f"{prefix}: {field} is {result.get(field)!r}, expected {expected!r}")
    metric = result.get("primaryMetric")
    if not isinstance(metric, dict):
        raise ReportError(f"{prefix}: missing primary metric")
    if metric.get("scoreUnit") != spec.score_unit:
        raise ReportError(
            f"{prefix}: score unit is {metric.get('scoreUnit')!r}, expected {spec.score_unit!r}"
        )
    finite_number(metric.get("score"), f"{prefix} primary score")
    finite_number(metric.get("scoreError"), f"{prefix} primary score error")
    confidence = metric.get("scoreConfidence")
    if not isinstance(confidence, list) or len(confidence) != 2:
        raise ReportError(f"{prefix}: malformed score confidence interval")
    finite_number(confidence[0], f"{prefix} confidence lower bound")
    finite_number(confidence[1], f"{prefix} confidence upper bound")
    for key in ("50.0", "90.0", "95.0", "99.0", "99.9", "100.0"):
        percentile(metric, key)
    raw_data = metric.get("rawData")
    if raw_data is None:
        if spec.mode != "sample":
            raise ReportError(f"{prefix}: primary metric is missing raw data")
    elif not isinstance(raw_data, list) or len(raw_data) != 2:
        raise ReportError(f"{prefix}: expected raw data for two forks")
    params = result.get("params")
    if not isinstance(params, dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in params.items()):
        raise ReportError(f"{prefix}: parameters must be a string map")
    secondary_metrics = result.get("secondaryMetrics")
    if not isinstance(secondary_metrics, dict):
        raise ReportError(f"{prefix}: secondary metrics must be an object")
    for name, secondary_metric in secondary_metrics.items():
        if not isinstance(name, str) or not isinstance(secondary_metric, dict):
            raise ReportError(f"{prefix}: malformed secondary metric")
        finite_number(secondary_metric.get("score"), f"{prefix} secondary metric {name}")


def normalize_result(result: Mapping[str, Any], spec: InputSpec) -> dict[str, Any]:
    benchmark = normalized_benchmark_name(result["benchmark"], spec.relative_path)
    benchmark_class, method = benchmark.rsplit(".", 1)
    metric = result["primaryMetric"]
    confidence = metric["scoreConfidence"]
    secondary_metrics = result["secondaryMetrics"]
    params = dict(sorted(result["params"].items()))
    return {
        "jdk_major": spec.jdk_major,
        "jdk_version": str(result["jdkVersion"]),
        "lane": spec.lane,
        "input_file": spec.relative_path,
        "benchmark": benchmark,
        "benchmark_class": benchmark_class,
        "method": method,
        "mode": str(result["mode"]),
        "threads": int(result["threads"]),
        "forks": int(result["forks"]),
        "warmup_iterations": int(result["warmupIterations"]),
        "warmup_time": str(result["warmupTime"]),
        "measurement_iterations": int(result["measurementIterations"]),
        "measurement_time": str(result["measurementTime"]),
        "dataset_size": params.get("datasetSize", ""),
        "parameters": json.dumps(params, sort_keys=True, separators=(",", ":")),
        "score": finite_number(metric["score"], "primary score"),
        "score_error": finite_number(metric["scoreError"], "primary score error"),
        "confidence_low": finite_number(confidence[0], "confidence lower bound"),
        "confidence_high": finite_number(confidence[1], "confidence upper bound"),
        "score_unit": str(metric["scoreUnit"]),
        "p50": percentile(metric, "50.0"),
        "p90": percentile(metric, "90.0"),
        "p95": percentile(metric, "95.0"),
        "p99": percentile(metric, "99.0"),
        "p999": percentile(metric, "99.9"),
        "p100": percentile(metric, "100.0"),
        "allocation_bytes_per_op": metric_score(secondary_metrics, "gc.alloc.rate.norm"),
        "allocation_mb_per_second": metric_score(secondary_metrics, "gc.alloc.rate"),
        "gc_count": metric_score(secondary_metrics, "gc.count"),
        "gc_time_milliseconds": metric_score(secondary_metrics, "gc.time"),
    }


def load_results(input_root: Path) -> tuple[list[dict[str, Any]], dict[str, str]]:
    if len(INPUT_SPECS) != 14:
        raise ReportError("internal error: the full baseline contract must contain exactly 14 inputs")
    rows: list[dict[str, Any]] = []
    input_hashes: dict[str, str] = {}
    for spec in INPUT_SPECS:
        path = input_root / spec.relative_path
        if not path.is_file():
            raise ReportError(f"missing full-baseline input: {spec.relative_path}")
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise ReportError(f"cannot parse {spec.relative_path}: {exc}") from exc
        if not isinstance(data, list) or len(data) != spec.result_count:
            actual_count = len(data) if isinstance(data, list) else "not an array"
            raise ReportError(
                f"{spec.relative_path}: expected {spec.result_count} results, found {actual_count}"
            )
        for index, result in enumerate(data):
            if not isinstance(result, dict):
                raise ReportError(f"{spec.relative_path}[{index}]: result must be an object")
            validate_result(result, spec, index)
            rows.append(normalize_result(result, spec))
        input_hashes[spec.relative_path] = sha256_file(path)

    if len(rows) != EXPECTED_TOTAL_RESULTS:
        raise ReportError(f"expected {EXPECTED_TOTAL_RESULTS} results, found {len(rows)}")
    counts = {jdk: sum(row["jdk_major"] == jdk for row in rows) for jdk in (21, 25)}
    if counts != {21: EXPECTED_RESULTS_PER_JDK, 25: EXPECTED_RESULTS_PER_JDK}:
        raise ReportError(f"unexpected per-JDK result counts: {counts}")
    unique_keys = {
        (row["jdk_major"], row["lane"], row["benchmark"], row["parameters"], row["mode"])
        for row in rows
    }
    if len(unique_keys) != EXPECTED_TOTAL_RESULTS:
        raise ReportError(
            f"expected {EXPECTED_TOTAL_RESULTS} unique benchmark identities, found {len(unique_keys)}"
        )
    rows.sort(
        key=lambda row: (
            row["jdk_major"],
            LANE_ORDER[row["lane"]],
            row["benchmark"],
            row["parameters"],
        )
    )
    return rows, input_hashes


def parse_boolean(value: str, description: str) -> bool:
    if value == "true":
        return True
    if value == "false":
        return False
    raise ReportError(f"{description} must be 'true' or 'false', found {value!r}")


def validate_capture_metadata(input_root: Path) -> CaptureMetadata:
    environment = read_properties(input_root / "jmh-baseline/environment.properties")
    summary = read_properties(input_root / "jmh-baseline/summary.txt")
    source_commit = require_property(environment, "sourceCommit")
    source_tree = require_property(environment, "sourceTree")
    if HEX_40.fullmatch(source_commit) is None:
        raise ReportError(f"sourceCommit must be a lowercase 40-hex identifier, found {source_commit!r}")
    if HEX_40.fullmatch(source_tree) is None:
        raise ReportError(f"sourceTree must be a lowercase 40-hex identifier, found {source_tree!r}")
    coordinate = require_property(environment, "coordinate")
    if not coordinate or any(character.isspace() for character in coordinate):
        raise ReportError(f"coordinate is invalid: {coordinate!r}")
    machine_approval = require_property(environment, "machineApproval")
    evidence_use = require_property(environment, "evidenceUse")
    numeric_readme_claims = require_property(environment, "numericReadmeClaims")
    status = require_property(summary, "status")
    approved_numerical_baseline = parse_boolean(
        require_property(summary, "approvedNumericalBaseline"), "approvedNumericalBaseline"
    )
    require_property(summary, "performanceThresholds", "none")
    require_property(summary, "java21Results", str(EXPECTED_RESULTS_PER_JDK))
    require_property(summary, "java25Results", str(EXPECTED_RESULTS_PER_JDK))
    require_property(summary, "totalResults", str(EXPECTED_TOTAL_RESULTS))
    environment_label = require_property(environment, "machineLabel")
    summary_label = require_property(summary, "machineLabel")
    if environment_label != summary_label:
        raise ReportError("environment and summary machine labels do not match")
    if MACHINE_LABEL.fullmatch(environment_label) is None:
        raise ReportError(f"captured machine label is invalid: {environment_label!r}")

    if machine_approval == "unapproved":
        expected = {
            "status": "verified-report-only",
            "evidenceUse": "report-only",
            "numericReadmeClaims": "forbidden",
        }
        actual = {
            "status": status,
            "evidenceUse": evidence_use,
            "numericReadmeClaims": numeric_readme_claims,
        }
        if actual != expected or approved_numerical_baseline:
            raise ReportError(f"unapproved capture metadata is inconsistent: {actual}")
    elif machine_approval == "approved":
        expected = {
            "status": "verified-machine-baseline",
            "evidenceUse": "machine-specific-development-baseline",
            "numericReadmeClaims": "machine-specific-only",
        }
        actual = {
            "status": status,
            "evidenceUse": evidence_use,
            "numericReadmeClaims": numeric_readme_claims,
        }
        if actual != expected or not approved_numerical_baseline:
            raise ReportError(f"approved capture metadata is inconsistent: {actual}")
        record = require_property(environment, "machineApprovalRecord")
        record_hash = require_property(environment, "machineApprovalRecordSha256")
        if Path(record).is_absolute() or ".." in Path(record).parts:
            raise ReportError("captured machine approval record must be a repository-relative identity")
        if HEX_64.fullmatch(record_hash) is None:
            raise ReportError("captured machine approval record hash must be lowercase SHA-256")
    else:
        raise ReportError(f"unsupported captured machine approval: {machine_approval!r}")

    for key, captured_value in (
        ("evidenceUse", evidence_use),
        ("numericReadmeClaims", numeric_readme_claims),
    ):
        if key in summary and summary[key] != captured_value:
            raise ReportError(f"environment and summary values differ for {key}")

    return CaptureMetadata(
        environment=environment,
        summary=summary,
        source_commit=source_commit,
        source_tree=source_tree,
        coordinate=coordinate,
        machine_label=environment_label,
        machine_approval=machine_approval,
        status=status,
        evidence_use=evidence_use,
        numeric_readme_claims=numeric_readme_claims,
        approved_numerical_baseline=approved_numerical_baseline,
    )


def repository_root() -> Path:
    root = Path(__file__).resolve().parent.parent
    if not (root / "settings.gradle.kts").is_file() or not (root / "build.gradle.kts").is_file():
        raise ReportError("generator is not located in a CQEngine repository root")
    return root


def publication_coordinate() -> str:
    properties = read_properties(repository_root() / "gradle.properties")
    group = require_property(properties, "group", "io.github.shuaibrao")
    version = require_property(properties, "version")
    if re.fullmatch(r"[0-9]+[.][0-9]+[.][0-9]+(?:-(?:SNAPSHOT|rc[.][1-9][0-9]*))?", version) is None:
        raise ReportError(f"publication version is invalid: {version!r}")
    return f"{group}:cqengine:{version}"


def validate_approval_record(
    approval_path: Path,
    capture: CaptureMetadata,
    display_host: str,
) -> tuple[str, str, str, str]:
    root = repository_root()
    try:
        resolved = approval_path.resolve(strict=True)
        identity = resolved.relative_to(root).as_posix()
    except (OSError, ValueError) as exc:
        raise ReportError("approval record must be an existing file within this repository") from exc
    if not resolved.is_file():
        raise ReportError("approval record must be a regular file")

    properties = read_properties(resolved)
    required_keys = {
        "formatVersion",
        "machineLabel",
        "operatingSystemRegex",
        "kernelRegex",
        "architecture",
        "virtualization",
        "wslVersion",
        "cpuModel",
        "cpuLogicalProcessors",
        "projectFileStoreType",
        "temporaryFileStoreType",
        "evidenceUse",
        "numericReadmeClaims",
    }
    declaration_keys = {"declaredPhysicalCpuModel", "declaredCpuModelEvidence"}
    present_keys = set(properties)
    if present_keys - declaration_keys != required_keys:
        raise ReportError(
            "approval record keys differ; "
            f"missing={sorted(required_keys - present_keys)}, "
            f"unexpected={sorted(present_keys - required_keys - declaration_keys)}"
        )
    if len(present_keys & declaration_keys) not in (0, len(declaration_keys)):
        raise ReportError("approval record declares a physical CPU model without its evidence basis")
    require_property(properties, "formatVersion", "1")
    label = require_property(properties, "machineLabel")
    if MACHINE_LABEL.fullmatch(label) is None:
        raise ReportError(f"approval record machine label is invalid: {label!r}")
    expected_identity = f"config/benchmark-hosts/{label}.properties"
    if identity != expected_identity:
        raise ReportError(
            f"approval record identity is {identity!r}, expected {expected_identity!r} from its label"
        )
    if display_host != label:
        raise ReportError(
            f"display host {display_host!r} must match approval record machine label {label!r}"
        )

    environment = capture.environment
    exact_fields = {
        "architecture": require_property(environment, "architecture"),
        "virtualization": require_property(environment, "virtualization"),
        "wslVersion": require_property(environment, "wslVersion"),
        "cpuModel": require_property(environment, "cpuModel"),
        "cpuLogicalProcessors": require_property(environment, "cpuLogicalProcessors"),
        "projectFileStoreType": file_store_type(require_property(environment, "projectFileStore")),
        "temporaryFileStoreType": file_store_type(require_property(environment, "temporaryFileStore")),
    }
    mismatches = [
        f"{key}: expected {properties[key]!r}, captured {actual!r}"
        for key, actual in exact_fields.items()
        if properties[key] != actual
    ]
    for regex_key, environment_key in (
        ("operatingSystemRegex", "operatingSystem"),
        ("kernelRegex", "kernel"),
    ):
        expression = properties[regex_key]
        try:
            pattern = re.compile(expression)
        except re.error as exc:
            raise ReportError(f"approval record contains invalid {regex_key}: {exc}") from exc
        captured_value = require_property(environment, environment_key)
        if pattern.fullmatch(captured_value) is None:
            mismatches.append(
                f"{regex_key}: expression {expression!r} does not match captured {captured_value!r}"
            )
    if mismatches:
        raise ReportError("approval record does not match captured host: " + "; ".join(mismatches))
    require_property(properties, "evidenceUse", "machine-specific-development-baseline")
    require_property(properties, "numericReadmeClaims", "machine-specific-only")
    # A declared physical model is an operator claim about the hypervisor host, never a captured measurement,
    # so it stays out of the exact-match comparison above and is only accepted where the guest reports a hypervisor.
    declared_model = "none"
    declared_evidence = "none"
    if present_keys >= declaration_keys:
        declared_model = require_property(properties, "declaredPhysicalCpuModel")
        declared_evidence = require_property(
            properties, "declaredCpuModelEvidence", "operator-declared"
        )
        if exact_fields["virtualization"] == "not-detected":
            raise ReportError(
                "approval record declares a physical CPU model for a capture reporting no virtualization"
            )
    captured_model = environment.get("declaredPhysicalCpuModel", "none")
    captured_evidence = environment.get("declaredCpuModelEvidence", "none")
    if captured_model != declared_model or captured_evidence != declared_evidence:
        raise ReportError(
            "approval record physical CPU declaration does not match the captured declaration: "
            f"record {declared_model!r}/{declared_evidence!r}, "
            f"captured {captured_model!r}/{captured_evidence!r}"
        )
    return identity, sha256_file(resolved), declared_model, declared_evidence


def resolve_approval(
    capture: CaptureMetadata,
    approval_record: Path | None,
    requested_display_host: str | None,
) -> ApprovalDecision:
    display_host = requested_display_host or capture.machine_label
    if MACHINE_LABEL.fullmatch(display_host) is None:
        raise ReportError(f"display host is invalid: {display_host!r}")

    effective_record = approval_record
    if effective_record is None and capture.machine_approval == "approved":
        effective_record = repository_root() / require_property(
            capture.environment, "machineApprovalRecord"
        )
    if effective_record is None:
        return ApprovalDecision(
            approved=False,
            basis="unapproved-captured-evidence",
            display_host=display_host,
            record_identity="none",
            record_sha256="none",
        )

    identity, record_hash, declared_model, declared_evidence = validate_approval_record(
        effective_record, capture, display_host
    )
    if capture.machine_approval == "approved":
        captured_identity = require_property(capture.environment, "machineApprovalRecord")
        captured_hash = require_property(capture.environment, "machineApprovalRecordSha256")
        if captured_identity != identity or captured_hash != record_hash:
            raise ReportError("captured approval identity or hash does not match the current approval record")
        basis = "captured-approval-revalidated"
    else:
        basis = "subsequent-characteristic-match"
    return ApprovalDecision(
        approved=True,
        basis=basis,
        display_host=display_host,
        record_identity=identity,
        record_sha256=record_hash,
        declared_physical_cpu_model=declared_model,
        declared_cpu_model_evidence=declared_evidence,
    )


def canonical_number(value: Any) -> str:
    if value is None or value == "":
        return ""
    return format(finite_number(value, "output value"), ".17g")


def display_number(value: float, significant_digits: int = 4) -> str:
    return format(value, f".{significant_digits}g")


def generate_csv(rows: Sequence[Mapping[str, Any]]) -> bytes:
    fields = (
        "jdk_major",
        "jdk_version",
        "lane",
        "input_file",
        "benchmark",
        "benchmark_class",
        "method",
        "mode",
        "threads",
        "forks",
        "warmup_iterations",
        "warmup_time",
        "measurement_iterations",
        "measurement_time",
        "dataset_size",
        "parameters",
        "score",
        "score_error",
        "confidence_low",
        "confidence_high",
        "score_unit",
        "p50",
        "p90",
        "p95",
        "p99",
        "p999",
        "p100",
        "allocation_bytes_per_op",
        "allocation_mb_per_second",
        "gc_count",
        "gc_time_milliseconds",
    )
    numeric_fields = {
        "score",
        "score_error",
        "confidence_low",
        "confidence_high",
        "p50",
        "p90",
        "p95",
        "p99",
        "p999",
        "p100",
        "allocation_bytes_per_op",
        "allocation_mb_per_second",
        "gc_count",
        "gc_time_milliseconds",
    }
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
    writer.writeheader()
    for row in rows:
        output_row = {field: row[field] for field in fields}
        for field in numeric_fields:
            output_row[field] = canonical_number(output_row[field])
        writer.writerow(output_row)
    return stream.getvalue().encode("utf-8")


def find_row(
    rows: Sequence[Mapping[str, Any]],
    jdk: int,
    lane: str,
    benchmark_suffix: str,
    parameters: Mapping[str, str],
) -> Mapping[str, Any]:
    parameters_json = json.dumps(dict(sorted(parameters.items())), sort_keys=True, separators=(",", ":"))
    matches = [
        row
        for row in rows
        if row["jdk_major"] == jdk
        and row["lane"] == lane
        and str(row["benchmark"]).endswith(benchmark_suffix)
        and row["parameters"] == parameters_json
    ]
    if len(matches) != 1:
        raise ReportError(
            f"expected one representative result for JDK {jdk}, {lane}, {benchmark_suffix}, "
            f"{parameters_json}; found {len(matches)}"
        )
    return matches[0]


def escape(value: Any) -> str:
    return html.escape(str(value), quote=True)


def grouped_log_chart(
    title: str,
    description: str,
    categories: Sequence[tuple[str, Mapping[str, float]]],
    series: Sequence[tuple[str, str]],
    unit: str,
    minimum_power: int,
    maximum_power: int,
) -> bytes:
    width = 1160
    plot_left = 300
    plot_right = 955
    plot_width = plot_right - plot_left
    bar_height = 14
    bar_gap = 5
    category_gap = 17
    category_height = len(series) * (bar_height + bar_gap) + category_gap
    top = 120
    height = top + len(categories) * category_height + 76
    colors = [color for _, color in series]

    def x_position(value: float) -> float:
        if value <= 0 or not math.isfinite(value):
            raise ReportError(f"chart value must be finite and positive, found {value!r}")
        bounded = min(max(math.log10(value), minimum_power), maximum_power)
        return plot_left + ((bounded - minimum_power) / (maximum_power - minimum_power)) * plot_width

    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" role="img">',
        f"  <title>{escape(title)}</title>",
        f"  <desc>{escape(description)}</desc>",
        "  <style>",
        "    text { font-family: system-ui, -apple-system, sans-serif; fill: #172033; }",
        "    .title { font-size: 24px; font-weight: 700; }",
        "    .subtitle { font-size: 13px; fill: #526071; }",
        "    .category { font-size: 13px; font-weight: 600; }",
        "    .series { font-size: 11px; fill: #526071; }",
        "    .value { font-size: 11px; font-variant-numeric: tabular-nums; }",
        "    .axis { font-size: 11px; fill: #657386; }",
        "    .grid { stroke: #d8dee8; stroke-width: 1; }",
        "  </style>",
        f'  <rect width="{width}" height="{height}" fill="#ffffff"/>',
        f'  <text class="title" x="24" y="34">{escape(title)}</text>',
        f'  <text class="subtitle" x="24" y="58">{escape(description)}</text>',
        f'  <text class="subtitle" x="24" y="78">Logarithmic scale; labels show measured {escape(unit)}.</text>',
    ]

    legend_x = 24
    for index, (label, color) in enumerate(series):
        x = legend_x + index * 180
        lines.append(f'  <rect x="{x}" y="91" width="14" height="10" rx="2" fill="{color}"/>')
        lines.append(f'  <text class="series" x="{x + 20}" y="100">{escape(label)}</text>')

    for power in range(minimum_power, maximum_power + 1):
        x = x_position(10**power)
        lines.append(f'  <line class="grid" x1="{x:.2f}" y1="{top - 8}" x2="{x:.2f}" y2="{height - 48}"/>')
        tick_value = 10**power
        lines.append(
            f'  <text class="axis" text-anchor="middle" x="{x:.2f}" y="{height - 28}">{escape(display_number(tick_value, 3))}</text>'
        )
    lines.append(
        f'  <text class="axis" text-anchor="middle" x="{(plot_left + plot_right) / 2:.2f}" y="{height - 9}">{escape(unit)}</text>'
    )

    for category_index, (category, values) in enumerate(categories):
        base_y = top + category_index * category_height
        lines.append(f'  <text class="category" text-anchor="end" x="286" y="{base_y + 14}">{escape(category)}</text>')
        for series_index, (series_name, _) in enumerate(series):
            if series_name not in values:
                raise ReportError(f"chart category {category!r} is missing series {series_name!r}")
            value = values[series_name]
            y = base_y + series_index * (bar_height + bar_gap)
            end_x = x_position(value)
            bar_width = max(1.0, end_x - plot_left)
            lines.append(
                f'  <rect x="{plot_left}" y="{y}" width="{bar_width:.2f}" height="{bar_height}" rx="2" fill="{colors[series_index]}"/>'
            )
            value_x = min(end_x + 6, width - 78)
            lines.append(
                f'  <text class="value" x="{value_x:.2f}" y="{y + 11}">{escape(display_number(value))}</text>'
            )
    lines.append("</svg>")
    return ("\n".join(lines) + "\n").encode("utf-8")


def query_chart(rows: Sequence[Mapping[str, Any]]) -> bytes:
    methods = (
        ("construct query", "QueryLifecycleBenchmark.constructCompoundQuery"),
        ("create/read/close", "QueryLifecycleBenchmark.createResultSetReadCostAndClose"),
        ("first result/close", "QueryLifecycleBenchmark.firstResultAndClose"),
        ("iterate/close", "QueryLifecycleBenchmark.fullIterationAndClose"),
        ("size/close", "QueryLifecycleBenchmark.sizeAndClose"),
        ("unindexed iterate/close", "QueryLifecycleBenchmark.unindexedFullIterationAndClose"),
    )
    categories = []
    for label, suffix in methods:
        values = {
            "Java 25": float(
                find_row(rows, 25, "query", suffix, {"datasetSize": "10000"})["score"]
            )
        }
        categories.append((label, values))
    return grouped_log_chart(
        "Query lifecycle on Java 25",
        "Current machine-specific measurements; lower is better. No regression thresholds are inferred.",
        categories,
        (("Java 25", "#2563eb"),),
        "ns/op",
        1,
        5,
    )


def query_scenario_chart(rows: Sequence[Mapping[str, Any]]) -> bytes:
    categories = []
    for label, scenario in QUERY_SCENARIOS:
        row = find_row(
            rows,
            25,
            "scenarios",
            "QueryScenarioBenchmark.fullIterationAndClose",
            {"datasetSize": "10000", "scenario": scenario},
        )
        categories.append((label, {"Java 25": float(row["score"])}))
    return grouped_log_chart(
        "Indexed query scenarios on Java 25",
        "Full result iteration over 10,000 records; lower is better. Result cardinality varies by scenario.",
        categories,
        (("Java 25", "#2563eb"),),
        "ns/op",
        2,
        5,
    )


def sampled_latency_chart(rows: Sequence[Mapping[str, Any]]) -> bytes:
    selections = (
        (
            "lookup: on heap",
            "PersistenceLifecycleBenchmark.pointLookupAndClose",
            {"datasetSize": "10000", "persistenceMode": "ON_HEAP"},
        ),
        (
            "lookup: off heap",
            "PersistenceLifecycleBenchmark.pointLookupAndClose",
            {"datasetSize": "10000", "persistenceMode": "OFF_HEAP"},
        ),
        (
            "lookup: disk WAL",
            "PersistenceLifecycleBenchmark.pointLookupAndClose",
            {"datasetSize": "10000", "persistenceMode": "DISK_WAL"},
        ),
        (
            "first: hash large",
            "QueryScenarioBenchmark.firstResultAndClose",
            {"datasetSize": "10000", "scenario": "HASH_LARGE"},
        ),
        (
            "iterate: hash large",
            "QueryScenarioBenchmark.fullIterationAndClose",
            {"datasetSize": "10000", "scenario": "HASH_LARGE"},
        ),
        (
            "iterate: fallback large",
            "QueryScenarioBenchmark.fullIterationAndClose",
            {"datasetSize": "10000", "scenario": "FALLBACK_LARGE"},
        ),
    )
    categories = []
    for label, suffix, params in selections:
        row = find_row(rows, 25, "latency", suffix, params)
        values = {
            "Java 25 p50": float(row["p50"]),
            "Java 25 p99": float(row["p99"]),
        }
        categories.append((label, values))
    return grouped_log_chart(
        "Representative sampled latency on Java 25",
        "Current-machine p50 and p99 observations from the sample-time lane; lower is better.",
        categories,
        (
            ("Java 25 p50", "#2563eb"),
            ("Java 25 p99", "#60a5fa"),
        ),
        "us/op",
        -1,
        3,
    )


def allocation_chart(rows: Sequence[Mapping[str, Any]]) -> bytes:
    selections = (
        ("construct query", "query", "QueryLifecycleBenchmark.constructCompoundQuery", {"datasetSize": "10000"}),
        (
            "create/read/close",
            "query",
            "QueryLifecycleBenchmark.createResultSetReadCostAndClose",
            {"datasetSize": "10000"},
        ),
        ("first result/close", "query", "QueryLifecycleBenchmark.firstResultAndClose", {"datasetSize": "10000"}),
        ("iterate/close", "query", "QueryLifecycleBenchmark.fullIterationAndClose", {"datasetSize": "10000"}),
        ("size/close", "query", "QueryLifecycleBenchmark.sizeAndClose", {"datasetSize": "10000"}),
        (
            "unindexed iterate/close",
            "query",
            "QueryLifecycleBenchmark.unindexedFullIterationAndClose",
            {"datasetSize": "10000"},
        ),
        (
            "singleton replace",
            "allocation",
            "MutationAllocationBenchmark.replaceWithSingletonInputs",
            {"datasetSize": "1000"},
        ),
    )
    categories = []
    for label, lane, suffix, params in selections:
        value = find_row(rows, 25, lane, suffix, params)["allocation_bytes_per_op"]
        if value is None:
            raise ReportError(f"representative allocation metric is absent for {label}, Java 25")
        values = {"Java 25": float(value)}
        categories.append((label, values))
    return grouped_log_chart(
        "Normalized allocation on Java 25",
        "Current-machine JMH gc.alloc.rate.norm; lower is better and zero allocation is not claimed.",
        categories,
        (("Java 25", "#2563eb"),),
        "B/op",
        2,
        6,
    )


def markdown_table(headers: Sequence[str], rows: Iterable[Sequence[str]]) -> list[str]:
    output = ["| " + " | ".join(headers) + " |", "| " + " | ".join("---" for _ in headers) + " |"]
    output.extend("| " + " | ".join(row) + " |" for row in rows)
    return output


def representative_markdown(
    rows: Sequence[Mapping[str, Any]],
    capture: CaptureMetadata,
    approval: ApprovalDecision,
) -> bytes:
    query_methods = (
        ("Construct compound query", "QueryLifecycleBenchmark.constructCompoundQuery"),
        ("Create/read/close", "QueryLifecycleBenchmark.createResultSetReadCostAndClose"),
        ("First result/close", "QueryLifecycleBenchmark.firstResultAndClose"),
        ("Full iteration/close", "QueryLifecycleBenchmark.fullIterationAndClose"),
        ("Size/close", "QueryLifecycleBenchmark.sizeAndClose"),
        ("Unindexed iteration/close", "QueryLifecycleBenchmark.unindexedFullIterationAndClose"),
    )
    query_table = []
    for label, suffix in query_methods:
        selected = find_row(rows, 25, "query", suffix, {"datasetSize": "10000"})
        query_table.append(
            (
                label,
                display_number(float(selected["score"])),
                display_number(float(selected["allocation_bytes_per_op"])),
            )
        )

    scenario_table = []
    for label, scenario in QUERY_SCENARIOS:
        selected = find_row(
            rows,
            25,
            "scenarios",
            "QueryScenarioBenchmark.fullIterationAndClose",
            {"datasetSize": "10000", "scenario": scenario},
        )
        scenario_table.append((label, display_number(float(selected["score"]))))

    latency_selections = (
        (
            "Persistence lookup, on heap",
            "PersistenceLifecycleBenchmark.pointLookupAndClose",
            {"datasetSize": "10000", "persistenceMode": "ON_HEAP"},
        ),
        (
            "Persistence lookup, off heap",
            "PersistenceLifecycleBenchmark.pointLookupAndClose",
            {"datasetSize": "10000", "persistenceMode": "OFF_HEAP"},
        ),
        (
            "Persistence lookup, disk WAL",
            "PersistenceLifecycleBenchmark.pointLookupAndClose",
            {"datasetSize": "10000", "persistenceMode": "DISK_WAL"},
        ),
        (
            "First result, hash large",
            "QueryScenarioBenchmark.firstResultAndClose",
            {"datasetSize": "10000", "scenario": "HASH_LARGE"},
        ),
        (
            "Full iteration, hash large",
            "QueryScenarioBenchmark.fullIterationAndClose",
            {"datasetSize": "10000", "scenario": "HASH_LARGE"},
        ),
        (
            "Full iteration, fallback large",
            "QueryScenarioBenchmark.fullIterationAndClose",
            {"datasetSize": "10000", "scenario": "FALLBACK_LARGE"},
        ),
    )
    latency_table = []
    for label, suffix, params in latency_selections:
        selected = find_row(rows, 25, "latency", suffix, params)
        latency_table.append(
            (
                label,
                display_number(float(selected["p50"])),
                display_number(float(selected["p99"])),
            )
        )

    allocation = find_row(
        rows,
        25,
        "allocation",
        "MutationAllocationBenchmark.replaceWithSingletonInputs",
        {"datasetSize": "1000"},
    )

    lines = [
        "# Representative benchmark results",
        "",
        "These Java 25 values are selected views of the complete 208-row `results.csv`, which retains",
        "the validated Java 21 and Java 25 qualification evidence. The public tables and charts focus",
        "on the current Java 25 runtime rather than presenting a cross-JDK comparison.",
        "",
        f"Measured host: {public_host_description(capture, approval)}. "
        f"Measured commit: `{capture.source_commit}`.",
        "They are not performance thresholds, cross-machine guarantees or universal CQEngine claims.",
        "",
        "## Indexed query scenarios",
        "",
        "Dataset size: 10,000. Values are average nanoseconds for full result iteration and close.",
        "Result cardinality differs by scenario, so compare only equivalent workloads. Lower is better.",
        "",
    ]
    lines.extend(markdown_table(("Scenario", "Java 25 ns/op"), scenario_table))
    lines.extend(
        [
            "",
            "![Indexed query scenarios on Java 25](query-scenarios.svg)",
            "",
            "## Query lifecycle",
            "",
            "Dataset size: 10,000. Average-time score is in ns/op; normalized allocation is in B/op. Lower is better.",
            "",
        ]
    )
    lines.extend(
        markdown_table(
            ("Operation", "Java 25 ns/op", "Java 25 B/op"),
            query_table,
        )
    )
    lines.extend(
        [
            "",
            "![Query lifecycle on Java 25](query-lifecycle.svg)",
            "",
            "## Sampled latency",
            "",
            "Dataset size: 10,000. Values are microseconds per operation from the sample-time lane. Lower is better.",
            "",
        ]
    )
    lines.extend(
        markdown_table(
            ("Operation", "Java 25 p50", "Java 25 p99"),
            latency_table,
        )
    )
    lines.extend(
        [
            "",
            "![Representative sampled latency](sampled-latency.svg)",
            "",
            "## Mutation allocation",
            "",
            "`replaceWithSingletonInputs`, dataset size 1,000. Average-time score is in ns/op; allocation is in B/op.",
            "",
        ]
    )
    lines.extend(
        markdown_table(
            ("Runtime", "Score ns/op", "Normalized allocation B/op"),
            (
                (
                    "Java 25",
                    display_number(float(allocation["score"])),
                    display_number(float(allocation["allocation_bytes_per_op"])),
                ),
            ),
        )
    )
    lines.extend(
        [
            "",
            "![Normalized allocation](allocation.svg)",
            "",
            "The complete CSV retains score error, confidence bounds, percentiles and available GC profiler metrics for every row.",
            "",
        ]
    )
    return ("\n".join(lines)).encode("utf-8")


def file_store_type(value: str) -> str:
    if "(" not in value or not value.endswith(")"):
        raise ReportError(f"cannot sanitize file-store description {value!r}")
    return value.rsplit("(", 1)[1][:-1]


def public_host_description(capture: CaptureMetadata, approval: ApprovalDecision) -> str:
    environment = capture.environment
    description = (
        f"{require_property(environment, 'operatingSystem')}, "
        f"{require_property(environment, 'cpuModel')} (measured), "
        f"{require_property(environment, 'cpuLogicalProcessors')} logical processors, "
        f"{file_store_type(require_property(environment, 'projectFileStore'))} storage"
    )
    if approval.declared_physical_cpu_model != "none":
        description += (
            f"; underlying physical CPU {approval.declared_physical_cpu_model} "
            f"({approval.declared_cpu_model_evidence}, not measured)"
        )
    return description


def sanitized_environment(capture: CaptureMetadata, approval: ApprovalDecision) -> bytes:
    environment = capture.environment
    values = {
        "approvalBasis": approval.basis,
        "approvalRecordIdentity": approval.record_identity,
        "approvalRecordSha256": approval.record_sha256,
        "approvedNumericalBaseline": str(approval.approved).lower(),
        "architecture": require_property(environment, "architecture"),
        "benchmarkJarSha256": require_property(environment, "benchmarkJarSha256"),
        "captureTimestamp": require_property(environment, "generatedAt"),
        "benchmarkNamespaceNormalization": BENCHMARK_NAMESPACE.removesuffix("."),
        "cpuLogicalProcessors": require_property(environment, "cpuLogicalProcessors"),
        "cpuModel": require_property(environment, "cpuModel"),
        "cpuModelBasis": "measured-on-the-benchmark-host",
        "declaredCpuModelEvidence": approval.declared_cpu_model_evidence,
        "declaredPhysicalCpuModel": approval.declared_physical_cpu_model,
        "evidenceClassification": (
            "approved-machine-specific-development-baseline"
            if approval.approved
            else "machine-specific-development-evidence"
        ),
        "formatVersion": "1",
        "gradleDistributionSha256": require_property(environment, "gradleDistributionSha256"),
        "gradleVersion": require_property(environment, "gradleVersion"),
        "gradleWrapperJarSha256": require_property(environment, "gradleWrapperJarSha256"),
        "java21ExecutableSha256": require_property(environment, "java21ExecutableSha256"),
        "java21Vendor": require_property(environment, "java21Vendor"),
        "java21Version": require_property(environment, "java21Version"),
        "java25ExecutableSha256": require_property(environment, "java25ExecutableSha256"),
        "java25Vendor": require_property(environment, "java25Vendor"),
        "java25Version": require_property(environment, "java25Version"),
        "jmhVersion": require_property(environment, "jmhVersion"),
        "kernel": require_property(environment, "kernel"),
        "locale": require_property(environment, "locale"),
        "memoryTotalKiB": require_property(environment, "memoryTotalKiB"),
        "operatingSystem": require_property(environment, "operatingSystem"),
        "originalCaptureApprovedNumericalBaseline": str(capture.approved_numerical_baseline).lower(),
        "originalCaptureCoordinateSha256": sha256_bytes(capture.coordinate.encode("utf-8")),
        "originalCaptureEvidenceUse": capture.evidence_use,
        "originalCaptureMachineApproval": capture.machine_approval,
        "originalCaptureMachineLabelSha256": sha256_bytes(capture.machine_label.encode("utf-8")),
        "originalCaptureNumericReadmeClaims": capture.numeric_readme_claims,
        "originalCaptureStatus": capture.status,
        "performanceThresholds": "none",
        "publicationCoordinate": publication_coordinate(),
        "processAvailableProcessors": require_property(environment, "processAvailableProcessors"),
        "projectFileStoreType": file_store_type(require_property(environment, "projectFileStore")),
        "reviewedDisplayHost": approval.display_host,
        "sourceCommit": capture.source_commit,
        "sourceCommitScope": "qualified-source-capture",
        "sourceTree": capture.source_tree,
        "temporaryFileStoreType": file_store_type(require_property(environment, "temporaryFileStore")),
        "timezone": require_property(environment, "timezone"),
        "unsafeJvmBuildEnvironment": require_property(environment, "unsafeJvmBuildEnvironment"),
        "virtualization": require_property(environment, "virtualization"),
        "wslVersion": require_property(environment, "wslVersion"),
    }
    lines = [
        "# Sanitized capture metadata; executable paths, repository URLs and device paths are intentionally omitted."
    ]
    lines.extend(f"{key}={values[key]}" for key in sorted(values))
    return ("\n".join(lines) + "\n").encode("utf-8")


def source_manifest(input_hashes: Mapping[str, str]) -> bytes:
    lines = [f"{input_hashes[path]}  {path}" for path in sorted(input_hashes)]
    return ("\n".join(lines) + "\n").encode("utf-8")


def readme(capture: CaptureMetadata, approval: ApprovalDecision) -> bytes:
    if approval.approved:
        classification = "an approved machine-specific development baseline"
        if approval.basis == "subsequent-characteristic-match":
            approval_description = (
                "Subsequent approval: the captured host characteristics exactly match the reviewed "
                f"`{approval.record_identity}` record (`sha256:{approval.record_sha256}`)."
            )
        else:
            approval_description = (
                "Approval validation: the approval captured with the run was revalidated against "
                f"`{approval.record_identity}` (`sha256:{approval.record_sha256}`)."
            )
    else:
        classification = "unapproved machine-specific development evidence"
        approval_description = "Approval: none; the capture remains report-only evidence."
    lines = [
        "# CQEngine development benchmark evidence",
        "",
        "This directory is a deterministic, sanitized report derived from one completed full JMH qualification. It is",
        f"{classification}. It has no performance thresholds.",
        "",
        f"- Measured commit: `{capture.source_commit}`",
        f"- Measured tree: `{capture.source_tree}`",
        f"- Report publication coordinate: `{publication_coordinate()}`",
        f"- Capture status: `{capture.status}` (`machineApproval={capture.machine_approval}`)",
        f"- Benchmark host: `{approval.display_host}`",
        f"- Host characteristics: {public_host_description(capture, approval)}",
        f"- {approval_description}",
        "- Qualification evidence: Java 21 and Java 25, 104 unique results each",
        "- Published tables and charts: Java 25",
        "- Thresholds: none",
        "",
        "The host approval checks the captured operating system, kernel, architecture, CPU, processor count and",
        "filesystems against a reviewed record. `cpuModel` is always the model the benchmark host reported; where a",
        "record also carries `declaredPhysicalCpuModel`, that value identifies the hypervisor's underlying processor",
        "and is an operator declaration that no part of this build measured or verified.",
        "The report preserves the measured source commit, source tree, input",
        "hashes and every value from both supported runtimes. Its public views use Java 25 so the charts describe",
        "the current runtime without turning runtime-version differences into a performance claim.",
        "",
        "Results are suitable for machine-specific inspection, but they do not establish cross-host guarantees,",
        "regression thresholds or universal CQEngine performance claims.",
        "",
        "## Contents",
        "",
        "- `results.csv` contains all 208 unique JMH result rows from exactly 14 full-run JSON inputs.",
        "- `representative-results.md` presents selected query, latency and allocation views.",
        "- `query-scenarios.svg` is the current indexed-query overview suitable for the project README.",
        "- `query-lifecycle.svg`, `sampled-latency.svg` and `allocation.svg` provide focused generated charts.",
        "- `environment.properties` contains allowlisted, sanitized capture metadata.",
        "- `source-inputs.sha256` identifies the 14 local JSON inputs without exposing absolute paths.",
        "- `SHA256SUMS` authenticates every generated file in this directory except itself.",
        "",
        "The raw JMH JSON and human-readable reports remain in the local build output and are not copied here,",
        "because they contain absolute executable and temporary-directory paths. Regenerate this report from",
        "those inputs with:",
        "",
        "```shell",
        "python3 scripts/generate-benchmark-report.py \\",
        "  --input benchmarks/build/reports \\",
        "  --output <destination> \\",
        "  --approval-record <repository-relative-host-properties> \\",
        "  --display-host <approved-machine-label>",
        "```",
        "",
        "Generation rejects missing or partial lanes, unexpected run settings, duplicate identities, non-finite",
        "metrics, incorrect capture metadata, and any output containing known local or internal path forms.",
        "",
    ]
    return ("\n".join(lines)).encode("utf-8")


def build_outputs(
    rows: Sequence[Mapping[str, Any]],
    input_hashes: Mapping[str, str],
    capture: CaptureMetadata,
    approval: ApprovalDecision,
) -> dict[str, bytes]:
    outputs = {
        "README.md": readme(capture, approval),
        "allocation.svg": allocation_chart(rows),
        "environment.properties": sanitized_environment(capture, approval),
        "query-lifecycle.svg": query_chart(rows),
        "query-scenarios.svg": query_scenario_chart(rows),
        "representative-results.md": representative_markdown(rows, capture, approval),
        "results.csv": generate_csv(rows),
        "sampled-latency.svg": sampled_latency_chart(rows),
        "source-inputs.sha256": source_manifest(input_hashes),
    }
    if set(outputs) != set(MANAGED_OUTPUTS):
        raise ReportError("internal error: generated output set does not match managed output contract")
    checksum_lines = [f"{sha256_bytes(outputs[name])}  {name}" for name in sorted(outputs)]
    outputs["SHA256SUMS"] = ("\n".join(checksum_lines) + "\n").encode("utf-8")
    return outputs


def validate_sanitization(outputs: Mapping[str, bytes]) -> None:
    forbidden = (
        b"/home/",
        b"/Users/",
        b"\\\\wsl.localhost",
        b"\\home\\",
        b"/repository-manager/",
        b"sdkman/candidates",
        b"cqengine-release-source.",
        b"/dev/sd",
    )
    for name, content in outputs.items():
        for token in forbidden:
            if token.lower() in content.lower():
                raise ReportError(f"generated {name} contains forbidden local/internal value {token!r}")


def validate_outputs(output_root: Path, expected: Mapping[str, bytes]) -> None:
    for name, expected_content in expected.items():
        path = output_root / name
        if not path.is_file():
            raise ReportError(f"missing generated output: {name}")
        actual_content = path.read_bytes()
        if actual_content != expected_content:
            raise ReportError(f"generated output is stale or non-deterministic: {name}")

    csv_text = expected["results.csv"].decode("utf-8")
    parsed_rows = list(csv.DictReader(io.StringIO(csv_text)))
    if len(parsed_rows) != EXPECTED_TOTAL_RESULTS:
        raise ReportError(f"generated CSV has {len(parsed_rows)} rows, expected {EXPECTED_TOTAL_RESULTS}")
    if {jdk: sum(row["jdk_major"] == str(jdk) for row in parsed_rows) for jdk in (21, 25)} != {
        21: EXPECTED_RESULTS_PER_JDK,
        25: EXPECTED_RESULTS_PER_JDK,
    }:
        raise ReportError("generated CSV per-JDK counts are invalid")

    checksum_lines = expected["SHA256SUMS"].decode("utf-8").splitlines()
    if len(checksum_lines) != len(MANAGED_OUTPUTS):
        raise ReportError("generated checksum manifest has an unexpected number of entries")
    for line in checksum_lines:
        digest, separator, name = line.partition("  ")
        if separator != "  " or name not in expected or name == "SHA256SUMS":
            raise ReportError(f"malformed generated checksum entry: {line!r}")
        if digest != sha256_bytes(expected[name]):
            raise ReportError(f"checksum mismatch for generated output {name}")

    for svg_name in (
        "allocation.svg",
        "query-lifecycle.svg",
        "query-scenarios.svg",
        "sampled-latency.svg",
    ):
        try:
            ET.fromstring(expected[svg_name])
        except ET.ParseError as exc:
            raise ReportError(f"generated SVG is not valid XML: {svg_name}: {exc}") from exc


def main() -> int:
    args = parse_args()
    try:
        input_root = args.input.resolve(strict=True)
        rows, input_hashes = load_results(input_root)
        capture = validate_capture_metadata(input_root)
        approval = resolve_approval(capture, args.approval_record, args.display_host)
        outputs = build_outputs(rows, input_hashes, capture, approval)
        validate_sanitization(outputs)
        if not args.validate_only:
            args.output.mkdir(parents=True, exist_ok=True)
            for name in sorted(outputs):
                (args.output / name).write_bytes(outputs[name])
        validate_outputs(args.output, outputs)
    except (OSError, ReportError) as exc:
        print(f"benchmark-report: ERROR: {exc}", file=sys.stderr)
        return 1

    action = "validated" if args.validate_only else "generated-and-validated"
    print(
        f"benchmark-report: {action}; inputs={len(INPUT_SPECS)}; results={len(rows)}; "
        f"jdk21={EXPECTED_RESULTS_PER_JDK}; jdk25={EXPECTED_RESULTS_PER_JDK}; "
        f"approval={approval.basis}; output={args.output}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
