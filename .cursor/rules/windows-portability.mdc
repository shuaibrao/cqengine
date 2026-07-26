---
description: Cross-platform correctness for Python, Gradle, hooks and filesystem or subprocess handling.
alwaysApply: false
---

# Windows portability

- Specify UTF-8 for Python text I/O and subprocess decoding; use `errors="replace"` for external text.
- Use `Path`/`Files` for local paths and forward slashes for archive, URL, container or Maven-repository paths.
- Do not assume POSIX permissions, `/proc`, symlinks or `python3` without a documented platform boundary.
- Shared hook command strings use `python3`; Windows setup must provide a real `python3` shim because JSON cannot select an interpreter by OS.
- Keep platform-native tests explicit and report unsupported platforms rather than silently skipping claimed behavior.
