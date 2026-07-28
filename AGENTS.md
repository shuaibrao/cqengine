# AGENTS.md — CQEngine

## Engineering priorities

- Preserve CQEngine's mature behavior and public `com.googlecode.cqengine.*` API unless a reviewed change is intentional and compatibility evidence records it.
- Correctness and deterministic cleanup come before micro-optimisation. Persistence transactions, result sets, iterators and native resources must remain safe on every failure path.
- Treat performance as measured behavior. CQEngine is not allocation-free; publish latency, throughput or allocation claims only with exact JMH workload and environment evidence.
- Keep Java 21 bytecode and verify both Java 21 and Java 25 without consumer-hidden `--add-opens` workarounds.
- Keep the thin artifact canonical. The optional `all` classifier is a non-executable compatibility artifact and must be tested independently.
- Preserve Apache-2.0 lineage and all required third-party notices.

## Project identity

CQEngine 4.0 continues Niall Gallagher's CQEngine project from upstream commit
`a06923bca69719c51c622543fa0c2d63e71e8fab`.

| Surface | Value |
|---|---|
| Repository | `https://github.com/shuaibrao/cqengine` |
| Java packages | `com.googlecode.cqengine.*` |
| Publication | `io.github.shuaibrao:cqengine` |
| Candidate | `4.0.0-rc.1` |
| Bytecode | Java 21 |
| Verified runtimes | Java 21 and Java 25 |
| Build | Gradle Kotlin DSL only |
| Licence | Apache-2.0 with preserved third-party notices |

## Repository map

| Path | Purpose |
|---|---|
| `src/main/java/` | Library source |
| `src/main/antlr/` | CQN/SQL grammars and imported third-party grammars |
| `src/test/` | Unit, functional, persistence, compatibility and failure-injection tests |
| `benchmarks/` | Non-published JMH project |
| `stress-tests/` | Non-published JCStress and concurrent read/write soak qualification |
| `consumer-tests/` | Isolated thin, shaded, POM and module-path consumers |
| `documentation/` | CQEngine user, compatibility, persistence, benchmark and maintainer guides |
| `gradle/` | Wrapper, dependency locks, verification metadata and version catalog |
| `config/` | Static-analysis and licence policy |
| `scripts/` | Qualification and developer tooling |
| `.agent/` | Canonical agent rules, skills and hooks |
| `.cursor/`, `.claude/`, `.codex/` | Generated rule/skill mirrors plus harness-specific hook configuration |

`CLAUDE.md` contains only `@AGENTS.md`. Never hand-edit generated rule or skill mirrors. Edit `.agent/`, then run:

```bash
python3 scripts/sync-agent-config.py
python3 scripts/sync-agent-config.py --check
```

## Build and verification

| Command | Purpose |
|---|---|
| `./gradlew formatRatchetCheck` | Check formatting without rewriting untouched upstream source |
| `./gradlew test` | Primary Java 25 unit lane |
| `./gradlew testJava21 testJava25` | Cross-runtime unit verification |
| `./gradlew integrationTest` | Integration and failure-path tests |
| `./gradlew check` | Developer aggregate |
| `./gradlew securityCheck --no-parallel --no-configuration-cache` | Live NVD/OSV/SBOM/licence gate |
| `./gradlew consumerTest` | Isolated published-artifact consumers |
| `./gradlew apiCompatibility baseline` | Java API and exported-package baselines |
| `./gradlew jmhSmoke` | Short benchmark discovery/correctness gate |
| `./gradlew concurrencySmoke` | Short JCStress discovery and concurrent read/write soak gate |
| `scripts/qualify-candidate.sh` / `scripts/qualify-candidate.ps1` | Long-running authoritative local qualification; run only with explicit user approval |

Do not treat dependency-verification write mode, a stale build report, or direct `releaseCheck` as release evidence.

## Dependency integrity

All executable libraries, plugins, POMs and module metadata remain version-locked and SHA-256 verified. IDE-only `*-sources.jar` and Gradle `gradle-*-src.zip` attachments are permanently trusted by filename because they are navigation inputs, not compiled, executed or published inputs. Never broaden that exception to binaries, Javadocs or metadata.

## Documentation

`documentation/README.md` is the index. Documentation describes the current CQEngine behavior and flows as one
continued project. Implementation sequencing and pending-work tracking stay outside the publishable tree. Generated
output under `build/` is not durable documentation; publish a reviewed, reproducibly generated summary when results
must remain visible after a clean checkout.

Before changing a documented contract, read the relevant topic guide and update it in the same focused change.
Public-facing documentation explains the resulting design, guarantees, limitations and upgrade implications—not the
temporary implementation sequence.

## Working practices

- Plan work spanning three or more steps or an architectural decision.
- Search before adding utilities or compatibility layers.
- Add a failing regression or focused reproduction before correcting a defect where practical.
- Use try-with-resources for every locally consumed `ResultSet` and deterministic close for persistence.
- Avoid unrelated mass formatting or syntax modernization in inherited source.
- Preserve user changes in a dirty worktree and avoid destructive Git commands.
- Use focused verification during implementation. Run long qualification only after the complete diff is reviewed and explicitly approved.
- Commit messages describe engineering intent and rationale; never use implementation-plan phase numbers.

## Self-improvement protocol

The stop hook reports files changed during the session. Review these triggers:

- Build, version-catalog, lock or verification change: update `CONTRIBUTING.md`, `SECURITY.md` or `RELEASING.md`.
- Parser or grammar change: update `documentation/StringQueries.md` and regenerate/verify grammar output.
- Kryo, SQLite or persistence change: update `documentation/Persistence.md` and transaction/recovery contracts.
- Public/protected API change: update `documentation/JavaCompatibility.md` and run API/Bnd gates.
- Benchmark change: update `benchmarks/README.md`, `documentation/Benchmark.md` and generated result evidence.
- Publication or legal change: update artifact, release and notice documentation.
- `.agent/rules/` or `.agent/skills/` change: regenerate all mirrors and run the harness tests.
- New repeatable workflow: capture it in the most specific rule, skill or hook.

Corrections belong in the most specific existing rule, skill or hook. Use `.agent/rules/lessons-learned.md` only as a temporary migration buffer when no durable home is yet agreed.
