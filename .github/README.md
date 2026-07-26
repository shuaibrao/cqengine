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
| `release.yml` | Qualifies protected `main`, signs the exact retained bytes, and can submit them to the Central Portal under a separately protected publication approval. |

All third-party actions are pinned to full commit hashes. Dependabot proposes Gradle and action updates, which still
require normal dependency-integrity review.

## Repository configuration

Before enabling these workflows:

- add `NVD_API_KEY` to the protected `security-scan` environment for main-branch/scheduled scans and to the
  protected `release` environment for qualification;
- verify the `io.github.shuaibrao` namespace in the Central Portal and add its token as
  `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` in the `release-publication` environment;
- generate and distribute a passphrase-protected OpenPGP signing key, then add the armored private key, full
  fingerprint and passphrase as `MAVEN_CENTRAL_SIGNING_KEY`, `MAVEN_CENTRAL_SIGNING_KEY_ID` and
  `MAVEN_CENTRAL_SIGNING_PASSWORD` in a separate `release-signing` environment;
- enable the dependency graph, Dependabot alerts, dependency-review support, CodeQL code scanning, and private
  vulnerability reporting in repository settings;
- create protected `release`, `release-signing`, `release-publication` and credential-free `release-tag`
  environments for qualification, signing, Central submission and GitHub mutation respectively. Restrict every
  release environment to protected `main`; when a distinct trusted maintainer is available, require that reviewer
  and prevent self-approval. Restrict `security-scan` to protected `main` without reviewers so scheduled scans do not
  stall; and
- register a dedicated, ephemeral, credential-free Linux x64 runner with the `cqengine-release` label. Reprovision
  or securely wipe it after every run. It must meet every prerequisite in `RELEASING.md`, including installed Java
  21 and Java 25 toolchains and a committed matching benchmark-host record.

Repository rules remain server-side controls and must be enabled after the repository exists. Protect `main` with
pull requests, CODEOWNERS review, conversation resolution, linear history, the stable `CI / Java 21 and 25` check,
and the corresponding merge-queue check. Reject force pushes and deletion. Protect `v*` tags from update or deletion
and restrict tag creation to the release role. Keep administrator and ruleset bypass narrowly scoped and audited.

Those rules, environments, secrets, runner registration, private vulnerability reporting, and Maven Central account
configuration cannot be activated by files in this repository. Push, pull-request and scheduled workflows start
after the files are published, but release and credentialed security jobs cannot complete safely until the remote
repository owner deliberately configures those controls.

Preventing self-approval requires a distinct trusted collaborator. For an initial solo-maintainer release,
deliberately leave required-reviewer protection unset rather than creating a deadlocked environment. Retain the
default-branch dispatch, environment separation, manual Central publication and audit trail, and add no-self-approval
as soon as a second trusted maintainer is available.

Release requests use the `cqengine-release` repository-dispatch event, so GitHub always loads `release.yml` from the
default branch. Use `central_action=qualify-only` for qualification without remote mutation.
`upload-user-managed` qualifies and signs the retained bytes,
uploads them for Central Portal validation, creates the annotated immutable tag only after validation succeeds, and
then stops for manual publication approval. The first public release, Maven Central publication and GitHub release
remain explicit maintainer actions. Portal credentials and the private signing key are never available to
pull-request jobs. Signing and Central jobs have read-only GitHub tokens. The separate tag job has write access to
repository contents but cannot access either the signing key or Central token.

The performance workflow deliberately labels GitHub-hosted measurements as unapproved, report-only diagnostics. A
maintainer must run the authoritative qualification on an approved host and explicitly invoke
`syncBenchmarkDocumentation` before any numerical result becomes tracked project documentation.
