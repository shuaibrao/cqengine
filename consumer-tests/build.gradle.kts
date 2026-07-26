import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.PathSensitivity
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

plugins {
    base
}

val cqengineVersion = providers.gradleProperty("cqengineVersion").orNull
    ?: throw GradleException("Pass -PcqengineVersion=<exact staged version>")
val cqengineCoordinate = "io.github.shuaibrao:cqengine:$cqengineVersion"
rootProject.extra["cqengineCoordinate"] = cqengineCoordinate
val cqengineRepository = providers.gradleProperty("cqengineRepository").orNull
    ?: throw GradleException("Pass -PcqengineRepository=<absolute path to build/local-repository>")
val producerRoot = providers.gradleProperty("producerRoot").orNull
    ?: throw GradleException("Pass -PproducerRoot=<absolute path to the CQEngine checkout>")
val metadataMode = providers.gradleProperty("pomOnly").map { value ->
    if (value.toBooleanStrictOrNull() == true) "pom" else "gradle"
}.orElse("gradle").get()

val publicationInventoryParserRegression by tasks.registering {
    group = "verification"
    description = "Proves strict matching of repository-relative publication inventory entries."

    doLast {
        val coordinate = "io.github.shuaibrao:cqengine:1.2.3-test.1"
        val artifactPath = "io/github/shuaibrao/cqengine/1.2.3-test.1/cqengine-1.2.3-test.1.jar"
        val sha256 = "a".repeat(64)
        val sha512 = "b".repeat(128)
        val header = "coordinate=$coordinate"
        val exactEntry = "$sha256 $sha512  $artifactPath"
        val sidecarEntry = "$sha256 $sha512  $artifactPath.sha256"
        val validInventory = listOf(header, exactEntry, sidecarEntry)

        check(
            publicationInventoryDigests(validInventory, coordinate, artifactPath) ==
                PublicationInventoryDigests(sha256, sha512),
        ) { "The exact repository-relative publication entry was not selected" }

        fun expectRejected(label: String, expectedMessage: String, lines: List<String>) {
            val failure = runCatching {
                publicationInventoryDigests(lines, coordinate, artifactPath)
            }.exceptionOrNull()
            check(failure is GradleException && failure.message.orEmpty().contains(expectedMessage)) {
                "$label was not rejected with '$expectedMessage': $failure"
            }
        }

        expectRejected(
            "wrong coordinate",
            "coordinate header",
            listOf("coordinate=com.example:cqengine:1.2.3-test.1", exactEntry),
        )
        expectRejected(
            "shortened path",
            "no unique entry",
            listOf(header, "$sha256 $sha512  1.2.3-test.1/cqengine-1.2.3-test.1.jar"),
        )
        expectRejected(
            "same file name under another coordinate",
            "no unique entry",
            listOf(header, "$sha256 $sha512  example/cqengine-1.2.3-test.1.jar"),
        )
        expectRejected(
            "duplicate exact path",
            "duplicate paths",
            listOf(header, exactEntry, exactEntry),
        )
        expectRejected(
            "malformed SHA-256",
            "malformed entry",
            listOf(header, "${sha256.dropLast(1)} $sha512  $artifactPath"),
        )
        expectRejected(
            "unsafe relative path",
            "unsafe path",
            listOf(header, "$sha256 $sha512  unsafe/../cqengine.jar"),
        )
        expectRejected(
            "drive-qualified path",
            "unsafe path",
            listOf(header, "$sha256 $sha512  C:/cqengine.jar"),
        )
    }
}

subprojects {
    group = "io.github.shuaibrao.cqengine.verification"
    version = "1"
    apply(plugin = "java")
    val launchMode = if (name.endsWith("-module")) "module" else "classpath"
    val artifactMode = name.removeSuffix("-module")
    val canonicalProducerRoot = file(producerRoot).canonicalFile
    val canonicalRepository = file(cqengineRepository).canonicalFile
    val publicationInventory = canonicalProducerRoot.resolve("build/reports/publication/inventory.txt")
    val versionDirectory = canonicalRepository.resolve("io/github/shuaibrao/cqengine/$cqengineVersion")

    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }

        extensions.configure<SourceSetContainer> {
            named("main") {
                java.setSrcDirs(listOf(rootProject.file("src/main/java")))
                if (launchMode == "module") {
                    java.srcDir(rootProject.file("src/module/$artifactMode"))
                }
            }
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    configurations.configureEach {
        resolutionStrategy.cacheChangingModulesFor(0, "seconds")
        resolutionStrategy.cacheDynamicVersionsFor(0, "seconds")
        resolutionStrategy.failOnVersionConflict()
    }

    val runtimeClasspath = configurations.named("runtimeClasspath")
    val graphReport = layout.buildDirectory.file("reports/consumer/resolved-graph.txt")

    val verifyResolution by tasks.registering {
        group = "verification"
        description = "Verifies the external $artifactMode consumer graph and writes its inventory."
        dependsOn(rootProject.tasks.named("publicationInventoryParserRegression"))
        inputs.files(runtimeClasspath)
        inputs.file(publicationInventory).withPathSensitivity(PathSensitivity.NONE)
        inputs.dir(versionDirectory).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.property("artifactMode", artifactMode)
        inputs.property("launchMode", launchMode)
        inputs.property("metadataMode", metadataMode)
        inputs.property("cqengineCoordinate", cqengineCoordinate)
        inputs.property("producerRoot", canonicalProducerRoot.path)
        inputs.property("repository", canonicalRepository.path)
        outputs.file(graphReport)

        doLast {
            val configuration = runtimeClasspath.get()
            val resolutionResult = configuration.incoming.resolutionResult
            val rootComponentId = resolutionResult.rootComponent.get().id
            val dependencyComponents = resolutionResult.allComponents
                .map { it.id }
                .filter { it != rootComponentId }
            val projectComponents = dependencyComponents.filterIsInstance<ProjectComponentIdentifier>()
            check(projectComponents.isEmpty()) {
                "External consumer resolved project components: $projectComponents"
            }
            val nonModuleComponents = dependencyComponents.filterNot { it is ModuleComponentIdentifier }
            check(nonModuleComponents.isEmpty()) {
                "External consumer resolved non-module components: $nonModuleComponents"
            }

            val modules = dependencyComponents
                .filterIsInstance<ModuleComponentIdentifier>()
                .map { "${it.group}:${it.module}:${it.version}" }
                .toSortedSet()
            val expectedThinModules = sortedSetOf(
                cqengineCoordinate,
                "com.esotericsoftware:kryo:5.6.2",
                "com.esotericsoftware:minlog:1.3.1",
                "com.esotericsoftware:reflectasm:1.11.9",
                "com.googlecode.concurrent-trees:concurrent-trees:2.6.1",
                "org.antlr:antlr4-runtime:4.13.2",
                "org.javassist:javassist:3.32.0-GA",
                "org.objenesis:objenesis:3.4",
                "org.xerial:sqlite-jdbc:3.53.2.0",
            )
            val expectedModules = if (artifactMode == "thin") {
                expectedThinModules
            }
            else {
                sortedSetOf(cqengineCoordinate)
            }
            check(modules == expectedModules) {
                "Unexpected $artifactMode consumer modules. Expected $expectedModules, resolved $modules"
            }

            val artifacts = configuration.resolvedConfiguration.resolvedArtifacts
                .sortedBy { "${it.moduleVersion.id}:${it.classifier.orEmpty()}:${it.file.name}" }
            val artifactFiles = artifacts.mapTo(sortedSetOf()) { it.file.canonicalPath }
            val configurationFiles = configuration.files.mapTo(sortedSetOf()) { it.canonicalPath }
            check(configurationFiles == artifactFiles) {
                "Runtime classpath contains non-module or substituted files. " +
                    "Expected $artifactFiles, resolved $configurationFiles"
            }
            val cqengineArtifacts = artifacts.filter {
                it.moduleVersion.id.group == "io.github.shuaibrao" && it.moduleVersion.id.name == "cqengine"
            }
            check(cqengineArtifacts.size == 1) {
                "Expected one CQEngine artifact, resolved ${cqengineArtifacts.map { it.file.name }}"
            }
            val expectedClassifier = if (artifactMode == "all") "all" else null
            check(cqengineArtifacts.single().classifier == expectedClassifier) {
                "Expected classifier ${expectedClassifier ?: "<none>"}, resolved " +
                    (cqengineArtifacts.single().classifier ?: "<none>")
            }

            val stagedPom = versionDirectory.listFiles()
                ?.singleOrNull { it.isFile && it.extension == "pom" }
                ?: throw GradleException("Expected one staged POM in $versionDirectory")
            val artifactStem = stagedPom.name.removeSuffix(".pom")
            val stagedArtifactName = if (artifactMode == "all") "$artifactStem-all.jar" else "$artifactStem.jar"
            val stagedArtifact = versionDirectory.resolve(stagedArtifactName)
            check(stagedArtifact.isFile) { "Missing staged artifact: $stagedArtifact" }
            val resolvedSha256 = digest(cqengineArtifacts.single().file, "SHA-256")
            val resolvedSha512 = digest(cqengineArtifacts.single().file, "SHA-512")
            val stagedSha256 = digest(stagedArtifact, "SHA-256")
            val stagedSha512 = digest(stagedArtifact, "SHA-512")
            check(resolvedSha256 == stagedSha256 && resolvedSha512 == stagedSha512) {
                "Resolved CQEngine artifact does not match the staged publication: " +
                    "SHA-256 $resolvedSha256 != $stagedSha256 or SHA-512 $resolvedSha512 != $stagedSha512"
            }
            val stagedArtifactRelativePath = canonicalRepository.toPath()
                .relativize(stagedArtifact.canonicalFile.toPath())
                .joinToString("/") { it.toString() }
            val (inventorySha256, inventorySha512) = publicationInventoryDigests(
                publicationInventory.readLines(StandardCharsets.UTF_8),
                cqengineCoordinate,
                stagedArtifactRelativePath,
            )
            check(resolvedSha256 == inventorySha256 && resolvedSha512 == inventorySha512) {
                "Resolved CQEngine artifact does not match the verified publication inventory: " +
                    "SHA-256 $resolvedSha256 != $inventorySha256 or " +
                    "SHA-512 $resolvedSha512 != $inventorySha512"
            }
            if (artifactMode == "all") {
                check(artifacts.size == 1) {
                    "The all consumer must resolve one non-transitive artifact: ${artifacts.map { it.file.name }}"
                }
            }
            else {
                check(artifacts.size == expectedThinModules.size) {
                    "Thin consumer expected ${expectedThinModules.size} JARs, resolved ${artifacts.map { it.file.name }}"
                }
            }

            val producerPath = canonicalProducerRoot.toPath()
            val repositoryPath = canonicalRepository.toPath()
            artifacts.forEach { artifact ->
                check(artifact.file.isFile && artifact.file.extension == "jar") {
                    "Consumer artifact is not a JAR file: ${artifact.file}"
                }
                val artifactPath = artifact.file.canonicalFile.toPath()
                check(!artifactPath.startsWith(producerPath.resolve("build/classes"))) {
                    "Consumer leaked producer classes: $artifactPath"
                }
                check(!artifactPath.startsWith(producerPath.resolve("build/resources"))) {
                    "Consumer leaked producer resources: $artifactPath"
                }
                check(!artifactPath.startsWith(producerPath) || artifactPath.startsWith(repositoryPath)) {
                    "Consumer classpath leaked a producer checkout path outside the staged repository: $artifactPath"
                }
            }

            val report = buildString {
                appendLine("mode=$artifactMode")
                appendLine("launch=$launchMode")
                appendLine("metadata=$metadataMode")
                appendLine("coordinate=$cqengineCoordinate")
                appendLine("cqengineSha256=$resolvedSha256")
                appendLine("cqengineSha512=$resolvedSha512")
                appendLine("repository=${canonicalRepository.path}")
                appendLine("modules:")
                modules.forEach { appendLine("  $it") }
                appendLine("artifacts:")
                artifacts.forEach { artifact ->
                    appendLine(
                        "  ${artifact.moduleVersion.id} classifier=${artifact.classifier ?: "<none>"} " +
                            "file=${artifact.file.canonicalPath}",
                    )
                }
            }
            graphReport.get().asFile.apply {
                parentFile.mkdirs()
                writeText(report)
            }
            println(report)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        dependsOn(verifyResolution)
    }

    plugins.withType<JavaPlugin> {
        val sourceSets = extensions.getByType<SourceSetContainer>()
        val javaToolchains = extensions.getByType<JavaToolchainService>()

        fun registerClasspathProbe(
            taskName: String,
            mainClassName: String,
            javaVersion: Int,
            nativeAccess: Boolean,
        ): TaskProvider<out Task> = tasks.register<JavaExec>(taskName) {
            val persistenceProbe = mainClassName.endsWith(".PersistenceConsumerProbe")
            val nativeEvidence = layout.buildDirectory.file(
                "reports/consumer/sqlite-native-java$javaVersion.properties",
            )
            val nativeExtractionDirectory = layout.buildDirectory.dir(
                "sqlite-native-extraction/java$javaVersion",
            )
            group = "verification"
            description = "Runs $mainClassName for the $artifactMode artifact on Java $javaVersion."
            dependsOn(verifyResolution, tasks.named("classes"))
            classpath = sourceSets.named("main").get().runtimeClasspath
            mainClass.set(mainClassName)
            javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(javaVersion))
            })
            systemProperty("consumer.artifactMode", artifactMode)
            systemProperty("consumer.launchMode", launchMode)
            systemProperty("consumer.producerRoot", canonicalProducerRoot.path)
            systemProperty("consumer.expectedJava", javaVersion)
            if (persistenceProbe) {
                systemProperty("consumer.nativeEvidenceFile", nativeEvidence.get().asFile.absolutePath)
                systemProperty("org.sqlite.tmpdir", nativeExtractionDirectory.get().asFile.absolutePath)
                outputs.file(nativeEvidence)
                outputs.upToDateWhen { false }
            }
            if (nativeAccess) {
                jvmArgs("--enable-native-access=ALL-UNNAMED")
            }
            environment.remove("JAVA_TOOL_OPTIONS")
            environment.remove("JDK_JAVA_OPTIONS")
            environment.remove("_JAVA_OPTIONS")

            doFirst {
                if (persistenceProbe) {
                    val extractionDirectory = nativeExtractionDirectory.get().asFile
                    check(!extractionDirectory.exists() || extractionDirectory.deleteRecursively()) {
                        "Could not clear SQLite extraction directory: $extractionDirectory"
                    }
                    check(extractionDirectory.mkdirs()) {
                        "Could not create SQLite extraction directory: $extractionDirectory"
                    }
                    Files.deleteIfExists(nativeEvidence.get().asFile.toPath())
                }
                val expectedClasspath = (
                    sourceSets.named("main").get().output.files + runtimeClasspath.get().files
                ).mapTo(sortedSetOf()) { it.canonicalPath }
                val effectiveClasspath = classpath.files.mapTo(sortedSetOf()) { it.canonicalPath }
                check(effectiveClasspath == expectedClasspath) {
                    "Consumer process classpath was modified. Expected $expectedClasspath, got $effectiveClasspath"
                }
                val opens = allJvmArgs.filter {
                    it == "--add-opens" || it.startsWith("--add-opens=")
                }
                check(opens.isEmpty()) { "Consumer probe received forbidden module opens: $opens" }
                val nativeOptions = allJvmArgs.filter {
                    it == "--enable-native-access" || it.startsWith("--enable-native-access=")
                }
                val expectedNativeOptions = if (nativeAccess) {
                    listOf("--enable-native-access=ALL-UNNAMED")
                }
                else {
                    emptyList()
                }
                check(nativeOptions == expectedNativeOptions) {
                    "Expected native-access options $expectedNativeOptions, received $nativeOptions"
                }
            }
        }

        fun registerModuleProbe(
            taskName: String,
            mainClassName: String,
            javaVersion: Int,
            nativeAccess: Boolean,
        ): TaskProvider<out Task> {
            val consumerJar = tasks.named<Jar>("jar")
            val javaLauncher = javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(javaVersion))
            }
            val persistenceProbe = mainClassName.endsWith(".PersistenceConsumerProbe")
            val nativeEvidence = layout.buildDirectory.file(
                "reports/consumer/sqlite-native-java$javaVersion.properties",
            )
            val nativeExtractionDirectory = layout.buildDirectory.dir(
                "sqlite-native-extraction/java$javaVersion",
            )
            return tasks.register<Exec>(taskName) {
                group = "verification"
                description = "Runs $mainClassName for the $artifactMode artifact as a named module on Java $javaVersion."
                dependsOn(verifyResolution, consumerJar)
                inputs.files(runtimeClasspath, consumerJar)
                if (persistenceProbe) {
                    outputs.file(nativeEvidence)
                    outputs.upToDateWhen { false }
                }
                environment.remove("JAVA_TOOL_OPTIONS")
                environment.remove("JDK_JAVA_OPTIONS")
                environment.remove("_JAVA_OPTIONS")

                doFirst {
                    if (persistenceProbe) {
                        val extractionDirectory = nativeExtractionDirectory.get().asFile
                        check(!extractionDirectory.exists() || extractionDirectory.deleteRecursively()) {
                            "Could not clear SQLite extraction directory: $extractionDirectory"
                        }
                        check(extractionDirectory.mkdirs()) {
                            "Could not create SQLite extraction directory: $extractionDirectory"
                        }
                        Files.deleteIfExists(nativeEvidence.get().asFile.toPath())
                    }
                    val consumerJarFile = consumerJar.get().archiveFile.get().asFile.canonicalFile
                    val dependencyJars = runtimeClasspath.get().files.map { it.canonicalFile }.sortedBy { it.path }
                    check(dependencyJars.all { it.isFile && it.extension == "jar" }) {
                        "Module path contains a non-JAR dependency: $dependencyJars"
                    }
                    val modulePathFiles = listOf(consumerJarFile) + dependencyJars
                    check(modulePathFiles.map { it.path }.toSet().size == modulePathFiles.size) {
                        "Module path contains duplicate entries: $modulePathFiles"
                    }

                    val arguments = mutableListOf(
                        javaLauncher.get().executablePath.asFile.absolutePath,
                        "--module-path",
                        modulePathFiles.joinToString(File.pathSeparator) { it.path },
                        "-Dconsumer.artifactMode=$artifactMode",
                        "-Dconsumer.launchMode=module",
                        "-Dconsumer.producerRoot=${canonicalProducerRoot.path}",
                        "-Dconsumer.expectedJava=$javaVersion",
                    )
                    if (persistenceProbe) {
                        arguments.add(
                            "-Dconsumer.nativeEvidenceFile=${nativeEvidence.get().asFile.absolutePath}",
                        )
                        arguments.add(
                            "-Dorg.sqlite.tmpdir=${nativeExtractionDirectory.get().asFile.absolutePath}",
                        )
                    }
                    if (nativeAccess) {
                        val nativeModule = if (artifactMode == "thin") "org.xerial.sqlitejdbc" else "cqengine"
                        arguments.add("--enable-native-access=$nativeModule")
                    }
                    arguments.add("--module")
                    arguments.add("io.github.shuaibrao.cqengine.consumer/$mainClassName")

                    check(arguments.none { it == "-cp" || it == "-classpath" || it == "--class-path" }) {
                        "Module probe contains a classpath option: $arguments"
                    }
                    check(arguments.none { it == "--add-opens" || it.startsWith("--add-opens=") }) {
                        "Module probe contains forbidden module opens: $arguments"
                    }
                    commandLine(arguments)
                }
            }
        }

        fun registerProbe(
            taskName: String,
            mainClassName: String,
            javaVersion: Int,
            nativeAccess: Boolean,
        ): TaskProvider<out Task> = if (launchMode == "module") {
            registerModuleProbe(taskName, mainClassName, javaVersion, nativeAccess)
        }
        else {
            registerClasspathProbe(taskName, mainClassName, javaVersion, nativeAccess)
        }

        val coreJava21 = registerProbe(
            "coreJava21",
            "io.github.shuaibrao.cqengine.consumer.CoreConsumerProbe",
            21,
            false,
        )
        val persistenceJava21 = registerProbe(
            "persistenceJava21",
            "io.github.shuaibrao.cqengine.consumer.PersistenceConsumerProbe",
            21,
            false,
        )
        val coreJava25 = registerProbe(
            "coreJava25",
            "io.github.shuaibrao.cqengine.consumer.CoreConsumerProbe",
            25,
            false,
        )
        val persistenceJava25 = registerProbe(
            "persistenceJava25",
            "io.github.shuaibrao.cqengine.consumer.PersistenceConsumerProbe",
            25,
            true,
        )

        tasks.register("consumerTest") {
            group = "verification"
            description = "Runs the $artifactMode external consumer on Java 21 and Java 25."
            dependsOn(coreJava21, persistenceJava21, coreJava25, persistenceJava25)
        }
    }
}

fun digest(file: File, algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

data class PublicationInventoryDigests(val sha256: String, val sha512: String)

private data class PublicationInventoryEntry(
    val sha256: String,
    val sha512: String,
    val relativePath: String,
)

private val publicationInventoryEntryPattern = Regex("^([0-9a-f]{64}) ([0-9a-f]{128})  (.+)$")
private val publicationInventoryRelativePathPattern = Regex("[A-Za-z0-9._+-]+(?:/[A-Za-z0-9._+-]+)*")

fun publicationInventoryDigests(
    lines: List<String>,
    expectedCoordinate: String,
    expectedRelativePath: String,
): PublicationInventoryDigests {
    val expectedHeader = "coordinate=$expectedCoordinate"
    if (lines.firstOrNull() != expectedHeader) {
        throw GradleException("Publication inventory has the wrong coordinate header; expected $expectedHeader")
    }
    val entries = lines.drop(1).mapIndexed { index, line ->
        val match = publicationInventoryEntryPattern.matchEntire(line)
            ?: throw GradleException("Publication inventory has a malformed entry at line ${index + 2}")
        val relativePath = match.groupValues[3]
        if (!isSafeRelativeInventoryPath(relativePath)) {
            throw GradleException("Publication inventory has an unsafe path at line ${index + 2}: $relativePath")
        }
        PublicationInventoryEntry(match.groupValues[1], match.groupValues[2], relativePath)
    }
    val duplicatePaths = entries.groupingBy(PublicationInventoryEntry::relativePath)
        .eachCount()
        .filterValues { count -> count > 1 }
        .keys
    if (duplicatePaths.isNotEmpty()) {
        throw GradleException("Publication inventory contains duplicate paths: $duplicatePaths")
    }
    val entry = entries.singleOrNull { it.relativePath == expectedRelativePath }
        ?: throw GradleException("Publication inventory has no unique entry for $expectedRelativePath")
    return PublicationInventoryDigests(entry.sha256, entry.sha512)
}

private fun isSafeRelativeInventoryPath(path: String): Boolean =
    publicationInventoryRelativePathPattern.matches(path) &&
        path.split('/').none { segment -> segment.isEmpty() || segment == "." || segment == ".." }
