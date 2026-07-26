"""Cross-harness JSON input and output helpers."""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any


VALID_HARNESSES = frozenset({"cursor", "claudecode", "codex"})


def _harness() -> str:
    args = sys.argv[1:]
    value: str | None = None
    for index, argument in enumerate(args):
        if argument == "--harness" and index + 1 < len(args):
            value = args[index + 1]
            break
        if argument.startswith("--harness="):
            value = argument.partition("=")[2]
            break
    value = (value or "").strip().lower()
    if value not in VALID_HARNESSES:
        sys.stderr.write("pass --harness cursor|claudecode|codex\n")
        raise SystemExit(2)
    return value


HARNESS = _harness()


def read_input() -> dict[str, Any]:
    try:
        value = json.load(sys.stdin)
    except (json.JSONDecodeError, OSError):
        return {}
    return value if isinstance(value, dict) else {}


def emit(value: dict[str, Any] | None = None) -> None:
    json.dump(value or {}, sys.stdout, separators=(",", ":"))
    sys.stdout.write("\n")
    sys.stdout.flush()


def workspace(data: dict[str, Any]) -> Path:
    roots = data.get("workspace_roots")
    if isinstance(roots, list) and roots and isinstance(roots[0], str):
        return Path(roots[0])
    project = os.environ.get("CLAUDE_PROJECT_DIR")
    if project:
        return Path(project)
    current = data.get("cwd")
    return Path(current) if isinstance(current, str) and current else Path.cwd()


def tool_input(data: dict[str, Any]) -> dict[str, Any]:
    value = data.get("tool_input", data.get("input", {}))
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except json.JSONDecodeError:
            return {"_raw": value}
    return value if isinstance(value, dict) else {}


def shell_command(data: dict[str, Any]) -> str:
    direct = data.get("command")
    if isinstance(direct, str):
        return direct
    return str(tool_input(data).get("command") or "")


def stop_reentry(data: dict[str, Any]) -> bool:
    if data.get("stop_hook_active") is True:
        return True
    try:
        return int(data.get("loop_count", 0) or 0) > 0
    except (TypeError, ValueError):
        return False


def emit_session_context(context: str) -> None:
    if HARNESS == "cursor":
        emit({"additional_context": context})
    else:
        emit({"hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext": context}})


def emit_denial(reason: str) -> None:
    if HARNESS == "cursor":
        emit({"permission": "deny", "agent_message": reason})
    else:
        emit({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
            "additionalContext": reason,
        }})


def emit_stop_followup(message: str) -> None:
    if HARNESS == "cursor":
        emit({"followup_message": message})
    else:
        emit({"decision": "block", "reason": message})
