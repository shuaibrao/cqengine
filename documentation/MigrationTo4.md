# Migrating from CQEngine 3.x to 4.0

CQEngine 4.0 keeps the `com.googlecode.cqengine.*` packages, the `cqengine` module and OSGi bundle
names, and the on-disk data formats readable — it is designed as a drop-in upgrade. A small number
of deliberate behavioural changes can still affect existing code. This page lists every change a
3.x application can trip on, with the migration for each. The complete change inventory is in the
[release notes](ReleaseNotes.md).

## Checklist

| Change | Affects you if... |
|---|---|
| [New coordinate](#1-new-maven-coordinate) | Always — the group id changed |
| [Java 21 baseline](#2-java-21-baseline) | You run on Java 8–17 |
| [Lambda attributes](#3-lambda-and-method-reference-attributes-need-explicit-types) | You create attributes from inline lambdas or method references |
| [`close()` instead of `finalize()`](#4-disk-and-off-heap-persistence-must-be-closed-explicitly) | You rely on GC to release disk/off-heap stores |
| [SQLite busy timeout](#5-sqlite-busy-timeout-now-3000-ms-by-default) | You share a disk store between contending processes/collections |
| [One-way SQLite migration](#6-sqlite-table-migration-is-one-way) | You open existing disk/off-heap stores |
| [`UniqueIndex` re-add semantics](#7-uniqueindex-no-longer-overwrites-on-re-add) | You "update" objects by re-adding an equal object |
| [Exception-type changes](#8-exception-type-changes) | You catch specific exception types from the parser or persistence |
| [`ResultSet` ownership](#9-close-every-resultset) | Any query code (best practice, now load-bearing) |

## 1. New Maven coordinate

```xml
<!-- 3.x -->
<dependency>
    <groupId>com.googlecode.cqengine</groupId>
    <artifactId>cqengine</artifactId>
    <version>3.6.0</version>
</dependency>

<!-- 4.0 -->
<dependency>
    <groupId>io.github.shuaibrao</groupId>
    <artifactId>cqengine</artifactId>
    <version>4.0.0</version>
</dependency>
```

Java packages are unchanged — no import changes. Because the group id changed, dependency
resolvers treat the old and new artifacts as different modules: make sure no transitive dependency
still drags in `com.googlecode.cqengine:cqengine`, or both jars land on the classpath. The
[README's coexistence section](../README.md) shows the Gradle capability conflict this fork
publishes to detect that automatically, and the Maven enforcer `bannedDependencies` rule for Maven
builds.

## 2. Java 21 baseline

CQEngine 4.0 requires Java 21+ and is verified on Java 21 and Java 25. No `--add-opens` flags are
needed for any feature. On Java 25 with disk or off-heap persistence, grant SQLite native access:
`--enable-native-access=ALL-UNNAMED` (classpath), `--enable-native-access=org.xerial.sqlitejdbc`
(thin jar, module path) or `--enable-native-access=cqengine` (`all` jar, module path).

## 3. Lambda and method-reference attributes need explicit types

The JVM does not reliably expose generic types of synthetic lambdas, so the TypeTools-based
inference was removed. Class-based functional attributes keep working; inline lambdas and method
references now throw `IllegalStateException` at attribute-creation time unless the types are
supplied explicitly:

```java
// 3.x — inferred via TypeTools, breaks on modern JVMs
Attribute<Car, Double> PRICE = QueryFactory.attribute(Car::getPrice);

// 4.0 — explicit types via the new factories
Attribute<Car, Double> PRICE =
        QueryFactory.simpleAttribute(Car.class, Double.class, "price", Car::getPrice);
```

Equivalent factories exist for each shape: `simpleAttribute`, `simpleNullableAttribute`,
`multiValueAttribute`, `multiValueNullableAttribute`. See
[LambdaAttributes](LambdaAttributes.md). If your application used TypeTools itself through
CQEngine's transitive dependency, declare it directly now.

## 4. Disk and off-heap persistence must be closed explicitly

`DiskPersistence` and `OffHeapPersistence` no longer have `finalize()` methods. An application
that dropped the last reference and relied on GC to release the SQLite store now leaks the file
handle until process exit. Close persistence deliberately:

```java
DiskPersistence<Car, Integer> persistence = DiskPersistence.onPrimaryKeyInFile(Car.CAR_ID, file);
try {
    IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(persistence);
    // ... use the collection ...
}
finally {
    persistence.close();
}
```

## 5. SQLite busy timeout now 3,000 ms by default

3.x waited effectively forever (`Integer.MAX_VALUE` ms) for a conflicting SQLite lock. 4.0 waits
at most 3,000 ms and then throws `SQLiteBusyException` (a subclass of `IllegalStateException`,
exposing the base and extended SQLite result codes). Highly contended workloads that previously
queued invisibly may now surface busy failures under load. To restore longer waits:

```java
DiskPersistence<Car, Integer> persistence = DiskPersistence.onPrimaryKeyInFileWithProperties(
        Car.CAR_ID, file, properties("busy_timeout", "60000"));
```

Treat a busy failure as retryable back-pressure, not corruption.

## 6. SQLite table migration is one-way

4.0 replaces the historical sanitized table names with collision-resistant SHA-256-derived names.
Opening an existing store migrates its tables transactionally and records a durable assignment so
a different colliding attribute name can never claim the data. Two consequences:

- **No downgrade.** A store opened by 4.0 cannot be reopened by 3.x. Back up disk stores before
  the first 4.0 run.
- Attribute names the legacy sanitizer collided (including purely non-alphanumeric names, which
  all mapped to the same bare legacy table) migrate safely: the first such attribute to open the
  store adopts the legacy data under its collision-free new name, and the durable assignment
  record prevents any different colliding name from claiming it later.

## 7. `UniqueIndex` no longer overwrites on re-add

3.x silently overwrote the index entry when an equal-but-different object was added, which could
corrupt the index. 4.0 follows `Set` semantics: re-adding an equal object keeps the **existing**
instance, and a genuine uniqueness violation rolls back and throws. Code that "updated" stored
objects by re-adding modified equal objects must switch to an explicit update:

```java
// 3.x habit — silently replaced the indexed object; 4.0 keeps the old instance instead
cars.add(updatedCar);

// 4.0 — replace explicitly
cars.update(singleton(oldCar), singleton(updatedCar));
```

## 8. Exception-type changes

| Situation | 3.x threw | 4.0 throws |
|---|---|---|
| `QueryParser.parse(null)` | `IllegalArgumentException` | `InvalidQueryException` |
| SQLite lock contention | JDBC `SQLException` wrapped opaquely | `SQLiteBusyException extends IllegalStateException` |
| Exhausted unique/deduplicated iterators | undefined/looping behaviour | `NoSuchElementException` (per the `Iterator` contract) |
| Query strings exceeding parser limits | unbounded parsing | `InvalidQueryException` (limits configurable via `ParserLimits`) |

Existing `catch (RuntimeException)` / `catch (IllegalStateException)` blocks keep working;
narrower catches may need the new types.

## 9. Close every `ResultSet`

Every `ResultSet` returned by `retrieve()` is caller-owned and must be closed — including from
purely on-heap collections, so the code stays correct when an index or persistence layer that owns
external resources is added later:

```java
try (ResultSet<Car> results = cars.retrieve(query)) {
    results.forEach(...);
}
```

4.0 closes request-scope resources deterministically even when a query fails mid-flight, and
tracks leaked iterators; the try-with-resources pattern above is the supported usage.

## Kryo-serialized stores

Existing stores open unchanged in the default `TRUSTED_STORE_COMPATIBILITY` mode (Kryo upgraded
5.0.0-RC1 → 5.6.2, wire-compatible, verified against 3.6.0-era fixtures). Treat such stores as
trusted input. New deployments that need untrusted-input hardening can opt into the
`REGISTERED_TYPES` envelope mode — see [Persistence](Persistence.md) for the trade-offs and the
compatibility-to-registered migration procedure.
