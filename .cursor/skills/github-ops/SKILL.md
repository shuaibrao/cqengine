---
name: github-ops
description: Inspect and operate GitHub repositories with the gh CLI. Use for pull requests, issues, code search, workflow runs, releases, repository metadata, or when the user explicitly requests a GitHub mutation.
---

# GitHub operations

Use `gh` and verify authentication with `gh auth status` before network operations. Repository mutations require an explicit user request; inspection is read-only by default.

## Common inspection

```bash
gh repo view
gh pr list --state all
gh pr view <number> --json title,body,author,baseRefName,headRefName,files,reviews,statusCheckRollup
gh pr diff <number>
gh pr checks <number>
gh issue list --state all
gh issue view <number>
gh run list
gh run view <run-id> --log
gh search code "<query>" --repo <owner>/<repo>
```

Use `--json` and `--jq` for machine-readable results. Read existing review comments before raising or posting duplicate findings.

## Mutations

Only when requested, use `gh issue create/comment/close`, `gh pr create/review/merge`, workflow dispatch, release, or repository commands. Preview the exact content before posting when wording or recipients matter. Never overwrite a release or append AI/agent attribution.

Replace a published branch only with a lease-checked push, which aborts when the remote holds commits the local clone has not seen. An unconditional force discards them silently and is blocked.

Commit and PR text must describe engineering intent. Do not encode implementation-plan phase numbers in titles or messages.
