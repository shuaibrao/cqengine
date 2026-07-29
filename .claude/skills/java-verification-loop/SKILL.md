---
name: java-verification-loop
description: Verify CQEngine Java and Gradle changes with proportionate focused checks, Java 21/25 compatibility gates, security/publication checks, and the authoritative local qualification wrapper. Use after implementation, refactoring, dependency changes, or when asked whether a change is ready.
---

# CQEngine verification loop

Choose the smallest gate that proves the changed behavior, then expand with risk. Stop at the first failure, diagnose it, fix it, and rerun the failed gate.

## Focused development checks

```bash
./gradlew formatRatchetCheck
./gradlew test --tests '<fully.qualified.TestName>'
./gradlew testJava21 testJava25
./gradlew integrationTest
./gradlew jmhSmoke
```

For agent-harness changes:

```bash
python3 scripts/sync-agent-config.py --check
python3 scripts/test-agent-harness.py
```

For dependency, parser, serialization or persistence changes, also run the corresponding focused security, compatibility, fuzz, recovery or consumer tasks defined by `./gradlew tasks` and the documentation index.

Any version that reaches the published POM needs `consumerTest`, not just `test` and `verifyPublication`. The consumer projects carry their own expected-coordinate inventories and a probe asserting the resolved SQLite driver version, so a bumped coordinate has to be updated there as well as in the root build. Neither root-build gate reads those lists, which is why a stale one reaches CI green-looking.

## Broader local gates

```bash
./gradlew check
./gradlew securityCheck --no-parallel --no-configuration-cache
./gradlew consumerTest apiCompatibility baseline verifyPublication
```

Never use dependency-verification write mode as passing evidence. Never add `--add-opens` to conceal a Java compatibility failure.

## Authoritative release qualification

Only run the qualification, whose full argument list `RELEASING.md` records, when the user explicitly approves the
long-running final run. The bare task name fails the invocation gate, because tracked `gradle.properties` enables the
caches and parallel execution the gate rejects. It
requires a clean worktree and an approved `CQENGINE_JMH_MACHINE_LABEL`, and writes
`qualification-completion.properties` only when the whole graph passed. A direct `releaseCheck` invocation skips the
clean-worktree gate and leaves no completion record, so it is not equivalent.

The run uses the working checkout with shared Gradle and vulnerability-database caches, which the evidence records as
`qualificationMode=local-checkout-shared-caches`. Do not describe it as a clean-room rebuild: that property comes from
CI, which checks out fresh and proves byte-identical output before signing. State which one a given claim rests on.

`centralPublicationToolsTest` is Linux-only, so a Windows run names it in `skippedReleaseGates`. Report that rather
than implying the platforms produce the same claim.

Report the commands, runtime, test counts, failures/skips, and whether evidence applies to the exact current commit.
