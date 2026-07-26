#!/usr/bin/env python3
"""Focused regression tests for preparation of a Central Portal deployment bundle."""

from __future__ import annotations

import hashlib
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


def create_fixture(root: Path, tool: Path) -> tuple[Path, Path]:
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
    names = [
        f"{stem}.jar",
        f"{stem}-all.jar",
        f"{stem}-sources.jar",
        f"{stem}-javadoc.jar",
        f"{stem}.pom",
        f"{stem}.module",
    ]
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
                "command=scripts/qualify-candidate.sh",
                "phaseIsolation=separate-fresh-source-and-gradle-homes",
                f"{digest(publication_inventory, 'sha256')} "
                f"{digest(publication_inventory, 'sha512')}  root:reports/publication/inventory.txt",
                "",
            ]
        ),
    )
    write(
        readiness.with_name("wrapper-completion.properties"),
        "\n".join(
            [
                "formatVersion=1",
                "status=passed",
                "validationStatus=passed",
                "wrapperExitCode=0",
                "repositoryMode=local",
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
    fake_curl = fake_bin / "curl"
    write(
        fake_curl,
        f"""#!/usr/bin/env python3
import json
import os
import pathlib
import sys

arguments = sys.argv[1:]
if arguments[0] != "--disable":
    raise SystemExit("curl configuration was not disabled first")
if "CENTRAL_TOKEN_USERNAME" in os.environ or "CENTRAL_TOKEN_PASSWORD" in os.environ:
    raise SystemExit("Central credentials leaked to curl")
configuration = pathlib.Path(arguments[arguments.index("--config") + 1]).read_text()
if "Authorization: Bearer dG9rZW4tdXNlcjp0b2tlbi1wYXNzd29yZA==" not in configuration:
    raise SystemExit("expected bearer token was not supplied through curl config")
url = arguments[-1]
if not url.startswith("https://central.sonatype.com/api/v1/publisher/"):
    raise SystemExit("unexpected Portal endpoint: " + url)
with pathlib.Path(os.environ["FAKE_CURL_LOG"]).open("a", encoding="utf-8") as log:
    log.write(json.dumps(arguments) + "\\n")
if "/upload?" in url:
    print("{DEPLOYMENT_ID}")
elif "/status?id=" in url:
    print(json.dumps({{
        "deploymentId": "{DEPLOYMENT_ID}",
        "deploymentState": "VALIDATED",
        "purls": ["pkg:maven/io.github.shuaibrao/cqengine@4.0.0-rc.1"],
    }}))
elif "/deployment/" not in url:
    raise SystemExit("unhandled Portal endpoint: " + url)
""",
    )
    fake_curl.chmod(fake_curl.stat().st_mode | stat.S_IXUSR)
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
            require(len(names) == 36, names)
            require(names == sorted(names), names)
            require(
                all(name.startswith("io/github/shuaibrao/cqengine/4.0.0-rc.1/") for name in names),
                names,
            )
            require(not any("maven-metadata.xml" in name for name in names), names)
        inventory = output.with_suffix(".zip.inventory.txt").read_text(encoding="utf-8")
        require(f"signingFingerprint={FINGERPRINT}" in inventory, inventory)
        require(f"bundleSha256={digest(output, 'sha256')}" in inventory, inventory)

        portal = Path(__file__).resolve().with_name("central-portal.sh")
        portal_environment = environment.copy()
        portal_environment.update(
            {
                "CENTRAL_TOKEN_USERNAME": "token-user",
                "CENTRAL_TOKEN_PASSWORD": "token-password",
                "FAKE_CURL_LOG": str(root / "curl.log"),
            }
        )
        uploaded = subprocess.run(
            [str(portal), "upload", str(output), "cqengine-4.0.0-rc.1", "USER_MANAGED"],
            check=True,
            env=portal_environment,
            stdout=subprocess.PIPE,
            text=True,
        ).stdout.strip()
        require(uploaded == DEPLOYMENT_ID, uploaded)
        automatic = subprocess.run(
            [str(portal), "upload", str(output), "cqengine-4.0.0-rc.1", "AUTOMATIC"],
            env=portal_environment,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )
        require(automatic.returncode != 0, "automatic Central publication was accepted")
        status_result = subprocess.run(
            [str(portal), "status", DEPLOYMENT_ID],
            check=True,
            env=portal_environment,
            stdout=subprocess.PIPE,
            text=True,
        ).stdout
        require('"deploymentState": "VALIDATED"' in status_result, status_result)
        subprocess.run(
            [str(portal), "publish", DEPLOYMENT_ID, DEPLOYMENT_ID, "4.0.0-rc.1"],
            check=True,
            env=portal_environment,
            stdout=subprocess.DEVNULL,
        )
        subprocess.run(
            [str(portal), "drop", DEPLOYMENT_ID, DEPLOYMENT_ID],
            check=True,
            env=portal_environment,
            stdout=subprocess.DEVNULL,
        )
        wrong_coordinate = subprocess.run(
            [str(portal), "publish", DEPLOYMENT_ID, DEPLOYMENT_ID, "4.0.1"],
            env=portal_environment,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )
        require(wrong_coordinate.returncode != 0, "publish accepted a different coordinate")
        curl_log = (root / "curl.log").read_text(encoding="utf-8")
        require("token-user" not in curl_log and "token-password" not in curl_log, curl_log)

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
