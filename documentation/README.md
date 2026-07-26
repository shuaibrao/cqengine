# CQEngine documentation

CQEngine is an indexed Java collection with programmatic, SQL and CQN query APIs, optional on-heap/off-heap/disk
persistence, and MVCC transaction isolation.

## Getting started

- [Obtaining and selecting artifacts](Downloads.md)
- [Java, classpath, JPMS and OSGi compatibility](JavaCompatibility.md)
- [Generating attributes](AutoGenerateAttributes.md)
- [Lambda attributes](LambdaAttributes.md)
- [SQL and CQN string queries](StringQueries.md)
- [Using CQEngine from other JVM languages](OtherJVMLanguages.md)

## Query and index behavior

- [The limits of iteration](TheLimitsOfIteration.md)
- [Ordering strategies](OrderingStrategies.md)
- [Deduplication strategies](DeduplicationStrategies.md)
- [Merge strategies](MergeStrategies.md)
- [Index quantization](IndexQuantization.md)
- [Joins](Joins.md)

## Persistence and concurrency

- [Persistence, SQLite and serialization](Persistence.md)
- [Transaction isolation](TransactionIsolation.md)

## Quality and project information

- [Current and historical benchmark methodology](Benchmark.md)
- [Frequently asked questions](FrequentlyAskedQuestions.md)
- [Static analysis](StaticAnalysis.md)
- [Concurrency qualification for maintainers](../stress-tests/README.md)
- [Release notes](ReleaseNotes.md)
- [Security policy](../SECURITY.md)
- [Contributing](../CONTRIBUTING.md)
- [Releasing](../RELEASING.md)

Run `./gradlew javadoc` to generate current API documentation at `build/docs/javadoc/index.html`. The checked-in
[`javadoc/apidocs/`](javadoc/Readme.md) tree is the historical CQEngine 3.5.0 snapshot and is retained only so old
documentation links remain inspectable.
