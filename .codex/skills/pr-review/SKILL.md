---
name: pr-review
description: Review CQEngine pull requests with GitHub context, upstream API/serialization compatibility, Gradle dependency integrity, parser/persistence security, performance evidence, legal provenance, and test coverage. Use when asked to review a PR or proposed CQEngine change.
---

# CQEngine pull-request review

## Gather context

Use `github-ops` to read PR metadata, diff, checks and existing comments. Read `AGENTS.md`, the relevant `.agent/rules/`, and the documentation index before judging the change. Do not post anything unless the user asks.

## Review surfaces

- Correctness, edge cases, resource ownership, concurrency and failure cleanup
- Java 21 bytecode plus Java 21/25 runtime behavior without hidden module opens
- Public/protected API descriptors, inherited serialization identities, JPMS/OSGi identity and upstream 3.6.0 baselines
- Thin versus `all` artifact behavior, publication metadata, legal resources and reproducibility
- Dependency locks, verification metadata, signatures/checksums and vulnerability evidence
- Parser limits/regex policy, Kryo trust/bounds, SQLite transactions, native access and persisted-format compatibility
- JMH lifecycle correctness and allocation/performance claims tied to exact evidence
- Focused regression tests, cross-JDK coverage, external consumer probes and documentation consistency

Every finding must cite a file and line, explain impact, and propose a concrete fix. Separate blocking issues from suggestions and nits. Avoid duplicate comments and unsupported universal performance claims.

## Output

Lead with findings ordered by severity. Then state open questions, verification gaps, and a concise verdict. If no findings exist, say so and identify residual test or operational risks.
