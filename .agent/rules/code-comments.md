---
description: Comment conventions for all authored code and configuration; explain non-obvious rationale, not syntax.
alwaysApply: true
---

# Comments

- Prefer names and structure that explain behavior without narration.
- Keep comments only for non-obvious rationale, ownership, compatibility constraints, persisted formats, concurrency, security boundaries or vendor quirks.
- Do not restate the next statement, method name, test name or configuration key.
- Do not put ticket IDs, implementation phases or temporary work logs in source comments.
- Preserve meaningful inherited attribution and comments; do not churn untouched upstream prose.
- Before presenting a change, ask whether each new comment explains something the code cannot.
