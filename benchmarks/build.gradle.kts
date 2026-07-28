import groovy.json.JsonSlurper
import me.champeau.jmh.JMHTask
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.Properties
import java.util.SortedSet
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.inject.Inject

plugins {
    java
    alias(libs.plugins.jmh)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    "jmh"(project(":"))
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
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-Xmaxwarns", "1000"))
}

val java25Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}
val java21Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}
val java25BenchmarkJvmOptions = listOf(
    "--sun-misc-unsafe-memory-access=allow",
    "--enable-native-access=ALL-UNNAMED",
)
val java21BenchmarkJvmOptions = emptyList<String>()
val java21PersistenceJvmOptions = listOf("--enable-native-access=ALL-UNNAMED")
val benchmarkNamespace = "io.github.shuaibrao.cqengine.benchmark"
val benchmarkNamespacePattern = Pattern.quote(benchmarkNamespace)
val unsafeJavaOptionEnvironmentVariables = listOf(
    "JAVA_TOOL_OPTIONS",
    "JDK_JAVA_OPTIONS",
    "_JAVA_OPTIONS",
    "JAVA_OPTS",
    "GRADLE_OPTS",
)
val inheritedUnsafeJavaOptions = unsafeJavaOptionEnvironmentVariables.filter(System.getenv()::containsKey)

jmh {
    jmhVersion.set(libs.versions.jmh)
    javaLauncher.set(java25Launcher)
    includes.set(listOf("io.github.shuaibrao.cqengine.benchmark.QueryLifecycleBenchmark.*"))
    benchmarkMode.set(listOf("avgt"))
    timeUnit.set("ns")
    warmupIterations.set(3)
    warmup.set("1s")
    iterations.set(5)
    timeOnIteration.set("1s")
    fork.set(2)
    failOnError.set(true)
    forceGC.set(false)
    profilers.set(listOf("gc"))
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("reports/jmh/results.json"))
    humanOutputFile.set(layout.buildDirectory.file("reports/jmh/human.txt"))
    duplicateClassesStrategy.set(DuplicatesStrategy.EXCLUDE)
    jvmArgsAppend.set(java25BenchmarkJvmOptions)
    includeTests.set(false)
}

tasks.named<JMHTask>("jmh") {
    javaLauncher.set(java25Launcher)
}

tasks.withType<JMHTask>().configureEach {
    inheritedUnsafeJavaOptions.forEach { environment.put(it, "") }
    outputs.upToDateWhen { false }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    filePermissions {
        unix("0644")
    }
    dirPermissions {
        unix("0755")
    }
}

private class JmhBaselineScoreSupport private constructor() {
    companion object {
        fun requireNonNegativeJmhPrimaryScore(rawScore: Any?, label: String): Double {
            val score = (rawScore as? Number)?.toDouble()
                ?: throw GradleException("$label primary score is not numeric")
            if (!score.isFinite() || score < 0.0) {
                throw GradleException("$label primary score is invalid: $score")
            }
            return score
        }
    }
}

private object JmhLaneSelectionSupport {
    fun requireExactTaskCoverage(contractedTaskNames: List<String>, registeredTaskNames: List<String>) {
        if (contractedTaskNames.isEmpty()) {
            throw GradleException("At least one JMH task selection contract is required")
        }
        if (contractedTaskNames.size != contractedTaskNames.toSet().size) {
            throw GradleException("JMH task selection contracts contain duplicate task names")
        }
        if (registeredTaskNames.size != registeredTaskNames.toSet().size) {
            throw GradleException("Registered JMH task names contain duplicates")
        }
        if (contractedTaskNames.sorted() != registeredTaskNames.sorted()) {
            throw GradleException(
                "JMH task selection contracts changed. Contracted ${contractedTaskNames.sorted()}, " +
                    "registered ${registeredTaskNames.sorted()}",
            )
        }
    }

    fun selectExactBenchmarks(
        taskName: String,
        lane: String,
        includeCount: Int,
        includePattern: String,
        excludeCount: Int,
        excludePattern: String?,
        expectedBenchmarkNames: SortedSet<String>,
        discoveredBenchmarkNames: SortedSet<String>,
    ): SortedSet<String> {
        if (includeCount != 1) {
            throw GradleException(
                "$taskName must have exactly one include regex: " +
                    "JMH Gradle plugin 0.7.3 joins multiple entries with a literal comma",
            )
        }
        if (excludeCount !in 0..1) {
            throw GradleException(
                "$taskName may have at most one exclude regex: " +
                    "JMH Gradle plugin 0.7.3 joins multiple entries with a literal comma",
            )
        }
        requireValidRegexText(includePattern, taskName, "include")
        val include = compilePattern(includePattern, taskName, "include")
        val exclude = excludePattern?.let { pattern ->
            requireValidRegexText(pattern, taskName, "exclude")
            compilePattern(pattern, taskName, "exclude")
        }
        if (expectedBenchmarkNames.isEmpty()) {
            throw GradleException("$taskName ($lane) must expect at least one benchmark")
        }
        val selected = discoveredBenchmarkNames.filterTo(sortedSetOf()) { benchmark ->
            include.matcher(benchmark).find() && (exclude == null || !exclude.matcher(benchmark).find())
        }
        if (selected.isEmpty()) {
            throw GradleException("$taskName ($lane) selects no benchmarks")
        }
        if (selected != expectedBenchmarkNames) {
            throw GradleException(
                "$taskName ($lane) selects the wrong benchmarks. " +
                    "Missing ${(expectedBenchmarkNames - selected).sorted()}, " +
                    "unexpected ${(selected - expectedBenchmarkNames).sorted()}",
            )
        }
        return selected
    }

    private fun requireValidRegexText(pattern: String, taskName: String, kind: String) {
        if (pattern.isBlank() || pattern.any { character -> Character.isISOControl(character.code) }) {
            throw GradleException("$taskName has a blank or control-character JMH $kind regex")
        }
    }

    private fun compilePattern(pattern: String, taskName: String, kind: String): Pattern =
        try {
            Pattern.compile(pattern)
        }
        catch (failure: RuntimeException) {
            throw GradleException("$taskName has an invalid JMH $kind regex: $pattern", failure)
        }
}

abstract class VerifyJmhLaneSelections @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:Classpath
    abstract val discoveryClasspath: ConfigurableFileCollection

    @get:Internal
    abstract val javaExecutable: RegularFileProperty

    @get:Input
    abstract val registeredTaskNames: ListProperty<String>

    @get:Input
    abstract val selectionSpecifications: ListProperty<String>

    @get:Input
    abstract val expectedBenchmarkNames: SetProperty<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val finalReport = reportFile.get().asFile.toPath()
        Files.deleteIfExists(finalReport)

        val listingOutput = ByteArrayOutputStream()
        val listingErrors = ByteArrayOutputStream()
        val result = execOperations.javaexec {
            executable = javaExecutable.get().asFile.absolutePath
            classpath(discoveryClasspath)
            mainClass.set("org.openjdk.jmh.Main")
            jvmArgs("-XX:+PerfDisableSharedMem")
            args("-l")
            standardOutput = listingOutput
            errorOutput = listingErrors
            isIgnoreExitValue = true
            unsafeJavaOptionEnvironmentVariables.forEach { variable -> environment.remove(variable) }
        }
        val listingText = listingOutput.toString(StandardCharsets.UTF_8)
        val errorText = listingErrors.toString(StandardCharsets.UTF_8)
        if (result.exitValue != 0) {
            throw GradleException(
                "JMH benchmark discovery failed with exit ${result.exitValue}:\n" +
                    "stdout:\n${listingText.trim()}\nstderr:\n${errorText.trim()}",
            )
        }
        if (errorText.isNotBlank()) {
            throw GradleException("JMH benchmark discovery wrote unexpected stderr:\n${errorText.trim()}")
        }

        val listingLines = listingText.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        if (listingLines.firstOrNull() != benchmarkListHeader) {
            throw GradleException(
                "JMH benchmark discovery did not begin with '$benchmarkListHeader':\n${listingText.trim()}",
            )
        }
        val discoveredList = listingLines.drop(1)
        val discovered = discoveredList.toSortedSet()
        if (discovered.isEmpty()) {
            throw GradleException("JMH benchmark discovery returned no benchmark names")
        }
        if (discoveredList.size != discovered.size) {
            throw GradleException("JMH benchmark discovery returned duplicate names")
        }
        val expectedInventory = expectedBenchmarkNames.get().toSortedSet()
        if (expectedInventory.isEmpty()) {
            throw GradleException("The expected JMH runner inventory must not be empty")
        }
        if (discovered != expectedInventory) {
            throw GradleException(
                "JMH runner inventory changed. Missing ${(expectedInventory - discovered).sorted()}, " +
                    "unexpected ${(discovered - expectedInventory).sorted()}",
            )
        }

        val specifications = selectionSpecifications.get().map(::parseSpecification)
        val contractedTaskNames = specifications.map(SelectionSpecification::taskName).sorted()
        val registeredNames = registeredTaskNames.get().sorted()
        JmhLaneSelectionSupport.requireExactTaskCoverage(contractedTaskNames, registeredNames)

        val reportLines = mutableListOf(
            "status=verified",
            "jmhTasks=${specifications.size}",
            "benchmarkMethods=${discovered.size}",
        )
        specifications.sortedBy(SelectionSpecification::taskName).forEach { specification ->
            val selected = JmhLaneSelectionSupport.selectExactBenchmarks(
                taskName = specification.taskName,
                lane = specification.lane,
                includeCount = specification.includeCount,
                includePattern = specification.includePattern,
                excludeCount = specification.excludeCount,
                excludePattern = specification.excludePattern,
                expectedBenchmarkNames = specification.expectedBenchmarkNames,
                discoveredBenchmarkNames = discovered,
            )
            reportLines += "task.${specification.taskName}.lane=${specification.lane}"
            reportLines += "task.${specification.taskName}.selected=${selected.joinToString(",")}"
        }

        Files.createDirectories(finalReport.parent)
        val temporaryReport = Files.createTempFile(finalReport.parent, "${finalReport.fileName}.", ".tmp")
        try {
            Files.writeString(
                temporaryReport,
                reportLines.joinToString("\n", postfix = "\n"),
                StandardCharsets.UTF_8,
            )
            Files.move(temporaryReport, finalReport, ATOMIC_MOVE, REPLACE_EXISTING)
        }
        finally {
            Files.deleteIfExists(temporaryReport)
        }
    }

    private fun parseSpecification(encoded: String): SelectionSpecification {
        val fields = encoded.split('\t')
        if (fields.size != 7) {
            throw GradleException("Invalid JMH selection specification: $encoded")
        }
        val taskName = decodeField(fields[0], "task name", encoded)
        val lane = decodeField(fields[1], "lane", encoded)
        requireNonBlankText(taskName, "JMH task name")
        requireNonBlankText(lane, "$taskName lane")
        val includeCount = fields[2].toIntOrNull()
            ?: throw GradleException("Invalid include count in JMH selection specification: $encoded")
        val includePattern = decodeField(fields[3], "include pattern", encoded)
        val excludeCount = fields[4].toIntOrNull()
            ?: throw GradleException("Invalid exclude count in JMH selection specification: $encoded")
        if (includeCount < 0 || excludeCount < 0) {
            throw GradleException("JMH selection counts must not be negative: $encoded")
        }
        val encodedExcludePattern = decodeField(fields[5], "exclude pattern", encoded)
        val expectedNamesText = decodeField(fields[6], "expected benchmark names", encoded)
        val expectedNameList = expectedNamesText.takeUnless(String::isEmpty)?.split('\n') ?: emptyList()
        val expectedNames = expectedNameList.toSortedSet()
        if (expectedNameList.size != expectedNames.size) {
            throw GradleException("$taskName contains duplicate expected JMH benchmark names")
        }
        if (excludeCount == 0 && encodedExcludePattern.isNotEmpty()) {
            throw GradleException("$taskName has an exclude pattern but an exclude count of zero")
        }
        expectedNames.forEach { benchmark -> requireNonBlankText(benchmark, "$taskName expected benchmark") }
        return SelectionSpecification(
            taskName = taskName,
            lane = lane,
            includeCount = includeCount,
            includePattern = includePattern,
            excludeCount = excludeCount,
            excludePattern = encodedExcludePattern.takeUnless { excludeCount == 0 },
            expectedBenchmarkNames = expectedNames,
        )
    }

    private fun decodeField(value: String, label: String, encodedSpecification: String): String =
        try {
            String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
        }
        catch (failure: IllegalArgumentException) {
            throw GradleException(
                "Invalid Base64-encoded $label in JMH selection specification: $encodedSpecification",
                failure,
            )
        }

    private fun requireNonBlankText(value: String, label: String) {
        if (value.isBlank() || value.any { character -> Character.isISOControl(character.code) }) {
            throw GradleException("$label must be non-blank and contain no control characters")
        }
    }

    private data class SelectionSpecification(
        val taskName: String,
        val lane: String,
        val includeCount: Int,
        val includePattern: String,
        val excludeCount: Int,
        val excludePattern: String?,
        val expectedBenchmarkNames: SortedSet<String>,
    )

    companion object {
        private const val benchmarkListHeader = "Benchmarks:"
        private val unsafeJavaOptionEnvironmentVariables = listOf(
            "JAVA_TOOL_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "_JAVA_OPTIONS",
            "JAVA_OPTS",
            "GRADLE_OPTS",
        )
    }
}

abstract class VerifyJmhLaneSelectionRegression : DefaultTask() {

    @TaskAction
    fun verify() {
        val first = "regression.ExampleBenchmark.first"
        val second = "regression.ExampleBenchmark.second"
        val discovered = sortedSetOf(first, second)
        val expected = sortedSetOf(first)

        val selected = JmhLaneSelectionSupport.selectExactBenchmarks(
            taskName = "exactSelection",
            lane = "regression",
            includeCount = 1,
            includePattern = "\\.first$",
            excludeCount = 0,
            excludePattern = null,
            expectedBenchmarkNames = expected,
            discoveredBenchmarkNames = discovered,
        )
        if (selected != expected) {
            throw GradleException("Exact JMH lane-selection regression returned $selected")
        }
        JmhLaneSelectionSupport.requireExactTaskCoverage(listOf("exactSelection"), listOf("exactSelection"))

        expectFailure("empty include list", "exactly one include regex") {
            selectForRegression(0, "", expected, discovered)
        }
        expectFailure("blank include regex", "blank or control-character") {
            selectForRegression(1, "", expected, discovered)
        }
        expectFailure("multiple include regexes", "exactly one include regex") {
            selectForRegression(2, "\\.first$,\\.second$", expected, discovered)
        }
        expectFailure("multiple exclude regexes", "at most one exclude regex") {
            JmhLaneSelectionSupport.selectExactBenchmarks(
                taskName = "invalidSelection",
                lane = "regression",
                includeCount = 1,
                includePattern = "\\.first$",
                excludeCount = 2,
                excludePattern = "first,second",
                expectedBenchmarkNames = expected,
                discoveredBenchmarkNames = discovered,
            )
        }
        expectFailure("blank exclude regex", "blank or control-character") {
            JmhLaneSelectionSupport.selectExactBenchmarks(
                taskName = "invalidSelection",
                lane = "regression",
                includeCount = 1,
                includePattern = "\\.first$",
                excludeCount = 1,
                excludePattern = "",
                expectedBenchmarkNames = expected,
                discoveredBenchmarkNames = discovered,
            )
        }
        expectFailure("overly broad include regex", "selects the wrong benchmarks") {
            selectForRegression(1, ".*", expected, discovered)
        }
        expectFailure("missing include regex", "selects no benchmarks") {
            selectForRegression(1, "\\.missing$", expected, discovered)
        }
        expectFailure("uncontracted JMH task", "selection contracts changed") {
            JmhLaneSelectionSupport.requireExactTaskCoverage(
                listOf("exactSelection"),
                listOf("exactSelection", "newUncontractedTask"),
            )
        }
    }

    private fun selectForRegression(
        includeCount: Int,
        includePattern: String,
        expected: SortedSet<String>,
        discovered: SortedSet<String>,
    ) {
        JmhLaneSelectionSupport.selectExactBenchmarks(
            taskName = "invalidSelection",
            lane = "regression",
            includeCount = includeCount,
            includePattern = includePattern,
            excludeCount = 0,
            excludePattern = null,
            expectedBenchmarkNames = expected,
            discoveredBenchmarkNames = discovered,
        )
    }

    private fun expectFailure(scenario: String, expectedMessage: String, action: () -> Unit) {
        try {
            action()
        }
        catch (failure: GradleException) {
            if (failure.message.orEmpty().contains(expectedMessage)) return
            throw GradleException(
                "$scenario failed with the wrong diagnostic: ${failure.message}",
                failure,
            )
        }
        throw GradleException("JMH lane-selection regression accepted $scenario")
    }
}

abstract class VerifyJmhBaselineValidatorRegression : DefaultTask() {

    @TaskAction
    fun verify() {
        JmhBaselineScoreSupport.requireNonNegativeJmhPrimaryScore(0.0, "zero-score regression fixture")
        JmhBaselineScoreSupport.requireNonNegativeJmhPrimaryScore(1.0, "positive-score regression fixture")
        listOf(-1.0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN).forEach { invalid ->
            try {
                JmhBaselineScoreSupport.requireNonNegativeJmhPrimaryScore(
                    invalid,
                    "invalid-score regression fixture",
                )
                throw GradleException("JMH primary-score validator accepted $invalid")
            }
            catch (expected: GradleException) {
                if (!expected.message.orEmpty().contains("primary score is invalid")) throw expected
            }
        }
    }
}

abstract class VerifyJmhReport : DefaultTask() {

    @get:InputFile
    abstract val resultsFile: RegularFileProperty

    @get:InputFile
    abstract val humanOutputFile: RegularFileProperty

    @get:Input
    abstract val expectedKeys: SetProperty<String>

    @get:Input
    abstract val expectedMode: Property<String>

    @get:Input
    abstract val expectedJavaMajor: Property<Int>

    @get:Input
    abstract val requiredJvmArgs: SetProperty<String>

    @TaskAction
    fun verify() {
        val humanOutput = humanOutputFile.get().asFile.readText()
        if ("<failure>" in humanOutput) {
            throw GradleException("JMH output contains a failed benchmark invocation")
        }

        val parsed = JsonSlurper().parse(resultsFile.get().asFile)
        if (parsed !is List<*>) {
            throw GradleException("JMH JSON report must contain a result array")
        }
        val actualKeys = parsed.map { rawResult ->
            val result = rawResult as? Map<*, *>
                ?: throw GradleException("JMH JSON result must be an object")
            val benchmark = result["benchmark"] as? String
                ?: throw GradleException("JMH JSON result is missing its benchmark name")
            val jdkVersion = result["jdkVersion"] as? String
                ?: throw GradleException("JMH JSON result is missing its JDK version: $benchmark")
            val actualJavaMajor = jdkVersion.substringBefore('.').toIntOrNull()
                ?: throw GradleException("JMH JSON result has an invalid JDK version: $jdkVersion")
            if (actualJavaMajor != expectedJavaMajor.get()) {
                throw GradleException(
                    "JMH result used Java $actualJavaMajor, expected Java ${expectedJavaMajor.get()}: $benchmark",
                )
            }
            val jvmArgs = (result["jvmArgs"] as? List<*>)?.filterIsInstance<String>()?.toSet()
                ?: throw GradleException("JMH JSON result is missing its JVM arguments: $benchmark")
            val missingJvmArgs = requiredJvmArgs.get() - jvmArgs
            if (missingJvmArgs.isNotEmpty()) {
                throw GradleException(
                    "JMH result is missing required JVM arguments $missingJvmArgs: $benchmark",
                )
            }
            val mode = result["mode"] as? String
                ?: throw GradleException("JMH JSON result is missing its mode: $benchmark")
            if (mode != expectedMode.get()) {
                throw GradleException(
                    "JMH result used mode $mode, expected ${expectedMode.get()}: $benchmark",
                )
            }
            val params = result["params"] as? Map<*, *>
                ?: throw GradleException("JMH JSON result is missing its parameters: $benchmark")
            val primaryMetric = result["primaryMetric"] as? Map<*, *>
                ?: throw GradleException("JMH JSON result is missing its primary metric: $benchmark")
            val score = (primaryMetric["score"] as? Number)?.toDouble()
                ?: throw GradleException("JMH primary score is not numeric: $benchmark")
            if (!score.isFinite()) {
                throw GradleException("JMH primary score is not finite: $benchmark")
            }
            if (benchmark.startsWith("io.github.shuaibrao.cqengine.benchmark.ConcurrentReadWriteBenchmark.")
                && !benchmark.endsWith(".readOnly")
            ) {
                val secondaryMetrics = result["secondaryMetrics"] as? Map<*, *>
                    ?: throw GradleException("Concurrency result is missing secondary metrics: $benchmark")

                fun metricScore(metricName: String): Double {
                    val metric = secondaryMetrics[metricName] as? Map<*, *>
                        ?: throw GradleException(
                            "Concurrency result is missing metric: $benchmark/$metricName",
                        )
                    val metricScore = (metric["score"] as? Number)?.toDouble()
                        ?: throw GradleException(
                            "Concurrency metric is not numeric: $benchmark/$metricName",
                        )
                    if (!metricScore.isFinite()) {
                        throw GradleException(
                            "Concurrency metric is not finite: $benchmark/$metricName",
                        )
                    }
                    return metricScore
                }

                val groupName = benchmark.substringAfterLast('.')
                if (metricScore("${groupName}Write") <= 0.0) {
                    throw GradleException("Concurrency benchmark did not execute its writer lane: $benchmark")
                }
                if (mode == "thrpt") {
                    val busyFailures = metricScore("busyFailures")
                    val successfulWrites = metricScore("successfulWrites")
                    if (successfulWrites <= 0.0) {
                        throw GradleException("Concurrency benchmark completed no writes: $benchmark")
                    }
                    if (busyFailures < 0.0) {
                        throw GradleException("Concurrency benchmark reported negative busy failures: $benchmark")
                    }
                }
            }
            val parameterKey = params.entries
                .map { (name, value) -> "$name=$value" }
                .sorted()
                .joinToString(",")
            "$benchmark|$parameterKey"
        }
        if (actualKeys.size != actualKeys.toSet().size) {
            throw GradleException("JMH JSON report contains duplicate benchmark/parameter results")
        }

        val expected = expectedKeys.get()
        val actual = actualKeys.toSet()
        if (actual != expected) {
            throw GradleException(
                "JMH result inventory mismatch; missing=${expected - actual}, unexpected=${actual - expected}",
            )
        }
    }
}

abstract class BenchmarkHostAwareTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val approvalDirectory: DirectoryProperty

    @get:Internal
    abstract val projectDirectory: DirectoryProperty

data class BenchmarkHostObservation(
    val operatingSystem: String,
    val kernel: String,
    val architecture: String,
    val virtualization: String,
    val wslVersion: String,
    val cpuModel: String,
    val cpuLogicalProcessors: Int,
    val projectFileStoreType: String,
    val temporaryFileStoreType: String,
)

data class BenchmarkHostApproval(
    val machineApproval: String,
    val evidenceUse: String,
    val numericReadmeClaims: String,
    val record: String,
    val recordSha256: String,
    val declaredPhysicalCpuModel: String = "none",
    val declaredCpuModelEvidence: String = "none",
)

protected fun readBenchmarkTextIfPresent(path: Path): String = if (Files.isRegularFile(path)) {
    Files.readString(path, StandardCharsets.UTF_8)
}
else {
    ""
}

protected fun readWindowsProbe(command: List<String>): String {
    return try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return ""
        }
        if (process.exitValue() == 0) output else ""
    }
    catch (_: Exception) {
        ""
    }
}

protected fun readWindowsCpuName(): String {
    // Resolve by absolute path: release qualification narrows PATH to the wrapper's bound tool directories.
    val system32 = Path.of(System.getenv("SystemRoot")?.takeIf(String::isNotEmpty) ?: "C:\\Windows", "System32")
    val registryName = readWindowsProbe(
        listOf(
            system32.resolve("reg.exe").toString(),
            "query",
            "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0",
            "/v",
            "ProcessorNameString",
        ),
    )
        .lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("ProcessorNameString", ignoreCase = true) }
        ?.substringAfter("REG_SZ")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    if (registryName != null) {
        return registryName
    }
    // WMIC is deprecated and absent from recent Windows images; it only backs hosts without the registry entry.
    return readWindowsProbe(
        listOf(system32.resolve("wbem").resolve("WMIC.exe").toString(), "cpu", "get", "name", "/value"),
    )
        .lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("Name=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        .orEmpty()
}

protected fun looksLikeVirtualCpu(name: String): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    return listOf("qemu", "virtual", "hypervisor", "hyper-v", "kvm", "vmware", "xen").any { it in lower }
}

protected fun observeBenchmarkHost(projectRoot: Path, temporaryRoot: Path): BenchmarkHostObservation {
    val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    val kernel = readBenchmarkTextIfPresent(Path.of("/proc/sys/kernel/osrelease"))
        .lineSequence()
        .firstOrNull()
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: System.getProperty("os.version")
    val kernelLower = kernel.lowercase(Locale.ROOT)
    val wslVersion = when {
        "wsl2" in kernelLower -> "2"
        "microsoft" in kernelLower && !windows -> "1"
        else -> "none"
    }
    val cgroup = readBenchmarkTextIfPresent(Path.of("/proc/1/cgroup"))
    val cpuInfo = readBenchmarkTextIfPresent(Path.of("/proc/cpuinfo"))
    val windowsCpuName = if (windows) readWindowsCpuName() else ""
    val virtualization = when {
        wslVersion == "2" -> "wsl2"
        wslVersion == "1" -> "wsl1"
        Files.isRegularFile(Path.of("/.dockerenv")) -> "container"
        listOf("docker", "containerd", "kubepods", "lxc").any { cgroup.contains(it, ignoreCase = true) } ->
            "container"
        cpuInfo.contains(" hypervisor ", ignoreCase = true) -> "virtual-machine-or-hypervisor"
        windows && looksLikeVirtualCpu(windowsCpuName) -> "virtual-machine-or-hypervisor"
        else -> "not-detected"
    }
    val operatingSystem = readBenchmarkTextIfPresent(Path.of("/etc/os-release")).lineSequence()
        .firstOrNull { it.startsWith("PRETTY_NAME=") }
        ?.substringAfter('=')
        ?.trim()
        ?.removeSurrounding("\"")
        ?: "${System.getProperty("os.name")} ${System.getProperty("os.version")}".trim()
    val cpuModel = cpuInfo.lineSequence()
        .firstOrNull { it.startsWith("model name") }
        ?.substringAfter(':')
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: windowsCpuName.takeIf(String::isNotEmpty)
        ?: "unavailable"
    val logicalProcessors = cpuInfo.lineSequence()
        .count { it.startsWith("processor") }
        .takeIf { it > 0 }
        ?: Runtime.getRuntime().availableProcessors()
    return BenchmarkHostObservation(
        operatingSystem = operatingSystem,
        kernel = kernel,
        architecture = System.getProperty("os.arch"),
        virtualization = virtualization,
        wslVersion = wslVersion,
        cpuModel = cpuModel,
        cpuLogicalProcessors = logicalProcessors,
        projectFileStoreType = Files.getFileStore(projectRoot).type(),
        temporaryFileStoreType = Files.getFileStore(temporaryRoot).type(),
    )
}

protected fun evaluateBenchmarkHostApproval(
    approvalDirectory: Path,
    label: String,
    observation: BenchmarkHostObservation,
): BenchmarkHostApproval {
    val directory = approvalDirectory.toRealPath()
    val approvalFile = directory.resolve("$label.properties").normalize()
    if (!approvalFile.startsWith(directory)) {
        throw GradleException("JMH machine label resolves outside the approval directory: $label")
    }
    if (!Files.exists(approvalFile)) {
        return BenchmarkHostApproval("unapproved", "report-only", "forbidden", "none", "none")
    }
    if (!Files.isRegularFile(approvalFile)) {
        throw GradleException("JMH machine approval is not a regular file: $approvalFile")
    }

    val properties = Properties().apply {
        Files.newInputStream(approvalFile).use(::load)
    }
    val requiredKeys = setOf(
        "formatVersion",
        "machineLabel",
        "operatingSystemRegex",
        "kernelRegex",
        "architecture",
        "virtualization",
        "wslVersion",
        "cpuModel",
        "cpuLogicalProcessors",
        "projectFileStoreType",
        "temporaryFileStoreType",
        "evidenceUse",
        "numericReadmeClaims",
    )
    val declarationKeys = setOf("declaredPhysicalCpuModel", "declaredCpuModelEvidence")
    val presentKeys = properties.stringPropertyNames()
    if (presentKeys - declarationKeys != requiredKeys) {
        throw GradleException(
            "JMH machine approval keys differ; missing=${requiredKeys - presentKeys}, " +
                "unexpected=${presentKeys - requiredKeys - declarationKeys}",
        )
    }
    if (presentKeys.intersect(declarationKeys).size !in setOf(0, declarationKeys.size)) {
        throw GradleException("JMH machine approval declares a physical CPU model without its evidence basis")
    }
    fun required(key: String): String = properties.getProperty(key)?.trim()?.takeIf(String::isNotEmpty)
        ?: throw GradleException("JMH machine approval has no value for $key")
    if (required("formatVersion") != "1") {
        throw GradleException("Unsupported JMH machine approval format: ${required("formatVersion")}")
    }
    if (required("machineLabel") != label) {
        throw GradleException("JMH machine approval label does not match its filename: $approvalFile")
    }
    val mismatches = mutableListOf<String>()
    fun requireExact(key: String, actual: String) {
        val expected = required(key)
        if (actual != expected) mismatches += "$key expected '$expected', observed '$actual'"
    }
    fun requireRegex(key: String, actual: String) {
        val expression = required(key)
        val matches = try {
            Regex(expression).matches(actual)
        }
        catch (exception: java.util.regex.PatternSyntaxException) {
            throw GradleException("JMH machine approval has invalid $key: $expression", exception)
        }
        if (!matches) mismatches += "$key '$expression' does not match '$actual'"
    }
    requireRegex("operatingSystemRegex", observation.operatingSystem)
    requireRegex("kernelRegex", observation.kernel)
    requireExact("architecture", observation.architecture)
    requireExact("virtualization", observation.virtualization)
    requireExact("wslVersion", observation.wslVersion)
    requireExact("cpuModel", observation.cpuModel)
    requireExact("cpuLogicalProcessors", observation.cpuLogicalProcessors.toString())
    requireExact("projectFileStoreType", observation.projectFileStoreType)
    requireExact("temporaryFileStoreType", observation.temporaryFileStoreType)
    if (mismatches.isNotEmpty()) {
        throw GradleException("Approved JMH machine '$label' does not match this host: ${mismatches.joinToString("; ")}")
    }
    val evidenceUse = required("evidenceUse")
    val numericReadmeClaims = required("numericReadmeClaims")
    if (evidenceUse != "machine-specific-development-baseline" || numericReadmeClaims != "machine-specific-only") {
        throw GradleException("JMH machine approval grants an unsupported evidence scope")
    }
    // A declared physical model is an operator claim, never a measurement, so it is only meaningful where a
    // hypervisor can mask the guest-visible model. It never participates in the approval comparison above.
    val declaresPhysicalCpu = presentKeys.containsAll(declarationKeys)
    if (declaresPhysicalCpu && required("declaredCpuModelEvidence") != "operator-declared") {
        throw GradleException("JMH machine approval supports only operator-declared physical CPU models")
    }
    if (declaresPhysicalCpu && observation.virtualization == "not-detected") {
        throw GradleException(
            "JMH machine approval declares a physical CPU model on a host reporting no virtualization",
        )
    }
    val sha256 = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(approvalFile))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return BenchmarkHostApproval(
        machineApproval = "approved",
        evidenceUse = evidenceUse,
        numericReadmeClaims = numericReadmeClaims,
        record = "config/benchmark-hosts/${approvalFile.fileName}",
        recordSha256 = sha256,
        declaredPhysicalCpuModel = if (declaresPhysicalCpu) required("declaredPhysicalCpuModel") else "none",
        declaredCpuModelEvidence = if (declaresPhysicalCpu) required("declaredCpuModelEvidence") else "none",
    )
}

}

abstract class VerifyBenchmarkHostApproval : BenchmarkHostAwareTask() {

    @get:Input
    abstract val machineLabel: Property<String>

    @get:Input
    abstract val requireApproval: Property<Boolean>

    @TaskAction
    fun verify() {
        val label = machineLabel.get()
        if (!label.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{2,63}"))) {
            throw GradleException(
                "Set CQENGINE_JMH_MACHINE_LABEL to a 3-64 character approved machine label",
            )
        }
        val observation = observeBenchmarkHost(
            projectDirectory.get().asFile.toPath().toRealPath(),
            Path.of(System.getProperty("java.io.tmpdir")).toRealPath(),
        )
        val approval = evaluateBenchmarkHostApproval(
            approvalDirectory.get().asFile.toPath(),
            label,
            observation,
        )
        if (requireApproval.get() && approval.machineApproval != "approved") {
            throw GradleException("No approved benchmark-host record exists for '$label'")
        }
        logger.lifecycle("benchmark-host=$label approval=${approval.machineApproval} use=${approval.evidenceUse}")
    }
}

abstract class VerifyJmhBaseline : BenchmarkHostAwareTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rawReportFiles: ConfigurableFileCollection

    @get:Internal
    abstract val benchmarkBuildDirectory: DirectoryProperty

    @get:Input
    abstract val reportSpecifications: ListProperty<String>

    @get:Input
    abstract val expectedResultKeys: SetProperty<String>

    @get:Input
    abstract val expectedResultsPerJava: Property<Int>

    @get:Input
    abstract val sourceCommit: Property<String>

    @get:Input
    abstract val sourceTree: Property<String>

    @get:Input
    abstract val publicationCoordinate: Property<String>

    @get:Input
    abstract val machineLabel: Property<String>

    @get:Input
    abstract val unsafeEnvironmentVariables: ListProperty<String>

    @get:Input
    abstract val expectedJmhVersion: Property<String>

    @get:Input
    abstract val gradleVersion: Property<String>

    @get:Input
    abstract val java21Executable: Property<String>

    @get:Input
    abstract val java21Vendor: Property<String>

    @get:Input
    abstract val java21Version: Property<String>

    @get:Input
    abstract val java25Executable: Property<String>

    @get:Input
    abstract val java25Vendor: Property<String>

    @get:Input
    abstract val java25Version: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val wrapperProperties: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val wrapperJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val benchmarkJar: RegularFileProperty

    @get:OutputFile
    abstract val environmentFile: RegularFileProperty

    @get:OutputFile
    abstract val summaryFile: RegularFileProperty

    @get:OutputFile
    abstract val inventoryFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val label = machineLabel.get()
        if (!label.matches(Regex(MACHINE_LABEL_PATTERN))) {
            throw GradleException(
                "CQENGINE_JMH_MACHINE_LABEL must match $MACHINE_LABEL_PATTERN; received '$label'",
            )
        }
        if (unsafeEnvironmentVariables.get().isNotEmpty()) {
            throw GradleException(
                "JMH baseline inherited unsafe JVM/build options: ${unsafeEnvironmentVariables.get()}",
            )
        }

        val specifications = reportSpecifications.get().map(::parseSpecification)
        val specificationIds = specifications.map { "${it.javaMajor}:${it.lane}" }
        if (specificationIds.size != specificationIds.toSet().size) {
            throw GradleException("JMH baseline report specifications contain duplicate Java/lane identities")
        }
        val reportDirectories = specifications.map(BaselineSpecification::reportDirectory)
        if (reportDirectories.size != reportDirectories.toSet().size) {
            throw GradleException("JMH baseline report specifications contain duplicate directories")
        }

        val buildRoot = benchmarkBuildDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        val expectedRawPaths = specifications.flatMap { specification ->
            listOf(
                "reports/${specification.reportDirectory}/results.json",
                "reports/${specification.reportDirectory}/human.txt",
            )
        }.toSortedSet()
        if (expectedRawPaths.size != EXPECTED_RAW_FILE_COUNT) {
            throw GradleException(
                "JMH baseline must define exactly $EXPECTED_RAW_FILE_COUNT raw files, found ${expectedRawPaths.size}",
            )
        }
        val actualRawPaths = rawReportFiles.files.map { file -> relativePath(buildRoot, file) }.toSortedSet()
        if (actualRawPaths != expectedRawPaths) {
            throw GradleException(
                "JMH baseline raw-file inventory differs; missing=${expectedRawPaths - actualRawPaths}, " +
                    "unexpected=${actualRawPaths - expectedRawPaths}",
            )
        }
        expectedRawPaths.forEach { relative ->
            val file = buildRoot.resolve(relative).toFile()
            if (!file.isFile || file.length() == 0L) {
                throw GradleException("JMH baseline raw evidence is missing or empty: $relative")
            }
        }

        val expectedKeys = expectedResultKeys.get()
        if (expectedKeys.size != EXPECTED_JAVA_COUNT * expectedResultsPerJava.get()) {
            throw GradleException(
                "Expected-key contract must contain ${EXPECTED_JAVA_COUNT * expectedResultsPerJava.get()} entries, " +
                    "found ${expectedKeys.size}",
            )
        }
        val actualKeys = linkedSetOf<String>()
        val laneCounts = linkedMapOf<String, Int>()
        specifications.forEach { specification ->
            val human = buildRoot.resolve("reports/${specification.reportDirectory}/human.txt").toFile()
                .readText(StandardCharsets.UTF_8)
            val failureMarkers = listOf("<failure>", "Exception in thread", "ERROR:")
                .filter(human::contains)
            if (failureMarkers.isNotEmpty()) {
                throw GradleException(
                    "JMH human report contains failure markers $failureMarkers: ${specification.reportDirectory}",
                )
            }

            val resultsFile = buildRoot.resolve("reports/${specification.reportDirectory}/results.json").toFile()
            val results = JsonSlurper().parse(resultsFile) as? List<*>
                ?: throw GradleException("JMH baseline JSON must contain an array: ${resultsFile.name}")
            if (results.size != specification.expectedCount) {
                throw GradleException(
                    "JMH ${specification.javaMajor}/${specification.lane} expected " +
                        "${specification.expectedCount} results, found ${results.size}",
                )
            }
            val localKeys = results.map { raw ->
                val result = requireObject(raw, "JMH result")
                validateResult(result, specification)
            }
            if (localKeys.size != localKeys.toSet().size) {
                throw GradleException(
                    "JMH ${specification.javaMajor}/${specification.lane} contains duplicate results",
                )
            }
            localKeys.forEach { key ->
                if (!actualKeys.add(key)) throw GradleException("Duplicate JMH baseline result: $key")
            }
            laneCounts["${specification.javaMajor}:${specification.lane}"] = localKeys.size
        }
        if (actualKeys != expectedKeys) {
            throw GradleException(
                "JMH baseline result inventory differs; missing=${expectedKeys - actualKeys}, " +
                    "unexpected=${actualKeys - expectedKeys}",
            )
        }
        listOf(21, 25).forEach { javaMajor ->
            val count = actualKeys.count { it.startsWith("$javaMajor\t") }
            if (count != expectedResultsPerJava.get()) {
                throw GradleException(
                    "Java $javaMajor JMH baseline expected ${expectedResultsPerJava.get()} results, found $count",
                )
            }
        }

        val approval = writeEnvironmentEvidence(label)
        writeSummary(label, actualKeys.size, laneCounts, specifications, approval)
        writeInventory(buildRoot, expectedRawPaths)
    }

    private fun validateResult(
        result: Map<*, *>,
        specification: BaselineSpecification,
    ): String {
        val benchmark = requireString(result, "benchmark", "JMH result")
        val jmhVersion = requireString(result, "jmhVersion", benchmark)
        if (jmhVersion != expectedJmhVersion.get()) {
            throw GradleException(
                "$benchmark used JMH $jmhVersion, expected ${expectedJmhVersion.get()}",
            )
        }
        val jdkVersion = requireString(result, "jdkVersion", benchmark)
        val javaMajor = jdkVersion.substringBefore('.').toIntOrNull()
            ?: throw GradleException("$benchmark has an invalid JDK version: $jdkVersion")
        if (javaMajor != specification.javaMajor) {
            throw GradleException(
                "$benchmark used Java $javaMajor, expected Java ${specification.javaMajor}",
            )
        }
        val configuredJava = if (javaMajor == 21) java21Executable.get() else java25Executable.get()
        val reportedJava = requireString(result, "jvm", benchmark)
        val configuredJavaPath = Path.of(configuredJava).toRealPath()
        val reportedJavaPath = Path.of(reportedJava).toRealPath()
        if (reportedJavaPath != configuredJavaPath) {
            throw GradleException(
                "$benchmark used $reportedJavaPath, expected configured launcher $configuredJavaPath",
            )
        }

        requireExact(result, "mode", specification.mode, benchmark)
        requireExactInt(result, "threads", specification.threads, benchmark)
        requireExactInt(result, "forks", specification.forks, benchmark)
        requireExactInt(result, "warmupIterations", specification.warmupIterations, benchmark)
        requireExact(result, "warmupTime", specification.warmupTime, benchmark)
        requireExactInt(result, "warmupBatchSize", 1, benchmark)
        requireExactInt(result, "measurementIterations", specification.measurementIterations, benchmark)
        requireExact(result, "measurementTime", specification.measurementTime, benchmark)
        requireExactInt(result, "measurementBatchSize", 1, benchmark)

        val jvmArgsRaw = result["jvmArgs"] as? List<*>
            ?: throw GradleException("$benchmark has no JVM argument list")
        val jvmArgs = jvmArgsRaw.map { value ->
            value as? String ?: throw GradleException("$benchmark has a non-string JVM argument")
        }
        if (jvmArgs.size != jvmArgs.toSet().size) {
            throw GradleException("$benchmark has duplicate JVM arguments")
        }
        val benchmarkBuildRoot = benchmarkBuildDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        val expectedJvmArgs = COMMON_JVM_ARGS +
            "-Djava.io.tmpdir=${benchmarkBuildRoot.resolve("tmp/${specification.taskName}")}" +
            specification.requiredJvmArgs
        // The JVM reports its own region and variant here; the build never sets them. A Linux qualification under
        // LC_ALL=C reports them empty while a Windows host reports a real region, so compare those two by key.
        // Encoding and language stay exact because they change locale-sensitive behaviour, and the published
        // environment.properties records the locale that actually applied.
        fun byLocaleKey(arguments: Set<String>): Set<String> = arguments.mapTo(mutableSetOf()) { argument ->
            when {
                argument.startsWith("-Duser.country=") -> "-Duser.country"
                argument.startsWith("-Duser.variant=") -> "-Duser.variant"
                else -> argument
            }
        }
        val observedJvmArgs = byLocaleKey(jvmArgs.toSet())
        val comparableJvmArgs = byLocaleKey(expectedJvmArgs)
        if (observedJvmArgs != comparableJvmArgs) {
            throw GradleException(
                "$benchmark JVM argument inventory differs; missing=${comparableJvmArgs - observedJvmArgs}, " +
                    "unexpected=${observedJvmArgs - comparableJvmArgs}",
            )
        }
        val forbiddenJvmArgs = jvmArgs.filter { argument ->
            argument == "--add-opens" || argument.startsWith("--add-opens=") ||
                argument == "--add-exports" || argument.startsWith("--add-exports=")
        }
        if (forbiddenJvmArgs.isNotEmpty()) {
            throw GradleException("$benchmark used forbidden module-opening JVM arguments: $forbiddenJvmArgs")
        }

        val primaryMetric = requireObject(result["primaryMetric"], "$benchmark primary metric")
        val primaryScore = JmhBaselineScoreSupport.requireNonNegativeJmhPrimaryScore(
            primaryMetric["score"],
            benchmark,
        )
        requireExact(primaryMetric, "scoreUnit", specification.scoreUnit, "$benchmark primary metric")
        val secondaryMetrics = requireObject(result["secondaryMetrics"], "$benchmark secondary metrics")
        secondaryMetrics.forEach { (rawName, rawMetric) ->
            val name = rawName as? String
                ?: throw GradleException("$benchmark has a non-string secondary metric name")
            val metric = requireObject(rawMetric, "$benchmark/$name")
            requireFiniteScore(metric, "$benchmark/$name")
            requireString(metric, "scoreUnit", "$benchmark/$name")
        }
        if (specification.concurrency && primaryScore <= 0.0) {
            throw GradleException("Concurrency benchmark completed no measurable operations: $benchmark")
        }
        if (specification.allocationRequired) validateAllocation(result, benchmark)
        if (specification.sampledLatency) validatePercentiles(primaryMetric, benchmark)
        if (specification.concurrency) validateConcurrency(result, benchmark)

        val params = requireObject(result["params"], "$benchmark parameters")
        val parameterKey = params.entries.map { (rawName, rawValue) ->
            val name = rawName as? String
                ?: throw GradleException("$benchmark has a non-string parameter name")
            val value = rawValue as? String
                ?: throw GradleException("$benchmark parameter $name is not a string")
            "$name=$value"
        }.sorted().joinToString(",")
        return "${specification.javaMajor}\t${specification.lane}\t$benchmark|$parameterKey"
    }

    private fun validateAllocation(result: Map<*, *>, benchmark: String) {
        val secondary = requireObject(result["secondaryMetrics"], "$benchmark secondary metrics")
        val allocation = requireObject(
            secondary["gc.alloc.rate.norm"],
            "$benchmark gc.alloc.rate.norm metric",
        )
        val allocationScore = requireFiniteScore(allocation, "$benchmark/gc.alloc.rate.norm")
        if (allocationScore < 0.0) {
            throw GradleException("$benchmark reported negative allocation: $allocationScore")
        }
        requireExact(allocation, "scoreUnit", "B/op", "$benchmark gc.alloc.rate.norm metric")
    }

    private fun validatePercentiles(primaryMetric: Map<*, *>, benchmark: String) {
        val percentiles = requireObject(primaryMetric["scorePercentiles"], "$benchmark percentiles")
        val values = REQUIRED_PERCENTILES.map { percentile ->
            val value = (percentiles[percentile] as? Number)?.toDouble()
                ?: throw GradleException("$benchmark has no numeric p$percentile")
            if (!value.isFinite() || value < 0.0) {
                throw GradleException("$benchmark has an invalid p$percentile: $value")
            }
            value
        }
        values.zipWithNext().forEach { (lower, higher) ->
            if (lower > higher) {
                throw GradleException("$benchmark sampled percentiles are not monotonic: $values")
            }
        }
    }

    private fun validateConcurrency(result: Map<*, *>, benchmark: String) {
        val group = benchmark.substringAfterLast('.')
        val secondary = requireObject(result["secondaryMetrics"], "$benchmark secondary metrics")
        val expectedMetricNames = if (group == "readOnly") {
            emptySet()
        }
        else {
            setOf("${group}Read", "${group}Write", "successfulWrites", "busyFailures")
        }
        if (secondary.keys != expectedMetricNames) {
            throw GradleException(
                "$benchmark secondary metric inventory differs; expected=$expectedMetricNames, " +
                    "found=${secondary.keys}",
            )
        }
        if (group == "readOnly") {
            return
        }

        listOf("${group}Read", "${group}Write").forEach { metricName ->
            val metric = requireObject(secondary[metricName], "$benchmark/$metricName")
            if (requireFiniteScore(metric, "$benchmark/$metricName") <= 0.0) {
                throw GradleException("$benchmark executed no $metricName operations")
            }
            requireExact(metric, "scoreUnit", "ops/s", "$benchmark/$metricName")
        }
        val successfulWrites = requireObject(secondary["successfulWrites"], "$benchmark/successfulWrites")
        if (requireFiniteScore(successfulWrites, "$benchmark/successfulWrites") <= 0.0) {
            throw GradleException("$benchmark completed no successful writes")
        }
        requireExact(successfulWrites, "scoreUnit", "ops/s", "$benchmark/successfulWrites")
        val busyFailures = requireObject(secondary["busyFailures"], "$benchmark/busyFailures")
        if (requireFiniteScore(busyFailures, "$benchmark/busyFailures") < 0.0) {
            throw GradleException("$benchmark reported negative SQLite busy failures")
        }
        requireExact(busyFailures, "scoreUnit", "ops/s", "$benchmark/busyFailures")
    }

    private fun writeEnvironmentEvidence(label: String): BenchmarkHostAwareTask.BenchmarkHostApproval {
        val projectRoot = projectDirectory.get().asFile.toPath().toRealPath()
        val temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
        val observation = observeBenchmarkHost(projectRoot, temporaryRoot)
        val approval = evaluateBenchmarkHostApproval(
            approvalDirectory.get().asFile.toPath(),
            label,
            observation,
        )
        val wrapper = Properties().apply {
            wrapperProperties.get().asFile.inputStream().use(::load)
        }
        val projectStore = Files.getFileStore(projectRoot)
        val temporaryStore = Files.getFileStore(temporaryRoot)
        val memoryInfo = readTextIfPresent(Path.of("/proc/meminfo"))
        val availableProcessors = Runtime.getRuntime().availableProcessors()
        val operatingSystemBean = ManagementFactory.getOperatingSystemMXBean()
        val totalMemoryBytes =
            (operatingSystemBean as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize ?: 0L
        val memoryTotalKiB = memoryInfo.lineSequence()
            .firstOrNull { it.startsWith("MemTotal:") }
            ?.substringAfter(':')
            ?.trim()
            ?.substringBefore(' ')
            ?.takeIf(String::isNotEmpty)
            ?: (totalMemoryBytes / 1024L).toString()
        val environment = sortedMapOf(
            "formatVersion" to "1",
            "generatedAt" to Instant.now().toString(),
            "sourceCommit" to sourceCommit.get(),
            "sourceTree" to sourceTree.get(),
            "benchmarkJarSha256" to digest(benchmarkJar.get().asFile, "SHA-256"),
            "coordinate" to publicationCoordinate.get(),
            "machineLabel" to label,
            "machineApproval" to approval.machineApproval,
            "machineApprovalRecord" to approval.record,
            "machineApprovalRecordSha256" to approval.recordSha256,
            "evidenceUse" to approval.evidenceUse,
            "numericReadmeClaims" to approval.numericReadmeClaims,
            "wslVersion" to observation.wslVersion,
            "operatingSystem" to observation.operatingSystem,
            "kernel" to observation.kernel,
            "architecture" to observation.architecture,
            "virtualization" to observation.virtualization,
            "cpuModel" to observation.cpuModel,
            "declaredPhysicalCpuModel" to approval.declaredPhysicalCpuModel,
            "declaredCpuModelEvidence" to approval.declaredCpuModelEvidence,
            "cpuLogicalProcessors" to observation.cpuLogicalProcessors.toString(),
            "processAvailableProcessors" to availableProcessors.toString(),
            "memoryTotalKiB" to memoryTotalKiB,
            "projectFileStore" to fileStoreDescription(projectStore),
            "projectFileStoreTotalBytes" to projectStore.totalSpace.toString(),
            "projectFileStoreUsableBytes" to projectStore.usableSpace.toString(),
            "temporaryFileStore" to fileStoreDescription(temporaryStore),
            "temporaryFileStoreTotalBytes" to temporaryStore.totalSpace.toString(),
            "temporaryFileStoreUsableBytes" to temporaryStore.usableSpace.toString(),
            "java21Vendor" to java21Vendor.get(),
            "java21Version" to java21Version.get(),
            "java21Executable" to Path.of(java21Executable.get()).toRealPath().toString(),
            "java21ExecutableSha256" to digest(File(java21Executable.get()), "SHA-256"),
            "java25Vendor" to java25Vendor.get(),
            "java25Version" to java25Version.get(),
            "java25Executable" to Path.of(java25Executable.get()).toRealPath().toString(),
            "java25ExecutableSha256" to digest(File(java25Executable.get()), "SHA-256"),
            "gradleVersion" to gradleVersion.get(),
            "gradleDistributionUrl" to wrapper.getProperty("distributionUrl").orEmpty(),
            "gradleDistributionSha256" to wrapper.getProperty("distributionSha256Sum").orEmpty(),
            "gradleWrapperJarSha256" to digest(wrapperJar.get().asFile, "SHA-256"),
            "jmhVersion" to expectedJmhVersion.get(),
            "locale" to Locale.getDefault().toLanguageTag(),
            "timezone" to TimeZone.getDefault().id,
            "unsafeJvmBuildEnvironment" to "none",
        )
        environment["sanitizedEnvironmentAllowlist"] = SANITIZED_ENVIRONMENT_VARIABLES.joinToString(",")
        SANITIZED_ENVIRONMENT_VARIABLES.forEach { name ->
            environment["environment.$name"] = System.getenv(name)?.take(256) ?: "<unset>"
        }
        writeKeyValueFile(environmentFile.get().asFile, environment)
        return approval
    }

    private fun writeSummary(
        label: String,
        totalResults: Int,
        laneCounts: Map<String, Int>,
        specifications: List<BaselineSpecification>,
        approval: BenchmarkHostAwareTask.BenchmarkHostApproval,
    ) {
        val lines = buildList {
            add("formatVersion=1")
            add(
                "status=" + if (approval.machineApproval == "approved") {
                    "verified-machine-baseline"
                }
                else {
                    "verified-report-only"
                },
            )
            add("machineLabel=$label")
            add("approvedNumericalBaseline=${approval.machineApproval == "approved"}")
            add("evidenceUse=${approval.evidenceUse}")
            add("numericReadmeClaims=${approval.numericReadmeClaims}")
            add("performanceThresholds=none")
            add("java21Results=${expectedResultsPerJava.get()}")
            add("java25Results=${expectedResultsPerJava.get()}")
            add("totalResults=$totalResults")
            add("rawFiles=$EXPECTED_RAW_FILE_COUNT")
            specifications.sortedWith(compareBy({ it.javaMajor }, { it.lane })).forEach { specification ->
                val id = "${specification.javaMajor}:${specification.lane}"
                add(
                    "lane.$id=${laneCounts.getValue(id)} results;mode=${specification.mode};" +
                        "unit=${specification.scoreUnit};forks=${specification.forks};" +
                        "warmup=${specification.warmupIterations}x${specification.warmupTime};" +
                        "measurement=${specification.measurementIterations}x${specification.measurementTime}",
                )
            }
        }
        val file = summaryFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(lines.joinToString("\n", postfix = "\n"), StandardCharsets.UTF_8)
    }

    private fun writeInventory(buildRoot: Path, expectedRawPaths: Set<String>) {
        val environment = environmentFile.get().asFile
        val summary = summaryFile.get().asFile
        val evidencePaths = (expectedRawPaths + setOf(
            relativePath(buildRoot, environment),
            relativePath(buildRoot, summary),
        )).toSortedSet()
        val lines = buildList {
            add("formatVersion=1")
            add("files=${evidencePaths.size}")
            evidencePaths.forEach { relative ->
                val file = buildRoot.resolve(relative).toFile()
                add("${digest(file, "SHA-256")} ${digest(file, "SHA-512")}  $relative")
            }
        }
        val file = inventoryFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(lines.joinToString("\n", postfix = "\n"), StandardCharsets.UTF_8)
    }

    private fun parseSpecification(encoded: String): BaselineSpecification {
        val fields = encoded.split('\t')
        if (fields.size != SPECIFICATION_FIELD_COUNT) {
            throw GradleException("Malformed JMH baseline report specification: $encoded")
        }
        fun intField(index: Int, name: String): Int = fields[index].toIntOrNull()
            ?: throw GradleException("Invalid $name in JMH baseline specification: $encoded")
        fun booleanField(index: Int, name: String): Boolean = fields[index].toBooleanStrictOrNull()
            ?: throw GradleException("Invalid $name in JMH baseline specification: $encoded")
        return BaselineSpecification(
            reportDirectory = fields[0],
            taskName = fields[1],
            lane = fields[2],
            javaMajor = intField(3, "Java major"),
            expectedCount = intField(4, "result count"),
            mode = fields[5],
            scoreUnit = fields[6],
            forks = intField(7, "fork count"),
            warmupIterations = intField(8, "warmup count"),
            warmupTime = fields[9],
            measurementIterations = intField(10, "measurement count"),
            measurementTime = fields[11],
            threads = intField(12, "thread count"),
            allocationRequired = booleanField(13, "allocation flag"),
            sampledLatency = booleanField(14, "sampled-latency flag"),
            concurrency = booleanField(15, "concurrency flag"),
            requiredJvmArgs = fields[16].takeUnless { it == "-" }?.split(',')?.toSet().orEmpty(),
        )
    }

    private fun requireObject(value: Any?, label: String): Map<*, *> =
        value as? Map<*, *> ?: throw GradleException("$label is not a JSON object")

    private fun requireString(parent: Map<*, *>, field: String, label: String): String =
        parent[field] as? String ?: throw GradleException("$label has no string $field")

    private fun requireExact(parent: Map<*, *>, field: String, expected: String, label: String) {
        val actual = requireString(parent, field, label)
        if (actual != expected) throw GradleException("$label $field is '$actual', expected '$expected'")
    }

    private fun requireExactInt(parent: Map<*, *>, field: String, expected: Int, label: String) {
        val actual = (parent[field] as? Number)?.toDouble()
            ?: throw GradleException("$label has no numeric $field")
        if (!actual.isFinite() || actual != expected.toDouble()) {
            throw GradleException("$label $field is $actual, expected $expected")
        }
    }

    private fun requireFiniteScore(metric: Map<*, *>, label: String): Double {
        val score = (metric["score"] as? Number)?.toDouble()
            ?: throw GradleException("$label score is not numeric")
        if (!score.isFinite()) throw GradleException("$label score is not finite: $score")
        return score
    }

    private fun relativePath(root: Path, file: File): String {
        val path = file.toPath().toAbsolutePath().normalize()
        if (!path.startsWith(root)) throw GradleException("JMH evidence is outside the benchmark build: $path")
        return root.relativize(path).joinToString("/")
    }

    private fun readTextIfPresent(path: Path): String = if (Files.isRegularFile(path)) {
        Files.readString(path, StandardCharsets.UTF_8)
    }
    else {
        ""
    }

    private fun fileStoreDescription(store: java.nio.file.FileStore): String =
        "${store.name()} (${store.type()})"

    private fun writeKeyValueFile(file: File, values: Map<String, String>) {
        file.parentFile.mkdirs()
        file.writeText(
            values.entries.joinToString("\n", postfix = "\n") { (key, rawValue) ->
                val value = rawValue.replace(Regex("[\\r\\n]+"), " ").trim()
                "$key=$value"
            },
            StandardCharsets.UTF_8,
        )
    }

    private fun digest(file: File, algorithm: String): String {
        if (!file.isFile || file.length() == 0L) {
            throw GradleException("Cannot hash missing or empty JMH evidence: $file")
        }
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

    private data class BaselineSpecification(
        val reportDirectory: String,
        val taskName: String,
        val lane: String,
        val javaMajor: Int,
        val expectedCount: Int,
        val mode: String,
        val scoreUnit: String,
        val forks: Int,
        val warmupIterations: Int,
        val warmupTime: String,
        val measurementIterations: Int,
        val measurementTime: String,
        val threads: Int,
        val allocationRequired: Boolean,
        val sampledLatency: Boolean,
        val concurrency: Boolean,
        val requiredJvmArgs: Set<String>,
    )

    companion object {
        private const val MACHINE_LABEL_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{2,63}"
        private const val EXPECTED_RAW_FILE_COUNT = 28
        private const val EXPECTED_JAVA_COUNT = 2
        private const val SPECIFICATION_FIELD_COUNT = 17
        private val COMMON_JVM_ARGS = setOf(
            "-Dfile.encoding=UTF-8",
            "-Duser.country",
            "-Duser.language=en",
            "-Duser.variant",
        )
        private val REQUIRED_PERCENTILES = listOf("50.0", "90.0", "95.0", "99.0", "99.9")
        private val SANITIZED_ENVIRONMENT_VARIABLES = listOf(
            "CI",
            "LANG",
            "LC_ALL",
            "LC_CTYPE",
            "TZ",
            "WSL_DISTRO_NAME",
        )
    }
}

val jmhJar = tasks.named<Jar>("jmhJar")

jmhJar.configure {
    eachFile {
        if (name.endsWith(".class")) duplicatesStrategy = DuplicatesStrategy.FAIL
    }
}

fun jmhIncludeArgument(patterns: List<String>): List<String> {
    require(patterns.isNotEmpty()) { "A report-only JMH task needs at least one include regex" }
    return listOf(patterns.joinToString("|", prefix = "(?:", postfix = ")") { pattern -> "(?:$pattern)" })
}

fun registerReportOnlyJmhTask(
    taskName: String,
    taskDescription: String,
    launcher: Provider<JavaLauncher>,
    benchmarkIncludes: List<String>,
    mode: String,
    unit: String,
    reportDirectory: String,
    profilerNames: List<String>,
    jdkOptions: List<String>,
    warmups: Int = 3,
): TaskProvider<JMHTask> = tasks.register<JMHTask>(taskName) {
    description = taskDescription
    group = "benchmark"

    jmhClasspath.from(configurations.named("jmh"))
    testRuntimeClasspath.from(configurations.named("jmhRuntimeClasspath"))
    jarArchive.set(jmhJar.flatMap { it.archiveFile })
    javaLauncher.set(launcher)

    includes.set(jmhIncludeArgument(benchmarkIncludes))
    benchmarkMode.set(listOf(mode))
    timeUnit.set(unit)
    warmupIterations.set(warmups)
    warmup.set("1s")
    iterations.set(5)
    timeOnIteration.set("1s")
    fork.set(2)
    failOnError.set(true)
    forceGC.set(false)
    profilers.set(profilerNames)
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("reports/$reportDirectory/results.json"))
    humanOutputFile.set(layout.buildDirectory.file("reports/$reportDirectory/human.txt"))
    jvmArgsAppend.set(jdkOptions)
}

tasks.register<JMHTask>("jmhMutation") {
    description = "Runs single-shot index and mutation benchmarks with isolated prebuilt state."
    group = "benchmark"

    jmhClasspath.from(configurations.named("jmh"))
    testRuntimeClasspath.from(configurations.named("jmhRuntimeClasspath"))
    jarArchive.set(jmhJar.flatMap { it.archiveFile })
    javaLauncher.set(java25Launcher)

    includes.set(listOf("io.github.shuaibrao.cqengine.benchmark.MutationBenchmark.*"))
    benchmarkMode.set(listOf("ss"))
    timeUnit.set("ms")
    warmupIterations.set(1)
    iterations.set(5)
    fork.set(2)
    failOnError.set(true)
    forceGC.set(false)
    profilers.set(emptyList())
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("reports/jmh-mutation/results.json"))
    humanOutputFile.set(layout.buildDirectory.file("reports/jmh-mutation/human.txt"))
}

tasks.register<JMHTask>("jmhQueryScenarios") {
    description = "Measures indexed and fallback query lifecycles across declared result cardinalities."
    group = "benchmark"

    jmhClasspath.from(configurations.named("jmh"))
    testRuntimeClasspath.from(configurations.named("jmhRuntimeClasspath"))
    jarArchive.set(jmhJar.flatMap { it.archiveFile })
    javaLauncher.set(java25Launcher)

    includes.set(listOf("io.github.shuaibrao.cqengine.benchmark.QueryScenarioBenchmark.*"))
    benchmarkMode.set(listOf("avgt"))
    timeUnit.set("ns")
    warmupIterations.set(3)
    warmup.set("1s")
    iterations.set(5)
    timeOnIteration.set("1s")
    fork.set(2)
    failOnError.set(true)
    forceGC.set(false)
    profilers.set(listOf("gc"))
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("reports/jmh-query-scenarios/results.json"))
    humanOutputFile.set(layout.buildDirectory.file("reports/jmh-query-scenarios/human.txt"))
}

tasks.register<JMHTask>("jmhMutationAllocation") {
    description = "Measures steady reversible update allocation without invocation-level reset work."
    group = "benchmark"

    jmhClasspath.from(configurations.named("jmh"))
    testRuntimeClasspath.from(configurations.named("jmhRuntimeClasspath"))
    jarArchive.set(jmhJar.flatMap { it.archiveFile })
    javaLauncher.set(java25Launcher)

    includes.set(listOf("io.github.shuaibrao.cqengine.benchmark.MutationAllocationBenchmark.*"))
    benchmarkMode.set(listOf("avgt"))
    timeUnit.set("ns")
    warmupIterations.set(3)
    warmup.set("1s")
    iterations.set(5)
    timeOnIteration.set("1s")
    fork.set(2)
    failOnError.set(true)
    forceGC.set(false)
    profilers.set(listOf("gc"))
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("reports/jmh-mutation-allocation/results.json"))
    humanOutputFile.set(layout.buildDirectory.file("reports/jmh-mutation-allocation/human.txt"))
}

tasks.register<JMHTask>("jmhPersistence") {
    description = "Measures equivalent on-heap, off-heap and disk persistence lifecycles."
    group = "benchmark"

    jmhClasspath.from(configurations.named("jmh"))
    testRuntimeClasspath.from(configurations.named("jmhRuntimeClasspath"))
    jarArchive.set(jmhJar.flatMap { it.archiveFile })
    javaLauncher.set(java25Launcher)

    includes.set(listOf("io.github.shuaibrao.cqengine.benchmark.PersistenceLifecycleBenchmark.*"))
    benchmarkMode.set(listOf("avgt"))
    timeUnit.set("us")
    warmupIterations.set(3)
    warmup.set("1s")
    iterations.set(5)
    timeOnIteration.set("1s")
    fork.set(2)
    failOnError.set(true)
    forceGC.set(false)
    profilers.set(listOf("gc"))
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("reports/jmh-persistence/results.json"))
    humanOutputFile.set(layout.buildDirectory.file("reports/jmh-persistence/human.txt"))
}

val jmhConcurrency by tasks.registering(JMHTask::class) {
    description = "Measures declared reader/writer mixes and reports bounded SQLite busy failures."
    group = "benchmark"

    jmhClasspath.from(configurations.named("jmh"))
    testRuntimeClasspath.from(configurations.named("jmhRuntimeClasspath"))
    jarArchive.set(jmhJar.flatMap { it.archiveFile })
    javaLauncher.set(java25Launcher)

    includes.set(listOf("io.github.shuaibrao.cqengine.benchmark.ConcurrentReadWriteBenchmark.*"))
    benchmarkMode.set(listOf("thrpt"))
    timeUnit.set("s")
    warmupIterations.set(3)
    warmup.set("1s")
    iterations.set(5)
    timeOnIteration.set("1s")
    fork.set(2)
    failOnError.set(true)
    forceGC.set(false)
    profilers.set(emptyList())
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("reports/jmh-concurrency/results.json"))
    humanOutputFile.set(layout.buildDirectory.file("reports/jmh-concurrency/human.txt"))
}

val sampledLatencyIncludes = listOf(
    "$benchmarkNamespacePattern\\.QueryScenarioBenchmark\\."
        + "(firstResultAndClose|fullIterationAndClose)",
    "$benchmarkNamespacePattern\\.PersistenceLifecycleBenchmark\\."
        + "(pointLookupAndClose|replace)",
)

val jmhLatencyJava25 = registerReportOnlyJmhTask(
    taskName = "jmhLatencyJava25",
    taskDescription = "Records Java 25 sampled query and persistence lifecycle latency distributions.",
    launcher = java25Launcher,
    benchmarkIncludes = sampledLatencyIncludes,
    mode = "sample",
    unit = "us",
    reportDirectory = "jmh-latency-java25",
    profilerNames = emptyList(),
    jdkOptions = java25BenchmarkJvmOptions,
)

val jmhQueryJava21 = registerReportOnlyJmhTask(
    taskName = "jmhQueryJava21",
    taskDescription = "Measures the core query lifecycle and allocation on Java 21.",
    launcher = java21Launcher,
    benchmarkIncludes = listOf("io.github.shuaibrao.cqengine.benchmark.QueryLifecycleBenchmark.*"),
    mode = "avgt",
    unit = "ns",
    reportDirectory = "jmh-query-java21",
    profilerNames = listOf("gc"),
    jdkOptions = java21BenchmarkJvmOptions,
)

val jmhQueryScenariosJava21 = registerReportOnlyJmhTask(
    taskName = "jmhQueryScenariosJava21",
    taskDescription = "Measures indexed and fallback query scenarios on Java 21.",
    launcher = java21Launcher,
    benchmarkIncludes = listOf("io.github.shuaibrao.cqengine.benchmark.QueryScenarioBenchmark.*"),
    mode = "avgt",
    unit = "ns",
    reportDirectory = "jmh-query-scenarios-java21",
    profilerNames = listOf("gc"),
    jdkOptions = java21BenchmarkJvmOptions,
)

val jmhMutationJava21 = registerReportOnlyJmhTask(
    taskName = "jmhMutationJava21",
    taskDescription = "Runs single-shot index and mutation benchmarks on Java 21.",
    launcher = java21Launcher,
    benchmarkIncludes = listOf("io.github.shuaibrao.cqengine.benchmark.MutationBenchmark.*"),
    mode = "ss",
    unit = "ms",
    reportDirectory = "jmh-mutation-java21",
    profilerNames = emptyList(),
    jdkOptions = java21BenchmarkJvmOptions,
    warmups = 1,
)

val jmhMutationAllocationJava21 = registerReportOnlyJmhTask(
    taskName = "jmhMutationAllocationJava21",
    taskDescription = "Measures steady reversible update allocation on Java 21.",
    launcher = java21Launcher,
    benchmarkIncludes = listOf("io.github.shuaibrao.cqengine.benchmark.MutationAllocationBenchmark.*"),
    mode = "avgt",
    unit = "ns",
    reportDirectory = "jmh-mutation-allocation-java21",
    profilerNames = listOf("gc"),
    jdkOptions = java21BenchmarkJvmOptions,
)

val jmhPersistenceJava21 = registerReportOnlyJmhTask(
    taskName = "jmhPersistenceJava21",
    taskDescription = "Measures equivalent persistence lifecycles on Java 21.",
    launcher = java21Launcher,
    benchmarkIncludes = listOf("io.github.shuaibrao.cqengine.benchmark.PersistenceLifecycleBenchmark.*"),
    mode = "avgt",
    unit = "us",
    reportDirectory = "jmh-persistence-java21",
    profilerNames = listOf("gc"),
    jdkOptions = java21PersistenceJvmOptions,
)

val jmhConcurrencyJava21 = registerReportOnlyJmhTask(
    taskName = "jmhConcurrencyJava21",
    taskDescription = "Measures declared reader/writer mixes on Java 21.",
    launcher = java21Launcher,
    benchmarkIncludes = listOf("io.github.shuaibrao.cqengine.benchmark.ConcurrentReadWriteBenchmark.*"),
    mode = "thrpt",
    unit = "s",
    reportDirectory = "jmh-concurrency-java21",
    profilerNames = emptyList(),
    jdkOptions = java21PersistenceJvmOptions,
)

val jmhLatencyJava21 = registerReportOnlyJmhTask(
    taskName = "jmhLatencyJava21",
    taskDescription = "Records Java 21 sampled query and persistence lifecycle latency distributions.",
    launcher = java21Launcher,
    benchmarkIncludes = sampledLatencyIncludes,
    mode = "sample",
    unit = "us",
    reportDirectory = "jmh-latency-java21",
    profilerNames = emptyList(),
    jdkOptions = java21PersistenceJvmOptions,
)

tasks.register<JMHTask>("jmhSmokeJava25") {
    description = "Runs one short Java 25 fork of every smoke-eligible JMH workload."
    group = "verification"

    jmhClasspath.from(configurations.named("jmh"))
    testRuntimeClasspath.from(configurations.named("jmhRuntimeClasspath"))
    jarArchive.set(jmhJar.flatMap { it.archiveFile })
    javaLauncher.set(java25Launcher)

    includes.set(listOf("io.github.shuaibrao.cqengine.benchmark.*"))
    excludes.set(listOf(".*ConcurrentReadWriteBenchmark\\.writeHeavy.*"))
    benchmarkMode.set(listOf("ss"))
    timeUnit.set("ms")
    warmupIterations.set(0)
    iterations.set(1)
    fork.set(1)
    failOnError.set(true)
    forceGC.set(false)
    profilers.set(emptyList())
    benchmarkParameters.put(
        "datasetSize",
        objects.listProperty(String::class.java).value(listOf("256")),
    )
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("reports/jmh-smoke-java25/results.json"))
    humanOutputFile.set(layout.buildDirectory.file("reports/jmh-smoke-java25/human.txt"))
}

tasks.register<JMHTask>("jmhSmokeJava21") {
    description = "Runs one short Java 21 fork of every smoke-eligible JMH workload."
    group = "verification"

    jmhClasspath.from(configurations.named("jmh"))
    testRuntimeClasspath.from(configurations.named("jmhRuntimeClasspath"))
    jarArchive.set(jmhJar.flatMap { it.archiveFile })
    javaLauncher.set(java21Launcher)

    includes.set(listOf("io.github.shuaibrao.cqengine.benchmark.*"))
    excludes.set(listOf(".*ConcurrentReadWriteBenchmark\\.writeHeavy.*"))
    benchmarkMode.set(listOf("ss"))
    timeUnit.set("ms")
    warmupIterations.set(0)
    iterations.set(1)
    fork.set(1)
    failOnError.set(true)
    forceGC.set(false)
    profilers.set(emptyList())
    benchmarkParameters.put(
        "datasetSize",
        objects.listProperty(String::class.java).value(listOf("256")),
    )
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("reports/jmh-smoke-java21/results.json"))
    humanOutputFile.set(layout.buildDirectory.file("reports/jmh-smoke-java21/human.txt"))
    jvmArgsAppend.set(java21PersistenceJvmOptions)
}

fun jmhKey(benchmark: String, vararg params: Pair<String, String>): String {
    val parameterKey = params
        .map { (name, value) -> "$name=$value" }
        .sorted()
        .joinToString(",")
    return "io.github.shuaibrao.cqengine.benchmark.$benchmark|$parameterKey"
}

val smokeExpectedKeys = mutableSetOf<String>()
listOf("add", "buildHashIndex", "remove", "update").forEach { method ->
    smokeExpectedKeys.add(jmhKey("MutationBenchmark.$method", "datasetSize" to "256"))
}
smokeExpectedKeys.add(
    jmhKey("MutationAllocationBenchmark.replaceWithSingletonInputs", "datasetSize" to "256"),
)
listOf(
    "constructCompoundQuery",
    "createResultSetReadCostAndClose",
    "firstResultAndClose",
    "fullIterationAndClose",
    "sizeAndClose",
    "unindexedFullIterationAndClose",
).forEach { method ->
    smokeExpectedKeys.add(jmhKey("QueryLifecycleBenchmark.$method", "datasetSize" to "256"))
}
listOf(
    "UNIQUE_ZERO",
    "UNIQUE_ONE",
    "HASH_LARGE",
    "NAVIGABLE_SMALL",
    "COMPOUND_LARGE",
    "STANDING_MEDIUM",
    "RADIX_LARGE",
    "REVERSED_RADIX_LARGE",
    "INVERTED_RADIX_LARGE",
    "SUFFIX_LARGE",
    "FALLBACK_LARGE",
).forEach { scenario ->
    listOf(
        "createResultSetReadCostAndClose",
        "firstResultAndClose",
        "fullIterationAndClose",
        "sizeAndClose",
    ).forEach { method ->
        smokeExpectedKeys.add(
            jmhKey(
                "QueryScenarioBenchmark.$method",
                "datasetSize" to "256",
                "scenario" to scenario,
            ),
        )
    }
}
listOf("ON_HEAP", "OFF_HEAP", "DISK_WAL").forEach { mode ->
    listOf(
        "pointLookupAndClose",
        "replace",
        "secondaryIndexFullIterationAndClose",
        "secondaryIndexSizeAndClose",
    ).forEach { method ->
        smokeExpectedKeys.add(
            jmhKey(
                "PersistenceLifecycleBenchmark.$method",
                "datasetSize" to "256",
                "persistenceMode" to mode,
            ),
        )
    }
    listOf("readHeavy", "readOnly").forEach { group ->
        smokeExpectedKeys.add(
            jmhKey(
                "ConcurrentReadWriteBenchmark.$group",
                "datasetSize" to "256",
                "persistenceMode" to mode,
            ),
        )
    }
}

val java25RequiredJvmArgs = setOf(
    "--sun-misc-unsafe-memory-access=allow",
    "--enable-native-access=ALL-UNNAMED",
)
val java21PersistenceRequiredJvmArgs = setOf("--enable-native-access=ALL-UNNAMED")

val verifyJmhSmokeJava25 by tasks.registering(VerifyJmhReport::class) {
    description = "Rejects invalid or incomplete Java 25 JMH smoke results."
    group = "verification"
    resultsFile.set(layout.buildDirectory.file("reports/jmh-smoke-java25/results.json"))
    humanOutputFile.set(layout.buildDirectory.file("reports/jmh-smoke-java25/human.txt"))
    expectedKeys.set(smokeExpectedKeys)
    expectedMode.set("ss")
    expectedJavaMajor.set(25)
    requiredJvmArgs.set(java25RequiredJvmArgs)
}

val verifyJmhSmokeJava21 by tasks.registering(VerifyJmhReport::class) {
    description = "Rejects invalid or incomplete Java 21 JMH smoke results."
    group = "verification"
    resultsFile.set(layout.buildDirectory.file("reports/jmh-smoke-java21/results.json"))
    humanOutputFile.set(layout.buildDirectory.file("reports/jmh-smoke-java21/human.txt"))
    expectedKeys.set(smokeExpectedKeys)
    expectedMode.set("ss")
    expectedJavaMajor.set(21)
    requiredJvmArgs.set(java21PersistenceRequiredJvmArgs)
}

tasks.named("jmhSmokeJava25") {
    finalizedBy(verifyJmhSmokeJava25)
}

tasks.named("jmhSmokeJava21") {
    finalizedBy(verifyJmhSmokeJava21)
}

val concurrencyExpectedKeys = mutableSetOf<String>()
listOf("ON_HEAP", "OFF_HEAP", "DISK_WAL").forEach { mode ->
    listOf("readHeavy", "readOnly", "writeHeavy").forEach { group ->
        concurrencyExpectedKeys.add(
            jmhKey(
                "ConcurrentReadWriteBenchmark.$group",
                "datasetSize" to "10000",
                "persistenceMode" to mode,
            ),
        )
    }
}

val verifyJmhConcurrency by tasks.registering(VerifyJmhReport::class) {
    description = "Rejects failed, missing, duplicate or unexpected concurrency benchmark results."
    group = "verification"
    resultsFile.set(layout.buildDirectory.file("reports/jmh-concurrency/results.json"))
    humanOutputFile.set(layout.buildDirectory.file("reports/jmh-concurrency/human.txt"))
    expectedKeys.set(concurrencyExpectedKeys)
    expectedMode.set("thrpt")
    expectedJavaMajor.set(25)
    requiredJvmArgs.set(java25RequiredJvmArgs)
}

val verifyJmhConcurrencyJava21 by tasks.registering(VerifyJmhReport::class) {
    description = "Rejects invalid or incomplete Java 21 concurrency benchmark results."
    group = "verification"
    resultsFile.set(layout.buildDirectory.file("reports/jmh-concurrency-java21/results.json"))
    humanOutputFile.set(layout.buildDirectory.file("reports/jmh-concurrency-java21/human.txt"))
    expectedKeys.set(concurrencyExpectedKeys)
    expectedMode.set("thrpt")
    expectedJavaMajor.set(21)
    requiredJvmArgs.set(java21PersistenceRequiredJvmArgs)
}

jmhConcurrency {
    finalizedBy(verifyJmhConcurrency)
}

jmhConcurrencyJava21.configure {
    finalizedBy(verifyJmhConcurrencyJava21)
}

tasks.register("jmhSmoke") {
    description = "Runs exact-inventory smoke matrices on Java 21 and Java 25."
    group = "verification"
    dependsOn("jmhSmokeJava21", "jmhSmokeJava25")
}

tasks.register("jmhJava25") {
    description = "Runs the complete report-only Java 25 benchmark suite."
    group = "benchmark"
    dependsOn(
        "jmh",
        "jmhConcurrency",
        "jmhLatencyJava25",
        "jmhMutation",
        "jmhMutationAllocation",
        "jmhPersistence",
        "jmhQueryScenarios",
    )
}

tasks.register("jmhJava21") {
    description = "Runs the complete report-only Java 21 benchmark suite."
    group = "benchmark"
    dependsOn(
        jmhConcurrencyJava21,
        jmhLatencyJava21,
        jmhMutationAllocationJava21,
        jmhMutationJava21,
        jmhPersistenceJava21,
        jmhQueryJava21,
        jmhQueryScenariosJava21,
    )
}

data class JmhBaselineReportDefinition(
    val reportDirectory: String,
    val taskName: String,
    val lane: String,
    val javaMajor: Int,
    val expectedCount: Int,
    val mode: String,
    val scoreUnit: String,
    val forks: Int,
    val warmupIterations: Int,
    val warmupTime: String,
    val measurementIterations: Int,
    val measurementTime: String,
    val threads: Int,
    val allocationRequired: Boolean,
    val sampledLatency: Boolean,
    val concurrency: Boolean,
    val requiredJvmArgs: Set<String>,
) {
    fun encode(): String = listOf(
        reportDirectory,
        taskName,
        lane,
        javaMajor,
        expectedCount,
        mode,
        scoreUnit,
        forks,
        warmupIterations,
        warmupTime,
        measurementIterations,
        measurementTime,
        threads,
        allocationRequired,
        sampledLatency,
        concurrency,
        requiredJvmArgs.sorted().joinToString(",").ifEmpty { "-" },
    ).joinToString("\t")
}

fun baselineReportDefinitions(
    javaMajor: Int,
    directorySuffix: String,
    requiredCoreJvmArgs: Set<String>,
    requiredPersistenceJvmArgs: Set<String>,
): List<JmhBaselineReportDefinition> {
    fun directory(java25Name: String, java21Name: String): String =
        if (javaMajor == 25) java25Name else "$java21Name$directorySuffix"
    return listOf(
        JmhBaselineReportDefinition(
            directory("jmh", "jmh-query"), if (javaMajor == 25) "jmh" else "jmhQueryJava21",
            "query", javaMajor, 6,
            "avgt", "ns/op", 2, 3, "1 s", 5, "1 s", 1,
            allocationRequired = true,
            sampledLatency = false,
            concurrency = false,
            requiredJvmArgs = requiredCoreJvmArgs,
        ),
        JmhBaselineReportDefinition(
            directory("jmh-query-scenarios", "jmh-query-scenarios"),
            if (javaMajor == 25) "jmhQueryScenarios" else "jmhQueryScenariosJava21",
            "scenarios", javaMajor, 44,
            "avgt", "ns/op", 2, 3, "1 s", 5, "1 s", 1,
            allocationRequired = true,
            sampledLatency = false,
            concurrency = false,
            requiredJvmArgs = requiredCoreJvmArgs,
        ),
        JmhBaselineReportDefinition(
            directory("jmh-mutation", "jmh-mutation"),
            if (javaMajor == 25) "jmhMutation" else "jmhMutationJava21",
            "mutation", javaMajor, 4,
            "ss", "ms/op", 2, 1, "1 s", 5, "1 s", 1,
            allocationRequired = false,
            sampledLatency = false,
            concurrency = false,
            requiredJvmArgs = requiredCoreJvmArgs,
        ),
        JmhBaselineReportDefinition(
            directory("jmh-mutation-allocation", "jmh-mutation-allocation"),
            if (javaMajor == 25) "jmhMutationAllocation" else "jmhMutationAllocationJava21",
            "allocation", javaMajor, 1,
            "avgt", "ns/op", 2, 3, "1 s", 5, "1 s", 1,
            allocationRequired = true,
            sampledLatency = false,
            concurrency = false,
            requiredJvmArgs = requiredCoreJvmArgs,
        ),
        JmhBaselineReportDefinition(
            directory("jmh-persistence", "jmh-persistence"),
            if (javaMajor == 25) "jmhPersistence" else "jmhPersistenceJava21",
            "persistence", javaMajor, 12,
            "avgt", "us/op", 2, 3, "1 s", 5, "1 s", 1,
            allocationRequired = true,
            sampledLatency = false,
            concurrency = false,
            requiredJvmArgs = requiredPersistenceJvmArgs,
        ),
        JmhBaselineReportDefinition(
            directory("jmh-concurrency", "jmh-concurrency"),
            if (javaMajor == 25) "jmhConcurrency" else "jmhConcurrencyJava21",
            "concurrency", javaMajor, 9,
            "thrpt", "ops/s", 2, 3, "1 s", 5, "1 s", 4,
            allocationRequired = false,
            sampledLatency = false,
            concurrency = true,
            requiredJvmArgs = requiredPersistenceJvmArgs,
        ),
        JmhBaselineReportDefinition(
            directory("jmh-latency-java25", "jmh-latency"),
            if (javaMajor == 25) "jmhLatencyJava25" else "jmhLatencyJava21",
            "latency", javaMajor, 28,
            "sample", "us/op", 2, 3, "1 s", 5, "1 s", 1,
            allocationRequired = false,
            sampledLatency = true,
            concurrency = false,
            requiredJvmArgs = requiredPersistenceJvmArgs,
        ),
    )
}

val java25BaselineJvmArgs = java25RequiredJvmArgs
val java21CoreBaselineJvmArgs = emptySet<String>()
val java21PersistenceBaselineJvmArgs = java21PersistenceRequiredJvmArgs
val jmhBaselineDefinitions =
    baselineReportDefinitions(21, "-java21", java21CoreBaselineJvmArgs, java21PersistenceBaselineJvmArgs) +
        baselineReportDefinitions(25, "", java25BaselineJvmArgs, java25BaselineJvmArgs)

check(jmhBaselineDefinitions.size == 14) { "The dual-JDK baseline must define 14 reports" }
listOf(21, 25).forEach { javaMajor ->
    check(jmhBaselineDefinitions.filter { it.javaMajor == javaMajor }.sumOf { it.expectedCount } == 104) {
        "Java $javaMajor baseline must define exactly 104 results"
    }
}

val baselineScenarioNames = listOf(
    "UNIQUE_ZERO",
    "UNIQUE_ONE",
    "HASH_LARGE",
    "NAVIGABLE_SMALL",
    "COMPOUND_LARGE",
    "STANDING_MEDIUM",
    "RADIX_LARGE",
    "REVERSED_RADIX_LARGE",
    "INVERTED_RADIX_LARGE",
    "SUFFIX_LARGE",
    "FALLBACK_LARGE",
)
val baselineExpectedResultKeys = mutableSetOf<String>()
fun addBaselineResult(lane: String, key: String) {
    listOf(21, 25).forEach { javaMajor ->
        check(baselineExpectedResultKeys.add("$javaMajor\t$lane\t$key")) {
            "Duplicate expected JMH baseline result: $javaMajor/$lane/$key"
        }
    }
}

listOf(
    "constructCompoundQuery",
    "createResultSetReadCostAndClose",
    "firstResultAndClose",
    "fullIterationAndClose",
    "sizeAndClose",
    "unindexedFullIterationAndClose",
).forEach { method ->
    addBaselineResult("query", jmhKey("QueryLifecycleBenchmark.$method", "datasetSize" to "10000"))
}
baselineScenarioNames.forEach { scenario ->
    listOf(
        "createResultSetReadCostAndClose",
        "firstResultAndClose",
        "fullIterationAndClose",
        "sizeAndClose",
    ).forEach { method ->
        addBaselineResult(
            "scenarios",
            jmhKey(
                "QueryScenarioBenchmark.$method",
                "datasetSize" to "10000",
                "scenario" to scenario,
            ),
        )
    }
}
listOf("add", "remove", "update").forEach { method ->
    addBaselineResult("mutation", jmhKey("MutationBenchmark.$method", "datasetSize" to "1000"))
}
addBaselineResult(
    "mutation",
    jmhKey("MutationBenchmark.buildHashIndex", "datasetSize" to "10000"),
)
addBaselineResult(
    "allocation",
    jmhKey("MutationAllocationBenchmark.replaceWithSingletonInputs", "datasetSize" to "1000"),
)
listOf("ON_HEAP", "OFF_HEAP", "DISK_WAL").forEach { persistenceMode ->
    listOf(
        "pointLookupAndClose",
        "replace",
        "secondaryIndexFullIterationAndClose",
        "secondaryIndexSizeAndClose",
    ).forEach { method ->
        addBaselineResult(
            "persistence",
            jmhKey(
                "PersistenceLifecycleBenchmark.$method",
                "datasetSize" to "10000",
                "persistenceMode" to persistenceMode,
            ),
        )
    }
    listOf("readHeavy", "readOnly", "writeHeavy").forEach { group ->
        addBaselineResult(
            "concurrency",
            jmhKey(
                "ConcurrentReadWriteBenchmark.$group",
                "datasetSize" to "10000",
                "persistenceMode" to persistenceMode,
            ),
        )
    }
    listOf("pointLookupAndClose", "replace").forEach { method ->
        addBaselineResult(
            "latency",
            jmhKey(
                "PersistenceLifecycleBenchmark.$method",
                "datasetSize" to "10000",
                "persistenceMode" to persistenceMode,
            ),
        )
    }
}
baselineScenarioNames.forEach { scenario ->
    listOf("firstResultAndClose", "fullIterationAndClose").forEach { method ->
        addBaselineResult(
            "latency",
            jmhKey(
                "QueryScenarioBenchmark.$method",
                "datasetSize" to "10000",
                "scenario" to scenario,
            ),
        )
    }
}
check(baselineExpectedResultKeys.size == 208) {
    "The dual-JDK JMH baseline must define exactly 208 result keys"
}

fun benchmarkMethod(className: String, methodName: String): String =
    "$benchmarkNamespace.$className.$methodName"

val queryBenchmarkMethods = setOf(
    "constructCompoundQuery",
    "createResultSetReadCostAndClose",
    "firstResultAndClose",
    "fullIterationAndClose",
    "sizeAndClose",
    "unindexedFullIterationAndClose",
).mapTo(sortedSetOf()) { method -> benchmarkMethod("QueryLifecycleBenchmark", method) }
val scenarioBenchmarkMethods = setOf(
    "createResultSetReadCostAndClose",
    "firstResultAndClose",
    "fullIterationAndClose",
    "sizeAndClose",
).mapTo(sortedSetOf()) { method -> benchmarkMethod("QueryScenarioBenchmark", method) }
val mutationBenchmarkMethods = setOf("add", "buildHashIndex", "remove", "update")
    .mapTo(sortedSetOf()) { method -> benchmarkMethod("MutationBenchmark", method) }
val allocationBenchmarkMethods = sortedSetOf(
    benchmarkMethod("MutationAllocationBenchmark", "replaceWithSingletonInputs"),
)
val persistenceBenchmarkMethods = setOf(
    "pointLookupAndClose",
    "replace",
    "secondaryIndexFullIterationAndClose",
    "secondaryIndexSizeAndClose",
).mapTo(sortedSetOf()) { method -> benchmarkMethod("PersistenceLifecycleBenchmark", method) }
val concurrencyBenchmarkMethods = setOf("readHeavy", "readOnly", "writeHeavy")
    .mapTo(sortedSetOf()) { method -> benchmarkMethod("ConcurrentReadWriteBenchmark", method) }
val latencyBenchmarkMethods = sortedSetOf(
    benchmarkMethod("QueryScenarioBenchmark", "firstResultAndClose"),
    benchmarkMethod("QueryScenarioBenchmark", "fullIterationAndClose"),
    benchmarkMethod("PersistenceLifecycleBenchmark", "pointLookupAndClose"),
    benchmarkMethod("PersistenceLifecycleBenchmark", "replace"),
)
val allBenchmarkMethods = sortedSetOf<String>().apply {
    addAll(queryBenchmarkMethods)
    addAll(scenarioBenchmarkMethods)
    addAll(mutationBenchmarkMethods)
    addAll(allocationBenchmarkMethods)
    addAll(persistenceBenchmarkMethods)
    addAll(concurrencyBenchmarkMethods)
}
check(allBenchmarkMethods.size == 22) { "The JMH runner must expose exactly 22 benchmark methods" }
val smokeBenchmarkMethods = allBenchmarkMethods
    .minus(benchmarkMethod("ConcurrentReadWriteBenchmark", "writeHeavy"))
    .toSortedSet()

data class JmhSelectionContract(
    val task: TaskProvider<JMHTask>,
    val lane: String,
    val expectedBenchmarkMethods: Set<String>,
)

val jmhSelectionContracts = listOf(
    JmhSelectionContract(tasks.named<JMHTask>("jmh"), "query", queryBenchmarkMethods),
    JmhSelectionContract(tasks.named<JMHTask>("jmhQueryScenarios"), "scenarios", scenarioBenchmarkMethods),
    JmhSelectionContract(tasks.named<JMHTask>("jmhMutation"), "mutation", mutationBenchmarkMethods),
    JmhSelectionContract(
        tasks.named<JMHTask>("jmhMutationAllocation"),
        "allocation",
        allocationBenchmarkMethods,
    ),
    JmhSelectionContract(tasks.named<JMHTask>("jmhPersistence"), "persistence", persistenceBenchmarkMethods),
    JmhSelectionContract(jmhConcurrency, "concurrency", concurrencyBenchmarkMethods),
    JmhSelectionContract(jmhLatencyJava25, "latency", latencyBenchmarkMethods),
    JmhSelectionContract(jmhQueryJava21, "query", queryBenchmarkMethods),
    JmhSelectionContract(jmhQueryScenariosJava21, "scenarios", scenarioBenchmarkMethods),
    JmhSelectionContract(jmhMutationJava21, "mutation", mutationBenchmarkMethods),
    JmhSelectionContract(jmhMutationAllocationJava21, "allocation", allocationBenchmarkMethods),
    JmhSelectionContract(jmhPersistenceJava21, "persistence", persistenceBenchmarkMethods),
    JmhSelectionContract(jmhConcurrencyJava21, "concurrency", concurrencyBenchmarkMethods),
    JmhSelectionContract(jmhLatencyJava21, "latency", latencyBenchmarkMethods),
    JmhSelectionContract(tasks.named<JMHTask>("jmhSmokeJava25"), "smoke", smokeBenchmarkMethods),
    JmhSelectionContract(tasks.named<JMHTask>("jmhSmokeJava21"), "smoke", smokeBenchmarkMethods),
)

fun encodeJmhSelectionContract(contract: JmhSelectionContract): String {
    val task = contract.task.get()
    val includePatterns = task.includes.get()
    val excludePatterns = task.excludes.orNull ?: emptyList()
    fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    return listOf(
        encode(task.name),
        encode(contract.lane),
        includePatterns.size.toString(),
        encode(includePatterns.joinToString(",")),
        excludePatterns.size.toString(),
        encode(excludePatterns.joinToString(",")),
        encode(contract.expectedBenchmarkMethods.sorted().joinToString("\n")),
    ).joinToString("\t")
}

check(jmhSelectionContracts.size == 16) { "Exactly 16 JMH task selections must be contracted" }
val encodedJmhSelectionContracts = providers.provider {
    jmhSelectionContracts.map(::encodeJmhSelectionContract)
}
val registeredJmhTaskNames = providers.provider {
    tasks.withType<JMHTask>().names.sorted()
}

val jmhLaneSelectionRegression by tasks.registering(VerifyJmhLaneSelectionRegression::class) {
    description = "Proves malformed, broadened, missing and uncontracted JMH lane selections fail closed."
    group = "verification"
}

val jmhLaneSelectionPreflight by tasks.registering(VerifyJmhLaneSelections::class) {
    description = "Lists the generated runner and verifies every JMH task selects its exact benchmark methods."
    group = "verification"
    dependsOn(jmhJar, jmhLaneSelectionRegression)
    discoveryClasspath.from(
        configurations.named("jmh"),
        jmhJar.flatMap { task -> task.archiveFile },
        configurations.named("jmhRuntimeClasspath"),
    )
    javaExecutable.set(java21Launcher.map { launcher -> launcher.executablePath })
    registeredTaskNames.set(registeredJmhTaskNames)
    selectionSpecifications.set(encodedJmhSelectionContracts)
    expectedBenchmarkNames.set(allBenchmarkMethods)
    reportFile.set(layout.buildDirectory.file("reports/jmh-selection/inventory.txt"))
    outputs.upToDateWhen { false }
}

tasks.withType<JMHTask>().configureEach {
    dependsOn(jmhLaneSelectionPreflight)
}

val jmhMachineLabel = providers.environmentVariable("CQENGINE_JMH_MACHINE_LABEL")
    .orElse(providers.gradleProperty("jmhMachineLabel"))
    .orElse("")
val inheritedUnsafeBaselineOptions = listOf(
    "JAVA_TOOL_OPTIONS",
    "JDK_JAVA_OPTIONS",
    "_JAVA_OPTIONS",
    "JAVA_OPTS",
    "GRADLE_OPTS",
).filter(System.getenv()::containsKey)
// Git for Windows installs no /usr/bin, so the POSIX default cannot be the only fallback.
val jmhTrustedGit = providers.environmentVariable("CQENGINE_TRUSTED_GIT").orElse(
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        Path.of(System.getenv("ProgramFiles") ?: "C:\\Program Files", "Git", "cmd", "git.exe").toString()
    }
    else {
        "/usr/bin/git"
    },
)
val benchmarkHostApprovalDirectory = rootProject.layout.projectDirectory.dir("config/benchmark-hosts")

val verifyJmhMachineApproval by tasks.registering(VerifyBenchmarkHostApproval::class) {
    description = "Verifies that the selected benchmark label matches an approved local host record."
    group = "verification"
    machineLabel.set(jmhMachineLabel)
    requireApproval.set(true)
    approvalDirectory.set(benchmarkHostApprovalDirectory)
    projectDirectory.set(rootProject.layout.projectDirectory)
    outputs.upToDateWhen { false }
}

val jmhMachineApprovalPreflight by tasks.registering(VerifyBenchmarkHostApproval::class) {
    description = "Records whether the selected benchmark label matches a reviewed host record."
    group = "verification"
    machineLabel.set(jmhMachineLabel)
    requireApproval.set(false)
    approvalDirectory.set(benchmarkHostApprovalDirectory)
    projectDirectory.set(rootProject.layout.projectDirectory)
    outputs.upToDateWhen { false }
}

val verifyJmhBaselinePrerequisites by tasks.registering {
    description = "Rejects an aggregate JMH baseline with an unusable label or contaminated JVM options."
    group = "verification"
    dependsOn(
        rootProject.tasks.named("verifyQualificationInvocation"),
        jmhLaneSelectionPreflight,
        jmhMachineApprovalPreflight,
    )
    outputs.upToDateWhen { false }
    doLast {
        val label = jmhMachineLabel.get()
        if (!label.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{2,63}"))) {
            throw GradleException(
                "Set CQENGINE_JMH_MACHINE_LABEL to a 3-64 character machine label before qualification",
            )
        }
        if (inheritedUnsafeBaselineOptions.isNotEmpty()) {
            throw GradleException(
                "Authoritative JMH baseline inherited unsafe JVM/build options: $inheritedUnsafeBaselineOptions",
            )
        }
    }
}

val fullBaselineTaskNames = listOf(
    "jmh",
    "jmhConcurrency",
    "jmhLatencyJava25",
    "jmhMutation",
    "jmhMutationAllocation",
    "jmhPersistence",
    "jmhQueryScenarios",
    "jmhConcurrencyJava21",
    "jmhLatencyJava21",
    "jmhMutationAllocationJava21",
    "jmhMutationJava21",
    "jmhPersistenceJava21",
    "jmhQueryJava21",
    "jmhQueryScenariosJava21",
)
fullBaselineTaskNames.forEach { taskName ->
    tasks.named(taskName) { mustRunAfter(verifyJmhBaselinePrerequisites) }
}

fun gitBaselineEvidence(vararg arguments: String): Provider<String> = providers.exec {
    workingDir(rootProject.layout.projectDirectory)
    commandLine(jmhTrustedGit.get(), *arguments)
}.standardOutput.asText.map(String::trim)

val verifyJmhBaselineValidatorRegression by tasks.registering(VerifyJmhBaselineValidatorRegression::class) {
    description = "Proves that the full JMH gate rejects negative and non-finite primary scores."
    group = "verification"
    outputs.upToDateWhen { false }
}

val verifyJmhBaseline by tasks.registering(VerifyJmhBaseline::class) {
    description = "Validates and inventories the exact dual-JDK 208-result local-release JMH baseline."
    group = "verification"
    dependsOn(
        "jmhJava21",
        "jmhJava25",
        verifyJmhBaselinePrerequisites,
        verifyJmhBaselineValidatorRegression,
    )
    val rawFiles = jmhBaselineDefinitions.flatMap { definition ->
        listOf(
            layout.buildDirectory.file("reports/${definition.reportDirectory}/results.json"),
            layout.buildDirectory.file("reports/${definition.reportDirectory}/human.txt"),
        )
    }
    rawReportFiles.from(rawFiles)
    benchmarkBuildDirectory.set(layout.buildDirectory)
    projectDirectory.set(rootProject.layout.projectDirectory)
    approvalDirectory.set(rootProject.layout.projectDirectory.dir("config/benchmark-hosts"))
    reportSpecifications.set(jmhBaselineDefinitions.map(JmhBaselineReportDefinition::encode))
    expectedResultKeys.set(baselineExpectedResultKeys)
    expectedResultsPerJava.set(104)
    sourceCommit.set(gitBaselineEvidence("rev-parse", "HEAD"))
    sourceTree.set(gitBaselineEvidence("rev-parse", "HEAD^{tree}"))
    publicationCoordinate.set(provider { "${rootProject.group}:cqengine:${rootProject.version}" })
    machineLabel.set(jmhMachineLabel)
    unsafeEnvironmentVariables.set(inheritedUnsafeBaselineOptions)
    expectedJmhVersion.set(libs.versions.jmh)
    gradleVersion.set(gradle.gradleVersion)
    java21Executable.set(java21Launcher.map { it.executablePath.asFile.absolutePath })
    java21Vendor.set(java21Launcher.map { it.metadata.vendor })
    java21Version.set(java21Launcher.map { it.metadata.jvmVersion })
    java25Executable.set(java25Launcher.map { it.executablePath.asFile.absolutePath })
    java25Vendor.set(java25Launcher.map { it.metadata.vendor })
    java25Version.set(java25Launcher.map { it.metadata.jvmVersion })
    wrapperProperties.set(rootProject.layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties"))
    wrapperJar.set(rootProject.layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.jar"))
    benchmarkJar.set(jmhJar.flatMap { it.archiveFile })
    environmentFile.set(layout.buildDirectory.file("reports/jmh-baseline/environment.properties"))
    summaryFile.set(layout.buildDirectory.file("reports/jmh-baseline/summary.txt"))
    inventoryFile.set(layout.buildDirectory.file("reports/jmh-baseline/inventory.txt"))
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("A JMH baseline records the current machine and run evidence") { true }
}

val jmhPublicationReportDirectory = layout.buildDirectory.dir("reports/jmh-publication")
val benchmarkDocumentationLine = provider {
    val version = rootProject.version.toString()
    val match = Regex("""([0-9]+[.][0-9]+[.][0-9]+)(?:-(?:SNAPSHOT|rc[.][1-9][0-9]*))?""")
        .matchEntire(version)
        ?: throw GradleException("Cannot derive benchmark documentation line from version '$version'")
    "${match.groupValues[1]}-development"
}
val jmhPublicationInputFiles = jmhBaselineDefinitions.map { definition ->
    layout.buildDirectory.file("reports/${definition.reportDirectory}/results.json")
}

// The qualification wrapper narrows PATH to its bound tool directories and passes the interpreter it resolved,
// so a bare name would not resolve inside a qualified run.
fun benchmarkPythonExecutable(): String =
    System.getenv("CQENGINE_TRUSTED_PYTHON")
        ?: if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")) "python" else "python3"

val generateJmhPublicationReport by tasks.registering(Exec::class) {
    description = "Generates sanitized tables and SVGs from the authoritative JMH baseline."
    group = "verification"
    dependsOn(verifyJmhBaseline)
    inputs.files(jmhPublicationInputFiles)
    inputs.files(
        layout.buildDirectory.file("reports/jmh-baseline/environment.properties"),
        layout.buildDirectory.file("reports/jmh-baseline/summary.txt"),
        rootProject.layout.projectDirectory.file("scripts/generate-benchmark-report.py"),
    )
    inputs.file(jmhMachineLabel.map { label ->
        rootProject.layout.projectDirectory.file("config/benchmark-hosts/$label.properties")
    })
    outputs.dir(jmhPublicationReportDirectory)
    outputs.upToDateWhen { false }
    notCompatibleWithConfigurationCache("The report records the current benchmark host and run")
    doFirst {
        val label = jmhMachineLabel.get()
        val approvalRecord = rootProject.layout.projectDirectory
            .file("config/benchmark-hosts/$label.properties")
            .asFile
        if (!approvalRecord.isFile) {
            throw GradleException("No benchmark-host approval record exists for '$label'")
        }
        commandLine(
            benchmarkPythonExecutable(),
            rootProject.layout.projectDirectory.file("scripts/generate-benchmark-report.py").asFile,
            "--input",
            layout.buildDirectory.dir("reports").get().asFile,
            "--output",
            jmhPublicationReportDirectory.get().asFile,
            "--approval-record",
            approvalRecord,
            "--display-host",
            label,
        )
    }
}

val syncBenchmarkDocumentation by tasks.registering(Exec::class) {
    description = "Copies the last reviewed full JMH report into tracked benchmark documentation."
    group = "documentation"
    inputs.files(jmhPublicationInputFiles)
    inputs.files(
        layout.buildDirectory.file("reports/jmh-baseline/environment.properties"),
        layout.buildDirectory.file("reports/jmh-baseline/summary.txt"),
        rootProject.layout.projectDirectory.file("scripts/generate-benchmark-report.py"),
    )
    outputs.upToDateWhen { false }
    notCompatibleWithConfigurationCache("This explicit task writes reviewed generated files into the source tree")
    doFirst {
        val environmentFile = layout.buildDirectory
            .file("reports/jmh-baseline/environment.properties")
            .get()
            .asFile
        if (!environmentFile.isFile) {
            throw GradleException("Run ./gradlew qualifyLocally before syncing benchmark documentation")
        }
        val environment = Properties().apply { environmentFile.inputStream().use(::load) }
        val sourceCommit = environment.getProperty("sourceCommit").orEmpty()
        if (!sourceCommit.matches(Regex("[0-9a-f]{40}"))) {
            throw GradleException("Retained JMH environment has no valid sourceCommit")
        }
        val label = environment.getProperty("machineLabel").orEmpty()
        if (!label.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{2,63}"))) {
            throw GradleException("Retained JMH environment has no valid machineLabel")
        }
        val approvalRecord = rootProject.layout.projectDirectory
            .file("config/benchmark-hosts/$label.properties")
            .asFile
        if (!approvalRecord.isFile) {
            throw GradleException("No benchmark-host approval record exists for retained label '$label'")
        }
        val destination = rootProject.layout.projectDirectory
            .dir("benchmarks/results/${benchmarkDocumentationLine.get()}/${sourceCommit.take(8)}-$label")
            .asFile
        commandLine(
            benchmarkPythonExecutable(),
            rootProject.layout.projectDirectory.file("scripts/generate-benchmark-report.py").asFile,
            "--input",
            layout.buildDirectory.dir("reports").get().asFile,
            "--output",
            destination,
            "--approval-record",
            approvalRecord,
            "--display-host",
            label,
        )
    }
}

tasks.register("jmhBaseline") {
    description = "Runs the release-wrapper-only exact Java 21/25 local-release JMH baseline."
    group = "verification"
    dependsOn(generateJmhPublicationReport)
}
