"""Destructive shell-command patterns shared by all harnesses."""
from __future__ import annotations

import re


PROTECTED_ROOTS = (
    "src", "gradle", "scripts", "documentation", "benchmarks", "consumer-tests",
    "config", "third-party-licenses", "archive", ".agent", ".cursor", ".claude", ".codex",
)

FORCE_PUSH = re.compile(r"\bgit\s+push\b[^|;&]*(?:--force(?!-with-lease|-if-includes)|-f(?:\s|$))")
RECURSIVE_FORCE_REMOVE = re.compile(
    r"\brm\s+-(?=[rRfF]*[rR])(?=[rRfF]*[fF])[rRfF]+\s+([^|;&]+)",
)


def destructive_reason(command: str) -> str | None:
    if FORCE_PUSH.search(command):
        return "Force-pushing is blocked because it rewrites remote history."
    for match in RECURSIVE_FORCE_REMOVE.finditer(command):
        for raw_path in match.group(1).split():
            path = raw_path.strip("'\"").removeprefix("./").rstrip("/")
            if any(path == root or path.startswith(root + "/") for root in PROTECTED_ROOTS):
                return f"Recursive forced deletion of protected project path '{path}' is blocked."
    return None
