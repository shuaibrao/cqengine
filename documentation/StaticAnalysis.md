# Static analysis

CQEngine uses SpotBugs 4.10.2 with FindSecBugs 1.14.0 at maximum analysis effort. Static analysis has two independent
contracts: a production gate for high-confidence findings and a complete reviewed inventory for findings at every
confidence level.

## Production gate

Run:

```bash
./gradlew verifySpotBugsMain --rerun-tasks --no-daemon --console=plain
```

`verifySpotBugsMain` independently parses the SpotBugs XML rather than trusting task success alone. It requires:

- exact equality between every compiled production class and every analyzed class;
- FindSecBugs to be enabled;
- zero analysis errors and zero missing classes; and
- zero unreviewed high-confidence findings.

The production exclusion file is deliberately field/type specific. It contains five inherited mutable public fields
which cannot become `final` without changing the established API, and the SQL date-math adapter whose public type name
matches its CQN superclass. No package, detector category or source tree is blanket-suppressed.

The machine-readable summary is `build/reports/spotbugs/main-inventory.txt`; XML and HTML reports are under
`build/reports/spotbugs/`.

SpotBugs tasks deliberately opt out of Gradle's build cache because their XML and HTML reports record absolute
analyzed-class paths. Compilation remains cacheable, but each worktree produces its own analysis reports so a report
created in another checkout cannot satisfy the exact class-inventory gate. The tasks also opt out of the configuration
cache because SpotBugs 4.10.2 task state is not serializable.

## Complete reviewed inventory

Run:

```bash
./gradlew verifySpotBugsReview --rerun-tasks --no-daemon --console=plain
```

`spotbugsReview` analyzes at LOW confidence without the production exclusion file. Its verifier requires the same
complete class coverage, plugin presence and error-free analysis, then compares every finding with
`config/spotbugs/review-baseline.txt`.

The committed inventory currently covers all 698 production classes and exactly 418 findings: 6 HIGH, 266 NORMAL and
146 LOW. The six HIGH entries are the precise API-compatibility exceptions described above, so the production gate
contains no unreviewed HIGH finding. The remaining inventory is not represented as a clean scan or as a list of
suppressed defects.

Baseline identities contain detector type, priority, class, method or field descriptor, and count. They deliberately
exclude line numbers and SpotBugs instance hashes, which change during harmless source movement. Missing or unexpected
identities fail the verifier.

Review outputs are:

- `build/reports/spotbugs/review.xml` — every finding, rank, priority, member and source location;
- `build/reports/spotbugs/review.html` — human-readable report;
- `build/reports/spotbugs/review-inventory.txt` — verified stable-identity inventory; and
- `config/spotbugs/review-baseline.txt` — committed expected identities.

## Disposition rationale

The residual inventory is retained for visibility and falls into these reviewed categories:

- generated ANTLR lexer/parser fields, token accessors and automaton switches which would be overwritten if edited;
- public/protected fields, constructors, methods and redundant interface declarations retained for API or reflection
  compatibility;
- live adapters and request-scoped views whose identity is required for resource registration, backing-store,
  iterator, callback or query-option semantics;
- query hierarchy equality/hash patterns and index casts covered by concrete-type dispatch and regression tests;
- explicitly best-effort quiet-cleanup helpers, while failure-preserving cleanup uses separate tested paths;
- SQLite detector findings where values are bound and every generated identifier component is validated and quoted;
  and
- generated, defensive or compatibility code whose exact locations remain visible in the XML inventory.

The review has also driven concrete iterator-contract, JDBC resource, locale, Unicode, equality, immutable-input and
synchronization fixes. Those behaviors remain covered by regressions; the baseline is not a substitute for them.

## Changing the baseline

Do not regenerate the baseline merely to make a mismatch pass. For every new, removed or moved identity:

1. inspect the XML at the affected class and member;
2. determine whether it is a correctness, security, concurrency, numeric or resource-lifecycle defect;
3. fix real defects and add focused regression coverage;
4. document the location-specific rationale for an intentionally retained finding; and
5. update the exact baseline and this document in the same reviewed change.

A new production exclusion must be narrower than a package or detector category and must explain the public or
generated-code contract which prevents a source fix. Run both static-analysis gates and the relevant tests after any
baseline or exclusion change.
