# Repository automation

GitHub automation complements the repository's Gradle gates; it does not replace the authoritative local release
qualification described in `RELEASING.md`.

| Workflow | Purpose |
|---|---|
| `ci.yml` | Runs Java 21/25 checks plus API, package, publication, consumer, and benchmark-harness probes on pull requests, merge queue entries, and `main`. |
| `security.yml` | Lints workflows, reviews dependency changes, submits the Gradle dependency graph, and runs the NVD, OSV, SBOM, and licence gates. |
| `codeql.yml` | Compiles the project under CodeQL and uploads Java security analysis. |
| `performance.yml` | Runs scheduled smoke checks or a manual report-only suite on variable GitHub-hosted machines. It never updates tracked benchmark documentation. |
| `upstream-monitor.yml` | Compares the preserved baseline with the original upstream and opens or updates a maintenance-intake issue without importing code. |
| `release-bundle.yml` | Dispatched from a `v*` release tag: verifies the committed qualification evidence, rebuilds the qualified commit on a hosted runner, proves the rebuilt publication is byte-identical to the qualified inventory, signs it, and optionally hands the bundle to the Central Portal (`skip`/`user-managed`/`automatic`). |

All third-party actions are pinned to full commit hashes. Dependabot proposes Gradle and action updates, which still
require normal dependency-integrity review.

`ci.yml` and `release-bundle.yml` check out full history. `formatRatchetCheck` compares maintainer-changed lines
against the preserved upstream commit, and `release-bundle.yml` rebuilds a qualified ancestor, so neither works from
the default shallow clone. Keep `fetch-depth: 0` on those two workflows.

`codeql.yml` compiles with `--no-build-cache`. CodeQL observes real compiler invocations, so a cache hit hands it the
class files without running `javac` and the analysis fails with no source seen. The flag is a correctness
requirement, not a performance preference.

`ci.yml` leaves Gradle's configuration cache enabled while `qualifyLocally` disables it, so an incompatibility
reaches CI without appearing in a local qualification. Reproduce one by running the CI command without
`--no-configuration-cache`; a task that reads project state from an execution-time action, or whose action is defined
in the build script, needs the state resolved at configuration time or an explicit
`notCompatibleWithConfigurationCache` reason.

## Repository configuration

Before enabling these workflows:

- add `NVD_API_KEY` to the protected `security-scan` environment for main-branch/scheduled scans;
- create one protected **`maven-central`** environment for release runs. Restrict it to `v*` tags (or protected
  `main` plus tags), add the repository owner as a required reviewer so every release run needs one explicit
  approval, and configure its secrets:
  - `CENTRAL_TOKEN_USERNAME` / `CENTRAL_TOKEN_PASSWORD` — Central Portal user token for the verified
    `io.github.shuaibrao` namespace;
  - `SIGNING_KEY` / `SIGNING_KEY_ID` / `SIGNING_PASSWORD` — armored passphrase-protected OpenPGP private key, its
    full fingerprint, and the passphrase;
  - `NVD_API_KEY` — for the verification gate inside release runs;
- enable the dependency graph, Dependabot alerts, dependency-review support, CodeQL code scanning, and private
  vulnerability reporting in repository settings. Restrict `security-scan` to protected `main` without reviewers so
  scheduled scans do not stall.

No self-hosted runner is required: the long-running qualification (`./gradlew qualifyLocally`) runs locally before
tagging, and `release-bundle.yml` verifies its committed evidence on a GitHub-hosted runner. The local run supplies
JMH, JCStress and the soak on an approved host; the clean-room rebuild comes from CI, which checks out fresh on a
pristine runner and proves byte-identical output before signing.

Repository rules remain server-side controls and must be enabled after the repository exists. Protect `main` with
pull requests, CODEOWNERS review, conversation resolution, linear history, the stable `CI / Java 21 and 25` check,
and the corresponding merge-queue check. Reject force pushes and deletion. Protect `v*` tags from update or deletion
and restrict tag creation to the maintainer. Keep administrator and ruleset bypass narrowly scoped and audited.

Those rules, the environment, secrets, private vulnerability reporting, and Maven Central account configuration
cannot be activated by files in this repository. Push, pull-request and scheduled workflows start after the files
are published, but release and credentialed security jobs cannot complete safely until the remote repository owner
deliberately configures those controls.

Releases follow the flow in `RELEASING.md`: the owner qualifies the release commit locally, commits the two
qualification evidence files under `release-evidence/<version>/`, creates and pushes the signed `v<version>` tag,
and dispatches `release-bundle.yml` from that tag. The workflow has a read-only GitHub token, requires the
`maven-central` environment approval, rebuilds the qualified commit, and refuses to sign anything whose rebuilt
publication inventory does not hash-match the committed readiness manifest. `centralPublishing=skip` produces the
signed bundle artifact only; `user-managed` uploads and stops at `VALIDATED` for a manual Portal publish;
`automatic` (permanent) publishes as soon as Central validation passes. Portal credentials and the private signing
key are never available to pull-request jobs.

The performance workflow deliberately labels GitHub-hosted measurements as unapproved, report-only diagnostics. A
maintainer must run the authoritative qualification on an approved host and explicitly invoke
`syncBenchmarkDocumentation` before any numerical result becomes tracked project documentation.
