# External consumer verification

This is a standalone Gradle build. It is deliberately not included in the CQEngine producer build and has no project,
composite-build, source-directory or flat-file dependency on CQEngine. The producer passes the exact staged version;
the consumer constructs only `io.github.shuaibrao:cqengine:<that-version>`.

The `thin` project's classpath graph is run once with Gradle module metadata and once in POM-only mode, which ignores
Gradle's POM redirection marker. A separate `thin-module` graph uses Gradle metadata and launches the same artifact on
the module path only. Both resolve the published artifact and its eight third-party runtime modules. Resolution fails
before consumer compilation if a graph contains a producer project component, an unexpected module, a class directory
or a producer-checkout path outside `build/local-repository`.
The verifier compares the complete resolved runtime file set with the module-artifact inventory, so an undeclared file
or directory dependency cannot disappear from the component-graph check. It also verifies that the CQEngine JAR's
SHA-256 and SHA-512 digests match both the staged file and the exact repository-relative entry in the producer's
verified publication inventory.

Each graph runs four probes:

- core query, `ResultSet` close, CQN parser, explicit typed lambda attribute, Javassist generation and Kryo trusted plus
  registered-types round trips on Java 21 and Java 25; and
- JDBC service loading, current-platform native extraction and byte verification, SQLite 3.53.2 integrity and compile
  options, disk create/close/reopen and off-heap persistence on Java 21 and Java 25.

No process receives `--add-opens`. Java 25 classpath persistence receives
`--enable-native-access=ALL-UNNAMED`. Java 25 module-path persistence grants native access only to
`org.xerial.sqlitejdbc`; Java 21 and all core processes receive no native-access option. Module probes invoke `java --module-path ... --module ...` directly, require an empty `java.class.path`, and
assert the expected consumer, CQEngine and SQLite modules. The build removes inherited
`JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` and `_JAVA_OPTIONS` from every probe process and checks its effective JVM
arguments before launch.

Each persistence process uses a fresh isolated SQLite extraction directory. Its retained evidence records the raw OS
and architecture, sqlite-jdbc platform folder, selected native resource, resource and extracted SHA-256, native-mode
result, SQLite version and integrity result, and a digest of the complete compile-option set. A host run claims only that its selected native loaded; it does not claim to execute binaries for other operating systems or architectures.

The CQEngine JAR remains an automatic module named `cqengine`; this proves automatic-JPMS compatibility, not strong
encapsulation. A modular consumer using attribute generation or Kryo must export its model package and selectively
open it to the modules CQEngine uses; the fixture descriptor is a concrete example.

From the producer checkout, run:

```bash
./gradlew consumerTest
```

The root task deletes and republishes `build/local-repository`, verifies the staged library, sources, Javadocs,
POM, Gradle metadata, artifact-level Maven metadata and every MD5/SHA-1/SHA-256/SHA-512 sidecar, then stages this
standalone build beneath `build/consumer-tests` with a copy of
the producer's strict dependency-verification metadata and keyring. The staged copy adds one exact-version trust rule
for CQEngine itself because `verifyPublication` has already checked every local artifact and checksum; all third-party
artifacts and metadata remain under strict Gradle verification. It launches three isolated graph runs with refreshed
metadata. To run a Gradle-metadata fixture directly after staging and publication:

```bash
./gradlew stageExternalConsumerBuild verifyPublication
./gradlew -p build/consumer-tests :thin:consumerTest \
  --dependency-verification strict \
  -PcqengineRepository="$PWD/build/local-repository" \
  -PproducerRoot="$PWD" \
  -PcqengineVersion=4.0.0
```

Add `-PpomOnly=true` to run the same fixture from Maven POM metadata only.

Use `:thin-module:consumerTest` to run the staged named-module fixture directly.

Third-party dependencies can resolve from Maven Central on the first thin run. Subsequent runs are offline-friendly
when those fixed artifacts and the Gradle wrapper are cached; CQEngine itself is exclusively resolved from the passed
project-local Maven repository. Neither consumer project applies `maven-publish` or produces a publication. The staged
build fails before resolution if its copied verification controls do not cover a third-party artifact or metadata file.
