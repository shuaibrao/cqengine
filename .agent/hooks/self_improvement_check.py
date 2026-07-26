"""Prompt for documentation/rule follow-up when tracked worktree state changes."""
from __future__ import annotations

import hashlib
import re
import subprocess
import sys
from datetime import date, datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _hook_io import emit, emit_stop_followup, read_input, stop_reentry, workspace


def status(root: Path) -> str:
    try:
        result = subprocess.run(
            ["git", "status", "--short"], cwd=root, capture_output=True,
            text=True, encoding="utf-8", errors="replace", timeout=10,
        )
        return result.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def stale_lessons(root: Path, days: int = 14) -> list[str]:
    path = root / ".agent" / "rules" / "lessons-learned.md"
    try:
        content = path.read_text(encoding="utf-8")
    except OSError:
        return []
    pattern = re.compile(r"^- \[(\d{4}-\d{2}-\d{2})] \[status:pending-migration] \[target:([^]]+)] (.+)$", re.MULTILINE)
    result: list[str] = []
    for match in pattern.finditer(content):
        try:
            age = (date.today() - datetime.strptime(match.group(1), "%Y-%m-%d").date()).days
        except ValueError:
            continue
        if age > days:
            result.append(f"{match.group(1)} target:{match.group(2)} {match.group(3)[:100]}")
    return result


def main() -> None:
    data = read_input()
    if stop_reentry(data):
        emit()
        return
    root = workspace(data).resolve()
    current = status(root)
    session_id = str(data.get("session_id") or "anonymous")
    state_dir = Path(__file__).resolve().parent / "state"
    state_dir.mkdir(parents=True, exist_ok=True)
    marker = state_dir / f"{session_id}.status"
    digest = hashlib.sha256(current.encode("utf-8")).hexdigest()
    previous = marker.read_text(encoding="utf-8").strip() if marker.exists() else ""
    marker.write_text(digest, encoding="utf-8")
    stale = stale_lessons(root)
    if digest == previous and not stale:
        emit()
        return
    messages: list[str] = []
    if current:
        messages.append(
            "Review the AGENTS.md self-improvement checklist for the current tracked changes. "
            "Update only documentation, rules or skills whose contract actually changed.\n\n" + current
        )
    if stale:
        messages.append("Resolve aged pending lessons:\n" + "\n".join(f"- {item}" for item in stale))
    if messages:
        emit_stop_followup("\n\n---\n\n".join(messages))
    else:
        emit()


if __name__ == "__main__":
    main()
