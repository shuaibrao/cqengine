---
description: Security and robustness requirements for CQN/SQL parsing, Kryo serialization and SQLite persistence.
alwaysApply: false
---

# Parser and persistence security

- Enforce finite parser input, token and grammar-depth limits before expensive work.
- Keep Java regular expressions behind the documented trusted-input policy or a caller-supplied policy; fuzz malformed/deep inputs in bounded child processes.
- Registered Kryo mode must allow only declared graph types, use the versioned envelope and enforce blob, graph, string and container limits. Compatibility mode is trusted-store-only.
- Treat application serializers as untrusted resource consumers until process-isolated fuzzing proves bounded behavior.
- Preserve immutable upstream-format fixtures and define migration/rollback before changing stored bytes.
- Start persistence requests in rollback state, commit only explicit success, close all resources after failures and retain the primary exception with suppressed cleanup failures.
- Exercise crash/WAL recovery, corrupt/truncated storage, disk-full/read-only/permission failures and bounded SQLite contention before claiming the affected production capability.
- Validate SQLite identifiers unchanged; persisted naming changes require collision detection and an explicit legacy migration.
