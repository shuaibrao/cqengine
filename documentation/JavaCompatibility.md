# Java compatibility

CQEngine requires Java 21 or later. Its published classes target Java 21 bytecode (`--release 21`) and the supported
runtime matrix is Java 21 and Java 25. Core queries, parsers, attribute generation, serialization and persistence are
tested through independently resolved thin and `all` artifacts on both runtimes.

## API compatibility

CQEngine retains the `com.googlecode.cqengine` package namespace and the public/protected API inherited from CQEngine
3.6.0. Version 4.0 adds APIs without removing the 3.6.0 binary descriptors. Its Java 21 runtime floor is nevertheless
a deliberate runtime compatibility change: applications which must run on Java 8 through Java 20 must remain on an
earlier CQEngine release.

`ObjectStore.getBackingIndex()` is an additive default SPI used by persistence-backed stores to expose the identity
index which owns their data. The default returns `null`, so existing third-party object-store implementations remain
binary and source compatible. The query engine uses a non-null backing index exactly as it historically used the
SQLite object store's identity index. The additive `PersistenceIdentityIndex` marker lets core recognize that index
without depending on SQLite classes; the existing `IdentityAttributeIndex` extends it, so implementations of the
historical interface retain the same behavior.

The legacy functional-attribute overloads remain binary-compatible. They infer generic arguments only when an
ordinary class records those arguments in its class-file signature. Synthetic lambda and method-reference classes do
not expose that information reliably through supported Java reflection. Use the explicit
`simpleAttribute`, `simpleNullableAttribute`, `multiValueAttribute` and `multiValueNullableAttribute` factories for
inline lambdas and method references.

TypeTools is not a CQEngine dependency. An application which uses TypeTools directly must declare its own dependency.

Every `ResultSet` is `AutoCloseable` and belongs to its caller. Always use try-with-resources, even for an in-memory
collection:

```java
try (ResultSet<Car> results = cars.retrieve(equal(Car.MANUFACTURER, "Honda"))) {
    results.forEach(System.out::println);
}
```

This ownership rule is independent of the selected index or persistence implementation.

## Classpath and native access

Core in-memory use, parsing, code generation and serialization require no `--add-opens` or native-access option on
Java 21 or Java 25.

SQLite loads native code for disk and off-heap persistence. On Java 25, use:

| Launch form | Native-access option |
|---|---|
| Classpath, thin or `all` JAR | `--enable-native-access=ALL-UNNAMED` |
| Module path, thin JAR | `--enable-native-access=org.xerial.sqlitejdbc` |
| Module path, `all` JAR | `--enable-native-access=cqengine` |

Java 21 does not require a native-access option. These options grant native access only; they do not open JDK
packages to reflection.

The optional `all` JAR contains the complete 20-library native inventory from sqlite-jdbc 3.53.2.0. Packaging
verification checks every native path and SHA-256 digest against the reviewed version-specific inventory. Runtime
qualification is deliberately host-specific: each Java 21/25 thin and `all` consumer derives sqlite-jdbc's current
OS/architecture resource, extracts it into an isolated directory, verifies the extracted bytes, confirms native mode
and runs SQLite version, integrity and compile-option queries. The retained report identifies the resource which was
actually loaded. Natives for other platforms are byte-verified, not represented as loaded on the current host.

## JPMS

The thin and `all` runtime JARs are automatic modules named `cqengine`. They must never appear together on one module
path or classpath. The thin form resolves SQLite and the other dependencies as separate modules. The `all` form embeds
its runtime dependencies and relocates their packages except SQLite, which remains under `org.sqlite`; it must not be
combined with an external SQLite JAR.

A modular application which generates attributes or serializes application objects must export its model package and
open it only to the modules which inspect it. For the thin artifact, a typical descriptor contains:

```java
requires cqengine;

exports com.example.model;
opens com.example.model to cqengine, com.esotericsoftware.kryo, org.javassist;
```

For the `all` artifact, the relocated implementations are inside `cqengine`:

```java
requires cqengine;

exports com.example.model;
opens com.example.model to cqengine;
```

CQEngine itself requires no application-wide or JDK-wide module opening.

## OSGi

The thin JAR is the canonical `cqengine` OSGi bundle. It exports the CQEngine packages, imports its external
dependencies and declares a JavaSE 21 execution environment. Version qualifiers are normalized to OSGi syntax; for
example, `4.0.0-rc.1` is represented as `4.0.0.rc_1`.

The `all` JAR is not an OSGi bundle. It is supported as a classpath library and automatic JPMS module only.

## Published artifacts

CQEngine is published as `io.github.shuaibrao:cqengine:<version>` in four forms:

| Artifact | Purpose |
|---|---|
| `cqengine-<version>.jar` | Canonical thin library with declared transitive dependencies |
| `cqengine-<version>-all.jar` | Optional non-executable library with relocated dependencies; SQLite remains unrelocated |
| `cqengine-<version>-sources.jar` | Source attachment |
| `cqengine-<version>-javadoc.jar` | API documentation attachment |

The thin and `all` forms contain the same CQEngine class names, but relocation can change descriptors which expose a
dependency type. Compile and run against the same artifact form. A Maven consumer requesting the `all` classifier
must disable transitive dependencies so embedded and external copies do not coexist.

The thin and `all` runtime artifacts carry `Automatic-Module-Name: cqengine`. Only the thin artifact carries the OSGi
bundle headers. None of the artifacts is executable.

## Persistence format compatibility

CQEngine uses Kryo 5.6.2 with reference tracking enabled. `TRUSTED_STORE_COMPATIBILITY`, the default deserialization
mode, reads the supported historical raw Kryo format and requires the database, backups and restore path to be
trusted. `REGISTERED_TYPES` requires an explicit application-type allowlist and writes the CQEngine version-1
envelope. Registered-mode bytes are not accepted by historical CQEngine releases, and registered mode never falls
back to the raw trusted format.

Serializer settings, polymorphism, wrapper registrations and the registered-type allowlist form part of the stored
data contract. Preserve them for the lifetime of a store. Moving an existing store to registered mode requires a
controlled read-and-rewrite with a retained rollback snapshot.

The supported compatibility contract covers historical CQEngine/Kryo collection-wrapper data and indexed SQLite
stores tested on Java 21 and Java 25. Application-specific classes and custom serializers remain the application's
compatibility responsibility.

## Code generation

`AttributeBytecodeGenerator` defines generated attributes beside the target POJO through supported Javassist APIs.
It works on the classpath and from named application modules on Java 21 and Java 25, without opening `java.base`.
Named modules must export and selectively open their model package as described above.
