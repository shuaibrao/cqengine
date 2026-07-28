---
description: Cross-platform correctness for Python, Gradle, hooks and filesystem or subprocess handling.
alwaysApply: false
---

# Windows portability

- Specify UTF-8 for Python text I/O and subprocess decoding; use `errors="replace"` for external text.
- Use `Path`/`Files` for local paths and forward slashes for archive, URL, container or Maven-repository paths.
- Do not assume POSIX permissions, `/proc`, symlinks or `python3` without a documented platform boundary.
- Close persistence, streams and native handles before deleting or renaming their files. POSIX unlinks an open file happily, so a leaked handle stays invisible on Linux and surfaces on Windows as a failed delete.
- Build executable paths through a platform-aware name. JDK and wrapper tools need the `.exe` or `.bat` suffix on Windows, and a hardcoded POSIX default such as `/usr/bin/git` silently resolves against the project directory, so both forms fail Gradle task-input validation before the task can report anything useful.
- Shared hook command strings use `python3`; Windows setup must provide a real `python3` shim because JSON cannot select an interpreter by OS.
- Keep platform-native tests explicit and report unsupported platforms rather than silently skipping claimed behavior.

## PowerShell gates

These defaults silently weaken fail-closed checks; each one has already produced a real gate bypass.

- `-eq`/`-ne`/`-like` ignore case. Use `-ceq`/`-cne`/`-clike` whenever the check depends on case, such as git's lowercase `ls-files -v` tags for assume-unchanged and skip-worktree entries.
- Native-command stderr becomes an `ErrorRecord` under `$ErrorActionPreference = 'Stop'`, including with `2>$null`. Route every native invocation that may legitimately write stderr through a helper that restores the preference afterward.
- `Set-StrictMode` rejects `.Count` on a scalar, so a pipeline that yields one item breaks a length check. Wrap pipeline results in `@()`.
- Resolve system executables by absolute path when the script narrows `PATH`; `cmd.exe`, `reg.exe` and `powershell.exe` are otherwise unreachable.
- Emit progress with `Write-Host`, not `Write-Output`: inside a function the latter joins the return value.
- Fixtures committed under an ambient `core.autocrlf=true` read back dirty once the script disables system and global Git configuration. Write fixture content with LF.
