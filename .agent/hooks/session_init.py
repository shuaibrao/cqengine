"""Inject concise CQEngine context at session start."""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _hook_io import emit_session_context, read_input, workspace


def branch(root: Path) -> str:
    try:
        result = subprocess.run(
            ["git", "branch", "--show-current"], cwd=root, capture_output=True,
            text=True, encoding="utf-8", errors="replace", timeout=5,
        )
        return result.stdout.strip() or "detached"
    except (OSError, subprocess.SubprocessError):
        return "unknown"


def main() -> None:
    data = read_input()
    root = workspace(data)
    warnings: list[str] = []
    if shutil.which("gh") is None:
        warnings.append("gh CLI unavailable")
    if shutil.which("node") is None:
        warnings.append("Node.js unavailable for documentation/source lookup skills")
    warning = "\nTool warnings: " + "; ".join(warnings) if warnings else ""
    emit_session_context(
        f"Branch: {branch(root)}\n\n"
        "Project: CQEngine 4.0 continuation; Java 21 bytecode, verified on Java 21 and 25.\n"
        "Priorities: public API and persistence compatibility, deterministic cleanup, strict binary dependency verification, and evidence-based performance claims.\n"
        "Long-running qualification requires explicit user approval."
        + warning
    )


if __name__ == "__main__":
    main()
