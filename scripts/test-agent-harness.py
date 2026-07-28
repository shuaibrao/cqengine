#!/usr/bin/env python3
"""Focused tests for canonical agent configuration, mirrors and hook schemas."""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import tomllib
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def run(command: list[str], root: Path = ROOT, input_value: dict | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=root,
        input=json.dumps(input_value) if input_value is not None else None,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        env={**__import__("os").environ, "CLAUDE_PROJECT_DIR": str(root)},
    )


def hook(name: str, harness: str, payload: dict) -> tuple[subprocess.CompletedProcess[str], dict]:
    result = run([sys.executable, f".agent/hooks/{name}.py", "--harness", harness], input_value=payload)
    require(result.returncode == 0, f"{name}/{harness} failed: {result.stderr}")
    try:
        output = json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise AssertionError(f"{name}/{harness} emitted invalid JSON: {result.stdout}") from error
    require(isinstance(output, dict), f"{name}/{harness} output is not an object")
    return result, output


def test_configs() -> None:
    for path in (".cursor/hooks.json", ".claude/settings.json", ".codex/hooks.json"):
        with (ROOT / path).open(encoding="utf-8") as handle:
            require(isinstance(json.load(handle), dict), f"invalid JSON object: {path}")
    with (ROOT / ".codex/config.toml").open("rb") as handle:
        require(tomllib.load(handle)["features"]["hooks"] is True, "Codex hooks are disabled")
    require((ROOT / "CLAUDE.md").read_text(encoding="utf-8") == "@AGENTS.md\n", "CLAUDE.md drift")


def test_hooks() -> None:
    denied = (
        "git push --force origin main",
        "git push -f origin main",
        "rm -rf src",
        "rm -fr ./documentation",
        "rm -Rf benchmarks/results",
    )
    # A lease-checked push refuses to discard commits the local clone has not seen, so it stays
    # available for the history rewrites this project performs deliberately.
    allowed = (
        "git push --force-with-lease origin main",
        "git push --force-if-includes origin main",
        "git push origin main",
        "rm -rf build",
    )
    for harness in ("cursor", "claudecode", "codex"):
        for command in denied:
            payload = {"command": command} if harness == "cursor" else {
                "tool_name": "Bash", "tool_input": {"command": command}
            }
            _, output = hook("shell_exec_guard", harness, payload)
            if harness == "cursor":
                require(output.get("permission") == "deny", f"{harness} allowed {command}")
            else:
                decision = output.get("hookSpecificOutput", {}).get("permissionDecision")
                require(decision == "deny", f"{harness} allowed {command}")

        for command in allowed:
            payload = {"command": command} if harness == "cursor" else {
                "tool_name": "Bash", "tool_input": {"command": command}
            }
            _, safe = hook("shell_exec_guard", harness, payload)
            require(safe == {}, f"{harness} blocked {command}")
        _, context = hook("session_init", harness, {"cwd": str(ROOT)})
        require("CQEngine" in json.dumps(context), f"{harness} session context missing")
        _, stopped = hook("self_improvement_check", harness, {"stop_hook_active": True})
        require(stopped == {}, f"{harness} stop re-entry was not bounded")


def test_mirror_sync() -> None:
    result = run([sys.executable, "scripts/sync-agent-config.py", "--check"])
    require(result.returncode == 0, result.stderr or result.stdout)
    with tempfile.TemporaryDirectory(prefix="cqengine-agent-sync-") as temporary:
        root = Path(temporary)
        shutil.copytree(ROOT / ".agent", root / ".agent")
        (root / "scripts").mkdir()
        shutil.copy2(ROOT / "scripts/sync-agent-config.py", root / "scripts/sync-agent-config.py")
        generated = run([sys.executable, "scripts/sync-agent-config.py"], root)
        require(generated.returncode == 0, generated.stderr)
        require(run([sys.executable, "scripts/sync-agent-config.py", "--check"], root).returncode == 0,
                "fresh mirrors are not idempotent")
        mirror = next((root / ".codex/rules").glob("*.md"))
        mirror.write_text("drift\n", encoding="utf-8")
        require(run([sys.executable, "scripts/sync-agent-config.py", "--check"], root).returncode != 0,
                "modified mirror was not detected")
        require(run([sys.executable, "scripts/sync-agent-config.py"], root).returncode == 0,
                "mirror repair failed")
        orphan = root / ".cursor/skills/orphan/SKILL.md"
        orphan.parent.mkdir(parents=True)
        orphan.write_text("orphan\n", encoding="utf-8")
        require(run([sys.executable, "scripts/sync-agent-config.py", "--check"], root).returncode != 0,
                "orphan mirror was not detected")
        require(run([sys.executable, "scripts/sync-agent-config.py"], root).returncode == 0,
                "orphan pruning failed")
        require(not orphan.exists(), "orphan mirror remains")


def test_scope() -> None:
    omitted = (
        ".agent/hooks/memory_session_start.py",
        ".agent/hooks/oms_pretool_guard.py",
        ".agent/hooks/allocation_check.py",
        ".agent/skills/memory-ops",
        ".agent/skills/dream-mode",
    )
    for path in omitted:
        require(not (ROOT / path).exists(), f"out-of-scope harness content exists: {path}")


def main() -> None:
    test_configs()
    test_hooks()
    test_mirror_sync()
    test_scope()
    print("agent-harness=verified")


if __name__ == "__main__":
    main()
