import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import javax.inject.Inject

abstract class RunJcstress @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:Classpath
    abstract val runtimeClasspath: ConfigurableFileCollection

    @get:Internal
    abstract val javaExecutable: RegularFileProperty

    @get:Input
    abstract val mode: Property<String>

    @get:Input
    abstract val forks: Property<Int>

    @get:Input
    abstract val testPattern: Property<String>

    @get:Input
    abstract val expectedTestCount: Property<Int>

    @get:Input
    abstract val jvmArguments: ListProperty<String>

    @get:OutputDirectory
    abstract val resultDirectory: DirectoryProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun run() {
        val results = resultDirectory.get().asFile
        results.deleteRecursively()
        results.mkdirs()
        val configuredJvmArguments = jvmArguments.get()
        val standardOutput = ByteArrayOutputStream()
        val errorOutput = ByteArrayOutputStream()
        val execution = execOperations.javaexec {
            executable = javaExecutable.get().asFile.absolutePath
            workingDir(results)
            classpath(runtimeClasspath)
            mainClass.set("org.openjdk.jcstress.Main")
            jvmArgs(configuredJvmArguments)
            args(
                "-m", mode.get(),
                "-f", forks.get().toString(),
                "-r", results.absolutePath,
                "-t", testPattern.get(),
                "-v",
            )
            this.standardOutput = standardOutput
            this.errorOutput = errorOutput
            isIgnoreExitValue = true
            listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS", "GRADLE_OPTS")
                .forEach { environment.remove(it) }
        }
        val output = standardOutput.toString(StandardCharsets.UTF_8)
        val errors = errorOutput.toString(StandardCharsets.UTF_8)
        val requiredEmptyClassifications = listOf("Interesting tests", "Failed tests", "Error tests")
        val missingOrNonEmpty = requiredEmptyClassifications.filter { classification ->
            !Regex("(?m)^\\s*${Regex.escape(classification)}:\\s+No matches[.]\\s*$").containsMatchIn(output)
        }
        val accepted = Regex(
            "(?m)^\\s*All remaining tests:\\s+${expectedTestCount.get()} matching test results[.]\\s*$",
        ).containsMatchIn(output)
        val passed = execution.exitValue == 0 && missingOrNonEmpty.isEmpty() && accepted
        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(
            buildString {
                appendLine("formatVersion=1")
                appendLine("status=${if (passed) "passed" else "failed"}")
                appendLine("harness=org.openjdk.jcstress:jcstress-core:0.16")
                appendLine("mode=${mode.get()}")
                appendLine("forks=${forks.get()}")
                appendLine("tests=${testPattern.get()}")
                appendLine("expectedTests=${expectedTestCount.get()}")
                appendLine("jvmArguments=$configuredJvmArguments")
                appendLine("exitCode=${execution.exitValue}")
                appendLine("emptyClassifications=${requiredEmptyClassifications - missingOrNonEmpty}")
                appendLine("--- stdout ---")
                append(output)
                if (!output.endsWith(System.lineSeparator())) appendLine()
                appendLine("--- stderr ---")
                append(errors)
                if (!errors.endsWith(System.lineSeparator())) appendLine()
            },
            StandardCharsets.UTF_8,
        )

        if (execution.exitValue != 0) {
            throw GradleException("JCStress exited with ${execution.exitValue}; see $report")
        }
        if (missingOrNonEmpty.isNotEmpty()) {
            throw GradleException(
                "JCStress did not report an empty fail-closed classification for $missingOrNonEmpty; see $report",
            )
        }
        if (!accepted) {
            throw GradleException(
                "JCStress did not report exactly ${expectedTestCount.get()} accepted tests; see $report",
            )
        }
    }
}

abstract class RunSoak @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:Classpath
    abstract val runtimeClasspath: ConfigurableFileCollection

    @get:Internal
    abstract val javaExecutable: RegularFileProperty

    @get:Input
    abstract val arguments: ListProperty<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun run() {
        val standardOutput = ByteArrayOutputStream()
        val errorOutput = ByteArrayOutputStream()
        val execution = execOperations.javaexec {
            executable = javaExecutable.get().asFile.absolutePath
            classpath(runtimeClasspath)
            mainClass.set("io.github.shuaibrao.cqengine.stress.ConcurrentCollectionSoak")
            args(arguments.get())
            this.standardOutput = standardOutput
            this.errorOutput = errorOutput
            isIgnoreExitValue = true
            listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS", "GRADLE_OPTS")
                .forEach { environment.remove(it) }
        }
        val output = standardOutput.toString(StandardCharsets.UTF_8)
        val errors = errorOutput.toString(StandardCharsets.UTF_8)
        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(output, StandardCharsets.UTF_8)
        if (execution.exitValue != 0) {
            throw GradleException(
                "CQEngine soak exited with ${execution.exitValue}; see $report\n${errors.trim()}",
            )
        }
        val statusCount = output.lineSequence().count { it == "status=passed" }
        val digestCount = output.lineSequence().count { it.matches(Regex("final[.]sha256=[0-9a-f]{64}")) }
        if (statusCount != 1 || digestCount != 1 || errors.isNotBlank()) {
            throw GradleException("CQEngine soak did not produce one clean, verified report; see $report")
        }
    }
}

plugins {
    java
}

description = "Non-published CQEngine concurrency stress and soak qualification"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(project(":"))
    compileOnly(libs.jcstress.core)
    annotationProcessor(libs.jcstress.core)
    runtimeOnly(libs.jcstress.core)
}

configurations.configureEach {
    resolutionStrategy.failOnVersionConflict()
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror", "-Xmaxwarns", "1000"))
}

tasks.jar {
    enabled = false
}

val java21Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}
val java25Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}
val stressTestPattern =
    "io[.]github[.]shuaibrao[.]cqengine[.]stress[.]" +
        "(ConcurrentAddVisibilityStress|ConcurrentDistinctWriterStress|ObjectLockingMutationStress)"
val java25JvmArguments = listOf(
    "--enable-native-access=ALL-UNNAMED",
    "--sun-misc-unsafe-memory-access=allow",
)

fun registerJcstress(
    taskName: String,
    runtime: Int,
    mode: String,
    forks: Int,
) = tasks.register<RunJcstress>(taskName) {
    description = "Runs the CQEngine JCStress suite on Java $runtime in $mode mode."
    group = "verification"
    dependsOn(tasks.classes)
    runtimeClasspath.from(sourceSets.main.get().runtimeClasspath)
    javaExecutable.set(
        (if (runtime == 21) java21Launcher else java25Launcher).map { it.executablePath },
    )
    this.mode.set(mode)
    this.forks.set(forks)
    testPattern.set(stressTestPattern)
    expectedTestCount.set(3)
    jvmArguments.set(if (runtime == 25) java25JvmArguments else emptyList())
    resultDirectory.set(layout.buildDirectory.dir("reports/$taskName/results"))
    reportFile.set(layout.buildDirectory.file("reports/$taskName/report.txt"))
    outputs.upToDateWhen { false }
}

val jcstressSmoke = registerJcstress("jcstressSmoke", 25, "sanity", 1)
val jcstressJava21 = registerJcstress("jcstressJava21", 21, "default", 3)
val jcstressJava25 = registerJcstress("jcstressJava25", 25, "default", 3)

tasks.register("jcstress") {
    description = "Runs the authoritative CQEngine JCStress suite on Java 21 and Java 25."
    group = "verification"
    dependsOn(jcstressJava21, jcstressJava25)
}

fun soakArguments(
    durationMillis: Provider<String>,
    seed: Provider<String>,
    writers: Provider<String>,
    readers: Provider<String>,
    keySpace: Provider<String>,
    groups: Provider<String>,
) = providers.provider {
    listOf(
        "--duration-millis=${durationMillis.get()}",
        "--seed=${seed.get()}",
        "--writers=${writers.get()}",
        "--readers=${readers.get()}",
        "--key-space=${keySpace.get()}",
        "--groups=${groups.get()}",
    )
}

fun registerSoak(taskName: String, taskArguments: Provider<List<String>>) = tasks.register<RunSoak>(taskName) {
    description = "Runs the bounded CQEngine concurrent read/write $taskName lane."
    group = "verification"
    dependsOn(tasks.classes)
    runtimeClasspath.from(sourceSets.main.get().runtimeClasspath)
    javaExecutable.set(java25Launcher.map { it.executablePath })
    arguments.set(taskArguments)
    reportFile.set(layout.buildDirectory.file("reports/$taskName/report.properties"))
    outputs.upToDateWhen { false }
}

val soakSmoke = registerSoak(
    "soakSmoke",
    soakArguments(
        providers.gradleProperty("cqengine.soak.smokeMillis").orElse("1000"),
        providers.provider { "7640891576956012809" },
        providers.provider { "2" },
        providers.provider { "2" },
        providers.provider { "256" },
        providers.provider { "16" },
    ),
)

registerSoak(
    "soak",
    soakArguments(
        providers.gradleProperty("cqengine.soak.durationMillis").orElse("60000"),
        providers.gradleProperty("cqengine.soak.seed").orElse("7640891576956012809"),
        providers.gradleProperty("cqengine.soak.writers").orElse("4"),
        providers.gradleProperty("cqengine.soak.readers").orElse("8"),
        providers.gradleProperty("cqengine.soak.keySpace").orElse("4096"),
        providers.gradleProperty("cqengine.soak.groups").orElse("64"),
    ),
)

val soakQualification = registerSoak(
    "soakQualification",
    soakArguments(
        providers.provider { "900000" },
        providers.provider { "7640891576956012809" },
        providers.provider { "4" },
        providers.provider { "8" },
        providers.provider { "4096" },
        providers.provider { "64" },
    ),
)

tasks.register("concurrencyQualification") {
    description = "Runs authoritative dual-JDK JCStress and the fixed-duration CQEngine soak."
    group = "verification"
    dependsOn(jcstressJava21, jcstressJava25, soakQualification)
}

tasks.register("concurrencySmoke") {
    description = "Runs short JCStress and CQEngine soak smoke lanes."
    group = "verification"
    dependsOn(jcstressSmoke, soakSmoke)
}
