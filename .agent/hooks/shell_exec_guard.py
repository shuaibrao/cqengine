"""Block destructive shell commands consistently across harnesses."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _hook_io import HARNESS, emit, emit_denial, read_input, shell_command
from _patterns import destructive_reason


def main() -> None:
    data = read_input()
    if HARNESS != "cursor" and data.get("tool_name", "") != "Bash":
        emit()
        return
    reason = destructive_reason(shell_command(data))
    emit_denial(reason) if reason else emit()


if __name__ == "__main__":
    main()
