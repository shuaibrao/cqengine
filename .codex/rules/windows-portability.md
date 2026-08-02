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
- An `orElse` default behind an environment variable that something always sets is untested code. Removing whatever set it makes every such default live at once, so audit them in the same change rather than discovering them one failed run at a time.
- Shared hook command strings use `python3`; Windows setup must provide a real `python3` shim because JSON cannot select an interpreter by OS.
- Keep platform-native tests explicit and report unsupported platforms rather than silently skipping claimed behavior.

## PowerShell gates

These defaults silently weaken fail-closed checks; each one has already produced a real gate bypass.

- `-eq`/`-ne`/`-like` ignore case. Use `-ceq`/`-cne`/`-clike` whenever the check depends on case, such as git's lowercase `ls-files -v` tags for assume-unchanged and skip-worktree entries.
- Native-command stderr becomes an `ErrorRecord` under `$ErrorActionPreference = 'Stop'`, including with `2>$null`. Route every native invocation that may legitimately write stderr through a helper that restores the preference afterward.
- `Set-StrictMode` rejects `.Count` on a scalar, so a pipeline that yields one item breaks a length check. Wrap pipeline results in `@()`.
- Resolve system executables by absolute path when the script narrows `PATH`; `cmd.exe`, `reg.exe` and `powershell.exe` are otherwise unreachable.
- MSYS tools inherit POSIX path parsing. GNU `tar` reads `C:\path` as a `host:path` remote spec and tries to resolve the drive letter as a hostname, so pass `--force-local` and convert separators to `/`; `--force-local` alone still fails on backslashes.
- MSYS `gpg` treats a `GNUPGHOME` that does not start with `/` as relative and prepends the working directory, so `D:/...` and `D:\...` spellings both fail with `keyblock resource ... No such file or directory`; give it the `cygpath -u` POSIX form.
- MSYS bash rewrites POSIX-looking environment values back to `D:/` form whenever it launches a native program, so a POSIX path that must survive a bash → native → MSYS process chain (for example `GNUPGHOME` through Python back into `gpg`) needs `MSYS2_ENV_CONV_EXCL=<NAME>` to cross the native boundary verbatim.
- Python `subprocess` text-mode stdin translates `\n` to `\r\n` on Windows, so a piped secret such as a gpg loopback passphrase gains a trailing CR and is rejected as wrong. Pipe exact-byte payloads as `bytes`, never through `text=True`.
- Emit progress with `Write-Host`, not `Write-Output`: inside a function the latter joins the return value.
- `2>$null` on a native command discards its stdout as well, so a captured probe silently returns nothing. Set `$ErrorActionPreference` around the call instead of redirecting.
- Keep quote characters out of `-c`/`-e` snippets passed to interpreters; build the interesting part of the string on the PowerShell side so no host re-quotes it.
- A Microsoft Store interpreter is not one file: its PATH entry is an unhashable AppExecLink, and inside the package `python.exe` hashes but will not execute while `python<major>.<minor>.exe` does both. Accept a bound tool only after proving this process can both hash and run it.
- Fixtures committed under an ambient `core.autocrlf=true` read back dirty once the script disables system and global Git configuration. Write fixture content with LF.
