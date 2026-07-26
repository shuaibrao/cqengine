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

## Broader local gates

```bash
./gradlew check
./gradlew securityCheck --no-parallel --no-configuration-cache
./gradlew consumerTest apiCompatibility baseline verifyPublication
```

Never use dependency-verification write mode as passing evidence. Never add `--add-opens` to conceal a Java compatibility failure.

## Authoritative release qualification

Only run `scripts/qualify-candidate.sh` when the user explicitly approves the long-running final qualification. It
rebuilds committed source in isolated homes and is the only command that creates authoritative release evidence. A
focused or direct `releaseCheck` invocation is not equivalent.

Report the commands, runtime, test counts, failures/skips, and whether evidence applies to the exact current commit.
