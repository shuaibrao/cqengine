#!/usr/bin/env python3
"""Sonatype Central Portal publication client.

The Portal token is read once from the environment and lives only in an in-process
Authorization header. Nothing here reaches a child process, so the credential is never
exposed through an argument list, an inherited environment or a curl configuration file.
"""

from __future__ import annotations

import http.client
import io
import json
import os
import re
import ssl
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Callable, Iterable, Sequence

PORTAL_ROOT = "https://central.sonatype.com/api/v1/publisher/"
PUBLICATION_COORDINATE = "pkg:maven/io.github.shuaibrao/cqengine@{version}"

DEPLOYMENT_ID = re.compile(r"\A[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\Z")
DEPLOYMENT_NAME = re.compile(r"\A[A-Za-z0-9._-]{3,100}\Z")
RELEASE_VERSION = re.compile(r"\A[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z.-]*)?\Z")

SUPPORTED_PUBLISHING_TYPES = ("USER_MANAGED", "AUTOMATIC")

CONNECT_TIMEOUT_SECONDS = 120

USAGE = """usage:
  scripts/central-portal.py upload <bundle.zip> <deployment-name> <USER_MANAGED|AUTOMATIC>
  scripts/central-portal.py status <deployment-id>
  scripts/central-portal.py publish <deployment-id> <same-deployment-id> <version>
  scripts/central-portal.py drop <deployment-id> <same-deployment-id>

Set CENTRAL_TOKEN_USERNAME and CENTRAL_TOKEN_PASSWORD in the process environment.
"""

# A transport receives a fully-formed request and returns the decoded response body.
Transport = Callable[[str, str, dict, bytes | None], str]


class PortalError(RuntimeError):
    """A publication request failed or returned an unusable response."""


def _usage() -> int:
    sys.stderr.write(USAGE)
    return 2


def _fail(message: str) -> None:
    raise PortalError(message)


def _read_authorization() -> str:
    """Consume the Portal token from the environment and return the header value."""
    username = os.environ.pop("CENTRAL_TOKEN_USERNAME", "")
    password = os.environ.pop("CENTRAL_TOKEN_PASSWORD", "")
    if not username or not password:
        _fail("Central Portal token credentials are required")
    if any(character in value for value in (username, password) for character in "\r\n"):
        _fail("Central Portal token credentials must not contain line breaks")
    import base64

    return "Bearer " + base64.b64encode(f"{username}:{password}".encode()).decode("ascii")


def _tls_context() -> ssl.SSLContext:
    context = ssl.create_default_context()
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.check_hostname = True
    context.verify_mode = ssl.CERT_REQUIRED
    return context


def _https_transport(method: str, url: str, headers: dict, body: bytes | None) -> str:
    # Reject anything that is not the Portal over HTTPS before a credential is attached.
    if not url.startswith(PORTAL_ROOT):
        _fail(f"refusing to send credentials to a non-Portal endpoint: {url}")
    # An explicit empty proxy map keeps an ambient proxy variable from intercepting the token.
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        urllib.request.HTTPSHandler(context=_tls_context()),
    )
    request = urllib.request.Request(url, data=body, method=method)
    for name, value in headers.items():
        request.add_header(name, value)
    try:
        with opener.open(request, timeout=CONNECT_TIMEOUT_SECONDS) as response:
            return response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace").strip()
        _fail(f"Central Portal returned HTTP {error.code}: {detail}")
    except (urllib.error.URLError, http.client.HTTPException, OSError) as error:
        _fail(f"Central Portal request failed: {error}")
    raise AssertionError("unreachable")


def _multipart_bundle(bundle: Path) -> tuple[bytes, str]:
    """Encode the bundle as a single multipart/form-data part."""
    boundary = "cqengine" + os.urandom(16).hex()
    buffer = io.BytesIO()
    buffer.write(f"--{boundary}\r\n".encode())
    buffer.write(
        f'Content-Disposition: form-data; name="bundle"; filename="{bundle.name}"\r\n'.encode()
    )
    buffer.write(b"Content-Type: application/octet-stream\r\n\r\n")
    buffer.write(bundle.read_bytes())
    buffer.write(f"\r\n--{boundary}--\r\n".encode())
    return buffer.getvalue(), f"multipart/form-data; boundary={boundary}"


def _require_deployment_id(value: str) -> str:
    if not DEPLOYMENT_ID.match(value):
        _fail("a Central deployment ID is required")
    return value


def _upload(arguments: Sequence[str], authorization: str, transport: Transport) -> str:
    bundle_argument, deployment_name, publishing_type = arguments
    bundle = Path(bundle_argument)
    if bundle.is_symlink() or not bundle.is_file() or bundle.suffix != ".zip":
        _fail("upload requires a regular zip bundle")
    if not DEPLOYMENT_NAME.match(deployment_name):
        _fail("deployment name contains unsupported characters")
    if publishing_type not in SUPPORTED_PUBLISHING_TYPES:
        _fail("publication tooling permits USER_MANAGED or AUTOMATIC Central deployments only")

    body, content_type = _multipart_bundle(bundle.resolve(strict=True))
    response = transport(
        "POST",
        f"{PORTAL_ROOT}upload?name={deployment_name}&publishingType={publishing_type}",
        {"Authorization": authorization, "Content-Type": content_type},
        body,
    )
    deployment_id = response.strip()
    if not DEPLOYMENT_ID.match(deployment_id):
        _fail("Central Portal returned an invalid deployment ID")
    return deployment_id


def _status(deployment_id: str, authorization: str, transport: Transport) -> str:
    return transport(
        "POST",
        f"{PORTAL_ROOT}status?id={deployment_id}",
        {"Authorization": authorization},
        None,
    )


def _publish(arguments: Sequence[str], authorization: str, transport: Transport) -> str:
    deployment_id, confirmation, version = arguments
    if not DEPLOYMENT_ID.match(deployment_id) or deployment_id != confirmation or not RELEASE_VERSION.match(version):
        _fail("publish requires the deployment ID twice and the exact release version")

    # Confirm the Portal validated exactly the coordinate being released before requesting publication.
    try:
        state = json.loads(_status(deployment_id, authorization, transport))
    except json.JSONDecodeError:
        _fail("Central Portal returned an unreadable deployment status")
    if state.get("deploymentId") != deployment_id:
        _fail("different deployment")
    if state.get("deploymentState") != "VALIDATED":
        _fail("deployment is not validated")
    if state.get("purls") != [PUBLICATION_COORDINATE.format(version=version)]:
        _fail("different coordinate")

    transport("POST", f"{PORTAL_ROOT}deployment/{deployment_id}", {"Authorization": authorization}, None)
    return f"publish requested for deployment {deployment_id}"


def _drop(arguments: Sequence[str], authorization: str, transport: Transport) -> str:
    deployment_id, confirmation = arguments
    if not DEPLOYMENT_ID.match(deployment_id) or deployment_id != confirmation:
        _fail("drop requires the deployment ID twice as confirmation")
    transport("DELETE", f"{PORTAL_ROOT}deployment/{deployment_id}", {"Authorization": authorization}, None)
    return f"drop requested for deployment {deployment_id}"


# Each command maps to its handler and its exact required argument count.
COMMANDS = {
    "upload": (_upload, 3),
    "status": (None, 1),
    "publish": (_publish, 3),
    "drop": (_drop, 2),
}


def main(argv: Iterable[str] | None = None, transport: Transport | None = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    if len(arguments) < 2:
        return _usage()

    command, operands = arguments[0], arguments[1:]
    if command not in COMMANDS:
        return _usage()
    handler, expected = COMMANDS[command]
    if len(operands) != expected:
        return _usage()
    if command in ("status", "publish", "drop") and not DEPLOYMENT_ID.match(operands[0]):
        return _usage()

    try:
        authorization = _read_authorization()
        send = transport or _https_transport
        if command == "status":
            sys.stdout.write(_status(_require_deployment_id(operands[0]), authorization, send).rstrip("\n") + "\n")
        else:
            sys.stdout.write(handler(operands, authorization, send) + "\n")
    except PortalError as error:
        sys.stderr.write(f"{error}\n")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
