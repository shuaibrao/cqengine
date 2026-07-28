# Using CQEngine artifacts

CQEngine 4.0 is currently unreleased. A local build stages its Maven-format repository in
`build/local-repository/`. The same coordinate and artifact names will be published to Maven Central after the
release is qualified.

Gradle consumers should normally select the canonical thin library:

```kotlin
repositories {
    maven { url = uri("/path/to/cqengine/build/local-repository") }
}

dependencies {
    implementation("io.github.shuaibrao:cqengine:4.0.0-rc.1")
}
```

The equivalent Maven dependency is:

```xml
<dependency>
    <groupId>io.github.shuaibrao</groupId>
    <artifactId>cqengine</artifactId>
    <version>4.0.0-rc.1</version>
</dependency>
```

The coordinate contains three artifact forms:

| Artifact | Purpose |
|---|---|
| `cqengine-<version>.jar` | Canonical thin library with declared runtime dependencies |
| `cqengine-<version>-sources.jar` | Source attachment |
| `cqengine-<version>-javadoc.jar` | Current API documentation |

The library JAR is the only runtime artifact and is also the canonical OSGi bundle. It declares its runtime
dependencies, so a consumer resolves and upgrades ANTLR, Kryo, SQLite and the other libraries directly rather than
inheriting whichever versions a shaded build embedded.

CQEngine 3.x also published a shaded `all` classifier. Version 4.0 does not; see
[migrating to CQEngine 4](MigrationTo4.md) if you depended on it.

See [Java compatibility](JavaCompatibility.md) for classpath, module-path, OSGi and native-access requirements, and
[Releasing CQEngine](../RELEASING.md) for producing the verified local repository.

## Historical upstream download statistics

The following December 2014 chart and count are retained as CQEngine project history. They are not download evidence
for the current coordinate.

![http://chart.googleapis.com/chart?chxl=0:|07/12|08/12|09/12|10/12|11/12|12/12|01/13|02/13|03/13|04/13|05/13|06/13|07/13|08/13|09/13|10/13|11/13|12/13|01/14|02/14|03/14|04/14|05/14|06/14|07/14|08/14|09/14|10/14|11/14|12/14&chxr=0,0,10|1,0,500&chxs=0,676767,11.5,-0.333,t,676767&chxt=x,y&chs=1000x300&cht=lc&chds=0,500&chd=t:4,10,41,20,108,209,123,143,186,168,187,323,161,259,192,339,159,303,399,486,562,402,408,345,245,235,435,293,483,382&chdl=Downloads+-+Maven+Central+per+month&chdlp=b&chls=0.667&chma=2,0,7|17,28&chm=B,C5D4B5BB,0,0,0&chtt=CQEngine+Maven+Central&dummy=foo.png](http://chart.googleapis.com/chart?chxl=0:|07/12|08/12|09/12|10/12|11/12|12/12|01/13|02/13|03/13|04/13|05/13|06/13|07/13|08/13|09/13|10/13|11/13|12/13|01/14|02/14|03/14|04/14|05/14|06/14|07/14|08/14|09/14|10/14|11/14|12/14&chxr=0,0,10|1,0,500&chxs=0,676767,11.5,-0.333,t,676767&chxt=x,y&chs=1000x300&cht=lc&chds=0,500&chd=t:4,10,41,20,108,209,123,143,186,168,187,323,161,259,192,339,159,303,399,486,562,402,408,345,245,235,435,293,483,382&chdl=Downloads+-+Maven+Central+per+month&chdlp=b&chls=0.667&chma=2,0,7|17,28&chm=B,C5D4B5BB,0,0,0&chtt=CQEngine+Maven+Central&dummy=foo.png)

The upstream project recorded 8,516 downloads at that time (7,610 Maven and 906 non-Maven).
