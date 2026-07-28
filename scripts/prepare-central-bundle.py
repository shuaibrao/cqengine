#!/usr/bin/env python3
"""Build a signed Central Portal bundle from an already-qualified local repository."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile


GROUP_PATH = Path("io/github/shuaibrao")
ARTIFACT = "cqengine"
CHECKSUMS = {
    "md5": "md5",
    "sha1": "sha1",
    "sha256": "sha256",
    "sha512": "sha512",
}
FINGERPRINT = re.compile(r"[0-9A-Fa-f]{40,64}")
VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?")
QUALIFY_COMMAND = "./gradlew qualifyLocally"
EVIDENCE_LINE = re.compile(r"([0-9a-f]{64}) ([0-9a-f]{128})  (root|benchmarks):(.+)")
INVENTORY_LINE = re.compile(r"([0-9a-f]{64}) ([0-9a-f]{128})  (.+)")
GPG_PASSPHRASE = os.environ.pop("CQENGINE_GPG_PASSPHRASE", None)


class BundleError(RuntimeError):
    pass


def digest(path: Path, algorithm: str) -> str:
    value = hashlib.new(algorithm)
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def properties(path: Path) -> dict[str, str]:
    if not path.is_file() or path.is_symlink():
        raise BundleError(f"missing regular evidence file: {path}")
    values: dict[str, str] = {}
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key in values:
            raise BundleError(f"duplicate property {key!r} in {path}:{number}")
        values[key] = value
    return values


def require_property(values: dict[str, str], key: str, expected: str | None = None) -> str:
    value = values.get(key)
    if value is None or not value:
        raise BundleError(f"required evidence property is absent: {key}")
    if expected is not None and value != expected:
        raise BundleError(f"evidence property {key} is {value!r}, expected {expected!r}")
    return value


def manifest_evidence(readiness_path: Path, label: str) -> tuple[str, str]:
    matches = [
        (match.group(1), match.group(2))
        for line in readiness_path.read_text(encoding="utf-8").splitlines()
        if (match := EVIDENCE_LINE.fullmatch(line)) and f"{match.group(3)}:{match.group(4)}" == label
    ]
    if len(matches) != 1:
        raise BundleError(f"readiness manifest must bind exactly one {label} entry")
    return matches[0]


def reject_symbolic_path(path: Path, root: Path) -> None:
    relative = path.relative_to(root)
    current = root
    for part in relative.parts:
        current /= part
        if current.is_symlink():
            raise BundleError(f"symbolic paths are forbidden in release input: {current}")


def verify_repository_inventory(project: Path, repository: Path, version: str) -> dict[str, tuple[str, str]]:
    inventory_path = project / "build/reports/publication/inventory.txt"
    readiness_path = project / "build/local-release-evidence/qualification/local-readiness-manifest.txt"
    expected_inventory_sha256, expected_inventory_sha512 = manifest_evidence(
        readiness_path, "root:reports/publication/inventory.txt"
    )
    if not inventory_path.is_file() or inventory_path.is_symlink():
        raise BundleError(f"missing regular qualified publication inventory: {inventory_path}")
    if digest(inventory_path, "sha256") != expected_inventory_sha256 or digest(
        inventory_path, "sha512"
    ) != expected_inventory_sha512:
        raise BundleError("publication inventory does not match the readiness manifest")

    lines = inventory_path.read_text(encoding="utf-8").splitlines()
    if not lines or lines[0] != f"coordinate=io.github.shuaibrao:cqengine:{version}":
        raise BundleError("publication inventory has the wrong coordinate")
    recorded: dict[str, tuple[str, str]] = {}
    for line in lines[1:]:
        match = INVENTORY_LINE.fullmatch(line)
        if not match:
            raise BundleError(f"malformed publication inventory line: {line!r}")
        relative = Path(match.group(3))
        if relative.is_absolute() or ".." in relative.parts or relative.as_posix() != match.group(3):
            raise BundleError(f"unsafe publication inventory path: {match.group(3)!r}")
        key = relative.as_posix()
        if key in recorded:
            raise BundleError(f"duplicate publication inventory path: {key}")
        recorded[key] = (match.group(1), match.group(2))

    actual: set[str] = set()
    for entry in repository.rglob("*"):
        if entry.is_symlink():
            raise BundleError(f"qualified repository contains a symbolic path: {entry}")
        if entry.is_dir():
            continue
        if not entry.is_file():
            raise BundleError(f"qualified repository contains a special file: {entry}")
        relative = entry.relative_to(repository).as_posix()
        actual.add(relative)
        expected_hashes = recorded.get(relative)
        if expected_hashes is None:
            raise BundleError(f"qualified repository file is absent from its inventory: {relative}")
        if (digest(entry, "sha256"), digest(entry, "sha512")) != expected_hashes:
            raise BundleError(f"qualified repository file differs from its inventory: {relative}")
    if actual != set(recorded):
        raise BundleError(f"publication inventory names absent repository files: {sorted(set(recorded) - actual)}")
    return recorded


def verify_qualification(
    project: Path, repository: Path
) -> tuple[str, dict[str, tuple[str, str]], str, str]:
    evidence = project / "build/local-release-evidence/qualification"
    completion_path = evidence / "qualification-completion.properties"
    readiness_path = evidence / "local-readiness-manifest.txt"
    completion = properties(completion_path)
    readiness = properties(readiness_path)

    # The completion record is written only when the whole qualification graph succeeded, so its presence
    # is the pass record; the hash pairing below stops it being matched with a different evidence set.
    require_property(completion, "formatVersion", "1")
    require_property(completion, "status", "passed")
    qualification_mode = require_property(completion, "qualificationMode")
    require_property(readiness, "formatVersion", "1")
    require_property(readiness, "command", QUALIFY_COMMAND)
    if require_property(readiness, "qualificationMode") != qualification_mode:
        raise BundleError("completion and readiness evidence disagree about the qualification mode")

    source_commit = require_property(completion, "sourceCommit")
    if require_property(readiness, "sourceCommit") != source_commit:
        raise BundleError("qualification completion and readiness evidence identify different commits")
    source_tree = require_property(completion, "sourceTree")
    if require_property(readiness, "sourceTree") != source_tree:
        raise BundleError("qualification completion and readiness evidence identify different source trees")
    if digest(readiness_path, "sha256") != require_property(completion, "readinessManifestSha256"):
        raise BundleError("readiness manifest SHA-256 does not match the qualification completion record")
    if digest(readiness_path, "sha512") != require_property(completion, "readinessManifestSha512"):
        raise BundleError("readiness manifest SHA-512 does not match the qualification completion record")

    version = require_property(readiness, "coordinate").removeprefix(
        "io.github.shuaibrao:cqengine:"
    )
    if not VERSION.fullmatch(version) or version.endswith("-SNAPSHOT"):
        raise BundleError(f"Central publication requires a non-SNAPSHOT SemVer version, found {version!r}")
    gradle_version = properties(project / "gradle.properties").get("version")
    if gradle_version != version:
        raise BundleError(
            f"qualified version {version!r} does not match gradle.properties version {gradle_version!r}"
        )
    expected_repository = project / "build/local-repository"
    if repository.absolute() != expected_repository.absolute():
        raise BundleError("only the authoritative build/local-repository may be bundled")
    reject_symbolic_path(repository, project)
    if not repository.is_dir() or repository.is_symlink():
        raise BundleError(f"missing qualified local repository: {repository}")

    git = shutil.which("git")
    if not git or not (project / ".git").exists() or (project / ".git").is_symlink():
        raise BundleError("a real Git checkout and git executable are required for source identity")
    actual_commit = subprocess.run(
        [git, "-C", str(project), "rev-parse", "HEAD"],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    ).stdout.strip()
    if actual_commit != source_commit:
        raise BundleError(
            f"qualified commit {source_commit} does not match checkout HEAD {actual_commit}"
        )
    actual_tree = subprocess.run(
        [git, "-C", str(project), "rev-parse", "HEAD^{tree}"],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    ).stdout.strip()
    if actual_tree != source_tree:
        raise BundleError(f"qualified tree {source_tree} does not match checkout tree {actual_tree}")
    # Older evidence predates the skipped-gate record; absent means the reference Linux run skipped nothing.
    skipped_gates = readiness.get("skippedReleaseGates", "none")
    return (
        version,
        verify_repository_inventory(project, repository, version),
        qualification_mode,
        skipped_gates,
    )


def primary_names(version: str) -> list[str]:
    stem = f"{ARTIFACT}-{version}"
    return [
        f"{stem}.jar",
        f"{stem}-all.jar",
        f"{stem}-sources.jar",
        f"{stem}-javadoc.jar",
        f"{stem}.pom",
        f"{stem}.module",
    ]


def verify_local_inventory(version_directory: Path, names: list[str]) -> None:
    expected = {
        name
        for primary in names
        for name in [primary, *(f"{primary}.{extension}" for extension in CHECKSUMS)]
    }
    entries = list(version_directory.iterdir())
    actual = {entry.name for entry in entries if entry.is_file() and not entry.is_symlink()}
    non_files = [entry.name for entry in entries if not entry.is_file() or entry.is_symlink()]
    if non_files or actual != expected:
        raise BundleError(
            "qualified version directory inventory mismatch: "
            f"missing={sorted(expected - actual)}, unexpected={sorted(actual - expected | set(non_files))}"
        )

    for name in names:
        primary = version_directory / name
        if primary.is_symlink() or primary.stat().st_size == 0:
            raise BundleError(f"qualified artifact is empty or symbolic: {primary}")
        for extension, algorithm in CHECKSUMS.items():
            sidecar = version_directory / f"{name}.{extension}"
            recorded = sidecar.read_text(encoding="ascii").strip().lower()
            actual_digest = digest(primary, algorithm)
            if recorded != actual_digest:
                raise BundleError(f"invalid {extension} sidecar for {name}")


def gpg_sign(gpg: str, key_id: str, source: Path, signature: Path) -> tuple[str, str]:
    command = [
        gpg,
        "--batch",
        "--yes",
        "--armor",
        "--detach-sign",
        "--local-user",
        key_id,
        "--output",
        str(signature),
        str(source),
    ]
    passphrase = GPG_PASSPHRASE
    child_environment = os.environ.copy()
    child_environment.pop("CQENGINE_GPG_PASSPHRASE", None)
    child_environment.pop("CQENGINE_GPG_KEY_ID", None)
    stdin: str | None = None
    if passphrase is not None:
        if "\n" in passphrase or "\r" in passphrase:
            raise BundleError("CQENGINE_GPG_PASSPHRASE must not contain line breaks")
        command[2:2] = ["--pinentry-mode", "loopback", "--passphrase-fd", "0"]
        stdin = passphrase + "\n"
    subprocess.run(
        command,
        check=True,
        input=stdin,
        text=True,
        stdout=subprocess.DEVNULL,
        env=child_environment,
    )
    verification = subprocess.run(
        [gpg, "--batch", "--status-fd", "1", "--verify", str(signature), str(source)],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        env=child_environment,
    ).stdout
    valid_signatures = [
        (line.split()[2], line.split()[-1])
        for line in verification.splitlines()
        if line.startswith("[GNUPG:] VALIDSIG ") and len(line.split()) >= 12
    ]
    if len(valid_signatures) != 1 or not all(
        FINGERPRINT.fullmatch(fingerprint) for fingerprint in valid_signatures[0]
    ):
        raise BundleError(f"could not determine one valid signing fingerprint for {source.name}")
    return tuple(fingerprint.upper() for fingerprint in valid_signatures[0])


def write_zip(source_root: Path, output: Path) -> None:
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for source in sorted(path for path in source_root.rglob("*") if path.is_file()):
            relative = source.relative_to(source_root).as_posix()
            info = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, source.read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def verify_zip(output: Path, expected: dict[str, str]) -> None:
    with zipfile.ZipFile(output) as archive:
        names = archive.namelist()
        if names != sorted(expected) or len(names) != len(set(names)):
            raise BundleError("Central bundle has an unexpected or duplicate entry inventory")
        for name, expected_sha256 in expected.items():
            if hashlib.sha256(archive.read(name)).hexdigest() != expected_sha256:
                raise BundleError(f"Central bundle entry changed while packaging: {name}")


def atomic_write(path: Path, value: str) -> None:
    if path.is_symlink():
        raise BundleError(f"refusing to replace symbolic output: {path}")
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="ascii", newline="\n") as target:
            target.write(value)
            target.flush()
            os.fsync(target.fileno())
        os.chmod(temporary, 0o644)
        os.replace(temporary, path)
    finally:
        if temporary.exists() or temporary.is_symlink():
            temporary.unlink()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--repository", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument(
        "--gpg-key-id",
        default=os.environ.get("CQENGINE_GPG_KEY_ID"),
        help="full signing-key fingerprint (or set CQENGINE_GPG_KEY_ID)",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    project = args.project.resolve()
    repository = (args.repository or project / "build/local-repository").absolute()
    key_id = args.gpg_key_id or ""
    if not FINGERPRINT.fullmatch(key_id):
        raise BundleError("CQENGINE_GPG_KEY_ID must be a 40-64 digit hexadecimal fingerprint")
    gpg = shutil.which("gpg")
    if not gpg:
        raise BundleError("gpg is required to create Central signatures")

    version, repository_inventory, qualification_mode, skipped_gates = verify_qualification(
        project, repository
    )
    names = primary_names(version)
    version_directory = repository / GROUP_PATH / ARTIFACT / version
    if not version_directory.is_dir() or version_directory.is_symlink():
        raise BundleError(f"missing qualified coordinate directory: {version_directory}")
    verify_local_inventory(version_directory, names)

    output = (args.output or project / f"build/central/{ARTIFACT}-{version}-central-bundle.zip").resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    if output == repository or repository in output.parents:
        raise BundleError("Central output must remain outside the qualified local repository")

    with tempfile.TemporaryDirectory(prefix="cqengine-central-", dir=output.parent) as temporary:
        staging = Path(temporary)
        target_directory = staging / GROUP_PATH / ARTIFACT / version
        target_directory.mkdir(parents=True)
        signing_identities: set[tuple[str, str]] = set()
        inventory: list[str] = []
        expected_zip_hashes: dict[str, str] = {}
        for name in names:
            source = version_directory / name
            target = target_directory / name
            relative_source = source.relative_to(repository).as_posix()
            qualified_sha256 = repository_inventory[relative_source][0]
            shutil.copyfile(source, target)
            if digest(target, "sha256") != qualified_sha256:
                raise BundleError(f"qualified artifact changed while copying: {name}")
            for extension in CHECKSUMS:
                source_sidecar = version_directory / f"{name}.{extension}"
                sidecar = target_directory / f"{name}.{extension}"
                shutil.copyfile(source_sidecar, sidecar)
                relative_sidecar = source_sidecar.relative_to(repository).as_posix()
                if digest(sidecar, "sha256") != repository_inventory[relative_sidecar][0]:
                    raise BundleError(f"qualified checksum changed while copying: {source_sidecar.name}")
            signature = target_directory / f"{name}.asc"
            signing_identities.add(gpg_sign(gpg, key_id, target, signature))
            if digest(target, "sha256") != qualified_sha256:
                raise BundleError(f"qualified artifact changed while signing: {name}")
            inventory.append(f"{digest(target, 'sha256')}  {target.relative_to(staging).as_posix()}")
            for bundled in [target, signature, *(target_directory / f"{name}.{ext}" for ext in CHECKSUMS)]:
                expected_zip_hashes[bundled.relative_to(staging).as_posix()] = digest(bundled, "sha256")

        if len(signing_identities) != 1:
            raise BundleError(f"bundle contains signatures from multiple keys: {sorted(signing_identities)}")
        signer, primary_signer = signing_identities.pop()
        if key_id.upper() not in {signer, primary_signer}:
            raise BundleError(
                f"signature fingerprints {signer}/{primary_signer} do not match requested key {key_id.upper()}"
            )

        temporary_zip = output.with_suffix(output.suffix + ".tmp")
        if temporary_zip.exists() or temporary_zip.is_symlink():
            temporary_zip.unlink()
        write_zip(staging, temporary_zip)
        verify_zip(temporary_zip, expected_zip_hashes)
        os.replace(temporary_zip, output)

    bundle_sha256 = digest(output, "sha256")
    atomic_write(output.with_suffix(output.suffix + ".sha256"), bundle_sha256 + "\n")
    atomic_write(
        output.with_suffix(output.suffix + ".inventory.txt"),
        "\n".join(
            [
                "formatVersion=1",
                f"coordinate=io.github.shuaibrao:cqengine:{version}",
                f"qualificationMode={qualification_mode}",
                f"skippedReleaseGates={skipped_gates}",
                f"signingFingerprint={signer}",
                f"primarySigningFingerprint={primary_signer}",
                f"bundleSha256={bundle_sha256}",
                *sorted(inventory),
                "",
            ]
        ),
    )
    print(output)
    print(f"sha256={bundle_sha256}")
    print(f"signingFingerprint={signer}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (BundleError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print(f"Central bundle preparation failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error
