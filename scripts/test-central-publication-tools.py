#!/usr/bin/env python3
"""Focused regression tests for preparation of a Central Portal deployment bundle."""

from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import zipfile


FINGERPRINT = "A" * 40
DEPLOYMENT_ID = "12345678-1234-1234-1234-123456789abc"
CHECKSUMS = {
    "md5": "md5",
    "sha1": "sha1",
    "sha256": "sha256",
    "sha512": "sha512",
}
# The exact set of files a Central deployment publishes, ordered as the bundle stores them.
PUBLISHED_ARTIFACT_SUFFIXES = (".jar", "-sources.jar", "-javadoc.jar", ".pom", ".module")


def write(path: Path, value: str | bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(value, bytes):
        path.write_bytes(value)
    else:
        path.write_text(value, encoding="utf-8")


def digest(path: Path, algorithm: str) -> str:
    return hashlib.new(algorithm, path.read_bytes()).hexdigest()


def require(condition: bool, message: object) -> None:
    if not condition:
        raise RuntimeError(str(message))


def load_portal_client():
    path = Path(__file__).resolve().with_name("central-portal.py")
    specification = importlib.util.spec_from_file_location("central_portal", path)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


class RecordingPortal:
    """Stands in for the Portal so requests can be asserted without reaching the network."""

    def __init__(self) -> None:
        self.requests: list[dict] = []

    def __call__(self, method: str, url: str, headers: dict, body: bytes | None) -> str:
        if not url.startswith("https://central.sonatype.com/api/v1/publisher/"):
            raise AssertionError("unexpected Portal endpoint: " + url)
        self.requests.append({"method": method, "url": url, "headers": dict(headers), "body": body})
        if "/upload?" in url:
            return DEPLOYMENT_ID
        if "/status?id=" in url:
            return json.dumps(
                {
                    "deploymentId": DEPLOYMENT_ID,
                    "deploymentState": "VALIDATED",
                    "purls": ["pkg:maven/io.github.shuaibrao/cqengine@4.0.0-rc.1"],
                }
            )
        if "/deployment/" in url:
            return ""
        raise AssertionError("unhandled Portal endpoint: " + url)


def run_portal(client, arguments: list[str], transport) -> tuple[int, str]:
    """Invoke the client with fresh credentials, since a run consumes them from the environment."""
    os.environ["CENTRAL_TOKEN_USERNAME"] = "token-user"
    os.environ["CENTRAL_TOKEN_PASSWORD"] = "token-password"
    stdout = io.StringIO()
    stderr = io.StringIO()
    with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
        status = client.main(arguments, transport)
    # A completed run must leave no credential behind for any later child process to inherit.
    require("CENTRAL_TOKEN_USERNAME" not in os.environ, "token username survived the run")
    require("CENTRAL_TOKEN_PASSWORD" not in os.environ, "token password survived the run")
    return status, stdout.getvalue() + stderr.getvalue()


def create_fixture(
    root: Path,
    tool: Path,
    qualification_mode: str = "local-checkout-shared-caches",
    skipped_release_gates: str = "none",
) -> tuple[Path, Path]:
    project = root / "project"
    project.mkdir()
    subprocess.run(["git", "init", "--quiet", "--initial-branch=main", str(project)], check=True)
    write(project / "gradle.properties", "group=io.github.shuaibrao\nversion=4.0.0-rc.1\n")
    write(project / "tracked.txt", "fixture\n")
    subprocess.run(["git", "-C", str(project), "add", "gradle.properties", "tracked.txt"], check=True)
    subprocess.run(
        [
            "git",
            "-c",
            "user.name=CQEngine test",
            "-c",
            "user.email=cqengine-test@invalid.example",
            "-c",
            "commit.gpgSign=false",
            "-C",
            str(project),
            "commit",
            "--quiet",
            "-m",
            "Create fixture",
        ],
        check=True,
    )
    commit = subprocess.run(
        ["git", "-C", str(project), "rev-parse", "HEAD"],
        check=True,
        stdout=subprocess.PIPE,
        text=True,
    ).stdout.strip()
    tree = subprocess.run(
        ["git", "-C", str(project), "rev-parse", "HEAD^{tree}"],
        check=True,
        stdout=subprocess.PIPE,
        text=True,
    ).stdout.strip()

    version = "4.0.0-rc.1"
    stem = f"cqengine-{version}"
    names = [f"{stem}{suffix}" for suffix in PUBLISHED_ARTIFACT_SUFFIXES]
    version_directory = (
        project / "build/local-repository/io/github/shuaibrao/cqengine" / version
    )
    for index, name in enumerate(names):
        primary = version_directory / name
        write(primary, f"qualified-{index}\n".encode())
        for extension, algorithm in CHECKSUMS.items():
            write(primary.with_name(f"{name}.{extension}"), digest(primary, algorithm) + "\n")

    repository = project / "build/local-repository"
    publication_inventory = project / "build/reports/publication/inventory.txt"
    inventory_lines = [f"coordinate=io.github.shuaibrao:cqengine:{version}"]
    for artifact in sorted(path for path in repository.rglob("*") if path.is_file()):
        inventory_lines.append(
            f"{digest(artifact, 'sha256')} {digest(artifact, 'sha512')}  "
            f"{artifact.relative_to(repository).as_posix()}"
        )
    write(publication_inventory, "\n".join([*inventory_lines, ""]))

    readiness = project / "build/local-release-evidence/qualification/local-readiness-manifest.txt"
    write(
        readiness,
        "\n".join(
            [
                "formatVersion=1",
                f"coordinate=io.github.shuaibrao:cqengine:{version}",
                f"sourceCommit={commit}",
                f"sourceTree={tree}",
                "command=./gradlew qualifyLocally",
                f"qualificationMode={qualification_mode}",
                f"skippedReleaseGates={skipped_release_gates}",
                f"{digest(publication_inventory, 'sha256')} "
                f"{digest(publication_inventory, 'sha512')}  root:reports/publication/inventory.txt",
                "",
            ]
        ),
    )
    write(
        readiness.with_name("qualification-completion.properties"),
        "\n".join(
            [
                "formatVersion=1",
                "status=passed",
                f"qualificationMode={qualification_mode}",
                f"sourceCommit={commit}",
                f"sourceTree={tree}",
                f"readinessManifestSha256={digest(readiness, 'sha256')}",
                f"readinessManifestSha512={digest(readiness, 'sha512')}",
                "",
            ]
        ),
    )

    fake_bin = root / "bin"
    fake_gpg = fake_bin / "gpg"
    write(
        fake_gpg,
        """#!/usr/bin/env python3
import pathlib
import sys

arguments = sys.argv[1:]
if "--detach-sign" in arguments:
    output = pathlib.Path(arguments[arguments.index("--output") + 1])
    output.write_text("-----BEGIN PGP SIGNATURE-----\\nfixture\\n-----END PGP SIGNATURE-----\\n")
elif "--verify" in arguments:
    print("[GNUPG:] VALIDSIG " + "A" * 40 + " 2026-01-01 0 0 4 0 1 10 00 " + "A" * 40)
else:
    raise SystemExit(3)
""",
    )
    fake_gpg.chmod(fake_gpg.stat().st_mode | stat.S_IXUSR)
    return project, version_directory / names[0]


def main() -> None:
    tool = Path(__file__).resolve().with_name("prepare-central-bundle.py")
    with tempfile.TemporaryDirectory(prefix="cqengine-central-test-") as temporary:
        root = Path(temporary)
        project, thin_jar = create_fixture(root, tool)
        output = root / "candidate.zip"
        environment = os.environ.copy()
        environment["PATH"] = f"{root / 'bin'}:{environment['PATH']}"
        environment["CQENGINE_GPG_KEY_ID"] = FINGERPRINT
        subprocess.run(
            [str(tool), "--project", str(project), "--output", str(output)],
            check=True,
            env=environment,
            stdout=subprocess.DEVNULL,
        )
        with zipfile.ZipFile(output) as bundle:
            names = bundle.namelist()
            # Every published file carries a detached signature and the four Central checksums,
            # so the bundle size follows the artifact inventory instead of a fixed count.
            expected_entries = len(PUBLISHED_ARTIFACT_SUFFIXES) * (2 + len(CHECKSUMS))
            require(len(names) == expected_entries, f"expected {expected_entries} entries: {names}")
            require(names == sorted(names), names)
            require(
                all(name.startswith("io/github/shuaibrao/cqengine/4.0.0-rc.1/") for name in names),
                names,
            )
            require(not any("maven-metadata.xml" in name for name in names), names)
        inventory = output.with_suffix(".zip.inventory.txt").read_text(encoding="utf-8")
        require(f"signingFingerprint={FINGERPRINT}" in inventory, inventory)
        require(f"bundleSha256={digest(output, 'sha256')}" in inventory, inventory)
        require("qualificationMode=local-checkout-shared-caches" in inventory, inventory)
        require("skippedReleaseGates=none" in inventory, inventory)

        # Skipped gates must reach the bundle inventory rather than being silently dropped.
        partial_root = root / "partial-gates"
        partial_root.mkdir()
        partial_project, _ = create_fixture(
            partial_root, tool, skipped_release_gates="centralPublicationToolsTest"
        )
        partial_output = partial_root / "candidate.zip"
        partial_environment = environment.copy()
        partial_environment["PATH"] = f"{partial_root / 'bin'}:{os.environ['PATH']}"
        subprocess.run(
            [str(tool), "--project", str(partial_project), "--output", str(partial_output)],
            check=True,
            env=partial_environment,
            stdout=subprocess.DEVNULL,
        )
        partial_inventory = partial_output.with_suffix(".zip.inventory.txt").read_text(encoding="utf-8")
        require(
            "skippedReleaseGates=centralPublicationToolsTest" in partial_inventory, partial_inventory
        )

        # A completion record must not be pairable with readiness evidence from a different qualification.
        mismatch_root = root / "mode-mismatch"
        mismatch_root.mkdir()
        mismatch_project, _ = create_fixture(mismatch_root, tool)
        completion = (
            mismatch_project
            / "build/local-release-evidence/qualification/qualification-completion.properties"
        )
        completion.write_text(
            completion.read_text(encoding="utf-8").replace(
                "qualificationMode=local-checkout-shared-caches",
                "qualificationMode=detached-clean-room",
            ),
            encoding="utf-8",
        )
        mismatch_environment = environment.copy()
        mismatch_environment["PATH"] = f"{mismatch_root / 'bin'}:{os.environ['PATH']}"
        mismatch = subprocess.run(
            [str(tool), "--project", str(mismatch_project), "--output", str(mismatch_root / "x.zip")],
            env=mismatch_environment,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )
        require(mismatch.returncode != 0, "a mismatched qualification mode was accepted")
        require("qualification mode" in mismatch.stderr, mismatch.stderr)

        portal = load_portal_client()
        recorder = RecordingPortal()

        status, uploaded = run_portal(
            portal, ["upload", str(output), "cqengine-4.0.0-rc.1", "USER_MANAGED"], recorder
        )
        require(status == 0 and uploaded.strip() == DEPLOYMENT_ID, uploaded)
        status, automatic = run_portal(
            portal, ["upload", str(output), "cqengine-4.0.0-rc.1", "AUTOMATIC"], recorder
        )
        require(status == 0 and automatic.strip() == DEPLOYMENT_ID, automatic)
        # The uploaded bundle must be transmitted intact rather than by reference.
        require(output.read_bytes() in recorder.requests[0]["body"], "bundle content was not uploaded")

        status, _ = run_portal(
            portal, ["upload", str(output), "cqengine-4.0.0-rc.1", "PORTAL_API"], recorder
        )
        require(status != 0, "an unsupported Central publishing type was accepted")

        status, status_result = run_portal(portal, ["status", DEPLOYMENT_ID], recorder)
        require(status == 0 and '"deploymentState": "VALIDATED"' in status_result, status_result)

        status, _ = run_portal(portal, ["publish", DEPLOYMENT_ID, DEPLOYMENT_ID, "4.0.0-rc.1"], recorder)
        require(status == 0, "publish rejected a validated deployment")
        status, _ = run_portal(portal, ["drop", DEPLOYMENT_ID, DEPLOYMENT_ID], recorder)
        require(status == 0, "drop rejected a confirmed deployment")

        status, _ = run_portal(portal, ["publish", DEPLOYMENT_ID, DEPLOYMENT_ID, "4.0.1"], recorder)
        require(status != 0, "publish accepted a different coordinate")
        status, _ = run_portal(portal, ["drop", DEPLOYMENT_ID, "87654321-4321-4321-4321-cba987654321"], recorder)
        require(status != 0, "drop accepted an unconfirmed deployment ID")

        # The token belongs in the Authorization header and must appear in no URL or request body.
        for request in recorder.requests:
            require(
                request["headers"].get("Authorization") == "Bearer dG9rZW4tdXNlcjp0b2tlbi1wYXNzd29yZA==",
                request["headers"],
            )
            require("token-user" not in request["url"] and "token-password" not in request["url"], request["url"])
            body = request["body"] or b""
            require(b"token-user" not in body and b"token-password" not in body, "credential reached a request body")

        # Credentials must never be sent anywhere but the Portal over HTTPS.
        for rejected in ("http://central.sonatype.com/api/v1/publisher/status?id=x", "https://example.invalid/upload"):
            try:
                portal._https_transport("POST", rejected, {"Authorization": "Bearer x"}, None)
            except portal.PortalError as error:
                require("non-Portal endpoint" in str(error), error)
            else:
                raise RuntimeError("a non-Portal endpoint was accepted: " + rejected)

        thin_jar.write_bytes(b"tampered\n")
        failed = subprocess.run(
            [str(tool), "--project", str(project), "--output", str(output)],
            env=environment,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )
        require(failed.returncode != 0, "tampered qualified artifact was accepted")
        require("differs from its inventory" in failed.stderr, failed.stderr)


if __name__ == "__main__":
    main()
