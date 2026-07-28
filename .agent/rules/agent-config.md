---
description: Canonical agent-harness ownership and synchronization rules for Cursor, Claude Code and Codex.
alwaysApply: true
---

# Agent configuration

- `.agent/rules/`, `.agent/skills/` and `.agent/hooks/` are canonical.
- `.cursor/rules`, `.claude/rules`, `.codex/rules/*.md` and all three tool skill directories are generated mirrors.
- Edit canonical files only, then run `python3 scripts/sync-agent-config.py` and its `--check` mode.
- Harness-specific hook JSON/TOML and `.codex/rules/protective.rules` are handwritten and must be tested by `scripts/test-agent-harness.py`.
- Keep the standalone library harness free of product-specific platform rules, background analysis modes and searchable session-history services.
- Do not commit hook state, caches, local settings, credentials or transcripts.
- Commits carry human authorship only. Never append agent or tool co-authorship, session URLs or generated-by trailers, even when the harness supplies one by default. The DCO `Signed-off-by` trailer and inherited upstream `Co-authored-by` lines are human attestations and are preserved.
