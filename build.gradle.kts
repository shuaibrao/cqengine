import aQute.bnd.gradle.Baseline
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import com.github.jk1.license.filter.SpdxLicenseBundleNormalizer
import com.github.jk1.license.render.CsvReportRenderer
import com.github.jk1.license.render.InventoryHtmlReportRenderer
import com.github.jk1.license.render.JsonReportRenderer
import com.github.jk1.license.render.ReportRenderer
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jengelman.gradle.plugins.shadow.transformers.CacheableTransformer
import com.github.jengelman.gradle.plugins.shadow.transformers.ResourceTransformer
import com.github.jengelman.gradle.plugins.shadow.transformers.TransformerContext
import org.apache.tools.zip.UnixStat
import org.apache.tools.zip.ZipEntry
import org.apache.tools.zip.ZipOutputStream
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.configuration.BuildFeatures
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileTreeElement
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.java.archives.Manifest
import org.gradle.api.java.archives.ManifestMergeSpec
import org.gradle.api.plugins.antlr.AntlrTask
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.process.ExecOperations
import org.cyclonedx.model.Bom
import org.cyclonedx.model.Component
import org.cyclonedx.model.Hash
import org.cyclonedx.model.Property as CdxProperty
import org.cyclonedx.Version
import org.cyclonedx.generators.BomGeneratorFactory
import org.cyclonedx.parsers.JsonParser
import org.cyclonedx.parsers.XmlParser
import org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.ObjectStreamClass
import java.io.Serializable
import java.lang.reflect.Modifier
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.SortedMap
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

abstract class VerifySourceAndLegalProvenance @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:Internal
    abstract val sourceDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val gitExecutable: RegularFileProperty

    @get:Input
    abstract val baselineCommit: Property<String>

    @get:Input
    abstract val baselineTag: Property<String>

    @get:Input
    abstract val expectedOriginUrl: Property<String>

    @get:Input
    abstract val expectedUpstreamUrl: Property<String>

    @get:Input
    abstract val requiredLegalPaths: ListProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val thinJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val allJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourcesJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val javadocJar: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val root = sourceDirectory.get().asFile.toPath().toRealPath()
        val baseline = baselineCommit.get()
        val tag = baselineTag.get()

        val localConfigKeys = git(root, "config", "--local", "--no-includes", "--name-only", "--list")
            .lines()
            .map { it.lowercase(Locale.ROOT) }
        val includeKeys = localConfigKeys.filter { key ->
            key == "include.path" || key.startsWith("includeif.") && key.endsWith(".path")
        }
        requireValid(includeKeys.isEmpty(), "Local Git include/includeIf configuration is forbidden: $includeKeys")
        requireValid(
            localConfigKeys.size == localConfigKeys.toSet().size,
            "Duplicate local Git configuration keys are forbidden: $localConfigKeys",
        )
        val coreLocalConfigKeys = setOf(
            "core.repositoryformatversion",
            "core.filemode",
            "core.bare",
            "core.logallrefupdates",
        )
        val upstreamLocalConfigKeys = setOf(
            "remote.upstream.url",
            "remote.upstream.fetch",
        )
        val originLocalConfigKeys = setOf("remote.origin.url", "remote.origin.fetch")
        val hostedMode = "remote.origin.url" in localConfigKeys
        val commonLocalConfigKeys = coreLocalConfigKeys + upstreamLocalConfigKeys +
            if (hostedMode) originLocalConfigKeys else emptySet()
        val sourceLocalConfigKeys = commonLocalConfigKeys + setOf("branch.main.remote", "branch.main.merge")
        val detachedLocalConfigKeys = commonLocalConfigKeys + setOf("core.autocrlf", "core.eol")
        requireValid(
            localConfigKeys.toSet() == sourceLocalConfigKeys || localConfigKeys.toSet() == detachedLocalConfigKeys,
            "Unexpected local Git configuration inventory: $localConfigKeys",
        )
        val expectedLocalConfig = linkedMapOf(
            "core.repositoryformatversion" to "0",
            "core.filemode" to "true",
            "core.bare" to "false",
            "core.logallrefupdates" to "true",
            "remote.upstream.url" to expectedUpstreamUrl.get(),
            "remote.upstream.fetch" to "+refs/heads/*:refs/remotes/upstream/*",
        ).apply {
            if (hostedMode) {
                put("remote.origin.url", expectedOriginUrl.get())
                put("remote.origin.fetch", "+refs/heads/*:refs/remotes/origin/*")
            }
            if (localConfigKeys.toSet() == sourceLocalConfigKeys) {
                put("branch.main.remote", if (hostedMode) "origin" else "upstream")
                put("branch.main.merge", if (hostedMode) "refs/heads/main" else "refs/heads/master")
            }
            else {
                put("core.autocrlf", "false")
                put("core.eol", "lf")
            }
        }
        expectedLocalConfig.forEach { (key, expectedValue) ->
            val values = git(root, "config", "--local", "--no-includes", "--get-all", key).lines()
            requireValid(values == listOf(expectedValue), "Unexpected local Git setting $key=$values")
        }
        val replaceRefs = git(root, "for-each-ref", "--format=%(refname)", "refs/replace/").lines()
        requireValid(replaceRefs.isEmpty(), "Git replacement refs are forbidden: $replaceRefs")
        val grafts = Path.of(
            git(root, "rev-parse", "--path-format=absolute", "--git-path", "info/grafts").text.trim(),
        )
        val shallow = Path.of(
            git(root, "rev-parse", "--path-format=absolute", "--git-path", "shallow").text.trim(),
        )
        requireValid(!Files.exists(grafts) && !Files.isSymbolicLink(grafts), "Legacy Git graft state is forbidden")
        requireValid(!Files.exists(shallow) && !Files.isSymbolicLink(shallow), "Shallow Git state is forbidden")

        val worktreeStatus = git(root, "status", "--porcelain=v1", "--untracked-files=all").text.trim()
        requireValid(worktreeStatus.isEmpty(), "Source worktree is not clean:\n$worktreeStatus")

        val tagType = git(root, "cat-file", "-t", "refs/tags/$tag").text.trim()
        requireValid(tagType == "tag", "Baseline tag must be annotated: $tag")
        val tagTarget = git(root, "rev-parse", "refs/tags/$tag^{}").text.trim()
        requireValid(tagTarget == baseline, "Baseline tag $tag targets $tagTarget, expected $baseline")
        val mergeBase = git(root, "merge-base", "HEAD", baseline).text.trim()
        requireValid(mergeBase == baseline, "HEAD is not based exactly on upstream baseline $baseline")
        git(root, "merge-base", "--is-ancestor", baseline, "HEAD")

        val remoteNames = git(root, "remote").lines()
        val expectedRemoteNames = if (hostedMode) listOf("origin", "upstream") else listOf("upstream")
        requireValid(
            remoteNames == expectedRemoteNames,
            "Expected exact ${if (hostedMode) "hosted" else "local"} remotes, found $remoteNames",
        )
        buildMap {
            if (hostedMode) put("origin", expectedOriginUrl.get())
            put("upstream", expectedUpstreamUrl.get())
        }.forEach {
            (remote, expectedUrl) ->
            val remoteUrls = git(
                root,
                "config",
                "--local",
                "--no-includes",
                "--get-all",
                "remote.$remote.url",
            ).lines()
            requireValid(remoteUrls == listOf(expectedUrl), "Unexpected $remote URL inventory: $remoteUrls")
        }

        val trackedPaths = git(root, "ls-files", "-z").text
            .split('\u0000')
            .filter(String::isNotEmpty)
            .toSet()
        val forbiddenMavenPaths = trackedPaths.filter { path ->
            val segments = path.lowercase(Locale.ROOT).split('/')
            segments.any { it == ".mvn" } ||
                segments.last() == "pom.xml" ||
                segments.last() == "mvnw" ||
                segments.last() == "mvnw.cmd"
        }.sorted()
        requireValid(forbiddenMavenPaths.isEmpty(), "Tracked Maven build files are forbidden: $forbiddenMavenPaths")

        val legalPaths = requiredLegalPaths.get()
        val expectedLegalPaths = legalPaths.toSet()
        requireValid(legalPaths.size == expectedLegalPaths.size, "Required legal path inventory contains duplicates")
        val unexpectedRootLegalFiles = trackedPaths.filter { path ->
            if ('/' in path || path in expectedLegalPaths) return@filter false
            val upperName = path.uppercase(Locale.ROOT)
            upperName.startsWith("LICENSE") || upperName.startsWith("NOTICE") || upperName.startsWith("COPYING")
        }.sorted()
        requireValid(
            unexpectedRootLegalFiles.isEmpty(),
            "Root legal files must be explicitly inventoried and packaged: $unexpectedRootLegalFiles",
        )
        val actualThirdPartyLicenses = trackedPaths
            .filter { it.startsWith("third-party-licenses/") }
            .toSet()
        requireValid(
            actualThirdPartyLicenses == expectedLegalPaths.filter { it.startsWith("third-party-licenses/") }.toSet(),
            "Third-party legal inventory mismatch: $actualThirdPartyLicenses",
        )
        val legalBytes = legalPaths.associateWith { path ->
            requireValid(path in trackedPaths, "Required legal file is not tracked: $path")
            val mode = git(root, "ls-files", "--stage", "--", path).text.substringBefore(' ')
            requireValid(mode == "100644", "Legal file must be a regular non-executable file: $path ($mode)")
            val file = root.resolve(path)
            requireValid(Files.isRegularFile(file), "Required legal file is missing: $path")
            Files.readAllBytes(file).also { bytes ->
                requireValid(bytes.isNotEmpty(), "Required legal file is empty: $path")
            }
        }
        val upstreamLicense = git(root, "show", "$baseline:LICENSE.txt").bytes
        requireValid(
            legalBytes.getValue("LICENSE.txt").contentEquals(upstreamLicense),
            "LICENSE.txt differs from the exact upstream baseline",
        )

        verifyPackagedLegalResources("thin", thinJar.get().asFile, legalBytes)
        verifyPackagedLegalResources("all", allJar.get().asFile, legalBytes)
        verifyPackagedLegalResources("sources", sourcesJar.get().asFile, legalBytes)
        verifyPackagedLegalResources("javadoc", javadocJar.get().asFile, legalBytes)

        val head = git(root, "rev-parse", "HEAD").text.trim()
        val report = buildString {
            appendLine("status=verified")
            appendLine("head=$head")
            appendLine("baseline=$baseline")
            appendLine("baselineTag=$tag")
            appendLine("repositoryMode=${if (hostedMode) "hosted" else "local"}")
            if (hostedMode) appendLine("origin=${expectedOriginUrl.get()}")
            appendLine("upstream=${expectedUpstreamUrl.get()}")
            appendLine("trackedFiles=${trackedPaths.size}")
            legalPaths.sorted().forEach { path ->
                appendLine("legal[$path]=${sha256(legalBytes.getValue(path))}")
            }
            appendLine("packagedLegalResources=byte-identical:thin,all,sources,javadoc")
            appendLine("mavenBuildFiles=absent")
        }
        reportFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(report, StandardCharsets.UTF_8)
        }
    }

    private fun verifyPackagedLegalResources(
        label: String,
        archive: File,
        legalBytes: Map<String, ByteArray>,
    ) {
        JarFile(archive).use { jar ->
            legalBytes.forEach { (sourcePath, expectedBytes) ->
                val resourcePath = when (sourcePath) {
                    "LICENSE.txt", "THIRD-PARTY-NOTICES" -> "META-INF/$sourcePath"
                    else -> "META-INF/THIRD-PARTY-LICENSES/${sourcePath.substringAfterLast('/')}"
                }
                val entry = jar.getJarEntry(resourcePath)
                    ?: throw GradleException("$label JAR is missing legal resource $resourcePath")
                val actualBytes = jar.getInputStream(entry).use { it.readAllBytes() }
                requireValid(
                    actualBytes.contentEquals(expectedBytes),
                    "$label JAR legal resource is not byte-identical to $sourcePath: $resourcePath",
                )
            }
        }
    }

    private fun git(root: Path, vararg arguments: String): CommandResult {
        val standardOutput = ByteArrayOutputStream()
        val errorOutput = ByteArrayOutputStream()
        val result = execOperations.exec {
            workingDir(root.toFile())
            commandLine(listOf(gitExecutable.get().asFile.absolutePath) + arguments)
            this.standardOutput = standardOutput
            this.errorOutput = errorOutput
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) {
            throw GradleException(
                "git ${arguments.joinToString(" ")} failed (${result.exitValue}): " +
                    errorOutput.toString(StandardCharsets.UTF_8).trim(),
            )
        }
        return CommandResult(standardOutput.toByteArray())
    }

    private fun CommandResult.lines(): List<String> = text.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toList()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

    private fun requireValid(condition: Boolean, message: String) {
        if (!condition) throw GradleException(message)
    }

    private data class CommandResult(val bytes: ByteArray) {
        val text: String
            get() = bytes.toString(StandardCharsets.UTF_8)
    }
}

abstract class VerifyFormatRatchet @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:Internal
    abstract val sourceDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val gitExecutable: RegularFileProperty

    @get:Input
    abstract val baselineCommit: Property<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val root = sourceDirectory.get().asFile.toPath().toRealPath()
        val baseline = baselineCommit.get()
        val whitespaceCheck = git(root, true, "diff", "--check", baseline, "--")
        if (whitespaceCheck.exitCode != 0) {
            throw GradleException(
                "Maintainer-changed lines contain whitespace errors relative to $baseline:\n${whitespaceCheck.output.trim()}",
            )
        }

        val changedTextPaths = git(
            root,
            false,
            "diff",
            "--name-only",
            "-z",
            "--diff-filter=ACMRT",
            baseline,
            "--",
        ).output.split('\u0000')
            .filter(String::isNotEmpty)
            .filter(::isTextPath)
            .sorted()
        val newTextPaths = git(
            root,
            false,
            "diff",
            "--name-only",
            "-z",
            "--diff-filter=A",
            baseline,
            "--",
        ).output.split('\u0000')
            .filter(String::isNotEmpty)
            .filter(::isTextPath)
            .sorted()
        val newTextPathSet = newTextPaths.toSet()
        changedTextPaths.forEach { relativePath ->
            val file = root.resolve(relativePath).normalize()
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                throw GradleException("Changed text path is not a regular project file: $relativePath")
            }
            val bytes = Files.readAllBytes(file)
            if (relativePath in newTextPathSet && bytes.isNotEmpty() && bytes.last() != '\n'.code.toByte()) {
                throw GradleException("Changed text file has no final newline: $relativePath")
            }
            if (bytes.any { it == '\r'.code.toByte() }) {
                throw GradleException("Changed text file contains CR/CRLF line endings: $relativePath")
            }
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = try {
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            }
            catch (exception: Exception) {
                throw GradleException("Changed text file is not valid UTF-8: $relativePath", exception)
            }
            if (text.startsWith('\uFEFF')) {
                throw GradleException("Changed text file contains a UTF-8 BOM: $relativePath")
            }
            if ('\u0000' in text) {
                throw GradleException("Changed text file contains a NUL byte: $relativePath")
            }
        }

        reportFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                "status=verified\nbaseline=$baseline\nchangedTextFiles=${changedTextPaths.size}\n" +
                    "newTextFiles=${newTextPaths.size}\nchangedLineWhitespace=clean\nnewTextEncoding=UTF-8-LF\n",
                StandardCharsets.UTF_8,
            )
        }
    }

    private fun isTextPath(path: String): Boolean {
        val name = path.substringAfterLast('/')
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return name in setOf(".gitignore", ".gitattributes", ".editorconfig", "gradlew") ||
            extension in setOf(
                "bnd", "g4", "gradle", "java", "json", "kt", "kts", "md", "properties", "sh", "toml", "txt", "xml",
                "yaml", "yml",
            )
    }

    private fun git(root: Path, allowFailure: Boolean, vararg arguments: String): GitResult {
        val standardOutput = ByteArrayOutputStream()
        val errorOutput = ByteArrayOutputStream()
        val result = execOperations.exec {
            workingDir(root.toFile())
            commandLine(listOf(gitExecutable.get().asFile.absolutePath) + arguments)
            this.standardOutput = standardOutput
            this.errorOutput = errorOutput
            isIgnoreExitValue = true
        }
        val combined = standardOutput.toString(StandardCharsets.UTF_8) +
            errorOutput.toString(StandardCharsets.UTF_8)
        if (!allowFailure && result.exitValue != 0) {
            throw GradleException("git ${arguments.joinToString(" ")} failed (${result.exitValue}): ${combined.trim()}")
        }
        return GitResult(result.exitValue, combined)
    }

    private data class GitResult(val exitCode: Int, val output: String)
}

abstract class VerifyReleaseInvocation @Inject constructor(
    buildFeatures: BuildFeatures,
) : DefaultTask() {

    @get:Input
    abstract val dependencyVerificationMode: Property<String>

    @get:Input
    abstract val excludedTaskNames: ListProperty<String>

    @get:Input
    abstract val buildCacheEnabled: Property<Boolean>

    @get:Input
    abstract val parallelEnabled: Property<Boolean>

    @get:Input
    abstract val configurationCacheRequested: Property<Boolean>

    @get:Input
    abstract val writeDependencyVerification: ListProperty<String>

    @get:Input
    abstract val dependencyLocksToUpdate: ListProperty<String>

    @get:Input
    abstract val dependencyLockWriteEnabled: Property<Boolean>

    @get:Input
    abstract val refreshKeysEnabled: Property<Boolean>

    @get:Input
    abstract val exportKeysEnabled: Property<Boolean>

    @get:Input
    abstract val includedBuilds: ListProperty<String>

    @get:Input
    abstract val initScripts: ListProperty<String>

    @get:Input
    abstract val gradleUserHomePath: Property<String>

    @get:Input
    abstract val environmentGradleUserHomePath: Property<String>

    @get:Input
    abstract val projectRootPath: Property<String>

    @get:Input
    abstract val releaseInvocationMarker: Property<String>

    @get:Input
    abstract val unsafeOptionEnvironmentVariables: ListProperty<String>

    @get:Input
    abstract val gitConfigNoSystem: Property<String>

    @get:Input
    abstract val gitConfigGlobal: Property<String>

    @get:Input
    abstract val gitNoReplaceObjects: Property<String>

    @get:Input
    abstract val gitAttrNoSystem: Property<String>

    @get:Input
    abstract val trustedPath: Property<String>

    @get:Input
    abstract val trustedToolBindings: ListProperty<String>

    @get:Input
    abstract val releaseEvidenceOverrides: ListProperty<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    init {
        configurationCacheRequested.convention(buildFeatures.configurationCache.requested)
    }

    @TaskAction
    fun verify() {
        val errors = mutableListOf<String>()
        if (dependencyVerificationMode.get() != "STRICT") {
            errors += "dependency verification must be strict"
        }
        if (excludedTaskNames.get().isNotEmpty()) errors += "task exclusions are forbidden"
        if (buildCacheEnabled.get()) errors += "the build cache must be disabled"
        if (parallelEnabled.get()) errors += "parallel project execution must be disabled"
        if (configurationCacheRequested.get()) errors += "the configuration cache must be disabled"
        if (writeDependencyVerification.get().isNotEmpty()) errors += "dependency-verification mutation is forbidden"
        if (dependencyLocksToUpdate.get().isNotEmpty() || dependencyLockWriteEnabled.get()) {
            errors += "dependency-lock mutation is forbidden"
        }
        if (refreshKeysEnabled.get() || exportKeysEnabled.get()) errors += "dependency-key mutation is forbidden"
        if (includedBuilds.get().isNotEmpty()) errors += "included builds are forbidden"
        if (initScripts.get().isNotEmpty()) errors += "Gradle init scripts are forbidden"
        if (releaseInvocationMarker.get() != "1") errors += "use scripts/qualify-candidate.sh"
        if (unsafeOptionEnvironmentVariables.get().isNotEmpty()) {
            errors += "unsafe JVM/build option environment variables are set: ${unsafeOptionEnvironmentVariables.get()}"
        }
        if (gitConfigNoSystem.get() != "1") {
            errors += "GIT_CONFIG_NOSYSTEM must disable system Git configuration"
        }
        if (gitConfigGlobal.get() != "/dev/null") {
            errors += "GIT_CONFIG_GLOBAL must disable global Git configuration"
        }
        if (gitNoReplaceObjects.get() != "1") {
            errors += "GIT_NO_REPLACE_OBJECTS must disable Git object replacement"
        }
        if (gitAttrNoSystem.get() != "1") {
            errors += "GIT_ATTR_NOSYSTEM must disable system Git attributes"
        }
        if (trustedPath.get() != "/usr/bin:/bin" || System.getenv("PATH") != trustedPath.get()) {
            errors += "PATH must be the wrapper's fixed /usr/bin:/bin tool path"
        }
        val verifiedTools = verifyTrustedTools(errors)
        if (releaseEvidenceOverrides.get().isNotEmpty()) {
            errors += "release evidence overrides are reserved for nested archive builds: " +
                releaseEvidenceOverrides.get()
        }

        val root = Path.of(projectRootPath.get()).toRealPath()
        val gradleHome = Path.of(gradleUserHomePath.get()).toRealPath()
        val environmentHome = environmentGradleUserHomePath.orNull
            ?.takeIf(String::isNotBlank)
            ?.let { Path.of(it) }
            ?.toRealPath()
        if (environmentHome == null || environmentHome != gradleHome) {
            errors += "GRADLE_USER_HOME must name the active isolated Gradle home"
        }
        if (gradleHome.startsWith(root)) errors += "release GRADLE_USER_HOME must be outside the source checkout"
        if (gradleHome == Path.of(System.getProperty("user.home"), ".gradle").toAbsolutePath().normalize()) {
            errors += "the shared user Gradle home is forbidden"
        }
        val marker = gradleHome.resolve(CLEAN_HOME_MARKER)
        val expectedMarker = "project=$root\nstate=created-empty\n"
        if (!Files.isRegularFile(marker) || Files.readString(marker, StandardCharsets.UTF_8) != expectedMarker) {
            errors += "isolated Gradle home has no valid clean-home marker"
        }

        if (errors.isNotEmpty()) {
            throw GradleException("Invalid release qualification invocation:\n - ${errors.joinToString("\n - ")}")
        }
        reportFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                "status=verified\ndependencyVerification=strict\ntaskExclusions=none\n" +
                    "buildCache=disabled\nparallel=disabled\nconfigurationCache=disabled\n" +
                    "gradleUserHome=isolated-clean\njavaOptionEnvironment=sanitized\n" +
                    "gitConfigGlobal=disabled\ngitConfigSystem=disabled\ngitObjectReplacement=disabled\n" +
                    "gitSystemAttributes=disabled\n" +
                    "trustedPath=${trustedPath.get()}\n" +
                    verifiedTools.toSortedMap().entries.joinToString("") { (name, binding) ->
                        "tool.$name.path=${binding.path}\ntool.$name.sha256=${binding.sha256}\n"
                    },
                StandardCharsets.UTF_8,
            )
        }
    }

    private fun verifyTrustedTools(errors: MutableList<String>): Map<String, ToolBinding> {
        val expectedNames = setOf("bash", "git", "java", "nproc", "shell", "tar")
        val bindings = linkedMapOf<String, ToolBinding>()
        trustedToolBindings.get().forEach { encoded ->
            val fields = encoded.split('|')
            if (fields.size != 3 || fields.any(String::isBlank)) {
                errors += "malformed trusted-tool binding"
                return@forEach
            }
            val (name, pathValue, expectedSha256) = fields
            if (name !in expectedNames || bindings.containsKey(name)) {
                errors += "unexpected or duplicate trusted-tool binding: $name"
                return@forEach
            }
            if (!expectedSha256.matches(Regex("[0-9a-f]{64}"))) {
                errors += "trusted-tool SHA-256 is malformed: $name"
                return@forEach
            }
            val configuredPath = try {
                Path.of(pathValue)
            }
            catch (_: RuntimeException) {
                errors += "trusted-tool path is malformed: $name"
                return@forEach
            }
            if (!configuredPath.isAbsolute) {
                errors += "trusted-tool path is not absolute: $name"
                return@forEach
            }
            val realPath = try {
                configuredPath.toRealPath()
            }
            catch (_: IOException) {
                errors += "trusted-tool path does not resolve: $name"
                return@forEach
            }
            if (realPath != configuredPath.normalize() || !Files.isRegularFile(realPath) ||
                !realPath.toFile().canExecute()
            ) {
                errors += "trusted-tool path is not one canonical executable: $name"
                return@forEach
            }
            val actualSha256 = sha256(realPath)
            if (actualSha256 != expectedSha256) {
                errors += "trusted-tool SHA-256 changed after wrapper binding: $name"
                return@forEach
            }
            if (name != "java") {
                val systemBin = Path.of("/usr/bin")
                if (!realPath.startsWith(systemBin) || Files.isWritable(realPath) ||
                    Files.isWritable(realPath.parent)
                ) {
                    errors += "trusted system tool is outside non-writable /usr/bin: $name"
                    return@forEach
                }
            }
            bindings[name] = ToolBinding(realPath, expectedSha256)
        }
        if (bindings.keys != expectedNames) {
            errors += "trusted-tool inventory differs: expected $expectedNames, found ${bindings.keys}"
        }

        val javaBinding = bindings["java"]
        if (javaBinding != null) {
            val javaExecutable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                "java.exe"
            }
            else {
                "java"
            }
            val runningJava = try {
                Path.of(System.getProperty("java.home"), "bin", javaExecutable).toRealPath()
            }
            catch (_: IOException) {
                null
            }
            if (runningJava != javaBinding.path) {
                errors += "trusted Java binding differs from the Gradle runtime: $runningJava"
            }
        }
        return bindings
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private data class ToolBinding(val path: Path, val sha256: String)

    companion object {
        const val CLEAN_HOME_MARKER = ".cqengine-clean-release-home"
    }
}

abstract class JavaAgentArgumentProvider : CommandLineArgumentProvider {
    @get:Classpath
    abstract val agentClasspath: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> =
        listOf("-javaagent:${agentClasspath.singleFile.absolutePath}")
}

abstract class VerifyApiCompatibility @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:Classpath
    abstract val toolClasspath: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val oldArchive: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val newArchive: RegularFileProperty

    @get:Classpath
    abstract val oldRuntimeClasspath: ConfigurableFileCollection

    @get:Classpath
    abstract val newRuntimeClasspath: ConfigurableFileCollection

    @get:Nested
    abstract val launcher: Property<JavaLauncher>

    @get:Input
    abstract val baselineCoordinate: Property<String>

    @get:Input
    abstract val toolVersion: Property<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val incompatible = runJapicmp(onlyIncompatible = true)
        if (incompatible.exitCode != 0) {
            throw GradleException("japicmp found an incompatible API change:\n${incompatible.output}")
        }
        val fullDelta = runJapicmp(onlyIncompatible = false)
        if (fullDelta.exitCode != 0) {
            throw GradleException("japicmp could not inventory the full API delta:\n${fullDelta.output}")
        }
        val serialVersionUids = verifySerialVersionUids()
        reportFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("formatVersion=1")
                    appendLine("baseline=${baselineCoordinate.get()}")
                    appendLine("tool=japicmp:${toolVersion.get()}")
                    appendLine("oldSha256=${digest(oldArchive.get().asFile, "SHA-256")}")
                    appendLine("oldSha512=${digest(oldArchive.get().asFile, "SHA-512")}")
                    appendLine("newSha256=${digest(newArchive.get().asFile, "SHA-256")}")
                    appendLine("newSha512=${digest(newArchive.get().asFile, "SHA-512")}")
                    appendLine("incompatibleChanges=0")
                    appendLine("serializableBaselineClasses=${serialVersionUids.size}")
                    appendLine()
                    appendLine("[incompatible-only-output]")
                    appendLine(incompatible.output.ifBlank { "(no output)" })
                    appendLine()
                    appendLine("[full-api-delta-output]")
                    appendLine(fullDelta.output.ifBlank { "(no output)" })
                    appendLine()
                    appendLine("[serial-version-uids]")
                    serialVersionUids.forEach { (className, uid) -> appendLine("$className=$uid") }
                },
                StandardCharsets.UTF_8,
            )
        }
    }

    private fun runJapicmp(onlyIncompatible: Boolean): JapicmpResult {
        val output = ByteArrayOutputStream()
        val result = execOperations.javaexec {
            classpath(toolClasspath)
            mainClass.set("japicmp.JApiCmp")
            executable = launcher.get().executablePath.asFile.absolutePath
            args(
                "--old", oldArchive.get().asFile.absolutePath,
                "--new", newArchive.get().asFile.absolutePath,
                "--old-classpath", oldRuntimeClasspath.asPath,
                "--new-classpath", newRuntimeClasspath.asPath,
            )
            if (onlyIncompatible) {
                args(
                    "--only-incompatible",
                    "--error-on-binary-incompatibility",
                    "--error-on-source-incompatibility",
                )
            }
            else {
                args("--only-modified")
            }
            standardOutput = output
            errorOutput = output
            isIgnoreExitValue = true
        }
        val normalizedOutput = output.toString(StandardCharsets.UTF_8)
            .replace("\r\n", "\n")
            .replace(newArchive.get().asFile.absolutePath, "<new-archive>")
            .replace(oldArchive.get().asFile.absolutePath, "<old-archive>")
            .trim()
        if (onlyIncompatible && normalizedOutput.isNotEmpty()) logger.lifecycle(normalizedOutput)
        return JapicmpResult(result.exitValue, normalizedOutput)
    }

    private fun verifySerialVersionUids(): SortedMap<String, Long> {
        val identities = sortedMapOf<String, Long>()
        val oldUrls = (listOf(oldArchive.get().asFile) + oldRuntimeClasspath.files)
            .distinct()
            .map { it.toURI().toURL() }
            .toTypedArray()
        val newUrls = (listOf(newArchive.get().asFile) + newRuntimeClasspath.files)
            .distinct()
            .map { it.toURI().toURL() }
            .toTypedArray()

        URLClassLoader(oldUrls, ClassLoader.getPlatformClassLoader()).use { oldLoader ->
            URLClassLoader(newUrls, ClassLoader.getPlatformClassLoader()).use { newLoader ->
                JarFile(oldArchive.get().asFile).use { oldJar ->
                    oldJar.entries().asSequence()
                        .map { it.name }
                        .filter { it.endsWith(".class") && !it.startsWith("META-INF/") }
                        .map { it.removeSuffix(".class").replace('/', '.') }
                        .sorted()
                        .forEach { className ->
                            val oldClass = Class.forName(className, false, oldLoader)
                            val apiType = Modifier.isPublic(oldClass.modifiers) ||
                                Modifier.isProtected(oldClass.modifiers)
                            val oldDescriptor = if (apiType && Serializable::class.java.isAssignableFrom(oldClass)) {
                                ObjectStreamClass.lookup(oldClass)
                            }
                            else {
                                null
                            }
                            if (oldDescriptor != null) {
                                val newClass = Class.forName(className, false, newLoader)
                                val newDescriptor = ObjectStreamClass.lookup(newClass)
                                    ?: throw GradleException("Serializable API type is no longer serializable: $className")
                                if (oldDescriptor.serialVersionUID != newDescriptor.serialVersionUID) {
                                    throw GradleException(
                                        "Java serialization identity changed for $className: " +
                                            "${oldDescriptor.serialVersionUID} -> ${newDescriptor.serialVersionUID}",
                                    )
                                }
                                identities[className] = oldDescriptor.serialVersionUID
                            }
                        }
                }
            }
        }
        return identities
    }

    private fun digest(file: File, algorithm: String): String {
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

    private data class JapicmpResult(val exitCode: Int, val output: String)
}

abstract class VerifySpotBugsResults : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val xmlReport: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classesDirectory: DirectoryProperty

    @get:OutputFile
    abstract val inventoryReport: RegularFileProperty

    @TaskAction
    fun verify() {
        val document = secureDocumentBuilderFactory().newDocumentBuilder().parse(xmlReport.get().asFile)
        val analyzedClasses = document.getElementsByTagName("Jar").asSequence()
            .map { Path.of(it.textContent).toAbsolutePath().normalize() }
            .filter { it.fileName.toString().endsWith(".class") }
            .toSet()
        val expectedClasses = classesDirectory.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.toPath().toAbsolutePath().normalize() }
            .toSet()
        if (analyzedClasses != expectedClasses) {
            val missing = (expectedClasses - analyzedClasses).sorted().take(20)
            val unexpected = (analyzedClasses - expectedClasses).sorted().take(20)
            throw GradleException(
                "SpotBugs class inventory mismatch. Missing $missing, unexpected $unexpected",
            )
        }

        val errors = document.getElementsByTagName("Errors").item(0) as? Element
            ?: throw GradleException("SpotBugs report has no Errors inventory")
        val errorCount = errors.getAttribute("errors").toInt()
        val missingClassCount = errors.getAttribute("missingClasses").toInt()
        if (errorCount != 0 || missingClassCount != 0) {
            throw GradleException(
                "SpotBugs analysis was incomplete: errors=$errorCount, missingClasses=$missingClassCount",
            )
        }

        val findingCount = document.getElementsByTagName("BugInstance").length
        if (findingCount != 0) {
            throw GradleException("SpotBugs report contains $findingCount unreviewed high-confidence findings")
        }

        inventoryReport.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                "spotbugs=${document.documentElement.getAttribute("version")}\n" +
                    "classes=${analyzedClasses.size}\nfindings=$findingCount\n" +
                    "errors=$errorCount\nmissingClasses=$missingClassCount\n",
                StandardCharsets.UTF_8,
            )
        }
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }

    private fun org.w3c.dom.NodeList.asSequence(): Sequence<org.w3c.dom.Node> = sequence {
        for (index in 0 until length) yield(item(index))
    }
}

abstract class VerifySpotBugsReview : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val xmlReport: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val reviewedBaseline: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classesDirectory: DirectoryProperty

    @get:OutputFile
    abstract val inventoryReport: RegularFileProperty

    @TaskAction
    fun verify() {
        val document = secureDocumentBuilderFactory().newDocumentBuilder().parse(xmlReport.get().asFile)
        val analyzedClasses = document.getElementsByTagName("Jar").asSequence()
            .map { Path.of(it.textContent).toAbsolutePath().normalize() }
            .filter { it.fileName.toString().endsWith(".class") }
            .toSet()
        val expectedClasses = classesDirectory.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.toPath().toAbsolutePath().normalize() }
            .toSet()
        if (analyzedClasses != expectedClasses) {
            val missing = (expectedClasses - analyzedClasses).sorted().take(20)
            val unexpected = (analyzedClasses - expectedClasses).sorted().take(20)
            throw GradleException(
                "SpotBugs review class inventory mismatch. Missing $missing, unexpected $unexpected",
            )
        }

        val errors = document.getElementsByTagName("Errors").item(0) as? Element
            ?: throw GradleException("SpotBugs review report has no Errors inventory")
        val errorCount = errors.getAttribute("errors").toInt()
        val missingClassCount = errors.getAttribute("missingClasses").toInt()
        if (errorCount != 0 || missingClassCount != 0) {
            throw GradleException(
                "SpotBugs review was incomplete: errors=$errorCount, missingClasses=$missingClassCount",
            )
        }

        val findSecBugsEnabled = document.getElementsByTagName("Plugin").asSequence()
            .map { it as Element }
            .any {
                it.getAttribute("id") == "com.h3xstream.findsecbugs" &&
                    it.getAttribute("enabled") == "true"
            }
        if (!findSecBugsEnabled) {
            throw GradleException("SpotBugs review did not enable the FindSecBugs plugin")
        }

        val findings = document.getElementsByTagName("BugInstance").asSequence()
            .map { it as Element }
            .toList()
        val actualIdentities = findings
            .groupingBy(::stableIdentity)
            .eachCount()
            .entries
            .map { (identity, count) -> "$identity|$count" }
            .sorted()
        val expectedIdentities = reviewedBaseline.get().asFile.readLines(StandardCharsets.UTF_8)
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        if (expectedIdentities != expectedIdentities.sorted() ||
            expectedIdentities.size != expectedIdentities.toSet().size
        ) {
            throw GradleException("SpotBugs review baseline must be sorted and contain no duplicate identities")
        }
        if (actualIdentities != expectedIdentities) {
            val expectedSet = expectedIdentities.toSet()
            val actualSet = actualIdentities.toSet()
            val missing = (expectedSet - actualSet).sorted().take(20)
            val unexpected = (actualSet - expectedSet).sorted().take(20)
            throw GradleException(
                "SpotBugs review inventory changed. Missing reviewed identities $missing, " +
                    "unexpected identities $unexpected. Review every change before updating the baseline.",
            )
        }

        val priorityCounts = findings.groupingBy { it.getAttribute("priority") }.eachCount()
        inventoryReport.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                "spotbugs=${document.documentElement.getAttribute("version")}\n" +
                    "findsecbugs=true\nclasses=${analyzedClasses.size}\n" +
                    "findings=${findings.size}\nhigh=${priorityCounts["1"] ?: 0}\n" +
                    "normal=${priorityCounts["2"] ?: 0}\nlow=${priorityCounts["3"] ?: 0}\n" +
                    "errors=$errorCount\nmissingClasses=$missingClassCount\n\n" +
                    actualIdentities.joinToString(separator = "\n", postfix = "\n"),
                StandardCharsets.UTF_8,
            )
        }
    }

    private fun stableIdentity(finding: Element): String {
        val classElement = finding.primaryElement("Class")
            ?: throw GradleException("SpotBugs finding has no class: ${finding.getAttribute("type")}")
        val methodElement = finding.primaryElement("Method")
        val fieldElement = finding.primaryElement("Field")
        return listOf(
            finding.getAttribute("type"),
            finding.getAttribute("priority"),
            classElement.getAttribute("classname"),
            methodElement?.getAttribute("name").orEmpty(),
            methodElement?.getAttribute("signature").orEmpty(),
            fieldElement?.getAttribute("name").orEmpty(),
            fieldElement?.getAttribute("signature").orEmpty(),
        ).joinToString("|")
    }

    private fun Element.primaryElement(tagName: String): Element? {
        val elements = getElementsByTagName(tagName)
        for (index in 0 until elements.length) {
            val element = elements.item(index) as Element
            if (element.getAttribute("primary") == "true") return element
        }
        return elements.item(0) as? Element
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }

    private fun org.w3c.dom.NodeList.asSequence(): Sequence<org.w3c.dom.Node> = sequence {
        for (index in 0 until length) yield(item(index))
    }
}

abstract class VerifyOwnedJdkUsage @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:Input
    abstract val scanType: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val tool: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classesDirectory: DirectoryProperty

    @get:Classpath
    abstract val runtimeClasspath: ConfigurableFileCollection

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val type = scanType.get()
        val output = ByteArrayOutputStream()
        val arguments = when (type) {
            "jdeprscan" -> listOf(
                "-J-XX:+PerfDisableSharedMem",
                "--release",
                "25",
                "--class-path",
                runtimeClasspath.asPath,
                classesDirectory.get().asFile.absolutePath,
            )
            "jdeps" -> listOf(
                "-J-XX:+PerfDisableSharedMem",
                "--jdk-internals",
                "-include",
                "^com[.]googlecode[.]cqengine[.].*",
                "--multi-release",
                "21",
                "--class-path",
                runtimeClasspath.asPath,
                classesDirectory.get().asFile.absolutePath,
            )
            else -> throw GradleException("Unsupported JDK scan type: $type")
        }
        val result = execOperations.exec {
            executable(tool.get().asFile)
            args(arguments)
            standardOutput = output
            errorOutput = output
            isIgnoreExitValue = true
        }
        val rawOutput = output.toString(StandardCharsets.UTF_8).trim()
        val unexpectedLines = rawOutput.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot { type == "jdeprscan" && it.matches(Regex("^(Directory|Jar file) .+:$")) }
            .toList()

        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(
            buildString {
                appendLine("scan=$type")
                appendLine("release=25")
                appendLine("exit=${result.exitValue}")
                appendLine("findings=${unexpectedLines.size}")
                if (unexpectedLines.isNotEmpty()) {
                    appendLine(unexpectedLines.joinToString("\n"))
                }
            },
            StandardCharsets.UTF_8,
        )

        if (result.exitValue != 0 || unexpectedLines.isNotEmpty()) {
            throw GradleException(
                "$type found unsupported JDK usage or unresolved classes; see ${report.absolutePath}\n" +
                    unexpectedLines.joinToString("\n"),
            )
        }
    }
}

abstract class VerifyFunctionalShardResults : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resultFiles: ConfigurableFileCollection

    @get:Input
    abstract val expectedScenarioCount: Property<Int>

    @get:Input
    abstract val expectedShardCount: Property<Int>

    @get:Input
    abstract val runtimeVersion: Property<Int>

    @get:OutputFile
    abstract val inventoryReport: RegularFileProperty

    @TaskAction
    fun verify() {
        val files = resultFiles.files.sortedBy { it.name }
        val shardCount = expectedShardCount.get()
        if (files.size != shardCount) {
            throw GradleException("Expected $shardCount functional shard reports, found ${files.size}: $files")
        }

        val allScenarioIds = linkedSetOf<Int>()
        val shardCounts = sortedMapOf<Int, Int>()
        for (file in files) {
            val shardNumber = Regex("[$]Shard([1-9][0-9]*)[.]xml$")
                .find(file.name)
                ?.groupValues
                ?.get(1)
                ?.toInt()
                ?: throw GradleException("Cannot identify functional shard from ${file.absolutePath}")
            val shardIndex = shardNumber - 1
            if (shardIndex !in 0 until shardCount || shardIndex in shardCounts) {
                throw GradleException("Unexpected or duplicate functional shard $shardNumber")
            }

            val document = secureDocumentBuilderFactory().newDocumentBuilder().parse(file)
            val suite = document.documentElement
            for (attribute in listOf("failures", "errors", "skipped")) {
                val count = suite.getAttribute(attribute).ifEmpty { "0" }.toInt()
                if (count != 0) {
                    throw GradleException("Functional shard $shardNumber reports $count $attribute")
                }
            }

            val testCases = suite.getElementsByTagName("testcase")
            var scenarioCount = 0
            for (index in 0 until testCases.length) {
                val testCase = testCases.item(index) as Element
                val name = testCase.getAttribute("name")
                val scenarioId = Regex("scenarioNumber=([0-9]+)")
                    .find(name)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
                    ?: throw GradleException("Functional testcase has no scenario ID: $name")
                if ((scenarioId - 1) % shardCount != shardIndex) {
                    throw GradleException("Scenario $scenarioId was emitted by the wrong shard $shardNumber")
                }
                if (!allScenarioIds.add(scenarioId)) {
                    throw GradleException("Functional scenario $scenarioId was executed more than once")
                }
                scenarioCount++
            }
            shardCounts[shardIndex] = scenarioCount
        }

        val expectedIds = (1..expectedScenarioCount.get()).toSet()
        val missing = expectedIds - allScenarioIds
        val unexpected = allScenarioIds - expectedIds
        if (missing.isNotEmpty() || unexpected.isNotEmpty()) {
            throw GradleException(
                "Functional shard union is incomplete. Missing ${missing.sorted()}, unexpected ${unexpected.sorted()}",
            )
        }

        val report = inventoryReport.get().asFile
        report.parentFile.mkdirs()
        report.writeText(
            buildString {
                appendLine("runtime=${runtimeVersion.get()}")
                appendLine("shards=$shardCount")
                appendLine("scenarios=${allScenarioIds.size}")
                shardCounts.forEach { (index, count) -> appendLine("shard.$index=$count") }
            },
            StandardCharsets.UTF_8,
        )
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
}

abstract class VerifyTestTaskResults : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resultDirectory: DirectoryProperty

    @get:Input
    abstract val expectedSuiteCount: Property<Int>

    @get:Input
    abstract val expectedTestCount: Property<Int>

    @get:Input
    abstract val expectedSkippedTests: ListProperty<String>

    @get:Input
    abstract val runtimeVersion: Property<Int>

    @get:OutputFile
    abstract val inventoryReport: RegularFileProperty

    @TaskAction
    fun verify() {
        val reports = resultDirectory.get().asFile.listFiles { file ->
            file.isFile && file.name.startsWith("TEST-") && file.extension == "xml"
        }?.sortedBy { it.name }.orEmpty()
        if (reports.size != expectedSuiteCount.get()) {
            throw GradleException(
                "Expected ${expectedSuiteCount.get()} test suite reports, found ${reports.size}",
            )
        }

        var tests = 0
        var failures = 0
        var errors = 0
        val skippedTests = sortedSetOf<String>()
        for (report in reports) {
            val document = secureDocumentBuilderFactory().newDocumentBuilder().parse(report)
            val suite = document.documentElement
            tests += suite.getAttribute("tests").toInt()
            failures += suite.getAttribute("failures").toInt()
            errors += suite.getAttribute("errors").toInt()
            val testCases = suite.getElementsByTagName("testcase")
            for (index in 0 until testCases.length) {
                val testCase = testCases.item(index) as Element
                if (testCase.childNodes.asSequence().any { it.nodeName == "skipped" }) {
                    skippedTests += testCase.getAttribute("classname") + "#" + testCase.getAttribute("name")
                }
            }
        }

        if (tests != expectedTestCount.get() || failures != 0 || errors != 0) {
            throw GradleException(
                "Unexpected test inventory: tests=$tests, failures=$failures, errors=$errors",
            )
        }
        val expectedSkips = expectedSkippedTests.get().toSortedSet()
        if (skippedTests != expectedSkips) {
            throw GradleException("Expected skipped tests $expectedSkips, found $skippedTests")
        }

        inventoryReport.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("runtime=${runtimeVersion.get()}")
                    appendLine("suites=${reports.size}")
                    appendLine("tests=$tests")
                    appendLine("failures=$failures")
                    appendLine("errors=$errors")
                    appendLine("skipped=${skippedTests.size}")
                    skippedTests.forEach { appendLine("skipped.test=$it") }
                },
                StandardCharsets.UTF_8,
            )
        }
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }

    private fun org.w3c.dom.NodeList.asSequence(): Sequence<org.w3c.dom.Node> = sequence {
        for (index in 0 until length) yield(item(index))
    }
}

abstract class VerifyCoverageEvidence : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val executionData: ConfigurableFileCollection

    @get:Input
    abstract val expectedExecutionDataNames: ListProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val xmlReport: RegularFileProperty

    @get:OutputFile
    abstract val inventoryReport: RegularFileProperty

    @TaskAction
    fun verify() {
        val files = executionData.files.sortedBy { it.name }
        val actualNames = files.map { it.name }
        val expectedNames = expectedExecutionDataNames.get().sorted()
        if (actualNames != expectedNames) {
            throw GradleException("Expected JaCoCo execution data $expectedNames, found $actualNames")
        }
        val emptyFiles = files.filter { !it.isFile || it.length() == 0L }
        if (emptyFiles.isNotEmpty()) {
            throw GradleException("Missing or empty JaCoCo execution data: $emptyFiles")
        }

        val document = secureDocumentBuilderFactory().newDocumentBuilder().parse(xmlReport.get().asFile)
        if (document.documentElement.nodeName != "report") {
            throw GradleException("JaCoCo XML has the wrong root element")
        }
        val counters = document.documentElement.childNodes.asSequence()
            .filterIsInstance<Element>()
            .filter { it.nodeName == "counter" }
            .associateBy { it.getAttribute("type") }
        val requiredCounters = listOf("INSTRUCTION", "BRANCH", "LINE", "METHOD", "CLASS")
        val missingCounters = requiredCounters.filterNot(counters::containsKey)
        if (missingCounters.isNotEmpty()) {
            throw GradleException("JaCoCo XML is missing root counters: $missingCounters")
        }

        val report = buildString {
            files.forEach { appendLine("executionData.${it.name}=${it.length()}") }
            requiredCounters.forEach { type ->
                val counter = counters.getValue(type)
                val missed = counter.getAttribute("missed").toLong()
                val covered = counter.getAttribute("covered").toLong()
                val total = missed + covered
                if (total == 0L) {
                    throw GradleException("JaCoCo $type counter has no executable items")
                }
                appendLine("${type.lowercase(Locale.ROOT)}.missed=$missed")
                appendLine("${type.lowercase(Locale.ROOT)}.covered=$covered")
                appendLine(
                    "${type.lowercase(Locale.ROOT)}.ratio=" +
                        String.format(Locale.ROOT, "%.6f", covered.toDouble() / total),
                )
            }
        }
        inventoryReport.get().asFile.apply {
            parentFile.mkdirs()
            writeText(report, StandardCharsets.UTF_8)
        }
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }

    private fun org.w3c.dom.NodeList.asSequence(): Sequence<org.w3c.dom.Node> = sequence {
        for (index in 0 until length) yield(item(index))
    }
}

@CacheableTransformer
open class SqlDriverServiceTransformer : ResourceTransformer {

    private val providers = linkedSetOf<String>()

    override fun canTransformResource(element: FileTreeElement): Boolean =
        element.path == SERVICE_PATH

    override fun transform(context: TransformerContext) {
        context.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .forEach(providers::add)
        }
    }

    override fun hasTransformedResource(): Boolean = providers.isNotEmpty()

    override fun modifyOutputStream(
        os: ZipOutputStream,
        preserveFileTimestamps: Boolean,
    ) {
        val entry = ZipEntry(SERVICE_PATH).apply {
            unixMode = UnixStat.FILE_FLAG or FILE_MODE_0644
            if (!preserveFileTimestamps) {
                time = ShadowJar.CONSTANT_TIME_FOR_ZIP_ENTRIES
            }
        }
        os.putNextEntry(entry)
        os.write((providers.joinToString("\n") + "\n").toByteArray(StandardCharsets.UTF_8))
        os.closeEntry()
    }

    companion object {
        private const val SERVICE_PATH = "META-INF/services/java.sql.Driver"
        private const val FILE_MODE_0644 = 420
    }
}

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class StandaloneShadowManifest(
    private val delegate: Manifest,
) : com.github.jengelman.gradle.plugins.shadow.tasks.InheritManifest, Manifest by delegate {

    override fun inheritFrom(
        inheritPaths: Array<out Any>,
        action: Action<ManifestMergeSpec>,
    ) {
        inheritPaths.forEach { delegate.from(it, action) }
    }
}

abstract class VerifyPublishedJars : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val thinJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val allJar: RegularFileProperty

    @get:Input
    abstract val expectedBundleVersion: Property<String>

    @get:Input
    abstract val expectedPublicationVersion: Property<String>

    @get:Input
    abstract val expectedSQLiteVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sqliteNativeChecksums: RegularFileProperty

    @get:OutputFile
    abstract val inventoryReport: RegularFileProperty

    @TaskAction
    fun verify() {
        val thin = readJar(thinJar.get().asFile)
        val all = readJar(allJar.get().asFile)

        verifyCommonContract("thin", thin)
        verifyCommonContract("all", all)
        assertValid(
            thin.mainAttributes.keys == EXPECTED_THIN_MAIN_ATTRIBUTES,
            "Thin JAR has an unexpected manifest attribute inventory: ${thin.mainAttributes.keys}",
        )
        assertValid(
            thin.mainAttributes["Manifest-Version"] == "1.0",
            "Thin JAR has the wrong manifest version",
        )
        assertValid(
            thin.mainAttributes["Implementation-Title"] == "CQEngine",
            "Thin JAR has the wrong implementation title",
        )
        assertValid(
            thin.mainAttributes["Implementation-Version"] == expectedPublicationVersion.get(),
            "Thin JAR has the wrong implementation version",
        )
        assertValid(
            thin.mainAttributes["Bundle-ManifestVersion"] == "2",
            "Thin JAR is not an OSGi R4+ bundle",
        )
        assertValid(thin.mainAttributes["Bundle-Name"] == "CQEngine", "Thin JAR has the wrong OSGi bundle name")
        assertValid(
            thin.mainAttributes["Bundle-SymbolicName"] == "cqengine",
            "Thin JAR has the wrong OSGi symbolic name",
        )
        assertValid(
            thin.mainAttributes["Bundle-Version"] == expectedBundleVersion.get(),
            "Thin JAR has the wrong OSGi version: ${thin.mainAttributes["Bundle-Version"]}",
        )
        assertValid(
            thin.mainAttributes["Bundle-License"] == "https://www.apache.org/licenses/LICENSE-2.0",
            "Thin JAR has the wrong OSGi licence declaration: ${thin.mainAttributes["Bundle-License"]}",
        )
        assertValid(
            thin.mainAttributes["Export-Package"]?.contains("com.googlecode.cqengine;version=") == true,
            "Thin JAR does not export the root CQEngine API package",
        )
        assertValid(
            !thin.mainAttributes["Import-Package"].isNullOrBlank(),
            "Thin JAR has no OSGi dependency imports",
        )
        assertValid(
            thin.mainAttributes["Require-Capability"]
                ?.contains("(&(osgi.ee=JavaSE)(version=21))") == true,
            "Thin JAR has the wrong OSGi execution-environment requirement",
        )
        assertValid(
            all.mainAttributes == expectedAllMainAttributes(expectedPublicationVersion.get()),
            "All JAR has unexpected manifest attributes: ${all.mainAttributes}",
        )
        assertValid(all.multiRelease == "true", "All JAR must preserve SQLite's multi-release manifest")

        val nativeInventory = verifySQLiteNatives(allJar.get().asFile)

        val dependencyPrefixes = listOf(
            "com/esotericsoftware/",
            "com/googlecode/concurrenttrees/",
            "javassist/",
            "org/antlr/v4/",
            "org/objenesis/",
            "org/sqlite/",
        )
        val embeddedInThin = dependencyPrefixes.filter { prefix ->
            thin.names.any { it.startsWith(prefix) }
        }
        assertValid(embeddedInThin.isEmpty(), "Thin JAR embeds dependency packages: $embeddedInThin")

        val forbiddenInAll = dependencyPrefixes.dropLast(1).filter { prefix ->
            all.names.any { it.startsWith(prefix) }
        }
        assertValid(forbiddenInAll.isEmpty(), "All JAR contains unrelocated dependency packages: $forbiddenInAll")

        val requiredRelocations = listOf(
            "com/googlecode/cqengine/lib/com/esotericsoftware/",
            "com/googlecode/cqengine/lib/com/googlecode/concurrenttrees/",
            "com/googlecode/cqengine/lib/javassist/",
            "com/googlecode/cqengine/lib/org/antlr/v4/",
            "com/googlecode/cqengine/lib/org/objenesis/",
        )
        val missingRelocations = requiredRelocations.filterNot { prefix ->
            all.names.any { it.startsWith(prefix) && it.endsWith(".class") }
        }
        assertValid(missingRelocations.isEmpty(), "All JAR is missing relocations: $missingRelocations")

        assertValid("org/sqlite/JDBC.class" in all.names, "All JAR is missing the SQLite JDBC driver")
        assertValid(
            all.serviceProviders == listOf("org.sqlite.JDBC"),
            "Unexpected java.sql.Driver providers: ${all.serviceProviders}",
        )
        val requiredEmbeddedLegalResources = listOf(
            "META-INF/LICENSE",
            "META-INF/NOTICE",
            "META-INF/maven/org.xerial/sqlite-jdbc/LICENSE",
            "META-INF/maven/org.xerial/sqlite-jdbc/LICENSE.zentus",
        )
        val missingEmbeddedLegalResources = requiredEmbeddedLegalResources.filterNot(all.names::contains)
        assertValid(
            missingEmbeddedLegalResources.isEmpty(),
            "All JAR is missing embedded dependency legal resources: $missingEmbeddedLegalResources",
        )

        val thinCqengineClasses = thin.names.filterTo(sortedSetOf()) {
            it.startsWith("com/googlecode/cqengine/") && it.endsWith(".class")
        }
        val allCqengineClasses = all.names.filterTo(sortedSetOf()) {
            it.startsWith("com/googlecode/cqengine/") &&
                !it.startsWith("com/googlecode/cqengine/lib/") &&
                it.endsWith(".class")
        }
        assertValid(
            thinCqengineClasses == allCqengineClasses,
            "Thin and all JAR CQEngine class inventories differ",
        )

        inventoryReport.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("publication.version=${expectedPublicationVersion.get()}")
                    appendLine("sqlite.version=${expectedSQLiteVersion.get()}")
                    appendLine("sqlite.native.count=${nativeInventory.size}")
                    nativeInventory.forEach { (path, checksum) ->
                        appendLine("sqlite.native.$path=$checksum")
                    }
                },
                StandardCharsets.UTF_8,
            )
        }
    }

    private fun verifySQLiteNatives(allJar: File): SortedMap<String, String> {
        val checksumFile = sqliteNativeChecksums.get().asFile
        val properties = Properties().apply {
            checksumFile.inputStream().buffered().use(::load)
        }
        val configuredVersion = properties.getProperty("sqlite.version")
        assertValid(
            configuredVersion == expectedSQLiteVersion.get(),
            "SQLite native checksum version $configuredVersion differs from ${expectedSQLiteVersion.get()}",
        )
        val expected = properties.stringPropertyNames()
            .filterNot { it == "sqlite.version" }
            .associateWith { path -> properties.getProperty(path).lowercase(Locale.ROOT) }
            .toSortedMap()
        assertValid(
            expected.size == EXPECTED_SQLITE_NATIVE_COUNT,
            "SQLite native checksum inventory must contain exactly $EXPECTED_SQLITE_NATIVE_COUNT entries, " +
                "found ${expected.size}",
        )
        expected.forEach { (path, checksum) ->
            assertValid(
                checksum.matches(Regex("[0-9a-f]{64}")),
                "SQLite native checksum is not SHA-256 for $path: $checksum",
            )
        }

        return JarFile(allJar).use { jar ->
            val actualPaths = jar.entries().asSequence()
                .filterNot { it.isDirectory }
                .map { it.name }
                .filter { it.startsWith(SQLITE_NATIVE_PREFIX) }
                .toSortedSet()
            assertValid(
                actualPaths == expected.keys,
                "SQLite native inventory differs: expected ${expected.keys}, found $actualPaths",
            )
            actualPaths.associateWithTo(sortedMapOf()) { path ->
                val entry = jar.getJarEntry(path)
                    ?: throw GradleException("SQLite native disappeared while reading all JAR: $path")
                val actual = jar.getInputStream(entry).buffered().use { stream ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                    digest.digest().joinToString("") { byte -> "%02x".format(byte) }
                }
                assertValid(
                    actual == expected.getValue(path),
                    "SQLite native checksum differs for $path: expected ${expected.getValue(path)}, found $actual",
                )
                actual
            }
        }
    }

    private fun verifyCommonContract(label: String, contents: JarContents) {
        val duplicates = contents.names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertValid(duplicates.isEmpty(), "$label JAR has duplicate entries: $duplicates")
        assertValid(contents.mainClass == null, "$label JAR must not be executable")
        assertValid(
            contents.manifestEntryNames.isEmpty(),
            "$label JAR has unexpected named manifest sections: ${contents.manifestEntryNames}",
        )
        assertValid(contents.automaticModuleName == "cqengine", "$label JAR has the wrong automatic module name")
        val requiredLegalResources = listOf(
            "META-INF/LICENSE.txt",
            "META-INF/THIRD-PARTY-NOTICES",
            "META-INF/THIRD-PARTY-LICENSES/antlr4-runtime-4.13.2.txt",
            "META-INF/THIRD-PARTY-LICENSES/kryo-5.6.2.txt",
            "META-INF/THIRD-PARTY-LICENSES/minlog-1.3.1.txt",
            "META-INF/THIRD-PARTY-LICENSES/reflectasm-1.11.9.txt",
        )
        val missingLegalResources = requiredLegalResources.filterNot(contents.names::contains)
        assertValid(missingLegalResources.isEmpty(), "$label JAR is missing legal resources: $missingLegalResources")
        val foreignMetadata = contents.names.filter {
            it == "module-info.class" ||
                it.endsWith("/module-info.class") ||
                it.matches(Regex("META-INF/[^/]+[.](SF|RSA|DSA|EC)", RegexOption.IGNORE_CASE))
        }
        assertValid(foreignMetadata.isEmpty(), "$label JAR contains foreign metadata: $foreignMetadata")
    }

    private fun readJar(file: java.io.File): JarContents = JarFile(file).use { jar ->
        val names = jar.entries().asSequence().map { it.name }.toList()
        val serviceProviders = jar.getJarEntry("META-INF/services/java.sql.Driver")?.let { entry ->
            jar.getInputStream(entry).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList()
            }
        }.orEmpty()
        val attributes = jar.manifest?.mainAttributes
        val mainAttributes = attributes?.entries?.associate { (key, value) ->
            key.toString() to value.toString()
        }.orEmpty()
        JarContents(
            names,
            attributes?.getValue(Attributes.Name.MAIN_CLASS),
            attributes?.getValue("Automatic-Module-Name"),
            attributes?.getValue("Multi-Release"),
            serviceProviders,
            mainAttributes,
            jar.manifest?.entries?.keys.orEmpty().toSortedSet(),
        )
    }

    private fun assertValid(condition: Boolean, message: String) {
        if (!condition) throw GradleException(message)
    }

    private data class JarContents(
        val names: List<String>,
        val mainClass: String?,
        val automaticModuleName: String?,
        val multiRelease: String?,
        val serviceProviders: List<String>,
        val mainAttributes: Map<String, String>,
        val manifestEntryNames: Set<String>,
    )

    companion object {
        private const val EXPECTED_SQLITE_NATIVE_COUNT = 20
        private const val SQLITE_NATIVE_PREFIX = "org/sqlite/native/"

        private val EXPECTED_THIN_MAIN_ATTRIBUTES = setOf(
            "Manifest-Version",
            "Automatic-Module-Name",
            "Bundle-License",
            "Bundle-ManifestVersion",
            "Bundle-Name",
            "Bundle-SymbolicName",
            "Bundle-Version",
            "Export-Package",
            "Implementation-Title",
            "Implementation-Version",
            "Import-Package",
            "Require-Capability",
        )

        private fun expectedAllMainAttributes(version: String) = mapOf(
            "Manifest-Version" to "1.0",
            "Automatic-Module-Name" to "cqengine",
            "Implementation-Title" to "CQEngine",
            "Implementation-Version" to version,
            "Multi-Release" to "true",
        )
    }
}

abstract class VerifyReleaseVersion : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val projectPropertiesFile: RegularFileProperty

    @get:Input
    abstract val expectedGroup: Property<String>

    @get:Input
    abstract val expectedArtifact: Property<String>

    @get:Input
    abstract val expectedVersion: Property<String>

    @get:Input
    abstract val configuredGroup: Property<String>

    @get:Input
    abstract val configuredArtifact: Property<String>

    @get:Input
    abstract val configuredVersion: Property<String>

    @get:Input
    abstract val versionOverrideSources: ListProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val thinJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val allJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourcesJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val javadocJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val generatedPom: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val generatedModuleMetadata: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val releaseSbomJson: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val releaseSbomXml: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val report = reportFile.get().asFile
        if (report.exists() && !report.delete()) {
            throw GradleException("Could not remove stale release-version evidence: $report")
        }
        val group = expectedGroup.get()
        val artifact = expectedArtifact.get()
        val version = expectedVersion.get()
        val coordinate = "$group:$artifact:$version"

        requireValid(configuredGroup.get() == group, "Project group must be $group, found ${configuredGroup.get()}")
        requireValid(
            configuredArtifact.get() == artifact,
            "Project/artifact name must be $artifact, found ${configuredArtifact.get()}",
        )
        requireValid(
            configuredVersion.get() == version,
            "Project version must be the fixed candidate $version, found ${configuredVersion.get()}",
        )
        requireValid(
            versionOverrideSources.get().isEmpty(),
            "The release version must come only from gradle.properties; remove overrides: ${versionOverrideSources.get()}",
        )
        verifyProjectProperties(group, version)

        val status = publicationStatus(version)
        requireValid(
            status != "integration",
            "Release verification requires a candidate or final version, not a snapshot: $version",
        )
        requireValid(SEMVER.matchEntire(version) != null, "Release version is not valid SemVer 2.0.0: $version")
        val candidate = CANDIDATE_VERSION.matchEntire(version)
        val semverCore = candidate?.groupValues?.subList(1, 4)?.joinToString(".") ?: version
        requireValid(
            publicationStatus("$semverCore-SNAPSHOT") == "integration",
            "Snapshot versions must map to Gradle integration status",
        )
        requireValid(
            publicationStatus("$semverCore-rc.1") == "milestone",
            "Release candidates must map to Gradle milestone status",
        )
        requireValid(
            publicationStatus(semverCore) == "release",
            "Final versions must map to Gradle release status",
        )
        val osgiVersion = toOsgiVersion(version)
        requireValid(OSGI_VERSION.matchEntire(osgiVersion) != null, "Invalid OSGi version: $osgiVersion")

        val jarEvidence = listOf(
            verifyJar(thinJar.get().asFile, artifact, version, "", JarKind.THIN, osgiVersion),
            verifyJar(allJar.get().asFile, artifact, version, "-all", JarKind.ALL, osgiVersion),
            verifyJar(sourcesJar.get().asFile, artifact, version, "-sources", JarKind.SOURCES, osgiVersion),
            verifyJar(javadocJar.get().asFile, artifact, version, "-javadoc", JarKind.JAVADOC, osgiVersion),
        )
        val pom = generatedPom.get().asFile
        val moduleMetadata = generatedModuleMetadata.get().asFile
        val sbomJson = releaseSbomJson.get().asFile
        val sbomXml = releaseSbomXml.get().asFile
        verifyPom(pom, group, artifact, version)
        verifyModuleMetadata(moduleMetadata, group, artifact, version, status, jarEvidence)
        verifySbom(sbomJson, sbomXml, group, artifact, version)

        report.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("formatVersion=1")
                    appendLine("status=verified")
                    appendLine("coordinate=$coordinate")
                    appendLine("versionSource=gradle.properties")
                    appendLine("versionOverrides=none")
                    appendLine("semver=valid")
                    appendLine("semverCore=$semverCore")
                    appendLine("releaseCandidate=${candidate?.groupValues?.get(4) ?: "none"}")
                    appendLine("publicationStatus=$status")
                    appendLine("osgiVersion=$osgiVersion")
                    appendLine("gradleStatus.snapshot=integration")
                    appendLine("gradleStatus.candidate=milestone")
                    appendLine("gradleStatus.final=release")
                    jarEvidence.forEach { evidence ->
                        appendLine("artifact.${evidence.kind.label}.file=${evidence.fileName}")
                        appendLine("artifact.${evidence.kind.label}.sha256=${evidence.sha256}")
                        appendLine("artifact.${evidence.kind.label}.implementationVersion=$version")
                    }
                    appendLine("artifact.thin.bundleVersion=$osgiVersion")
                    appendLine("artifact.all.osgiIdentity=absent")
                    appendLine("artifact.sources.osgiIdentity=absent")
                    appendLine("artifact.javadoc.osgiIdentity=absent")
                    appendLine("pom.coordinate=$coordinate")
                    appendLine("pom.sha256=${digest(pom, "SHA-256")}")
                    appendLine("module.coordinate=$coordinate")
                    appendLine("module.status=$status")
                    appendLine("module.sha256=${digest(moduleMetadata, "SHA-256")}")
                    appendLine("sbom.json.coordinate=$coordinate")
                    appendLine("sbom.json.sha256=${digest(sbomJson, "SHA-256")}")
                    appendLine("sbom.xml.coordinate=$coordinate")
                    appendLine("sbom.xml.sha256=${digest(sbomXml, "SHA-256")}")
                },
                StandardCharsets.UTF_8,
            )
        }
    }

    private fun verifyProjectProperties(expectedGroup: String, expectedVersion: String) {
        val file = projectPropertiesFile.get().asFile
        val properties = Properties().apply { file.inputStream().use(::load) }
        requireValid(properties.getProperty("group") == expectedGroup, "gradle.properties has the wrong group")
        requireValid(properties.getProperty("version") == expectedVersion, "gradle.properties has the wrong version")
        val assignments = file.readLines(StandardCharsets.UTF_8)
            .map(String::trim)
            .filter { line -> line.isNotEmpty() && !line.startsWith('#') && !line.startsWith('!') }
            .filter { line -> propertyKey(line) in setOf("group", "version") }
        requireValid(
            assignments == listOf("group=$expectedGroup", "version=$expectedVersion"),
            "gradle.properties must contain one canonical group and version assignment; found $assignments",
        )
    }

    private fun propertyKey(line: String): String {
        val separator = line.indexOfFirst { character ->
            character == '=' || character == ':' || character.isWhitespace()
        }
        return if (separator < 0) line else line.substring(0, separator)
    }

    private fun publicationStatus(version: String): String = when {
        SNAPSHOT_VERSION.matches(version) -> "integration"
        CANDIDATE_VERSION.matches(version) -> "milestone"
        FINAL_VERSION.matches(version) -> "release"
        else -> throw GradleException("Unsupported CQEngine publication version: $version")
    }

    private fun verifyJar(
        file: File,
        artifact: String,
        version: String,
        classifier: String,
        kind: JarKind,
        osgiVersion: String,
    ): JarEvidence {
        val expectedName = "$artifact-$version$classifier.jar"
        requireValid(file.name == expectedName, "${kind.label} JAR must be named $expectedName, found ${file.name}")
        JarFile(file).use { jar ->
            val manifest = jar.manifest ?: throw GradleException("${kind.label} JAR has no manifest")
            val attributes = manifest.mainAttributes
            requireValid(
                attributes.getValue(Attributes.Name.MANIFEST_VERSION) == "1.0",
                "${kind.label} JAR has the wrong Manifest-Version",
            )
            requireValid(
                attributes.getValue("Implementation-Title") == "CQEngine",
                "${kind.label} JAR has the wrong Implementation-Title",
            )
            requireValid(
                attributes.getValue("Implementation-Version") == version,
                "${kind.label} JAR has the wrong Implementation-Version",
            )
            requireValid(
                attributes.getValue(Attributes.Name.MAIN_CLASS) == null,
                "${kind.label} JAR must not be executable",
            )
            when (kind) {
                JarKind.THIN -> {
                    requireValid(
                        attributes.getValue("Automatic-Module-Name") == "cqengine",
                        "Thin JAR has the wrong automatic module name",
                    )
                    requireValid(
                        attributes.getValue("Bundle-SymbolicName") == "cqengine",
                        "Thin JAR has the wrong OSGi symbolic name",
                    )
                    requireValid(
                        attributes.getValue("Bundle-Version") == osgiVersion,
                        "Thin JAR has the wrong Bundle-Version",
                    )
                }
                JarKind.ALL -> {
                    requireValid(
                        attributes.getValue("Automatic-Module-Name") == "cqengine",
                        "All JAR has the wrong automatic module name",
                    )
                    requireValid(
                        attributes.getValue("Multi-Release") == "true",
                        "All JAR must retain its multi-release manifest",
                    )
                    requireNoOsgiHeaders(kind, attributes)
                }
                JarKind.SOURCES, JarKind.JAVADOC -> {
                    requireValid(
                        attributes.getValue("Automatic-Module-Name") == null,
                        "${kind.label} attachment must not claim the runtime module name",
                    )
                    requireNoOsgiHeaders(kind, attributes)
                }
            }
        }
        return JarEvidence(kind, file.name, digest(file, "SHA-256"))
    }

    private fun requireNoOsgiHeaders(kind: JarKind, attributes: Attributes) {
        val headers = attributes.keys.map(Any::toString).filter { header ->
            header.startsWith("Bundle-") || header in setOf(
                "Export-Package",
                "Import-Package",
                "Private-Package",
                "Require-Capability",
                "Provide-Capability",
                "Service-Component",
            )
        }
        requireValid(headers.isEmpty(), "${kind.label} JAR must not claim OSGi bundle metadata: $headers")
    }

    private fun verifyPom(file: File, group: String, artifact: String, version: String) {
        val document = secureDocumentBuilderFactory().newDocumentBuilder().parse(file)
        val root = document.documentElement
        requireValid(root.localName == "project", "Generated POM has the wrong root element")
        requireValid(directText(root, "groupId") == group, "Generated POM has the wrong groupId")
        requireValid(directText(root, "artifactId") == artifact, "Generated POM has the wrong artifactId")
        requireValid(directText(root, "version") == version, "Generated POM has the wrong version")
    }

    private fun directText(parent: Element, name: String): String {
        val matches = parent.childNodes.asSequence()
            .filterIsInstance<Element>()
            .filter { child -> child.localName == name }
            .toList()
        requireValid(matches.size == 1, "Expected exactly one direct POM $name element, found ${matches.size}")
        return matches.single().textContent.trim()
    }

    private fun verifyModuleMetadata(
        file: File,
        group: String,
        artifact: String,
        version: String,
        expectedStatus: String,
        jars: List<JarEvidence>,
    ) {
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
            .createParser(file)
            .use { parser -> while (parser.nextToken() != null) Unit }
        val root = JsonSlurper().parse(file) as? Map<*, *>
            ?: throw GradleException("Generated Gradle module metadata is not an object")
        val component = root["component"] as? Map<*, *>
            ?: throw GradleException("Generated Gradle module metadata has no component object")
        requireValid(component["group"] == group, "Gradle module metadata has the wrong group")
        requireValid(component["module"] == artifact, "Gradle module metadata has the wrong artifact")
        requireValid(component["version"] == version, "Gradle module metadata has the wrong version")
        requireValid(
            component["attributes"] == mapOf("org.gradle.status" to expectedStatus),
            "Release Gradle module metadata must have $expectedStatus status",
        )
        val variants = root["variants"] as? List<*>
            ?: throw GradleException("Generated Gradle module metadata has no variants")
        val actualFileNames = variants.flatMap { variant ->
            val variantObject = variant as? Map<*, *>
                ?: throw GradleException("Gradle module metadata variant is not an object")
            (variantObject["files"] as? List<*>)?.map { entry ->
                val fileObject = entry as? Map<*, *>
                    ?: throw GradleException("Gradle module metadata file entry is not an object")
                val name = fileObject["name"] as? String
                    ?: throw GradleException("Gradle module metadata file entry has no name")
                requireValid(fileObject["url"] == name, "Gradle module metadata file URL differs from its name")
                name
            }.orEmpty()
        }.sorted()
        val expectedFileNames = buildList {
            add(jars.single { it.kind == JarKind.THIN }.fileName)
            add(jars.single { it.kind == JarKind.THIN }.fileName)
            addAll(jars.filter { it.kind != JarKind.THIN }.map(JarEvidence::fileName))
        }.sorted()
        requireValid(
            actualFileNames == expectedFileNames,
            "Gradle module metadata artifact inventory differs: expected $expectedFileNames, found $actualFileNames",
        )
    }

    private fun verifySbom(
        jsonFile: File,
        xmlFile: File,
        group: String,
        artifact: String,
        version: String,
    ) {
        val expectedPurl = "pkg:maven/$group/$artifact@$version"
        val parsers = listOf(
            "JSON" to JsonParser().parse(jsonFile),
            "XML" to XmlParser().parse(xmlFile),
        )
        parsers.forEach { (format, bom) ->
            val component = bom.metadata?.component
                ?: throw GradleException("$format release SBOM has no root component")
            requireValid(component.group == group, "$format release SBOM has the wrong group")
            requireValid(component.name == artifact, "$format release SBOM has the wrong artifact")
            requireValid(component.version == version, "$format release SBOM has the wrong version")
            requireValid(component.bomRef == expectedPurl, "$format release SBOM has the wrong bom-ref")
            requireValid(component.purl == expectedPurl, "$format release SBOM has the wrong purl")
        }
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }

    private fun org.w3c.dom.NodeList.asSequence(): Sequence<org.w3c.dom.Node> = sequence {
        for (index in 0 until length) yield(item(index))
    }

    private fun toOsgiVersion(mavenVersion: String): String {
        val components = Regex("""([0-9]+)[.]([0-9]+)[.]([0-9]+)(?:-(.+))?""")
            .matchEntire(mavenVersion)
            ?: throw GradleException("Cannot convert release candidate to OSGi syntax: $mavenVersion")
        val qualifier = components.groupValues[4].replace(Regex("[^A-Za-z0-9_-]"), "_")
        return components.groupValues.subList(1, 4).joinToString(".") +
            if (qualifier.isEmpty()) "" else ".$qualifier"
    }

    private fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
    }

    private fun requireValid(condition: Boolean, message: String) {
        if (!condition) throw GradleException(message)
    }

    private enum class JarKind(val label: String) {
        THIN("thin"),
        ALL("all"),
        SOURCES("sources"),
        JAVADOC("javadoc"),
    }

    private data class JarEvidence(
        val kind: JarKind,
        val fileName: String,
        val sha256: String,
    )

    companion object {
        private val CANDIDATE_VERSION = Regex(
            """(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)-rc[.]([1-9][0-9]*)""",
        )
        private val SNAPSHOT_VERSION = Regex(
            """(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)-SNAPSHOT""",
        )
        private val FINAL_VERSION = Regex(
            """(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)""",
        )
        private val SEMVER = Regex(
            """(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)(?:-((?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(?:[.](?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*))?(?:[+]([0-9A-Za-z-]+(?:[.][0-9A-Za-z-]+)*))?""",
        )
        private val OSGI_VERSION = Regex(
            """(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)(?:[.][A-Za-z0-9_-]+)?""",
        )
    }
}

abstract class GenerateDeterministicReleaseEvidence : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rawSbomJson: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rawLicenseInventory: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val noticesFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val thirdPartyLicenseDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val wrapperProperties: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val thinJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val allJar: RegularFileProperty

    @get:Input
    abstract val publicationGroup: Property<String>

    @get:Input
    abstract val publicationArtifact: Property<String>

    @get:Input
    abstract val publicationVersion: Property<String>

    @get:Input
    abstract val sourceCommit: Property<String>

    @get:Input
    abstract val sourceTree: Property<String>

    @get:Input
    abstract val sourceEpochSeconds: Property<Long>

    @get:Input
    abstract val gradleVersion: Property<String>

    @get:Input
    abstract val operatingSystem: Property<String>

    @get:Input
    abstract val architecture: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.get().asFile
        if (output.exists() && !output.deleteRecursively()) {
            throw GradleException("Could not clean deterministic release evidence directory: $output")
        }
        if (!output.mkdirs() && !output.isDirectory) {
            throw GradleException("Could not create deterministic release evidence directory: $output")
        }

        val expectedGroup = publicationGroup.get()
        val expectedArtifact = publicationArtifact.get()
        val expectedVersion = publicationVersion.get()
        val canonicalRootReference = "pkg:maven/$expectedGroup/$expectedArtifact@$expectedVersion"
        val thinBom = normalizeBom(
            canonicalRootReference,
            thinJar.get().asFile,
            distribution = "thin",
            embedded = false,
        )
        val jsonFile = output.resolve(SBOM_JSON)
        val xmlFile = output.resolve(SBOM_XML)
        writeSbom(thinBom, jsonFile, xmlFile)

        val allRootReference = "$canonicalRootReference?classifier=all&type=jar"
        val allBom = normalizeBom(
            allRootReference,
            allJar.get().asFile,
            distribution = "all",
            embedded = true,
        )
        writeSbom(allBom, output.resolve(SBOM_ALL_JSON), output.resolve(SBOM_ALL_XML))

        val licenseInventory = canonicalizeLicenseInventory(rawLicenseInventory.get().asFile)
        output.resolve(LICENSE_INVENTORY).writeText(licenseInventory, StandardCharsets.UTF_8)

        copyFile(licenseFile.get().asFile, output.resolve(LICENSE_FILE))
        copyFile(noticesFile.get().asFile, output.resolve(NOTICES_FILE))
        val legalDirectory = output.resolve(THIRD_PARTY_DIRECTORY)
        if (!legalDirectory.mkdirs() && !legalDirectory.isDirectory) {
            throw GradleException("Could not create third-party legal directory: $legalDirectory")
        }
        val thirdPartyFiles = thirdPartyLicenseDirectory.get().asFile.listFiles { file -> file.isFile }
            ?.sortedBy { it.name }
            .orEmpty()
        if (thirdPartyFiles.isEmpty()) {
            throw GradleException("No third-party licence files were found")
        }
        thirdPartyFiles.forEach { source -> copyFile(source, legalDirectory.resolve(source.name)) }

        val wrapper = Properties().apply {
            wrapperProperties.get().asFile.inputStream().use(::load)
        }
        val wrapperSha = wrapper.getProperty("distributionSha256Sum")
            ?: throw GradleException("Gradle wrapper properties have no distributionSha256Sum")
        writeProperties(
            output.resolve(SOURCE_PROVENANCE),
            linkedMapOf(
                "formatVersion" to "1",
                "coordinate" to "$expectedGroup:$expectedArtifact:$expectedVersion",
                "sourceCommit" to sourceCommit.get(),
                "sourceTree" to sourceTree.get(),
                "sourceCommitEpochSeconds" to sourceEpochSeconds.get().toString(),
                "upstreamBaseline" to UPSTREAM_BASELINE,
                "upstreamBaselineTag" to UPSTREAM_BASELINE_TAG,
                "license" to "Apache-2.0",
            ),
        )
        writeProperties(
            output.resolve(BUILD_PROVENANCE),
            linkedMapOf(
                "formatVersion" to "1",
                "gradleVersion" to gradleVersion.get(),
                "gradleDistributionSha256" to wrapperSha,
                "javaBytecodeRelease" to "21",
                "operatingSystem" to normalizeOperatingSystem(operatingSystem.get()),
                "architecture" to normalizeArchitecture(architecture.get()),
            ),
        )

        val legalFiles = buildList {
            add(output.resolve(LICENSE_FILE))
            add(output.resolve(NOTICES_FILE))
            addAll(legalDirectory.listFiles { file -> file.isFile }?.sortedBy { it.name }.orEmpty())
        }
        output.resolve(LEGAL_MANIFEST).writeText(
            legalFiles.joinToString("\n", postfix = "\n") { file ->
                "${digest(file, "SHA-256")}  ${file.relativeTo(output).invariantSeparatorsPath}"
            },
            StandardCharsets.UTF_8,
        )

        val evidenceFiles = output.walkTopDown()
            .filter { it.isFile && it.name !in setOf(SHA256_SUMS, SHA512_SUMS) }
            .sortedBy { it.relativeTo(output).invariantSeparatorsPath }
            .toList()
        writeDigestManifest(output.resolve(SHA256_SUMS), output, evidenceFiles, "SHA-256")
        writeDigestManifest(output.resolve(SHA512_SUMS), output, evidenceFiles, "SHA-512")
    }

    private fun normalizeBom(
        rootReference: String,
        rootArtifact: File,
        distribution: String,
        embedded: Boolean,
    ): Bom {
        val normalizedBom = JsonParser().parse(rawSbomJson.get().asFile)
        val metadata = normalizedBom.metadata
            ?: throw GradleException("CycloneDX SBOM has no metadata")
        metadata.timestamp = Date.from(Instant.ofEpochSecond(sourceEpochSeconds.get()))
        val rootComponent = metadata.component
            ?: throw GradleException("CycloneDX SBOM has no root component")
        val expectedGroup = publicationGroup.get()
        val expectedArtifact = publicationArtifact.get()
        val expectedVersion = publicationVersion.get()
        if (rootComponent.group != expectedGroup ||
            rootComponent.name != expectedArtifact ||
            rootComponent.version != expectedVersion
        ) {
            throw GradleException(
                "CycloneDX root coordinate does not match $expectedGroup:$expectedArtifact:$expectedVersion",
            )
        }
        val oldRootReference = rootComponent.bomRef
            ?: throw GradleException("CycloneDX root component has no bom-ref")
        rootComponent.bomRef = rootReference
        rootComponent.purl = rootReference
        rootComponent.externalReferences = emptyList()
        rootComponent.hashes = strongHashes(rootArtifact)
        rootComponent.properties = listOf(CdxProperty(DISTRIBUTION_PROPERTY, distribution))
        rootComponent.licenses = org.cyclonedx.model.LicenseChoice().apply {
            licenses = listOf(org.cyclonedx.model.License().apply { id = "Apache-2.0" })
        }
        normalizedBom.serialNumber = null
        normalizedBom.components = normalizedBom.components.orEmpty()
            .onEach { component ->
                if (embedded) {
                    component.modified = true
                    component.hashes = emptyList()
                    component.properties = component.properties.orEmpty()
                        .filterNot { property -> property.name == EMBEDDED_PROPERTY } +
                        CdxProperty(EMBEDDED_PROPERTY, "true")
                }
                else {
                    component.hashes = component.hashes.orEmpty()
                        .filter { hash -> hash.algorithm in STRONG_SBOM_HASH_ALGORITHMS }
                        .sortedBy { hash -> hash.algorithm }
                }
            }
            .sortedWith(
                compareBy<Component>({ it.group.orEmpty() }, { it.name.orEmpty() }, { it.version.orEmpty() }),
            )
        normalizedBom.dependencies = normalizedBom.dependencies.orEmpty()
            .map { dependency -> normalizeDependency(dependency, oldRootReference, rootReference) }
            .sortedBy { it.ref }
        return normalizedBom
    }

    private fun strongHashes(file: File): List<Hash> = listOf(
        Hash(Hash.Algorithm.SHA_256, digest(file, "SHA-256")),
        Hash(Hash.Algorithm.SHA_512, digest(file, "SHA-512")),
    )

    private fun writeSbom(bom: Bom, jsonFile: File, xmlFile: File) {
        jsonFile.writeText(
            BomGeneratorFactory.createJson(Version.VERSION_16, bom).toJsonString(true).trimEnd() + "\n",
            StandardCharsets.UTF_8,
        )
        xmlFile.writeText(
            BomGeneratorFactory.createXml(Version.VERSION_16, bom).toXmlString().trimEnd() + "\n",
            StandardCharsets.UTF_8,
        )
        validateSbom(jsonFile, xmlFile)
    }

    private fun normalizeDependency(
        dependency: org.cyclonedx.model.Dependency,
        oldReference: String,
        newReference: String,
    ): org.cyclonedx.model.Dependency = org.cyclonedx.model.Dependency(
        if (dependency.ref == oldReference) newReference else dependency.ref,
    ).apply {
        dependencies = dependency.dependencies.orEmpty()
            .map { child -> normalizeDependency(child, oldReference, newReference) }
            .sortedBy { it.ref }
    }

    private fun validateSbom(jsonFile: File, xmlFile: File) {
        val jsonErrors = JsonParser().validate(jsonFile)
        if (jsonErrors.isNotEmpty()) {
            throw GradleException("Deterministic CycloneDX JSON is invalid: $jsonErrors")
        }
        val xmlErrors = XmlParser().validate(xmlFile)
        if (xmlErrors.isNotEmpty()) {
            throw GradleException("Deterministic CycloneDX XML is invalid: $xmlErrors")
        }
    }

    private fun canonicalizeLicenseInventory(source: File): String {
        val root = JsonSlurper().parse(source) as? Map<*, *>
            ?: throw GradleException("Dependency licence inventory is not a JSON object")
        if (root.keys != setOf("dependencies", "importedModules")) {
            throw GradleException("Dependency licence inventory has unexpected keys: ${root.keys}")
        }
        val dependencies = root["dependencies"] as? List<*>
            ?: throw GradleException("Dependency licence inventory has no dependencies array")
        val importedModules = root["importedModules"] as? List<*>
            ?: throw GradleException("Dependency licence inventory has no importedModules array")
        if (importedModules.isNotEmpty()) {
            throw GradleException("Dependency licence inventory unexpectedly imports modules: $importedModules")
        }
        val canonicalDependencies = dependencies.map { value ->
            val dependency = value as? Map<*, *>
                ?: throw GradleException("Dependency licence record is not an object: $value")
            val expectedKeys = setOf("moduleName", "moduleVersion", "moduleUrls", "moduleLicenses")
            if (dependency.keys != expectedKeys) {
                throw GradleException("Dependency licence record has unexpected keys: ${dependency.keys}")
            }
            val moduleName = dependency["moduleName"] as? String
                ?: throw GradleException("Dependency licence record has no moduleName")
            val moduleVersion = dependency["moduleVersion"] as? String
                ?: throw GradleException("Dependency licence record has no moduleVersion")
            val moduleUrls = (dependency["moduleUrls"] as? List<*>)?.map { url ->
                url as? String ?: throw GradleException("Dependency module URL is not a string: $url")
            }?.distinct()?.sorted()
                ?: throw GradleException("Dependency licence record has no moduleUrls array")
            val moduleLicenses = (dependency["moduleLicenses"] as? List<*>)?.mapNotNull { licenceValue ->
                val licence = licenceValue as? Map<*, *>
                    ?: throw GradleException("Dependency licence tuple is not an object: $licenceValue")
                if (licence.keys != setOf("moduleLicense", "moduleLicenseUrl")) {
                    throw GradleException("Dependency licence tuple has unexpected keys: ${licence.keys}")
                }
                val id = licence["moduleLicense"] as? String
                val url = licence["moduleLicenseUrl"] as? String
                if (id.isNullOrBlank()) {
                    null
                }
                else {
                    linkedMapOf("moduleLicense" to id, "moduleLicenseUrl" to url.orEmpty())
                }
            }?.distinct()?.sortedBy { tuple -> tuple.getValue("moduleLicense") }
                ?: throw GradleException("Dependency licence record has no moduleLicenses array")
            if (moduleLicenses.isEmpty()) {
                throw GradleException("Dependency has no identified licence: $moduleName:$moduleVersion")
            }
            linkedMapOf<String, Any?>(
                "moduleName" to moduleName,
                "moduleVersion" to moduleVersion,
                "moduleUrls" to moduleUrls,
                "moduleLicenses" to moduleLicenses,
            )
        }
        val canonical = linkedMapOf<String, Any?>(
            "dependencies" to canonicalDependencies.sortedBy { entry ->
                "${entry["moduleName"]}:${entry["moduleVersion"]}"
            },
            "importedModules" to emptyList<Any>(),
        )
        return JsonOutput.prettyPrint(JsonOutput.toJson(canonical)).trimEnd() + "\n"
    }

    private fun copyFile(source: File, destination: File) {
        if (!source.isFile || source.length() == 0L) {
            throw GradleException("Release evidence source is missing or empty: $source")
        }
        destination.parentFile.mkdirs()
        Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun writeProperties(file: File, values: Map<String, String>) {
        file.writeText(
            values.entries.sortedBy { it.key }.joinToString("\n", postfix = "\n") { (key, value) ->
                "$key=$value"
            },
            StandardCharsets.UTF_8,
        )
    }

    private fun writeDigestManifest(
        manifest: File,
        root: File,
        files: List<File>,
        algorithm: String,
    ) {
        manifest.writeText(
            files.joinToString("\n", postfix = "\n") { file ->
                "$algorithm:${digest(file, algorithm)}  ${file.relativeTo(root).invariantSeparatorsPath}"
            },
            StandardCharsets.UTF_8,
        )
    }

    private fun digest(file: File, algorithm: String): String {
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

    private fun normalizeOperatingSystem(value: String): String = when {
        value.contains("linux", ignoreCase = true) -> "linux"
        value.contains("mac", ignoreCase = true) -> "macos"
        value.contains("windows", ignoreCase = true) -> "windows"
        else -> value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }

    private fun normalizeArchitecture(value: String): String = when (value.lowercase(Locale.ROOT)) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> value.lowercase(Locale.ROOT)
    }

    companion object {
        const val SBOM_JSON = "cqengine.cdx.json"
        const val SBOM_XML = "cqengine.cdx.xml"
        const val SBOM_ALL_JSON = "cqengine-all.cdx.json"
        const val SBOM_ALL_XML = "cqengine-all.cdx.xml"
        const val LICENSE_INVENTORY = "dependency-licenses.json"
        const val LICENSE_FILE = "LICENSE.txt"
        const val NOTICES_FILE = "THIRD-PARTY-NOTICES"
        const val THIRD_PARTY_DIRECTORY = "third-party-licenses"
        const val SOURCE_PROVENANCE = "source-provenance.properties"
        const val BUILD_PROVENANCE = "build-provenance.properties"
        const val LEGAL_MANIFEST = "legal-manifest.sha256"
        const val SHA256_SUMS = "SHA256SUMS"
        const val SHA512_SUMS = "SHA512SUMS"
        const val UPSTREAM_BASELINE = "a06923bca69719c51c622543fa0c2d63e71e8fab"
        const val UPSTREAM_BASELINE_TAG = "upstream-npgall-a06923bc"
        const val DISTRIBUTION_PROPERTY = "io.github.shuaibrao.cqengine:distribution"
        const val EMBEDDED_PROPERTY = "io.github.shuaibrao.cqengine:embedded"
        val STRONG_SBOM_HASH_ALGORITHMS = setOf("SHA-256", "SHA-512")
    }
}

abstract class VerifyStagedReleaseEvidence : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val evidenceDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatedEvidenceDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val noticesFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val thirdPartyLicenseDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val wrapperProperties: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val thinJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val allJar: RegularFileProperty

    @get:Input
    abstract val publicationCoordinate: Property<String>

    @get:Input
    abstract val sourceCommit: Property<String>

    @get:Input
    abstract val sourceTree: Property<String>

    @get:Input
    abstract val sourceEpochSeconds: Property<Long>

    @get:Input
    abstract val gradleVersion: Property<String>

    @get:Input
    abstract val operatingSystem: Property<String>

    @get:Input
    abstract val architecture: Property<String>

    @get:Input
    abstract val expectedRuntimeCoordinates: ListProperty<String>

    @get:Input
    abstract val expectedDirectRuntimeCoordinates: ListProperty<String>

    @get:Input
    abstract val expectedTransitiveEdges: ListProperty<String>

    @get:Classpath
    abstract val runtimeArtifacts: ConfigurableFileCollection

    @get:Input
    abstract val expectedRuntimeArtifactNames: ListProperty<String>

    @get:Input
    abstract val expectedRuntimeLicenseIds: ListProperty<String>

    @get:Input
    abstract val expectedThirdPartyLicenseNames: ListProperty<String>

    @get:Input
    abstract val forbiddenPathFragments: ListProperty<String>

    @get:OutputFile
    abstract val inventoryReport: RegularFileProperty

    @TaskAction
    fun verify() {
        val root = evidenceDirectory.get().asFile
        val actualThirdPartyNames = thirdPartyLicenseDirectory.get().asFile
            .listFiles { file -> file.isFile }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
        val expectedThirdPartyNames = expectedThirdPartyLicenseNames.get().sorted()
        if (actualThirdPartyNames != expectedThirdPartyNames) {
            throw GradleException(
                "Third-party licence inventory differs. Expected $expectedThirdPartyNames, " +
                    "found $actualThirdPartyNames",
            )
        }
        val expectedNames = sortedSetOf(
            GenerateDeterministicReleaseEvidence.SBOM_JSON,
            GenerateDeterministicReleaseEvidence.SBOM_XML,
            GenerateDeterministicReleaseEvidence.SBOM_ALL_JSON,
            GenerateDeterministicReleaseEvidence.SBOM_ALL_XML,
            GenerateDeterministicReleaseEvidence.LICENSE_INVENTORY,
            GenerateDeterministicReleaseEvidence.LICENSE_FILE,
            GenerateDeterministicReleaseEvidence.NOTICES_FILE,
            GenerateDeterministicReleaseEvidence.SOURCE_PROVENANCE,
            GenerateDeterministicReleaseEvidence.BUILD_PROVENANCE,
            GenerateDeterministicReleaseEvidence.LEGAL_MANIFEST,
            GenerateDeterministicReleaseEvidence.SHA256_SUMS,
            GenerateDeterministicReleaseEvidence.SHA512_SUMS,
            *expectedThirdPartyNames.map {
                "${GenerateDeterministicReleaseEvidence.THIRD_PARTY_DIRECTORY}/$it"
            }.toTypedArray(),
        )
        val actualNames = root.walkTopDown()
            .filter(File::isFile)
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toSortedSet()
        if (actualNames != expectedNames) {
            throw GradleException(
                "Unexpected release evidence inventory. Missing ${expectedNames - actualNames}, " +
                    "unexpected ${actualNames - expectedNames}",
            )
        }

        val generatedRoot = generatedEvidenceDirectory.get().asFile
        val generatedNames = generatedRoot.walkTopDown()
            .filter(File::isFile)
            .map { it.relativeTo(generatedRoot).invariantSeparatorsPath }
            .toSortedSet()
        if (generatedNames != actualNames) {
            throw GradleException(
                "Staged release evidence differs from its generated inventory. Missing " +
                    "${generatedNames - actualNames}, unexpected ${actualNames - generatedNames}",
            )
        }
        actualNames.forEach { name -> assertSameBytes(generatedRoot.resolve(name), root.resolve(name)) }

        assertSameBytes(licenseFile.get().asFile, root.resolve(GenerateDeterministicReleaseEvidence.LICENSE_FILE))
        assertSameBytes(noticesFile.get().asFile, root.resolve(GenerateDeterministicReleaseEvidence.NOTICES_FILE))
        expectedThirdPartyNames.forEach { name ->
            assertSameBytes(
                thirdPartyLicenseDirectory.get().asFile.resolve(name),
                root.resolve("${GenerateDeterministicReleaseEvidence.THIRD_PARTY_DIRECTORY}/$name"),
            )
        }
        verifyLegalManifest(root, expectedThirdPartyNames)
        verifyDigestManifest(root, GenerateDeterministicReleaseEvidence.SHA256_SUMS, "SHA-256")
        verifyDigestManifest(root, GenerateDeterministicReleaseEvidence.SHA512_SUMS, "SHA-512")
        verifySboms(root)
        verifyLicenseInventory(root)
        verifyProvenance(root)

        val textEvidence = actualNames.filterNot { it.endsWith("SUMS") }.map(root::resolve)
        forbiddenPathFragments.get().filter(String::isNotBlank).forEach { forbidden ->
            val leakingFiles = textEvidence.filter { file ->
                file.readText(StandardCharsets.UTF_8).contains(forbidden)
            }
            if (leakingFiles.isNotEmpty()) {
                throw GradleException("Release evidence leaks checkout path '$forbidden': $leakingFiles")
            }
        }

        inventoryReport.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                actualNames.joinToString("\n", postfix = "\n") { name ->
                    val file = root.resolve(name)
                    "${digest(file, "SHA-256")} ${digest(file, "SHA-512")}  $name"
                },
                StandardCharsets.UTF_8,
            )
        }
    }

    private fun verifySboms(root: File) {
        verifySbomPair(
            root.resolve(GenerateDeterministicReleaseEvidence.SBOM_JSON),
            root.resolve(GenerateDeterministicReleaseEvidence.SBOM_XML),
            thinJar.get().asFile,
            distribution = "thin",
            embedded = false,
        )
        verifySbomPair(
            root.resolve(GenerateDeterministicReleaseEvidence.SBOM_ALL_JSON),
            root.resolve(GenerateDeterministicReleaseEvidence.SBOM_ALL_XML),
            allJar.get().asFile,
            distribution = "all",
            embedded = true,
        )
    }

    private fun verifySbomPair(
        jsonFile: File,
        xmlFile: File,
        rootArtifact: File,
        distribution: String,
        embedded: Boolean,
    ) {
        val jsonErrors = JsonParser().validate(jsonFile)
        val xmlErrors = XmlParser().validate(xmlFile)
        if (jsonErrors.isNotEmpty() || xmlErrors.isNotEmpty()) {
            throw GradleException("Invalid deterministic SBOMs: JSON=$jsonErrors, XML=$xmlErrors")
        }
        val jsonBom = JsonParser().parse(jsonFile)
        val xmlBom = XmlParser().parse(xmlFile)
        val jsonGraph = dependencyGraph(jsonBom, "JSON")
        val xmlGraph = dependencyGraph(xmlBom, "XML")
        if (jsonGraph != xmlGraph) {
            throw GradleException("Deterministic JSON and XML SBOM dependency graphs differ")
        }
        val jsonComponents = componentRecords(jsonBom, "JSON")
        val xmlComponents = componentRecords(xmlBom, "XML")
        if (jsonComponents != xmlComponents) {
            throw GradleException("Deterministic JSON and XML SBOM component records differ")
        }
        verifyRuntimeComponentEvidence(jsonComponents, embedded)
        val expected = expectedRuntimeCoordinates.get().sorted()
        listOf("JSON" to jsonBom, "XML" to xmlBom).forEach { (format, bom) ->
            val metadata = bom.metadata ?: throw GradleException("$format SBOM has no metadata")
            val expectedTimestamp = Date.from(Instant.ofEpochSecond(sourceEpochSeconds.get()))
            if (metadata.timestamp != expectedTimestamp) {
                throw GradleException(
                    "$format release SBOM timestamp must equal the source commit time: " +
                        "expected $expectedTimestamp, found ${metadata.timestamp}",
                )
            }
            val rootComponent = metadata.component
                ?: throw GradleException("$format SBOM has no root component")
            if (rootComponent.type != Component.Type.LIBRARY || rootComponent.modified != false) {
                throw GradleException("$format release SBOM root is not an unmodified library component")
            }
            val coordinate = "${rootComponent.group}:${rootComponent.name}:${rootComponent.version}"
            if (coordinate != publicationCoordinate.get()) {
                throw GradleException("$format SBOM root coordinate is $coordinate")
            }
            if (bom.serialNumber != null) {
                throw GradleException("$format release SBOM must not contain a generated serial number")
            }
            if (!rootComponent.externalReferences.isNullOrEmpty()) {
                throw GradleException("$format release SBOM contains checkout-derived external references")
            }
            val rootLicences = rootComponent.licenses?.licenses.orEmpty().map { licence ->
                listOf(licence.id.orEmpty(), licence.name.orEmpty(), licence.url.orEmpty()).joinToString("|")
            }.sorted()
            if (rootLicences != listOf("Apache-2.0||")) {
                throw GradleException("$format release SBOM root licence is not exactly Apache-2.0")
            }
            val (group, artifact, version) = publicationCoordinate.get().split(':', limit = 3)
            val baseRootReference = "pkg:maven/$group/$artifact@$version"
            val expectedRootReference = if (embedded) {
                "$baseRootReference?classifier=all&type=jar"
            }
            else {
                baseRootReference
            }
            if (rootComponent.bomRef != expectedRootReference || rootComponent.purl != expectedRootReference) {
                throw GradleException(
                    "$format release SBOM root identity differs from $expectedRootReference",
                )
            }
            val actual = bom.components.orEmpty().map {
                "${it.group}:${it.name}:${it.version}"
            }.sorted()
            if (actual != expected) {
                throw GradleException("$format SBOM components differ. Expected $expected, found $actual")
            }
            val rootHashes = rootComponent.hashes.orEmpty().associate { hash ->
                hash.algorithm to hash.value.lowercase(Locale.ROOT)
            }
            if (rootHashes.keys != GenerateDeterministicReleaseEvidence.STRONG_SBOM_HASH_ALGORITHMS) {
                throw GradleException("$format $distribution SBOM has unexpected root hashes: ${rootHashes.keys}")
            }
            mapOf("SHA-256" to "SHA-256", "SHA-512" to "SHA-512").forEach { (name, algorithm) ->
                if (rootHashes[name] != digest(rootArtifact, algorithm)) {
                    throw GradleException("$format $distribution SBOM root $name does not match $rootArtifact")
                }
            }
            val rootProperties = rootComponent.properties.orEmpty().map { property ->
                "${property.name}=${property.value}"
            }
            val expectedRootProperties = listOf(
                "${GenerateDeterministicReleaseEvidence.DISTRIBUTION_PROPERTY}=$distribution",
            )
            if (rootProperties != expectedRootProperties) {
                throw GradleException(
                    "$format $distribution SBOM has unexpected root properties: $rootProperties",
                )
            }
            verifyComponentIdentitiesAndGraph(bom, format, expectedRootReference)
        }
    }

    private fun componentRecords(bom: Bom, format: String): Map<String, ComponentRecord> {
        val records = bom.components.orEmpty().map { component ->
            val coordinate = "${component.group}:${component.name}:${component.version}"
            val hashes = component.hashes.orEmpty().map { hash ->
                hash.algorithm to hash.value.lowercase(Locale.ROOT)
            }
            if (hashes.size != hashes.map { it.first }.toSet().size) {
                throw GradleException("$format SBOM component has duplicate hash algorithms: $coordinate")
            }
            val licences = component.licenses?.licenses.orEmpty().map { licence ->
                listOf(licence.id.orEmpty(), licence.name.orEmpty(), licence.url.orEmpty()).joinToString("|")
            }.sorted()
            val properties = component.properties.orEmpty().map { property ->
                "${property.name}=${property.value}"
            }.sorted()
            if (properties.size != properties.toSet().size) {
                throw GradleException("$format SBOM component has duplicate properties: $coordinate")
            }
            val externalReferences = component.externalReferences.orEmpty().map { reference ->
                "${reference.type}:${reference.url}:${reference.comment.orEmpty()}"
            }.sorted()
            coordinate to ComponentRecord(
                type = component.type?.name.orEmpty(),
                bomRef = component.bomRef.orEmpty(),
                purl = component.purl.orEmpty(),
                description = component.description.orEmpty(),
                modified = component.modified,
                hashes = hashes.toMap(sortedMapOf()),
                licences = licences,
                properties = properties,
                externalReferences = externalReferences,
            )
        }
        if (records.size != records.map { it.first }.toSet().size) {
            throw GradleException("$format SBOM contains duplicate component coordinates")
        }
        return records.toMap(sortedMapOf())
    }

    private fun verifyRuntimeComponentEvidence(records: Map<String, ComponentRecord>, embedded: Boolean) {
        val artifactNames = parseExpectedMappings(expectedRuntimeArtifactNames.get(), "runtime artifact")
        val licenseIds = parseExpectedMappings(expectedRuntimeLicenseIds.get(), "runtime licence")
            .mapValues { (_, ids) -> ids.split(',').filter(String::isNotBlank).sorted() }
        val expectedCoordinates = expectedRuntimeCoordinates.get().toSet()
        if (artifactNames.keys != expectedCoordinates || licenseIds.keys != expectedCoordinates) {
            throw GradleException("Runtime SBOM evidence mappings do not cover the exact component inventory")
        }
        val artifactsByName = runtimeArtifacts.files.filter(File::isFile).groupBy { it.name }
        if (artifactsByName.keys != artifactNames.values.toSet() || artifactsByName.any { it.value.size != 1 }) {
            throw GradleException(
                "Resolved runtime artifact inventory differs from SBOM evidence mapping: ${artifactsByName.keys}",
            )
        }
        records.forEach { (coordinate, record) ->
            if (record.type != "LIBRARY" || record.modified != embedded) {
                throw GradleException("SBOM component has unexpected type/modified state: $coordinate, $record")
            }
            val expectedProperties = if (embedded) {
                listOf(
                    "cdx:maven:package:test=false",
                    "${GenerateDeterministicReleaseEvidence.EMBEDDED_PROPERTY}=true",
                ).sorted()
            }
            else {
                listOf("cdx:maven:package:test=false")
            }
            if (record.properties != expectedProperties) {
                throw GradleException("SBOM component has unexpected properties: $coordinate, ${record.properties}")
            }
            val actualLicenseIds = record.licences.map { it.substringBefore('|') }.sorted()
            if (actualLicenseIds != licenseIds.getValue(coordinate)) {
                throw GradleException(
                    "SBOM component licence IDs differ for $coordinate: " +
                        "expected ${licenseIds.getValue(coordinate)}, found $actualLicenseIds",
                )
            }
            val artifact = artifactsByName.getValue(artifactNames.getValue(coordinate)).single()
            if (embedded) {
                if (record.hashes.isNotEmpty()) {
                    throw GradleException("Embedded SBOM component must not claim source-JAR hashes: $coordinate")
                }
            }
            else {
                if (record.hashes.keys != GenerateDeterministicReleaseEvidence.STRONG_SBOM_HASH_ALGORITHMS) {
                    throw GradleException("Thin SBOM component has unexpected hashes: $coordinate, ${record.hashes.keys}")
                }
                mapOf("SHA-256" to "SHA-256", "SHA-512" to "SHA-512").forEach { (sbomName, algorithm) ->
                    val expectedDigest = digest(artifact, algorithm)
                    if (record.hashes[sbomName] != expectedDigest) {
                        throw GradleException("SBOM $sbomName does not match $artifact for $coordinate")
                    }
                }
            }
        }
    }

    private fun parseExpectedMappings(values: List<String>, label: String): Map<String, String> {
        val mappings = values.map { value ->
            val separator = value.indexOf('|')
            if (separator <= 0 || separator == value.lastIndex || value.indexOf('|', separator + 1) >= 0) {
                throw GradleException("Malformed expected $label mapping: $value")
            }
            value.substring(0, separator) to value.substring(separator + 1)
        }
        if (mappings.size != mappings.map { it.first }.toSet().size) {
            throw GradleException("Duplicate expected $label coordinate")
        }
        return mappings.toMap()
    }

    private fun verifyComponentIdentitiesAndGraph(bom: Bom, format: String, rootReference: String) {
        val components = bom.components.orEmpty()
        val referenceToCoordinate = linkedMapOf<String, String>()
        components.forEach { component ->
            val coordinate = "${component.group}:${component.name}:${component.version}"
            val expectedReference = mavenComponentPurl(coordinate)
            if (component.bomRef != expectedReference || component.purl != expectedReference) {
                throw GradleException(
                    "$format SBOM component identity differs from its canonical purl: " +
                        "$coordinate, bom-ref=${component.bomRef}, purl=${component.purl}",
                )
            }
            if (referenceToCoordinate.put(expectedReference, coordinate) != null) {
                throw GradleException("$format SBOM contains a duplicate component reference: $expectedReference")
            }
        }
        val rootCoordinate = publicationCoordinate.get()
        referenceToCoordinate[rootReference] = rootCoordinate

        val graph = dependencyGraph(bom, format)
        if (graph.keys != referenceToCoordinate.keys) {
            throw GradleException(
                "$format SBOM dependency inventory differs. Expected ${referenceToCoordinate.keys}, " +
                    "found ${graph.keys}",
            )
        }
        val unknownEdges = graph.values.flatten().toSet() - referenceToCoordinate.keys
        if (unknownEdges.isNotEmpty()) {
            throw GradleException("$format SBOM has dependency edges to unknown components: $unknownEdges")
        }
        val actualCoordinateGraph = graph.mapKeys { (reference, _) ->
            referenceToCoordinate.getValue(reference)
        }.mapValues { (_, children) ->
            children.map(referenceToCoordinate::getValue).sorted()
        }
        val expectedCoordinateGraph = (expectedRuntimeCoordinates.get() + rootCoordinate)
            .associateWithTo(linkedMapOf()) { emptyList<String>() }
        expectedCoordinateGraph[rootCoordinate] = expectedDirectRuntimeCoordinates.get().sorted()
        expectedTransitiveEdges.get().forEach { edge ->
            val separator = edge.indexOf(" -> ")
            if (separator <= 0 || edge.indexOf(" -> ", separator + 4) >= 0) {
                throw GradleException("Malformed expected SBOM dependency edge: $edge")
            }
            val parent = edge.substring(0, separator)
            val child = edge.substring(separator + 4)
            val existing = expectedCoordinateGraph[parent]
                ?: throw GradleException("Expected SBOM edge has unknown parent: $edge")
            if (child !in expectedCoordinateGraph) {
                throw GradleException("Expected SBOM edge has unknown child: $edge")
            }
            expectedCoordinateGraph[parent] = (existing + child).distinct().sorted()
        }
        if (actualCoordinateGraph != expectedCoordinateGraph) {
            throw GradleException(
                "$format SBOM dependency graph differs. Expected $expectedCoordinateGraph, " +
                    "found $actualCoordinateGraph",
            )
        }
    }

    private fun mavenComponentPurl(coordinate: String): String {
        val parts = coordinate.split(':', limit = 3)
        if (parts.size != 3 || parts.any(String::isBlank)) {
            throw GradleException("Malformed expected Maven coordinate: $coordinate")
        }
        return "pkg:maven/${parts[0]}/${parts[1]}@${parts[2]}?type=jar"
    }

    private fun dependencyGraph(bom: Bom, format: String): Map<String, List<String>> {
        val dependencies = bom.dependencies.orEmpty()
        val references = dependencies.map { it.ref }
        if (references.size != references.toSet().size) {
            throw GradleException("$format SBOM contains duplicate dependency references")
        }
        return dependencies.associate { dependency ->
            val children = dependency.dependencies.orEmpty().map { it.ref }
            if (children.size != children.toSet().size) {
                throw GradleException("$format SBOM contains duplicate edges from ${dependency.ref}")
            }
            dependency.ref to children.sorted()
        }
    }

    private fun verifyLicenseInventory(root: File) {
        val parsed = JsonSlurper().parse(
            root.resolve(GenerateDeterministicReleaseEvidence.LICENSE_INVENTORY),
        ) as? Map<*, *> ?: throw GradleException("Release licence inventory is not a JSON object")
        if (parsed.keys != setOf("dependencies", "importedModules")) {
            throw GradleException("Release licence inventory has unexpected keys: ${parsed.keys}")
        }
        val importedModules = parsed["importedModules"] as? List<*>
            ?: throw GradleException("Release licence inventory has no importedModules array")
        if (importedModules.isNotEmpty()) {
            throw GradleException("Release licence inventory unexpectedly imports modules: $importedModules")
        }
        val approvedLicenseUrls = mapOf(
            "Apache-2.0" to "https://www.apache.org/licenses/LICENSE-2.0",
            "BSD-3-Clause" to "https://opensource.org/licenses/BSD-3-Clause",
            "LGPL-2.1-only" to "https://www.gnu.org/licenses/lgpl-2.1",
            "MPL-1.1" to "https://www.mozilla.org/en-US/MPL/1.1",
        )
        val expectedLicenseIds = parseExpectedMappings(expectedRuntimeLicenseIds.get(), "runtime licence")
            .mapValues { (_, ids) -> ids.split(',').filter(String::isNotBlank).sorted() }
        val records = (parsed["dependencies"] as? List<*>)?.map { value ->
            val dependency = value as? Map<*, *>
                ?: throw GradleException("Release licence dependency is not an object")
            val expectedKeys = setOf("moduleName", "moduleVersion", "moduleUrls", "moduleLicenses")
            if (dependency.keys != expectedKeys) {
                throw GradleException("Release licence dependency has unexpected keys: ${dependency.keys}")
            }
            val moduleName = dependency["moduleName"] as? String
                ?: throw GradleException("Release licence dependency has no moduleName")
            val moduleVersion = dependency["moduleVersion"] as? String
                ?: throw GradleException("Release licence dependency has no moduleVersion")
            val coordinate = "$moduleName:$moduleVersion"
            val moduleUrls = (dependency["moduleUrls"] as? List<*>)?.map { url ->
                url as? String ?: throw GradleException("Release licence module URL is not a string: $url")
            } ?: throw GradleException("Release licence dependency has no moduleUrls array")
            if (moduleUrls != moduleUrls.distinct().sorted()) {
                throw GradleException("Release licence module URLs are not unique and sorted: $coordinate")
            }
            val licenceTuples = (dependency["moduleLicenses"] as? List<*>)?.map { licenceValue ->
                val licence = licenceValue as? Map<*, *>
                    ?: throw GradleException("Release licence tuple is not an object: $licenceValue")
                if (licence.keys != setOf("moduleLicense", "moduleLicenseUrl")) {
                    throw GradleException("Release licence tuple has unexpected keys: ${licence.keys}")
                }
                val id = licence["moduleLicense"] as? String
                    ?: throw GradleException("Release licence tuple has no SPDX ID: $coordinate")
                val url = licence["moduleLicenseUrl"] as? String
                    ?: throw GradleException("Release licence tuple has no URL: $coordinate/$id")
                "$id|$url"
            } ?: throw GradleException("Release licence dependency has no moduleLicenses array")
            if (licenceTuples != licenceTuples.distinct().sorted()) {
                throw GradleException("Release licence tuples are not unique and sorted: $coordinate")
            }
            coordinate to licenceTuples
        } ?: throw GradleException("Release licence inventory has no dependencies")
        if (records.size != records.map { it.first }.toSet().size) {
            throw GradleException("Release licence inventory contains duplicate dependency coordinates")
        }
        val actual = records.toMap()
        val expectedCoordinates = expectedRuntimeCoordinates.get().toSet()
        if (actual.keys != expectedCoordinates || expectedLicenseIds.keys != expectedCoordinates) {
            throw GradleException(
                "Release licence inventory differs. Expected $expectedCoordinates, found ${actual.keys}",
            )
        }
        expectedLicenseIds.forEach { (coordinate, ids) ->
            val expectedTuples = ids.map { id ->
                val url = approvedLicenseUrls[id]
                    ?: throw GradleException("No approved URL for licence $id")
                "$id|$url"
            }.sorted()
            if (actual.getValue(coordinate) != expectedTuples) {
                throw GradleException(
                    "Release licence tuples differ for $coordinate. " +
                        "Expected $expectedTuples, found ${actual.getValue(coordinate)}",
                )
            }
        }
    }

    private fun verifyProvenance(root: File) {
        val source = readProperties(root.resolve(GenerateDeterministicReleaseEvidence.SOURCE_PROVENANCE))
        val expected = mapOf(
            "formatVersion" to "1",
            "coordinate" to publicationCoordinate.get(),
            "sourceCommit" to sourceCommit.get(),
            "sourceTree" to sourceTree.get(),
            "sourceCommitEpochSeconds" to sourceEpochSeconds.get().toString(),
            "upstreamBaseline" to GenerateDeterministicReleaseEvidence.UPSTREAM_BASELINE,
            "upstreamBaselineTag" to GenerateDeterministicReleaseEvidence.UPSTREAM_BASELINE_TAG,
            "license" to "Apache-2.0",
        )
        if (source != expected) {
            throw GradleException("Unexpected source provenance. Expected $expected, found $source")
        }
        val build = readProperties(root.resolve(GenerateDeterministicReleaseEvidence.BUILD_PROVENANCE))
        val wrapper = Properties().apply {
            wrapperProperties.get().asFile.inputStream().use(::load)
        }
        val wrapperSha = wrapper.getProperty("distributionSha256Sum")
            ?: throw GradleException("Gradle wrapper properties have no distributionSha256Sum")
        val expectedBuild = mapOf(
            "formatVersion" to "1",
            "gradleVersion" to gradleVersion.get(),
            "gradleDistributionSha256" to wrapperSha,
            "javaBytecodeRelease" to "21",
            "operatingSystem" to normalizeOperatingSystem(operatingSystem.get()),
            "architecture" to normalizeArchitecture(architecture.get()),
        )
        if (build != expectedBuild) {
            throw GradleException("Unexpected deterministic build provenance: $build")
        }
    }

    private fun normalizeOperatingSystem(value: String): String = when {
        value.contains("linux", ignoreCase = true) -> "linux"
        value.contains("mac", ignoreCase = true) -> "macos"
        value.contains("windows", ignoreCase = true) -> "windows"
        else -> value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }

    private fun normalizeArchitecture(value: String): String = when (value.lowercase(Locale.ROOT)) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> value.lowercase(Locale.ROOT)
    }

    private fun verifyDigestManifest(root: File, name: String, algorithm: String) {
        val manifest = root.resolve(name)
        val expectedFiles = root.walkTopDown()
            .filter { file -> file.isFile && file.name !in setOf(
                GenerateDeterministicReleaseEvidence.SHA256_SUMS,
                GenerateDeterministicReleaseEvidence.SHA512_SUMS,
            ) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toSortedSet()
        val entries = parseDigestManifest(manifest, algorithm, prefix = "$algorithm:")
        if (entries.keys != expectedFiles) {
            throw GradleException("$algorithm manifest inventory differs from release evidence")
        }
        entries.forEach { (relative, expected) ->
            val actual = digest(root.resolve(relative), algorithm)
            if (actual != expected) {
                throw GradleException("$algorithm mismatch for $relative: expected $expected, got $actual")
            }
        }
    }

    private fun verifyLegalManifest(root: File, thirdPartyNames: List<String>) {
        val expectedFiles = sortedSetOf(
            GenerateDeterministicReleaseEvidence.LICENSE_FILE,
            GenerateDeterministicReleaseEvidence.NOTICES_FILE,
            *thirdPartyNames.map {
                "${GenerateDeterministicReleaseEvidence.THIRD_PARTY_DIRECTORY}/$it"
            }.toTypedArray(),
        )
        val manifest = root.resolve(GenerateDeterministicReleaseEvidence.LEGAL_MANIFEST)
        val entries = parseDigestManifest(manifest, "SHA-256", prefix = "")
        if (entries.keys != expectedFiles) {
            throw GradleException("Legal manifest inventory differs from reviewed legal files")
        }
        entries.forEach { (relative, expected) ->
            val actual = digest(root.resolve(relative), "SHA-256")
            if (actual != expected) {
                throw GradleException("Legal manifest SHA-256 mismatch for $relative")
            }
        }
    }

    private fun parseDigestManifest(file: File, algorithm: String, prefix: String): SortedMap<String, String> {
        val lines = file.readLines(StandardCharsets.UTF_8).filter(String::isNotBlank)
        val parsed = lines.map { line ->
            val separator = line.indexOf("  ")
            if (separator <= 0 || line.indexOf("  ", separator + 2) >= 0) {
                throw GradleException("Malformed $algorithm manifest line in $file: $line")
            }
            val digestField = line.substring(0, separator)
            if (!digestField.startsWith(prefix)) {
                throw GradleException("Malformed $algorithm manifest prefix in $file: $line")
            }
            val checksum = digestField.removePrefix(prefix)
            val expectedLength = MessageDigest.getInstance(algorithm).digestLength * 2
            if (checksum.length != expectedLength || checksum.any { it !in '0'..'9' && it !in 'a'..'f' }) {
                throw GradleException("Malformed $algorithm checksum in $file: $checksum")
            }
            val relative = line.substring(separator + 2)
            val pathSegments = relative.split('/')
            if (relative.isBlank() || relative.startsWith('/') || '\\' in relative ||
                pathSegments.any { it.isBlank() || it == "." || it == ".." }
            ) {
                throw GradleException("Unsafe evidence path in $file: $relative")
            }
            relative to checksum
        }
        val paths = parsed.map { it.first }
        if (paths.size != paths.toSet().size) {
            throw GradleException("Duplicate evidence path in $file")
        }
        if (paths != paths.sorted()) {
            throw GradleException("Evidence manifest is not sorted by path: $file")
        }
        return parsed.toMap(sortedMapOf())
    }

    private fun assertSameBytes(expected: File, actual: File) {
        if (!expected.readBytes().contentEquals(actual.readBytes())) {
            throw GradleException("Staged release evidence differs from its expected source: ${actual.name}")
        }
    }

    private fun readProperties(file: File): Map<String, String> {
        val entries = file.readLines(StandardCharsets.UTF_8)
            .filter(String::isNotBlank)
            .map { line ->
                if ('=' !in line) throw GradleException("Malformed provenance line in $file: $line")
                val key = line.substringBefore('=')
                if (key.isBlank() || key.any { it.isWhitespace() }) {
                    throw GradleException("Malformed provenance key in $file: $key")
                }
                key to line.substringAfter('=')
            }
        val keys = entries.map { it.first }
        if (keys.size != keys.toSet().size) throw GradleException("Duplicate provenance key in $file")
        if (keys != keys.sorted()) throw GradleException("Provenance keys are not sorted in $file")
        return entries.toMap()
    }

    private fun digest(file: File, algorithm: String): String {
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

    private data class ComponentRecord(
        val type: String,
        val bomRef: String,
        val purl: String,
        val description: String,
        val modified: Boolean?,
        val hashes: Map<String, String>,
        val licences: List<String>,
        val properties: List<String>,
        val externalReferences: List<String>,
    )

}

abstract class VerifyReproducibleRelease : DefaultTask() {

    @get:Internal
    abstract val sourceDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val gitExecutable: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val shellExecutable: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val gradleExecutable: RegularFileProperty

    @get:Internal
    abstract val readOnlyDependencyCache: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val canonicalThinJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val canonicalAllJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val canonicalSourcesJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val canonicalJavadocJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val canonicalPom: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val canonicalModuleMetadata: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val canonicalEvidenceDirectory: DirectoryProperty

    @get:Input
    abstract val expectedEvidencePaths: ListProperty<String>

    @get:Input
    abstract val publicationVersion: Property<String>

    @get:Input
    abstract val sourceCommit: Property<String>

    @get:Input
    abstract val sourceTree: Property<String>

    @get:Input
    abstract val sourceEpochSeconds: Property<Long>

    @get:Input
    abstract val offline: Property<Boolean>

    @get:LocalState
    abstract val workspaceDirectory: DirectoryProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val workspace = workspaceDirectory.get().asFile
        if (workspace.exists() && !workspace.deleteRecursively()) {
            throw GradleException("Could not clean reproducibility workspace: $workspace")
        }
        verifyCommittedSourceIdentity(sourceDirectory.get().asFile)
        val sourceArchive = workspace.resolve("committed-source.zip")
        createCommittedSourceArchive(sourceDirectory.get().asFile, sourceArchive)
        val first = workspace.resolve("checkout-a")
        val second = workspace.resolve("different-absolute-path-length/checkout-b")
        extractCommittedSource(sourceArchive, first)
        extractCommittedSource(sourceArchive, second)
        if (first.absolutePath.length == second.absolutePath.length) {
            throw GradleException("Reproducibility checkout paths must have different lengths")
        }

        val firstLog = workspace.resolve("checkout-a.log")
        val secondLog = workspace.resolve("checkout-b.log")
        runIsolatedBuild(first, workspace.resolve("gradle-home-a"), firstLog, "0022")
        runIsolatedBuild(second, workspace.resolve("different-gradle-home-length/home-b"), secondLog, "0077")

        val firstFiles = releaseFiles(first)
        val secondFiles = releaseFiles(second)
        val canonicalFiles = canonicalReleaseFiles()
        if (firstFiles.keys != secondFiles.keys || firstFiles.keys != canonicalFiles.keys) {
            throw GradleException(
                "Reproducible-build inventories differ. First-only ${firstFiles.keys - secondFiles.keys}, " +
                    "second-only ${secondFiles.keys - firstFiles.keys}, " +
                    "producer-only ${canonicalFiles.keys - firstFiles.keys}, " +
                    "isolated-only ${firstFiles.keys - canonicalFiles.keys}",
            )
        }
        val isolatedDifferences = firstFiles.keys.filter { relative ->
            digest(firstFiles.getValue(relative), "SHA-256") !=
                digest(secondFiles.getValue(relative), "SHA-256")
        }
        if (isolatedDifferences.isNotEmpty()) {
            throw GradleException(
                "Release outputs differ across isolated paths and Gradle homes: $isolatedDifferences. " +
                    "Build logs: $firstLog, $secondLog",
            )
        }
        val producerDifferences = firstFiles.keys.filter { relative ->
            digest(firstFiles.getValue(relative), "SHA-256") !=
                digest(canonicalFiles.getValue(relative), "SHA-256")
        }
        if (producerDifferences.isNotEmpty()) {
            throw GradleException(
                "Committed-source outputs differ from the candidate producer outputs: $producerDifferences",
            )
        }

        reportFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("sourceCommit=${sourceCommit.get()}")
                    appendLine("sourceTree=${sourceTree.get()}")
                    appendLine("version=${publicationVersion.get()}")
                    appendLine("sourceExtraction=git-archive")
                    appendLine("checkouts=2")
                    appendLine("isolatedGradleHomes=2")
                    appendLine("isolatedUmasks=0022,0077")
                    appendLine("locale=C")
                    appendLine("timezone=UTC")
                    appendLine("candidateProducerMatch=true")
                    firstFiles.forEach { (relative, file) ->
                        appendLine(
                            "${digest(file, "SHA-256")} ${digest(file, "SHA-512")}  $relative",
                        )
                    }
                },
                StandardCharsets.UTF_8,
            )
        }
    }

    private fun runIsolatedBuild(projectDirectory: File, gradleHome: File, logFile: File, umask: String) {
        gradleHome.mkdirs()
        logFile.parentFile.mkdirs()
        val command = mutableListOf(
            gradleExecutable.get().asFile.absolutePath,
            "--no-daemon",
            "--no-build-cache",
            "--no-configuration-cache",
            "--no-parallel",
            "--dependency-verification",
            "strict",
            "--console=plain",
        )
        if (offline.get()) command += "--offline"
        command += listOf(
            "clean",
            "jar",
            "shadowJar",
            "sourcesJar",
            "javadocJar",
            "generatePomFileForMavenJavaPublication",
            "generateMetadataFileForMavenJavaPublication",
            "generateReleaseEvidence",
        )
        command += listOf(
            "-PreleaseSourceCommit=${sourceCommit.get()}",
            "-PreleaseSourceTree=${sourceTree.get()}",
            "-PreleaseSourceEpochSeconds=${sourceEpochSeconds.get()}",
        )
        val processCommand = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            command
        }
        else {
            listOf(
                shellExecutable.get().asFile.absolutePath,
                "-c",
                "umask \"\$1\"; shift; exec \"\$@\"",
                "cqengine-repro",
                umask,
            ) + command
        }
        val process = ProcessBuilder(processCommand)
            .directory(projectDirectory)
            .redirectErrorStream(true)
            .redirectOutput(logFile)
            .apply {
                environment()["GRADLE_USER_HOME"] = gradleHome.absolutePath
                environment()["GRADLE_RO_DEP_CACHE"] = readOnlyDependencyCache.get().asFile.absolutePath
                environment().remove("JAVA_TOOL_OPTIONS")
                environment().remove("JDK_JAVA_OPTIONS")
                environment().remove("_JAVA_OPTIONS")
                environment().remove("JAVA_OPTS")
                environment().remove("GRADLE_OPTS")
                environment()["LC_ALL"] = "C"
                environment()["LANG"] = "C"
                environment()["TZ"] = "UTC"
            }
            .start()
        if (!process.waitFor(ISOLATED_BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
            throw GradleException(
                "Isolated reproducibility build timed out after $ISOLATED_BUILD_TIMEOUT_MINUTES minutes: " +
                    projectDirectory,
            )
        }
        val exit = process.exitValue()
        if (exit != 0) {
            val tail = logFile.readLines(StandardCharsets.UTF_8).takeLast(80).joinToString("\n")
            throw GradleException("Isolated reproducibility build failed in $projectDirectory:\n$tail")
        }
    }

    private fun verifyCommittedSourceIdentity(source: File) {
        val actualCommit = git(source, "rev-parse", "HEAD")
        val actualTree = git(source, "rev-parse", "HEAD^{tree}")
        val actualEpoch = git(source, "show", "-s", "--format=%ct", "HEAD").toLong()
        if (actualCommit != sourceCommit.get() || actualTree != sourceTree.get() ||
            actualEpoch != sourceEpochSeconds.get()
        ) {
            throw GradleException(
                "Release provenance differs from Git HEAD: " +
                    "commit=$actualCommit tree=$actualTree epoch=$actualEpoch",
            )
        }
    }

    private fun createCommittedSourceArchive(source: File, archive: File) {
        archive.parentFile.mkdirs()
        val process = ProcessBuilder(
            gitExecutable.get().asFile.absolutePath,
            "-c",
            "core.attributesFile=/dev/null",
            "archive",
            "--format=zip",
            "--output=${archive.absolutePath}",
            sourceCommit.get(),
        )
            .directory(source)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val exit = process.waitFor()
        if (exit != 0 || !archive.isFile || archive.length() == 0L) {
            throw GradleException("git archive failed ($exit): ${output.trim()}")
        }
    }

    private fun extractCommittedSource(archive: File, destination: File) {
        val destinationRoot = destination.toPath().toAbsolutePath().normalize()
        Files.createDirectories(destinationRoot)
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = destinationRoot.resolve(entry.name).normalize()
                if (!target.startsWith(destinationRoot)) {
                    throw GradleException("Committed-source archive contains an unsafe path: ${entry.name}")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                }
                else {
                    Files.createDirectories(target.parent)
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
                }
                zip.closeEntry()
            }
        }
    }

    private fun git(source: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf(gitExecutable.get().asFile.absolutePath) + arguments)
            .directory(source)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trim()
        val exit = process.waitFor()
        if (exit != 0 || output.isBlank()) {
            throw GradleException("git ${arguments.joinToString(" ")} failed ($exit): $output")
        }
        return output
    }

    private fun releaseFiles(projectDirectory: File): SortedMap<String, File> {
        val version = publicationVersion.get()
        val fixedFiles = listOf(
            "build/libs/cqengine-$version.jar",
            "build/libs/cqengine-$version-all.jar",
            "build/libs/cqengine-$version-sources.jar",
            "build/libs/cqengine-$version-javadoc.jar",
            "build/publications/mavenJava/pom-default.xml",
            "build/publications/mavenJava/module.json",
        )
        val evidenceRoot = projectDirectory.resolve("build/generated-release-evidence/publishable")
        val actualEvidencePaths = evidenceRoot.walkTopDown().filter(File::isFile).map { file ->
            file.relativeTo(evidenceRoot).invariantSeparatorsPath
        }.toSortedSet()
        val expectedEvidencePathSet = expectedEvidencePaths.get().toSortedSet()
        if (actualEvidencePaths != expectedEvidencePathSet) {
            throw GradleException(
                "Isolated release evidence inventory differs. Missing " +
                    "${expectedEvidencePathSet - actualEvidencePaths}, " +
                    "unexpected ${actualEvidencePaths - expectedEvidencePathSet}",
            )
        }
        val evidenceFiles = expectedEvidencePathSet.map { relative ->
            "build/generated-release-evidence/publishable/" +
                relative
        }
        val relativeFiles = (fixedFiles + evidenceFiles).toSortedSet()
        val missing = relativeFiles.filterNot { projectDirectory.resolve(it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("Isolated build did not produce release files: $missing")
        }
        return relativeFiles.associateWithTo(sortedMapOf()) { relative -> projectDirectory.resolve(relative) }
    }

    private fun canonicalReleaseFiles(): SortedMap<String, File> {
        val version = publicationVersion.get()
        val files = sortedMapOf(
            "build/libs/cqengine-$version.jar" to canonicalThinJar.get().asFile,
            "build/libs/cqengine-$version-all.jar" to canonicalAllJar.get().asFile,
            "build/libs/cqengine-$version-sources.jar" to canonicalSourcesJar.get().asFile,
            "build/libs/cqengine-$version-javadoc.jar" to canonicalJavadocJar.get().asFile,
            "build/publications/mavenJava/pom-default.xml" to canonicalPom.get().asFile,
            "build/publications/mavenJava/module.json" to canonicalModuleMetadata.get().asFile,
        )
        val evidenceRoot = canonicalEvidenceDirectory.get().asFile
        val actualEvidencePaths = evidenceRoot.walkTopDown().filter(File::isFile).map { file ->
            file.relativeTo(evidenceRoot).invariantSeparatorsPath
        }.toSortedSet()
        val expectedEvidencePathSet = expectedEvidencePaths.get().toSortedSet()
        if (actualEvidencePaths != expectedEvidencePathSet) {
            throw GradleException(
                "Candidate release evidence inventory differs. Missing " +
                    "${expectedEvidencePathSet - actualEvidencePaths}, " +
                    "unexpected ${actualEvidencePaths - expectedEvidencePathSet}",
            )
        }
        expectedEvidencePathSet.forEach { relative ->
            files[
                "build/generated-release-evidence/publishable/" +
                    relative
            ] = evidenceRoot.resolve(relative)
        }
        val missing = files.filterValues { file -> !file.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("Candidate producer did not create release files: ${missing.keys}")
        }
        return files
    }

    private fun digest(file: File, algorithm: String): String {
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

    companion object {
        private const val ISOLATED_BUILD_TIMEOUT_MINUTES = 15L
    }
}

abstract class GenerateLocalReadinessManifest : DefaultTask() {

    @get:Internal
    abstract val rootBuildDirectory: DirectoryProperty

    @get:Internal
    abstract val benchmarkBuildDirectory: DirectoryProperty

    @get:Input
    abstract val requiredEvidencePaths: ListProperty<String>

    @get:Input
    abstract val publicationCoordinate: Property<String>

    @get:Input
    abstract val sourceCommit: Property<String>

    @get:Input
    abstract val sourceTree: Property<String>

    @get:Input
    abstract val java21Runtime: Property<String>

    @get:Input
    abstract val java25Runtime: Property<String>

    @get:Input
    abstract val gradleVersion: Property<String>

    @get:Input
    abstract val operatingSystem: Property<String>

    @get:Input
    abstract val architecture: Property<String>

    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val root = rootBuildDirectory.get().asFile
        val benchmarks = benchmarkBuildDirectory.get().asFile
        val requiredPaths = requiredEvidencePaths.get()
        if (requiredPaths.size != requiredPaths.toSet().size) {
            throw GradleException("Local-readiness evidence paths contain duplicates")
        }
        val evidence = requiredPaths.associateWith { path ->
            when {
                path.startsWith("root:") -> root.resolve(path.removePrefix("root:"))
                path.startsWith("benchmarks:") -> benchmarks.resolve(path.removePrefix("benchmarks:"))
                else -> throw GradleException("Unknown local-readiness evidence root: $path")
            }
        }
        val missing = evidence.filterValues { file -> !file.isFile || file.length() == 0L }
        if (missing.isNotEmpty()) {
            throw GradleException("Missing or empty local-readiness evidence: $missing")
        }

        manifestFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("formatVersion=1")
                    appendLine("coordinate=${publicationCoordinate.get()}")
                    appendLine("sourceCommit=${sourceCommit.get()}")
                    appendLine("sourceTree=${sourceTree.get()}")
                    appendLine("generatedAt=${Instant.now()}")
                    appendLine("java21=${java21Runtime.get()}")
                    appendLine("java25=${java25Runtime.get()}")
                    appendLine("gradle=${gradleVersion.get()}")
                    appendLine("os=${operatingSystem.get()}")
                    appendLine("architecture=${architecture.get()}")
                    appendLine("command=scripts/qualify-candidate.sh")
                    appendLine("phaseIsolation=separate-fresh-source-and-gradle-homes")
                    appendLine(
                        "preflightGradleArguments=--no-daemon --no-build-cache --no-configuration-cache " +
                            "--no-parallel --dependency-verification strict --console=plain " +
                            ":benchmarks:jmhLaneSelectionPreflight :stress-tests:compileJava",
                    )
                    appendLine(
                        "gradleArguments=--no-daemon --no-build-cache --no-configuration-cache --no-parallel " +
                            "--dependency-verification strict --console=plain clean releaseCheck",
                    )
                    appendLine("evidenceFiles=${evidence.size}")
                    evidence.toSortedMap().forEach { (label, file) ->
                        appendLine(
                            "${digest(file, "SHA-256")} ${digest(file, "SHA-512")}  $label",
                        )
                    }
                },
                StandardCharsets.UTF_8,
            )
        }
    }

    private fun digest(file: File, algorithm: String): String {
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

}

abstract class VerifyLocalPublication : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val repositoryDirectory: DirectoryProperty

    @get:Input
    abstract val publicationVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val thinJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val allJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourcesJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val javadocJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val generatedPom: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val generatedModuleMetadata: RegularFileProperty

    @get:Input
    abstract val expectedGradleVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val noticesFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val thirdPartyLicenseDirectory: DirectoryProperty

    @get:OutputFile
    abstract val inventoryReport: RegularFileProperty

    @TaskAction
    fun verify() {
        val version = publicationVersion.get()
        val repositoryRoot = repositoryDirectory.get().asFile
        val artifactDirectory = repositoryRoot.resolve(ARTIFACT_DIRECTORY)
        val versionDirectory = artifactDirectory.resolve(version)
        val artifactMetadata = artifactDirectory.resolve(MAVEN_METADATA)
        verifyArtifactMetadata(artifactMetadata, version)
        val artifactStem = if (version.endsWith(SNAPSHOT_SUFFIX)) {
            val uniqueVersion = verifySnapshotMetadata(versionDirectory.resolve(MAVEN_METADATA), version)
            "$ARTIFACT_NAME-$uniqueVersion"
        }
        else {
            "$ARTIFACT_NAME-$version"
        }
        val expectedArtifactNames = sortedSetOf(
            "$artifactStem.jar",
            "$artifactStem-all.jar",
            "$artifactStem-javadoc.jar",
            "$artifactStem-sources.jar",
            "$artifactStem.module",
            "$artifactStem.pom",
        )
        val expectedPrimaryNames = if (version.endsWith("-SNAPSHOT")) {
            (expectedArtifactNames + "maven-metadata.xml").toSortedSet()
        }
        else {
            expectedArtifactNames
        }
        verifyRepositoryInventory(repositoryRoot, version, expectedPrimaryNames)
        val primaryFiles = expectedPrimaryNames.map(versionDirectory::resolve)
        val pom = versionDirectory.resolve("$artifactStem.pom")
        val moduleMetadata = versionDirectory.resolve("$artifactStem.module")

        verifyChecksumSidecars(versionDirectory, primaryFiles)
        verifyStagedArtifacts(versionDirectory, artifactStem, version)
        verifyChecksumSidecars(artifactDirectory, listOf(artifactMetadata))

        verifyPom(pom, version)
        verifyModuleMetadata(moduleMetadata, versionDirectory, artifactStem, version)

        val repositoryFiles = mutableListOf<File>()
        Files.walk(repositoryRoot.toPath()).use { paths ->
            paths.forEach { path ->
                if (Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
                    repositoryFiles += path.toFile()
                }
            }
        }
        val report = buildString {
            appendLine("coordinate=io.github.shuaibrao:cqengine:$version")
            repositoryFiles.sortedBy { file ->
                repositoryRoot.toPath().relativize(file.toPath()).toString()
            }.forEach { file ->
                val relative = repositoryRoot.toPath().relativize(file.toPath()).joinToString("/") {
                    it.toString()
                }
                appendLine(
                    "${digest(file, "SHA-256")} ${digest(file, "SHA-512")}  $relative",
                )
            }
        }
        inventoryReport.get().asFile.apply {
            parentFile.mkdirs()
            writeText(report)
        }
        println(report)
    }

    private fun verifyRepositoryInventory(
        repositoryRoot: File,
        version: String,
        expectedVersionFiles: Set<String>,
    ) {
        val versionPath = "$ARTIFACT_DIRECTORY/$version"
        val expected = sortedMapOf(
            "" to "directory",
            "io" to "directory",
            "io/github" to "directory",
            "io/github/shuaibrao" to "directory",
            ARTIFACT_DIRECTORY to "directory",
            versionPath to "directory",
        )
        fun addChecksummedFile(relative: String) {
            expected[relative] = "file"
            CHECKSUM_ALGORITHMS.keys.forEach { extension ->
                expected["$relative.$extension"] = "file"
            }
        }
        addChecksummedFile("$ARTIFACT_DIRECTORY/$MAVEN_METADATA")
        expectedVersionFiles.forEach { name -> addChecksummedFile("$versionPath/$name") }

        assertValid(
            repositoryRoot.exists(),
            "Missing staged local repository: $repositoryRoot",
        )
        val rootPath = repositoryRoot.toPath()
        val actual = sortedMapOf<String, String>()
        Files.walk(rootPath).use { paths ->
            paths.forEach { path ->
                val relative = rootPath.relativize(path).joinToString("/") { it.toString() }
                val type = when {
                    Files.isSymbolicLink(path) -> "symbolic-link"
                    Files.isDirectory(path) -> "directory"
                    Files.isRegularFile(path) -> "file"
                    else -> "special"
                }
                actual[relative] = type
            }
        }
        assertValid(
            actual == expected,
            "Unexpected recursive local-repository path/type inventory. " +
                "Missing ${expected.entries - actual.entries}, unexpected ${actual.entries - expected.entries}",
        )
    }

    private fun verifyArtifactMetadata(file: File, version: String) {
        val metadata = parseXml(file)
        assertElement(metadata, namespace = null, localName = "metadata", expectedAttributes = emptyMap())
        val snapshot = version.endsWith(SNAPSHOT_SUFFIX)
        val expectedVersioningChildren = if (snapshot) {
            listOf("latest", "versions", "lastUpdated")
        }
        else {
            listOf("latest", "release", "versions", "lastUpdated")
        }
        val children = requireExactChildren(
            metadata,
            namespace = null,
            expectedNames = listOf("groupId", "artifactId", "versioning"),
        )
        assertLeafText(children[0], GROUP_NAME)
        assertLeafText(children[1], ARTIFACT_NAME)
        val versioning = children[2]
        assertExactAttributes(versioning, emptyMap())
        val versioningChildren = requireExactChildren(versioning, null, expectedVersioningChildren)
        var index = 0
        assertLeafText(versioningChildren[index++], version)
        if (!snapshot) assertLeafText(versioningChildren[index++], version)
        val versions = versioningChildren[index++]
        assertExactAttributes(versions, emptyMap())
        val publishedVersions = requireExactChildren(versions, null, listOf("version"))
        assertLeafText(publishedVersions.single(), version)
        assertTimestamp(versioningChildren[index], "Artifact metadata lastUpdated")
    }

    private fun verifySnapshotMetadata(file: File, version: String): String {
        val metadata = parseXml(file)
        assertElement(
            metadata,
            namespace = null,
            localName = "metadata",
            expectedAttributes = mapOf(AttributeName(null, "modelVersion") to "1.1.0"),
        )
        val children = requireExactChildren(
            metadata,
            namespace = null,
            expectedNames = listOf("groupId", "artifactId", "versioning", "version"),
        )
        assertLeafText(children[0], GROUP_NAME)
        assertLeafText(children[1], ARTIFACT_NAME)
        assertLeafText(children[3], version)
        val versioning = children[2]
        assertExactAttributes(versioning, emptyMap())
        val versioningChildren = requireExactChildren(
            versioning,
            namespace = null,
            expectedNames = listOf("lastUpdated", "snapshot", "snapshotVersions"),
        )
        val lastUpdated = leafText(versioningChildren[0])
        assertValid(
            isValidTimestamp(lastUpdated, LAST_UPDATED_FORMATTER),
            "Snapshot metadata has invalid lastUpdated: $lastUpdated",
        )

        val snapshot = versioningChildren[1]
        assertExactAttributes(snapshot, emptyMap())
        val snapshotChildren = requireExactChildren(snapshot, null, listOf("timestamp", "buildNumber"))
        val timestamp = leafText(snapshotChildren[0])
        val buildNumber = leafText(snapshotChildren[1])
        assertValid(
            isValidTimestamp(timestamp, SNAPSHOT_TIMESTAMP_FORMATTER),
            "Snapshot metadata has invalid timestamp: $timestamp",
        )
        assertValid(POSITIVE_INTEGER.matches(buildNumber), "Snapshot metadata has invalid build number: $buildNumber")
        assertValid(
            lastUpdated == timestamp.replace(".", ""),
            "Snapshot timestamp and lastUpdated disagree: $timestamp != $lastUpdated",
        )
        val uniqueVersion = version.removeSuffix(SNAPSHOT_SUFFIX) + "-$timestamp-$buildNumber"

        val snapshotVersions = versioningChildren[2]
        assertExactAttributes(snapshotVersions, emptyMap())
        val entries = elementChildren(snapshotVersions)
        assertValid(
            entries.all { it.namespaceURI == null && it.localName == "snapshotVersion" },
            "Snapshot metadata contains an unexpected snapshotVersions child",
        )
        val actualArtifacts = entries.map { entry ->
            assertExactAttributes(entry, emptyMap())
            val names = elementChildren(entry).map(Element::getLocalName)
            val expectedNames = if (names.firstOrNull() == "classifier") {
                listOf("classifier", "extension", "value", "updated")
            }
            else {
                listOf("extension", "value", "updated")
            }
            val values = requireExactChildren(entry, null, expectedNames)
            var valueIndex = 0
            val classifier = if (expectedNames.first() == "classifier") {
                leafText(values[valueIndex++])
            }
            else {
                null
            }
            val extension = leafText(values[valueIndex++])
            assertLeafText(values[valueIndex++], uniqueVersion)
            assertLeafText(values[valueIndex], lastUpdated)
            SnapshotArtifact(classifier, extension)
        }
        assertValid(
            actualArtifacts.size == actualArtifacts.toSet().size,
            "Snapshot metadata contains duplicate artifact identities: $actualArtifacts",
        )
        assertValid(
            actualArtifacts.toSet() == EXPECTED_SNAPSHOT_ARTIFACTS,
            "Unexpected snapshot metadata artifacts. Expected $EXPECTED_SNAPSHOT_ARTIFACTS, found $actualArtifacts",
        )
        return uniqueVersion
    }

    private fun verifyStagedArtifacts(versionDirectory: File, artifactStem: String, version: String) {
        val stagedArtifacts = mapOf(
            versionDirectory.resolve("$artifactStem.jar") to thinJar.get().asFile,
            versionDirectory.resolve("$artifactStem-all.jar") to allJar.get().asFile,
            versionDirectory.resolve("$artifactStem-sources.jar") to sourcesJar.get().asFile,
            versionDirectory.resolve("$artifactStem-javadoc.jar") to javadocJar.get().asFile,
            versionDirectory.resolve("$artifactStem.pom") to generatedPom.get().asFile,
            versionDirectory.resolve("$artifactStem.module") to generatedModuleMetadata.get().asFile,
        )
        stagedArtifacts.forEach { (staged, producer) ->
            assertValid(
                Files.mismatch(staged.toPath(), producer.toPath()) == -1L,
                "Staged artifact differs from its producer output: ${staged.name}",
            )
        }
        verifyJar(
            versionDirectory.resolve("$artifactStem.jar"),
            "thin",
            expectedImplementationVersion = version,
            expectedManifestAttributes = EXPECTED_THIN_MANIFEST_ATTRIBUTES,
            requiredSuffix = ".class",
            forbiddenSuffixes = emptySet(),
        )
        verifyJar(
            versionDirectory.resolve("$artifactStem-all.jar"),
            "all",
            version,
            expectedManifestAttributes = EXPECTED_ALL_MANIFEST_ATTRIBUTES,
            requiredSuffix = ".class",
            forbiddenSuffixes = emptySet(),
        )
        verifyJar(
            versionDirectory.resolve("$artifactStem-sources.jar"),
            "sources",
            expectedImplementationVersion = version,
            expectedManifestAttributes = EXPECTED_DOCUMENTATION_MANIFEST_ATTRIBUTES,
            requiredSuffix = ".java",
            forbiddenSuffixes = setOf(".class"),
        )
        verifyJar(
            versionDirectory.resolve("$artifactStem-javadoc.jar"),
            "javadoc",
            expectedImplementationVersion = version,
            expectedManifestAttributes = EXPECTED_DOCUMENTATION_MANIFEST_ATTRIBUTES,
            requiredSuffix = ".html",
            forbiddenSuffixes = setOf(".class", ".java"),
        )
    }

    private fun verifyJar(
        file: File,
        label: String,
        expectedImplementationVersion: String?,
        expectedManifestAttributes: Set<String>,
        requiredSuffix: String,
        forbiddenSuffixes: Set<String>,
    ) {
        JarFile(file).use { jar ->
            val entries = jar.entries().asSequence().toList()
            val names = entries.map { it.name }
            val duplicates = names.groupingBy(String::toString).eachCount().filterValues { it > 1 }.keys
            assertValid(duplicates.isEmpty(), "$label JAR contains duplicate entries: $duplicates")
            assertValid(names.any { it.endsWith(requiredSuffix) }, "$label JAR contains no $requiredSuffix payload")
            val forbidden = names.filter { name -> forbiddenSuffixes.any(name::endsWith) }
            assertValid(forbidden.isEmpty(), "$label JAR contains forbidden payload: ${forbidden.take(20)}")

            val legalSources = linkedMapOf(
                "META-INF/LICENSE.txt" to licenseFile.get().asFile,
                "META-INF/THIRD-PARTY-NOTICES" to noticesFile.get().asFile,
            )
            thirdPartyLicenseDirectory.get().asFile.listFiles { source -> source.isFile }
                ?.sortedBy { it.name }
                .orEmpty()
                .forEach { source ->
                    legalSources["META-INF/THIRD-PARTY-LICENSES/${source.name}"] = source
                }
            legalSources.forEach { (entryName, source) ->
                val entry = jar.getJarEntry(entryName)
                    ?: throw GradleException("$label JAR is missing reviewed legal resource $entryName")
                val packaged = jar.getInputStream(entry).use { it.readBytes() }
                assertValid(
                    source.readBytes().contentEquals(packaged),
                    "$label JAR legal resource differs from $source: $entryName",
                )
            }
            val attributes = jar.manifest?.mainAttributes
            val actualManifestAttributes = attributes?.entries?.associate { (key, value) ->
                key.toString() to value.toString()
            }.orEmpty()
            assertValid(
                jar.manifest?.entries.orEmpty().isEmpty(),
                "$label JAR has unexpected named manifest sections: ${jar.manifest?.entries?.keys}",
            )
            assertValid(
                actualManifestAttributes.keys == expectedManifestAttributes,
                "$label JAR has unexpected manifest attributes: ${actualManifestAttributes.keys}",
            )
            assertValid(
                actualManifestAttributes["Manifest-Version"] == "1.0",
                "$label JAR has the wrong Manifest-Version",
            )
            if (expectedImplementationVersion != null) {
                assertValid(
                    actualManifestAttributes["Implementation-Version"] == expectedImplementationVersion,
                    "$label JAR has the wrong Implementation-Version",
                )
                assertValid(
                    actualManifestAttributes["Implementation-Title"] == "CQEngine",
                    "$label JAR has the wrong Implementation-Title",
                )
                if ("Automatic-Module-Name" in expectedManifestAttributes) {
                    assertValid(
                        actualManifestAttributes["Automatic-Module-Name"] == "cqengine",
                        "$label JAR has the wrong Automatic-Module-Name",
                    )
                }
            }
            assertValid(
                actualManifestAttributes[Attributes.Name.MAIN_CLASS.toString()] == null,
                "$label JAR must not be executable",
            )
        }
    }

    private fun verifyModuleMetadata(
        moduleFile: File,
        versionDirectory: File,
        artifactStem: String,
        version: String,
    ) {
        val root = parseModuleJson(moduleFile)
        assertJsonKeys(root, setOf("formatVersion", "component", "createdBy", "variants"), "metadata root")
        assertValid(root["formatVersion"] == "1.1", "Staged Gradle metadata has the wrong format")

        val component = jsonObject(root["component"], "Gradle module component")
        assertJsonKeys(component, setOf("group", "module", "version", "attributes"), "component")
        assertValid(component["group"] == GROUP_NAME, "Gradle metadata has the wrong group")
        assertValid(component["module"] == ARTIFACT_NAME, "Gradle metadata has the wrong module")
        assertValid(component["version"] == version, "Gradle metadata has the wrong version")
        val componentAttributes = jsonObject(component["attributes"], "Gradle module component attributes")
        val expectedStatus = publicationStatus(version)
        assertValid(
            componentAttributes == mapOf("org.gradle.status" to expectedStatus),
            "Unexpected Gradle component attributes: $componentAttributes",
        )

        val createdBy = jsonObject(root["createdBy"], "Gradle module createdBy")
        assertJsonKeys(createdBy, setOf("gradle"), "createdBy")
        val createdByGradle = jsonObject(createdBy["gradle"], "Gradle module createdBy.gradle")
        assertValid(
            createdByGradle == mapOf("version" to expectedGradleVersion.get()),
            "Unexpected Gradle metadata producer: $createdByGradle",
        )

        val variants = (root["variants"] as? List<*>)?.mapIndexed { index, value ->
            jsonObject(value, "Gradle module variant $index")
        } ?: throw GradleException("Staged Gradle metadata has no variants array")
        val variantNames = variants.map { variant ->
            variant["name"] as? String ?: throw GradleException("Gradle metadata variant has no string name")
        }
        assertValid(
            variantNames.size == variantNames.toSet().size,
            "Gradle metadata contains duplicate variant names: $variantNames",
        )
        val variantsByName = variants.associateBy { it.getValue("name") as String }
        assertValid(
            variantsByName.keys == EXPECTED_VARIANTS,
            "Unexpected Gradle metadata variants. Expected $EXPECTED_VARIANTS, found ${variantsByName.keys}",
        )

        val thinStaged = versionDirectory.resolve("$artifactStem.jar")
        verifyModuleVariant(
            variantsByName.getValue("apiElements"),
            expectedAttributes = mapOf(
                "org.gradle.category" to "library",
                "org.gradle.dependency.bundling" to "external",
                "org.gradle.jvm.version" to 21,
                "org.gradle.libraryelements" to "jar",
                "org.gradle.usage" to "java-api",
            ),
            expectedFile = "$ARTIFACT_NAME-$version.jar",
            stagedFile = thinStaged,
            expectedDependencies = EXPECTED_DEPENDENCIES,
        )
        verifyModuleVariant(
            variantsByName.getValue("runtimeElements"),
            expectedAttributes = mapOf(
                "org.gradle.category" to "library",
                "org.gradle.dependency.bundling" to "external",
                "org.gradle.jvm.version" to 21,
                "org.gradle.libraryelements" to "jar",
                "org.gradle.usage" to "java-runtime",
            ),
            expectedFile = "$ARTIFACT_NAME-$version.jar",
            stagedFile = thinStaged,
            expectedDependencies = EXPECTED_DEPENDENCIES,
        )
        verifyModuleVariant(
            variantsByName.getValue("shadowRuntimeElements"),
            expectedAttributes = mapOf(
                "org.gradle.category" to "library",
                "org.gradle.dependency.bundling" to "shadowed",
                "org.gradle.jvm.version" to 21,
                "org.gradle.libraryelements" to "jar",
                "org.gradle.usage" to "java-runtime",
            ),
            expectedFile = "$ARTIFACT_NAME-$version-all.jar",
            stagedFile = versionDirectory.resolve("$artifactStem-all.jar"),
            expectedDependencies = emptyList(),
        )
        verifyModuleVariant(
            variantsByName.getValue("sourcesElements"),
            expectedAttributes = mapOf(
                "org.gradle.category" to "documentation",
                "org.gradle.dependency.bundling" to "external",
                "org.gradle.docstype" to "sources",
                "org.gradle.usage" to "java-runtime",
            ),
            expectedFile = "$ARTIFACT_NAME-$version-sources.jar",
            stagedFile = versionDirectory.resolve("$artifactStem-sources.jar"),
            expectedDependencies = emptyList(),
        )
        verifyModuleVariant(
            variantsByName.getValue("javadocElements"),
            expectedAttributes = mapOf(
                "org.gradle.category" to "documentation",
                "org.gradle.dependency.bundling" to "external",
                "org.gradle.docstype" to "javadoc",
                "org.gradle.usage" to "java-runtime",
            ),
            expectedFile = "$ARTIFACT_NAME-$version-javadoc.jar",
            stagedFile = versionDirectory.resolve("$artifactStem-javadoc.jar"),
            expectedDependencies = emptyList(),
        )
    }

    private fun verifyModuleVariant(
        variant: Map<String, Any?>,
        expectedAttributes: Map<String, Any>,
        expectedFile: String,
        stagedFile: File,
        expectedDependencies: List<PomDependency>,
    ) {
        val name = variant["name"] as String
        val expectedKeys = if (expectedDependencies.isEmpty()) {
            setOf("name", "attributes", "files")
        }
        else {
            setOf("name", "attributes", "dependencies", "files")
        }
        assertJsonKeys(variant, expectedKeys, "variant $name")
        val attributes = jsonObject(variant["attributes"], "Gradle module attributes for $name")
        assertValid(attributes == expectedAttributes, "Unexpected attributes for $name: $attributes")

        val dependencies = if (expectedDependencies.isEmpty()) {
            emptyList()
        }
        else {
            (variant["dependencies"] as? List<*>)?.mapIndexed { index, value ->
                val dependency = jsonObject(value, "Gradle module dependency $index for $name")
                assertJsonKeys(dependency, setOf("group", "module", "version"), "dependency $index for $name")
                val dependencyVersion = jsonObject(
                    dependency["version"],
                    "Gradle module dependency version $index for $name",
                )
                assertJsonKeys(dependencyVersion, setOf("requires"), "dependency version $index for $name")
                PomDependency(
                    dependency["group"] as? String
                        ?: throw GradleException("Gradle metadata dependency has no string group"),
                    dependency["module"] as? String
                        ?: throw GradleException("Gradle metadata dependency has no string module"),
                    dependencyVersion["requires"] as? String
                        ?: throw GradleException("Gradle metadata dependency has no required version"),
                    "compile",
                )
            } ?: throw GradleException("Gradle metadata variant $name has no dependencies array")
        }
        assertValid(
            dependencies == expectedDependencies,
            "Unexpected dependencies for $name. Expected $expectedDependencies, found $dependencies",
        )

        val files = variant["files"] as? List<*>
            ?: throw GradleException("Gradle metadata variant $name has no files array")
        val fileEntries = files.mapIndexed { index, value ->
            jsonObject(value, "Gradle module file $index for $name")
        }
        val fileNames = fileEntries.map { file -> file["name"] as? String
            ?: throw GradleException("Gradle metadata file for $name has no string name") }
        assertValid(fileNames.size == fileNames.toSet().size, "Variant $name contains duplicate file names")
        assertValid(fileEntries.size == 1, "Variant $name must contain exactly one file: $fileNames")
        val file = fileEntries.single()
        assertJsonKeys(
            file,
            setOf("name", "url", "size", "sha512", "sha256", "sha1", "md5"),
            "file for $name",
        )
        assertValid(file["name"] == expectedFile, "Unexpected file name for $name: ${file["name"]}")
        assertValid(file["url"] == expectedFile, "Unexpected file URL for $name: ${file["url"]}")
        val size = file["size"] as? Number
            ?: throw GradleException("Gradle metadata file for $name has no numeric size")
        assertValid(
            size.toString() == stagedFile.length().toString(),
            "Unexpected file size for $name: $size",
        )
        MODULE_FILE_DIGESTS.forEach { (field, algorithm) ->
            val expected = digest(stagedFile, algorithm)
            assertValid(
                file[field] == expected,
                "Gradle metadata $field differs from staged ${stagedFile.name}",
            )
        }
    }

    private fun verifyChecksumSidecars(directory: File, primaryFiles: List<File>) {
        primaryFiles.forEach { file ->
            CHECKSUM_ALGORITHMS.forEach { (extension, algorithm) ->
                val sidecar = directory.resolve("${file.name}.$extension")
                val expected = sidecar.readText(StandardCharsets.US_ASCII)
                val expectedLength = MessageDigest.getInstance(algorithm).digestLength * 2
                assertValid(
                    expected.length == expectedLength && expected.all { it in '0'..'9' || it in 'a'..'f' },
                    "Published $algorithm sidecar is not one exact lowercase hexadecimal digest: $sidecar",
                )
                val actual = digest(file, algorithm)
                assertValid(
                    actual == expected,
                    "Published $algorithm does not match ${file.name}: expected $expected, got $actual",
                )
            }
        }
    }

    private fun publicationStatus(version: String): String = when {
        version.matches(
            Regex("""[0-9]+[.][0-9]+[.][0-9]+-SNAPSHOT"""),
        ) -> "integration"
        version.matches(
            Regex("""[0-9]+[.][0-9]+[.][0-9]+-rc[.][1-9][0-9]*"""),
        ) -> "milestone"
        version.matches(
            Regex("""[0-9]+[.][0-9]+[.][0-9]+"""),
        ) -> "release"
        else -> throw GradleException("Unsupported CQEngine publication version: $version")
    }

    private fun verifyPom(pom: File, version: String) {
        val pomText = pom.readText(StandardCharsets.UTF_8)
        val gradleMetadataMarker = "do_not_remove: published-with-gradle-metadata"
        assertValid(
            pomText.windowed(gradleMetadataMarker.length).count { it == gradleMetadataMarker } == 1,
            "Staged POM must contain exactly one Gradle metadata redirect marker",
        )
        assertValid(
            Regex("<!--\\s*do_not_remove: published-with-gradle-metadata\\s*-->")
                .findAll(pomText)
                .count() == 1,
            "Staged POM Gradle metadata redirect marker must be an exact XML comment",
        )
        val project = parseXml(pom)
        assertElement(
            project,
            namespace = POM_NAMESPACE,
            localName = "project",
            expectedAttributes = mapOf(
                AttributeName(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "schemaLocation") to
                    "$POM_NAMESPACE https://maven.apache.org/xsd/maven-4.0.0.xsd",
            ),
        )
        val projectChildren = requireExactChildren(
            project,
            POM_NAMESPACE,
            listOf(
                "modelVersion",
                "groupId",
                "artifactId",
                "version",
                "name",
                "description",
                "url",
                "licenses",
                "developers",
                "scm",
                "issueManagement",
                "dependencies",
            ),
        )
        assertLeafText(projectChildren[0], "4.0.0")
        assertLeafText(projectChildren[1], GROUP_NAME)
        assertLeafText(projectChildren[2], ARTIFACT_NAME)
        assertLeafText(projectChildren[3], version)
        assertLeafText(projectChildren[4], "CQEngine")
        assertLeafText(
            projectChildren[5],
            "Collection Query Engine: indexed queries over Java collections",
        )
        assertLeafText(projectChildren[6], "https://github.com/shuaibrao/cqengine")

        val licenses = projectChildren[7]
        assertExactAttributes(licenses, emptyMap())
        val license = requireExactChildren(licenses, POM_NAMESPACE, listOf("license")).single()
        assertExactAttributes(license, emptyMap())
        val licenseChildren = requireExactChildren(
            license,
            POM_NAMESPACE,
            listOf("name", "url", "distribution"),
        )
        assertLeafText(licenseChildren[0], "The Apache Software License, Version 2.0")
        assertLeafText(licenseChildren[1], "https://www.apache.org/licenses/LICENSE-2.0.txt")
        assertLeafText(licenseChildren[2], "repo")

        val developers = projectChildren[8]
        assertExactAttributes(developers, emptyMap())
        val developerElements = requireExactChildren(
            developers,
            POM_NAMESPACE,
            listOf("developer", "developer"),
        )
        assertExactAttributes(developerElements[0], emptyMap())
        val originalDeveloper = requireExactChildren(
            developerElements[0],
            POM_NAMESPACE,
            listOf("id", "name"),
        )
        assertLeafText(originalDeveloper[0], "npgall")
        assertLeafText(originalDeveloper[1], "Niall Gallagher")
        assertExactAttributes(developerElements[1], emptyMap())
        val maintainer = requireExactChildren(
            developerElements[1],
            POM_NAMESPACE,
            listOf("id", "name", "url"),
        )
        assertLeafText(maintainer[0], "shuaibrao")
        assertLeafText(maintainer[1], "Shuaib Rao")
        assertLeafText(maintainer[2], "https://github.com/shuaibrao")

        val scm = projectChildren[9]
        assertExactAttributes(scm, emptyMap())
        val scmChildren = requireExactChildren(
            scm,
            POM_NAMESPACE,
            listOf("connection", "developerConnection", "url"),
        )
        assertLeafText(scmChildren[0], "scm:git:https://github.com/shuaibrao/cqengine.git")
        assertLeafText(scmChildren[1], "scm:git:ssh://git@github.com/shuaibrao/cqengine.git")
        assertLeafText(scmChildren[2], "https://github.com/shuaibrao/cqengine")

        val issueManagement = projectChildren[10]
        assertExactAttributes(issueManagement, emptyMap())
        val issueManagementChildren = requireExactChildren(
            issueManagement,
            POM_NAMESPACE,
            listOf("system", "url"),
        )
        assertLeafText(issueManagementChildren[0], "GitHub")
        assertLeafText(issueManagementChildren[1], "https://github.com/shuaibrao/cqengine/issues")

        val dependencies = projectChildren[11]
        assertExactAttributes(dependencies, emptyMap())
        val dependencyElements = requireExactChildren(
            dependencies,
            POM_NAMESPACE,
            List(EXPECTED_DEPENDENCIES.size) { "dependency" },
        )
        val actualDependencies = dependencyElements.map { dependency ->
            assertExactAttributes(dependency, emptyMap())
            val dependencyChildren = requireExactChildren(
                dependency,
                POM_NAMESPACE,
                listOf("groupId", "artifactId", "version", "scope"),
            )
            PomDependency(
                leafText(dependencyChildren[0]),
                leafText(dependencyChildren[1]),
                leafText(dependencyChildren[2]),
                leafText(dependencyChildren[3]),
            )
        }
        assertValid(
            actualDependencies == EXPECTED_DEPENDENCIES,
            "Unexpected staged POM dependencies. Expected $EXPECTED_DEPENDENCIES, found $actualDependencies",
        )
    }

    private fun parseXml(file: File): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        return file.inputStream().use { factory.newDocumentBuilder().parse(it).documentElement }
    }

    private fun assertElement(
        element: Element,
        namespace: String?,
        localName: String,
        expectedAttributes: Map<AttributeName, String>,
    ) {
        assertValid(
            element.namespaceURI == namespace && element.localName == localName,
            "Expected {$namespace}$localName, found {${element.namespaceURI}}${element.localName}",
        )
        assertExactAttributes(element, expectedAttributes)
    }

    private fun assertExactAttributes(element: Element, expected: Map<AttributeName, String>) {
        val actual = buildMap {
            val attributes = element.attributes
            for (index in 0 until attributes.length) {
                val attribute = attributes.item(index)
                if (attribute.namespaceURI == XMLNS_NAMESPACE) continue
                val name = AttributeName(attribute.namespaceURI, attribute.localName ?: attribute.nodeName)
                put(name, attribute.nodeValue)
            }
        }
        assertValid(actual == expected, "Unexpected attributes on ${element.localName}: $actual")
    }

    private fun requireExactChildren(
        parent: Element,
        namespace: String?,
        expectedNames: List<String>,
    ): List<Element> {
        val children = elementChildren(parent)
        val actualNames = children.map { child ->
            assertValid(
                child.namespaceURI == namespace,
                "Unexpected child namespace in ${parent.localName}: {${child.namespaceURI}}${child.localName}",
            )
            child.localName
        }
        assertValid(
            actualNames == expectedNames,
            "Unexpected children in ${parent.localName}. Expected $expectedNames, found $actualNames",
        )
        return children
    }

    private fun elementChildren(parent: Element): List<Element> = buildList {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element) add(child)
        }
    }

    private fun assertLeafText(element: Element, expected: String) {
        assertValid(leafText(element) == expected, "Unexpected ${element.localName} value")
    }

    private fun leafText(element: Element): String {
        assertExactAttributes(element, emptyMap())
        assertValid(elementChildren(element).isEmpty(), "${element.localName} must not contain elements")
        return element.textContent
    }

    private fun assertTimestamp(element: Element, label: String) {
        val value = leafText(element)
        assertValid(isValidTimestamp(value, LAST_UPDATED_FORMATTER), "$label is invalid: $value")
    }

    private fun isValidTimestamp(value: String, formatter: DateTimeFormatter): Boolean {
        return runCatching { LocalDateTime.parse(value, formatter) }.isSuccess
    }

    private fun jsonObject(value: Any?, context: String): Map<String, Any?> {
        val source = value as? Map<*, *> ?: throw GradleException("$context is not an object")
        return source.entries.associate { (key, entryValue) ->
            val stringKey = key as? String ?: throw GradleException("$context has a non-string key")
            stringKey to entryValue
        }
    }

    private fun parseModuleJson(file: File): Map<String, Any?> {
        try {
            JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build()
                .createParser(file)
                .use { parser -> while (parser.nextToken() != null) Unit }
        }
        catch (failure: Exception) {
            throw GradleException("Staged Gradle module metadata is not strict JSON: $file", failure)
        }
        return jsonObject(JsonSlurper().parse(file), "Gradle module metadata root")
    }

    private fun assertJsonKeys(value: Map<String, Any?>, expected: Set<String>, context: String) {
        assertValid(
            value.keys == expected,
            "Unexpected keys in $context. Expected $expected, found ${value.keys}",
        )
    }

    private fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            String.format("%02x", byte.toInt() and 0xff)
        }
    }

    private fun assertValid(condition: Boolean, message: String) {
        if (!condition) throw GradleException(message)
    }

    companion object {
        private val CHECKSUM_ALGORITHMS = linkedMapOf(
            "md5" to "MD5",
            "sha1" to "SHA-1",
            "sha256" to "SHA-256",
            "sha512" to "SHA-512",
        )
        private val EXPECTED_DEPENDENCIES = listOf(
            PomDependency("org.antlr", "antlr4-runtime", "4.13.2", "compile"),
            PomDependency("com.googlecode.concurrent-trees", "concurrent-trees", "2.6.1", "compile"),
            PomDependency("org.javassist", "javassist", "3.32.0-GA", "compile"),
            PomDependency("com.esotericsoftware", "kryo", "5.6.2", "compile"),
            PomDependency("org.xerial", "sqlite-jdbc", "3.53.2.0", "compile"),
        )
        private val EXPECTED_THIN_MANIFEST_ATTRIBUTES = setOf(
            "Manifest-Version",
            "Automatic-Module-Name",
            "Bundle-License",
            "Bundle-ManifestVersion",
            "Bundle-Name",
            "Bundle-SymbolicName",
            "Bundle-Version",
            "Export-Package",
            "Implementation-Title",
            "Implementation-Version",
            "Import-Package",
            "Require-Capability",
        )
        private val EXPECTED_ALL_MANIFEST_ATTRIBUTES = setOf(
            "Manifest-Version",
            "Automatic-Module-Name",
            "Implementation-Title",
            "Implementation-Version",
            "Multi-Release",
        )
        private val EXPECTED_DOCUMENTATION_MANIFEST_ATTRIBUTES = setOf(
            "Manifest-Version",
            "Implementation-Title",
            "Implementation-Version",
        )
        private val EXPECTED_VARIANTS = setOf(
            "apiElements",
            "runtimeElements",
            "shadowRuntimeElements",
            "sourcesElements",
            "javadocElements",
        )
        private val EXPECTED_SNAPSHOT_ARTIFACTS = setOf(
            SnapshotArtifact(null, "module"),
            SnapshotArtifact("sources", "jar"),
            SnapshotArtifact(null, "jar"),
            SnapshotArtifact("all", "jar"),
            SnapshotArtifact("javadoc", "jar"),
            SnapshotArtifact(null, "pom"),
        )
        private val MODULE_FILE_DIGESTS = linkedMapOf(
            "md5" to "MD5",
            "sha1" to "SHA-1",
            "sha256" to "SHA-256",
            "sha512" to "SHA-512",
        )
        private val LAST_UPDATED_FORMATTER = DateTimeFormatter
            .ofPattern("uuuuMMddHHmmss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT)
        private val SNAPSHOT_TIMESTAMP_FORMATTER = DateTimeFormatter
            .ofPattern("uuuuMMdd.HHmmss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT)
        private val POSITIVE_INTEGER = Regex("[1-9][0-9]*")
        private const val GROUP_NAME = "io.github.shuaibrao"
        private const val ARTIFACT_NAME = "cqengine"
        private const val ARTIFACT_DIRECTORY = "io/github/shuaibrao/cqengine"
        private const val MAVEN_METADATA = "maven-metadata.xml"
        private const val SNAPSHOT_SUFFIX = "-SNAPSHOT"
        private const val POM_NAMESPACE = "http://maven.apache.org/POM/4.0.0"
        private const val XMLNS_NAMESPACE = "http://www.w3.org/2000/xmlns/"
    }

    private data class AttributeName(val namespace: String?, val localName: String)

    private data class SnapshotArtifact(val classifier: String?, val extension: String)

    private data class PomDependency(
        val group: String,
        val artifact: String,
        val version: String,
        val scope: String,
    )
}

abstract class GenerateConsumerVerificationMetadata : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceMetadata: RegularFileProperty

    @get:Input
    abstract val publicationVersion: Property<String>

    @get:OutputFile
    abstract val outputMetadata: RegularFileProperty

    @TaskAction
    fun generate() {
        val version = publicationVersion.get()
        if (!version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) {
            throw GradleException("Publication version is not safe for verification metadata: $version")
        }
        val source = sourceMetadata.get().asFile.readText(Charsets.UTF_8)
        val trustRule = "         <trust group=\"io.github.shuaibrao\" name=\"cqengine\" version=\"$version\" " +
            "reason=\"Exact project-local publication verified by verifyPublication\"/>"
        val generated = if (source.contains(TRUSTED_ARTIFACTS_CLOSE)) {
            insertBeforeUnique(source, TRUSTED_ARTIFACTS_CLOSE, "$trustRule\n")
        }
        else {
            val section = "\n      <trusted-artifacts>\n$trustRule\n      </trusted-artifacts>"
            insertAfterUnique(source, VERIFY_SIGNATURES, section)
        }
        outputMetadata.get().asFile.apply {
            parentFile.mkdirs()
            writeText(generated, Charsets.UTF_8)
        }
    }

    private fun insertBeforeUnique(source: String, anchor: String, addition: String): String {
        val index = uniqueAnchorIndex(source, anchor)
        return source.substring(0, index) + addition + source.substring(index)
    }

    private fun insertAfterUnique(source: String, anchor: String, addition: String): String {
        val index = uniqueAnchorIndex(source, anchor) + anchor.length
        return source.substring(0, index) + addition + source.substring(index)
    }

    private fun uniqueAnchorIndex(source: String, anchor: String): Int {
        val index = source.indexOf(anchor)
        if (index < 0 || index != source.lastIndexOf(anchor)) {
            throw GradleException("Expected exactly one verification-metadata anchor: $anchor")
        }
        return index
    }

    companion object {
        private const val VERIFY_SIGNATURES = "      <verify-signatures>true</verify-signatures>"
        private const val TRUSTED_ARTIFACTS_CLOSE = "      </trusted-artifacts>"
    }
}

abstract class Sha256VerifyingTask : DefaultTask() {

    protected fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            String.format("%02x", byte.toInt() and 0xff)
        }
    }
}

abstract class DownloadVerifiedExecutable : Sha256VerifyingTask() {

    @get:Input
    abstract val downloadUri: Property<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:Input
    abstract val expectedSize: Property<Long>

    @get:OutputFile
    abstract val executableFile: RegularFileProperty

    @TaskAction
    fun download() {
        val destination = executableFile.get().asFile.toPath()
        val expected = expectedSha256.get()
        val size = expectedSize.get()
        Files.createDirectories(destination.parent)
        if (Files.isRegularFile(destination) && Files.size(destination) == size && sha256(destination) == expected) {
            makeExecutable(destination)
            return
        }

        val temporary = Files.createTempFile(destination.parent, destination.fileName.toString(), ".part")
        try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
            val request = HttpRequest.newBuilder(URI.create(downloadUri.get()))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() != 200) {
                throw GradleException("Tool download failed with HTTP ${response.statusCode()}")
            }
            val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            if (contentLength != size) {
                response.body().close()
                throw GradleException("Tool download size header mismatch: expected $size, got $contentLength")
            }
            response.body().use { input ->
                Files.newOutputStream(temporary).use { output ->
                    var copied = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (copied < size) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), size - copied).toInt())
                        if (read < 0) {
                            throw GradleException("Tool download was truncated at $copied of $size bytes")
                        }
                        output.write(buffer, 0, read)
                        copied += read
                    }
                    if (input.read() >= 0) {
                        throw GradleException("Tool download exceeded the pinned size of $size bytes")
                    }
                }
            }
            val actual = sha256(temporary)
            if (actual != expected) {
                throw GradleException("Downloaded tool SHA-256 mismatch: expected $expected, got $actual")
            }
            try {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            makeExecutable(destination)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun makeExecutable(path: Path) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
            !path.toFile().setExecutable(true, false)
        ) {
            throw GradleException("Could not make downloaded tool executable: $path")
        }
    }
}

abstract class OsvScan @Inject constructor(private val execOperations: ExecOperations) : Sha256VerifyingTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val scannerExecutable: RegularFileProperty

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sbomFile: RegularFileProperty

    @get:Input
    abstract val expectedCoordinates: ListProperty<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun scan() {
        val scanner = scannerExecutable.get().asFile.toPath()
        val actual = sha256(scanner)
        if (actual != expectedSha256.get()) {
            throw GradleException("OSV-Scanner SHA-256 mismatch: expected ${expectedSha256.get()}, got $actual")
        }
        val sbom = sbomFile.get().asFile
        val parser = JsonParser()
        val validationErrors = parser.validate(sbom)
        if (validationErrors.isNotEmpty()) {
            throw GradleException("Runtime SBOM failed CycloneDX validation: $validationErrors")
        }
        val components = parser.parse(sbom).components.orEmpty()
        val actualCoordinates = components.map { "${it.group}:${it.name}:${it.version}" }.sorted()
        val expected = expectedCoordinates.get().sorted()
        if (actualCoordinates != expected) {
            throw GradleException("Unexpected runtime SBOM components. Expected $expected, found $actualCoordinates")
        }
        val invalidPurls = components.filter { component ->
            val purl = component.purl
            purl.isNullOrBlank() || !purl.startsWith("pkg:maven/")
        }
        if (invalidPurls.isNotEmpty()) {
            throw GradleException(
                "Runtime SBOM components have missing or non-Maven package URLs: " +
                    invalidPurls.map { "${it.group}:${it.name}:${it.version}" },
            )
        }
        val report = reportFile.get().asFile
        Files.createDirectories(report.toPath().parent)
        execOperations.exec {
            executable(scanner)
            args(
                "scan",
                "source",
                "--lockfile=${sbomFile.get().asFile.absolutePath}",
                "--format=json",
                "--all-packages",
                "--all-vulns",
                "--output-file=${report.absolutePath}",
                "--verbosity=info",
            )
        }.assertNormalExitValue()
        val resultRoot = requireObject(JsonSlurper().parse(report), "OSV report")
        val results = requireList(resultRoot, "results")
        if (results.size != 1) {
            throw GradleException("Expected one OSV result for the runtime SBOM, found ${results.size}")
        }
        val packages = requireList(requireObject(results.single(), "OSV result"), "packages")
        val scannedCoordinates = packages.map { entry ->
            val packageDetails = requireObject(requireObject(entry, "OSV package entry")["package"], "OSV package")
            val ecosystem = requireString(packageDetails, "ecosystem")
            if (ecosystem != "Maven") {
                throw GradleException("Expected Maven OSV package, found ecosystem $ecosystem")
            }
            "${requireString(packageDetails, "name")}:${requireString(packageDetails, "version")}"
        }.sorted()
        if (scannedCoordinates != expected) {
            throw GradleException(
                "Unexpected OSV result inventory. Expected $expected, found $scannedCoordinates",
            )
        }
    }

    private fun requireObject(value: Any?, label: String): Map<*, *> =
        value as? Map<*, *> ?: throw GradleException("Expected $label to be a JSON object")

    private fun requireList(parent: Map<*, *>, field: String): List<*> =
        parent[field] as? List<*> ?: throw GradleException("Expected JSON array: $field")

    private fun requireString(parent: Map<*, *>, field: String): String =
        parent[field] as? String ?: throw GradleException("Expected JSON string: $field")
}

abstract class VerifySecurityInventories : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseReport: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dependencyCheckReport: RegularFileProperty

    @get:Input
    abstract val expectedCoordinates: ListProperty<String>

    @get:Input
    abstract val expectedDependencyCheckFiles: ListProperty<String>

    @TaskAction
    fun verify() {
        val licenseRoot = parseObject(licenseReport.get().asFile)
        val licenseCoordinates = requireList(licenseRoot, "dependencies").map { entry ->
            val dependency = requireObject(entry, "licence dependency")
            "${requireString(dependency, "moduleName")}:${requireString(dependency, "moduleVersion")}"
        }.sorted()
        val expectedLicenses = expectedCoordinates.get().sorted()
        if (licenseCoordinates != expectedLicenses) {
            throw GradleException(
                "Unexpected runtime licence inventory. Expected $expectedLicenses, found $licenseCoordinates",
            )
        }

        val dependencyCheckRoot = parseObject(dependencyCheckReport.get().asFile)
        val dependencyCheckFiles = requireList(dependencyCheckRoot, "dependencies").map { entry ->
            requireString(requireObject(entry, "Dependency-Check dependency"), "fileName")
        }.sorted()
        val expectedFiles = expectedDependencyCheckFiles.get().sorted()
        if (dependencyCheckFiles != expectedFiles) {
            throw GradleException(
                "Unexpected Dependency-Check inventory. Expected $expectedFiles, found $dependencyCheckFiles",
            )
        }
    }

    private fun parseObject(file: File): Map<*, *> =
        requireObject(JsonSlurper().parse(file), file.absolutePath)

    private fun requireObject(value: Any?, label: String): Map<*, *> =
        value as? Map<*, *> ?: throw GradleException("Expected $label to be a JSON object")

    private fun requireList(parent: Map<*, *>, field: String): List<*> =
        parent[field] as? List<*> ?: throw GradleException("Expected JSON array: $field")

    private fun requireString(parent: Map<*, *>, field: String): String =
        parent[field] as? String ?: throw GradleException("Expected JSON string: $field")
}

plugins {
    `java-library`
    antlr
    jacoco
    `maven-publish`
    alias(libs.plugins.bnd)
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.dependency.check)
    alias(libs.plugins.license.report)
    alias(libs.plugins.shadow)
    alias(libs.plugins.spotbugs)
}

base {
    archivesName.set("cqengine")
}

val cqenginePublicationStatus = when {
    version.toString().matches(Regex("""[0-9]+[.][0-9]+[.][0-9]+-SNAPSHOT""")) ->
        "integration"
    version.toString().matches(Regex("""[0-9]+[.][0-9]+[.][0-9]+-rc[.][1-9][0-9]*""")) ->
        "milestone"
    version.toString().matches(Regex("""[0-9]+[.][0-9]+[.][0-9]+""")) ->
        "release"
    else -> throw GradleException("Unsupported CQEngine publication version: $version")
}
project.status = cqenginePublicationStatus

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withJavadocJar()
    withSourcesJar()
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

val mockitoAgent by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val japicmpTool by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val upstreamApiBaseline by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val upstreamApiRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val upstreamOsgiBaseline by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    antlr(libs.antlr.tool)

    api(libs.antlr.runtime)
    api(libs.concurrent.trees)
    api(libs.javassist)
    api(libs.kryo.core)
    api(libs.sqlite.jdbc)

    testImplementation(libs.equalsverifier)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.core)
    mockitoAgent(libs.mockito.core)
    testRuntimeOnly(libs.junit.platform.launcher)
    spotbugsPlugins(libs.findsecbugs)
    japicmpTool(libs.japicmp)
    upstreamApiBaseline("com.googlecode.cqengine:cqengine:3.6.0")
    upstreamApiRuntime("com.googlecode.cqengine:cqengine:3.6.0")
    upstreamOsgiBaseline("com.googlecode.cqengine:cqengine:3.6.0")

    constraints {
        testImplementation(libs.byte.buddy) {
            because("EqualsVerifier and Mockito require one converged Byte Buddy version")
        }
        testImplementation(libs.objenesis) {
            because("Kryo, EqualsVerifier and Mockito require one converged Objenesis version in tests")
        }
    }
}

val pythonExecutable = providers.provider {
    if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")) "python" else "python3"
}

val centralPublicationToolsTest by tasks.registering(Exec::class) {
    description = "Runs Linux release-host signing-bundle and Central Portal publication-tool regressions."
    group = "verification"
    commandLine(pythonExecutable.get(), "scripts/test-central-publication-tools.py")
    inputs.files(
        layout.projectDirectory.file("scripts/prepare-central-bundle.py"),
        layout.projectDirectory.file("scripts/central-portal.sh"),
        layout.projectDirectory.file("scripts/test-central-publication-tools.py"),
    )
    onlyIf("Central publication tooling executes only on the supported Linux release host") {
        System.getProperty("os.name").lowercase(Locale.ROOT).contains("linux")
    }
    outputs.upToDateWhen { false }
}

val testAgentHarness by tasks.registering(Exec::class) {
    description = "Runs focused tests for the shared agent harness."
    group = "verification"
    commandLine(pythonExecutable.get(), "scripts/test-agent-harness.py")
    inputs.files(
        fileTree(".agent") { exclude("hooks/state/**", "**/__pycache__/**", "**/*.pyc") },
        fileTree(".cursor"),
        fileTree(".claude") { exclude("settings.local.json") },
        fileTree(".codex"),
        layout.projectDirectory.file("AGENTS.md"),
        layout.projectDirectory.file("CLAUDE.md"),
        layout.projectDirectory.file("scripts/sync-agent-config.py"),
        layout.projectDirectory.file("scripts/test-agent-harness.py"),
    )
    outputs.upToDateWhen { false }
}

val checkAgentConfigSync by tasks.registering(Exec::class) {
    description = "Fails when generated agent rules or skills differ from canonical .agent content."
    group = "verification"
    dependsOn(testAgentHarness)
    commandLine(pythonExecutable.get(), "scripts/sync-agent-config.py", "--check")
    inputs.files(
        fileTree(".agent") { exclude("hooks/state/**", "**/__pycache__/**", "**/*.pyc") },
        fileTree(".cursor"),
        fileTree(".claude") { exclude("settings.local.json") },
        fileTree(".codex"),
        layout.projectDirectory.file("scripts/sync-agent-config.py"),
    )
    outputs.upToDateWhen { false }
}

val checkMarkdownLinks by tasks.registering(Exec::class) {
    description = "Verifies repository-local links in maintained Markdown documentation."
    group = "verification"
    commandLine(pythonExecutable.get(), "scripts/check-markdown-links.py")
    inputs.files(
        fileTree(".") {
            include("*.md")
            include("documentation/**/*.md")
            include("benchmarks/**/*.md")
            include("consumer-tests/**/*.md")
            include("stress-tests/**/*.md")
            include(".agent/**/*.md")
            exclude("build/**")
            exclude("**/build/**")
            exclude("documentation/javadoc/apidocs/**")
        },
        layout.projectDirectory.file("scripts/check-markdown-links.py"),
    )
    outputs.upToDateWhen { false }
}

val checkNoLegacyJUnit by tasks.registering {
    description = "Rejects JUnit 3/4, Vintage, legacy runners and retired test-library dependencies."
    group = "verification"
    val javaTests = fileTree("src/test") { include("**/*.java") }
    val dependencyMetadata = files(
        layout.projectDirectory.file("gradle/libs.versions.toml"),
        layout.projectDirectory.file("gradle.lockfile"),
        fileTree(".") {
            include("**/*.gradle.kts")
            exclude("**/build/**")
        },
    )
    val repositoryRoot = layout.projectDirectory.asFile
    inputs.files(javaTests, dependencyMetadata)
    doLast {
        val forbiddenSourcePatterns = linkedMapOf(
            "JUnit 3" to Regex("junit\\.framework"),
            "JUnit 4" to Regex("org\\.junit\\.(?!jupiter(?:\\.|$))"),
            "JUnit 4 runner" to Regex("@(RunWith|Rule|ClassRule)\\b"),
            "legacy data provider" to Regex("com\\.tngtech\\.java\\.junit\\.dataprovider"),
            "Guava testlib" to Regex("com\\.google\\.common\\.collect\\.testing"),
        )
        val sourceViolations = javaTests.files.sortedBy(File::getPath).flatMap { file ->
            val text = file.readText(StandardCharsets.UTF_8)
            forbiddenSourcePatterns.entries
                .filter { (_, pattern) -> pattern.containsMatchIn(text) }
                .map { (label, _) -> "$label in ${file.relativeTo(repositoryRoot)}" }
        }
        val forbiddenDependencyMarkers = listOf(
            "junit:" + "junit:4",
            "junit-vintage" + "-engine",
            "junit-data" + "provider",
            "guava-test" + "lib",
        )
        val dependencyViolations = dependencyMetadata.files.sortedBy(File::getPath).flatMap { file ->
            val text = file.readText(StandardCharsets.UTF_8)
            forbiddenDependencyMarkers
                .filter(text::contains)
                .map { marker -> "$marker in ${file.relativeTo(repositoryRoot)}" }
        }
        val violations = sourceViolations + dependencyViolations
        if (violations.isNotEmpty()) {
            throw GradleException("Legacy test stack detected:\n${violations.joinToString("\n")}")
        }
    }
}

tasks.named("check") {
    dependsOn(
        ":benchmarks:jmhLaneSelectionPreflight",
        centralPublicationToolsTest,
        checkAgentConfigSync,
        checkMarkdownLinks,
        checkNoLegacyJUnit,
    )
}

spotbugs {
    toolVersion.set(libs.versions.spotbugs)
    effort.set(Effort.MAX)
    reportLevel.set(Confidence.HIGH)
    ignoreFailures.set(false)
    excludeFilter.set(layout.projectDirectory.file("config/spotbugs/exclude.xml"))
    runOnCheck.set(false)
}

tasks.withType<SpotBugsTask>().configureEach {
    notCompatibleWithConfigurationCache("SpotBugs 4.10.2 task state is not serializable")
    outputs.doNotCacheIf("SpotBugs reports contain absolute analyzed-class paths") { true }
    reports.create("xml") {
        required.set(true)
    }
    reports.create("html") {
        required.set(true)
    }
}

val spotbugsMain = tasks.named<SpotBugsTask>("spotbugsMain")
val verifySpotBugsMain by tasks.registering(VerifySpotBugsResults::class) {
    description = "Verifies SpotBugs analyzed every production class without errors or findings."
    group = "verification"
    dependsOn(spotbugsMain)
    xmlReport.set(spotbugsMain.map { task ->
        task.reports.getByName("xml").outputLocation.get()
    })
    classesDirectory.set(layout.buildDirectory.dir("classes/java/main"))
    inventoryReport.set(layout.buildDirectory.file("reports/spotbugs/main-inventory.txt"))
}

val spotbugsReview by tasks.registering(SpotBugsTask::class) {
    description = "Runs the unfiltered maximum-effort LOW-confidence SpotBugs and FindSecBugs review."
    group = "verification"
    init(project.extensions.getByType<SpotBugsExtension>(), true)
    dependsOn(tasks.named("classes"))
    val productionAnalysis = spotbugsMain.get()
    classes = productionAnalysis.classes
    sourceDirs.from(productionAnalysis.sourceDirs)
    classDirs.from(productionAnalysis.classDirs)
    auxClassPaths.from(productionAnalysis.auxClassPaths)
    effort.set(Effort.MAX)
    reportLevel.set(Confidence.LOW)
    ignoreFailures = true
    excludeFilter.unset()
    excludeFilter.unsetConvention()
    reports.named("xml") {
        outputLocation.set(layout.buildDirectory.file("reports/spotbugs/review.xml"))
    }
    reports.named("html") {
        outputLocation.set(layout.buildDirectory.file("reports/spotbugs/review.html"))
    }
}

val verifySpotBugsReview by tasks.registering(VerifySpotBugsReview::class) {
    description = "Verifies the complete LOW-confidence inventory matches its reviewed baseline."
    group = "verification"
    dependsOn(spotbugsReview)
    xmlReport.set(spotbugsReview.map { task ->
        task.reports.getByName("xml").outputLocation.get()
    })
    reviewedBaseline.set(layout.projectDirectory.file("config/spotbugs/review-baseline.txt"))
    classesDirectory.set(layout.buildDirectory.dir("classes/java/main"))
    inventoryReport.set(layout.buildDirectory.file("reports/spotbugs/review-inventory.txt"))
}

tasks.check {
    dependsOn(verifySpotBugsMain)
}

// Gradle's ANTLR plugin makes its tool configuration extend the published API configuration.
// The ANTLR tool is build-only; the generated parsers require only antlr4-runtime at runtime.
configurations.named("api") {
    setExtendsFrom(extendsFrom.filterNot { it.name == "antlr" })
}

configurations.configureEach {
    resolutionStrategy.failOnVersionConflict()
}

dependencyLocking {
    lockAllConfigurations()
}

val sourceRoot = layout.projectDirectory.dir("src")
val antlrSourceRoot = sourceRoot.dir("main/antlr")
val antlrImports = antlrSourceRoot.dir("imports")

val cqnGrammarSources = objects.sourceDirectorySet("cqnGrammar", "CQN grammar sources").apply {
    srcDir(antlrSourceRoot.dir("com/googlecode/cqengine/query/parser/cqn/grammar"))
    include("CQNGrammar.g4")
}

val sqlGrammarSources = objects.sourceDirectorySet("sqlGrammar", "SQL grammar sources").apply {
    srcDir(antlrSourceRoot.dir("com/googlecode/cqengine/query/parser/sql/grammar"))
    include("SQLGrammar.g4")
}

val generateCqnGrammarSource by tasks.registering(AntlrTask::class) {
    description = "Generates the CQN parser without treating its imported Java grammar as a root grammar."
    setSource(cqnGrammarSources)
    outputDirectory = layout.buildDirectory.dir("generated-src/antlr/cqn").get().asFile
    packageName.set("com.googlecode.cqengine.query.parser.cqn.grammar")
    arguments = listOf(
        "-lib",
        antlrImports.asFile.absolutePath,
        "-listener",
        "-no-visitor",
        "-long-messages",
    )
    inputs.file(antlrImports.file("Java.g4"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

val generateSqlGrammarSource by tasks.registering(AntlrTask::class) {
    description = "Generates the SQL parser without treating its imported SQLite grammar as a root grammar."
    setSource(sqlGrammarSources)
    outputDirectory = layout.buildDirectory.dir("generated-src/antlr/sql").get().asFile
    packageName.set("com.googlecode.cqengine.query.parser.sql.grammar")
    arguments = listOf(
        "-lib",
        antlrImports.asFile.absolutePath,
        "-listener",
        "-no-visitor",
        "-long-messages",
    )
    inputs.file(antlrImports.file("SQLite.g4"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

sourceSets {
    main {
        java.setSrcDirs(listOf(sourceRoot.dir("main/java")))
        java.srcDir(generateCqnGrammarSource.map { it.outputDirectory })
        java.srcDir(generateSqlGrammarSource.map { it.outputDirectory })
        resources.setSrcDirs(listOf(sourceRoot.dir("main/resources")))
        antlr.setSrcDirs(emptyList<String>())
    }
    test {
        java.setSrcDirs(listOf(sourceRoot.dir("test/java")))
        resources.setSrcDirs(listOf(sourceRoot.dir("test/resources")))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-Xmaxwarns", "1000"))
}

val java21Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

val java25Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}

val jdeprscan by tasks.registering(VerifyOwnedJdkUsage::class) {
    description = "Rejects deprecated Java 25 APIs and unresolved classes in CQEngine bytecode."
    group = "verification"
    dependsOn(tasks.classes)
    scanType.set("jdeprscan")
    tool.set(java25Launcher.map { it.metadata.installationPath.file("bin/jdeprscan") })
    classesDirectory.set(layout.buildDirectory.dir("classes/java/main"))
    runtimeClasspath.from(sourceSets.main.get().runtimeClasspath)
    reportFile.set(layout.buildDirectory.file("reports/jdk-usage/jdeprscan.txt"))
}

val jdepsJdkInternals by tasks.registering(VerifyOwnedJdkUsage::class) {
    description = "Rejects JDK-internal API dependencies and unresolved classes in CQEngine bytecode."
    group = "verification"
    dependsOn(tasks.classes)
    scanType.set("jdeps")
    tool.set(java25Launcher.map { it.metadata.installationPath.file("bin/jdeps") })
    classesDirectory.set(layout.buildDirectory.dir("classes/java/main"))
    runtimeClasspath.from(sourceSets.main.get().runtimeClasspath)
    reportFile.set(layout.buildDirectory.file("reports/jdk-usage/jdeps-jdk-internals.txt"))
}

val jdkUsageCheck by tasks.registering {
    description = "Runs fail-closed Java 25 deprecated and internal API scans."
    group = "verification"
    dependsOn(jdeprscan, jdepsJdkInternals)
}

val apiCompatibility by tasks.registering(VerifyApiCompatibility::class) {
    description = "Rejects source or binary incompatibility with upstream CQEngine 3.6.0."
    group = "verification"
    dependsOn(tasks.jar)
    toolClasspath.from(japicmpTool)
    oldArchive.fileProvider(upstreamApiBaseline.elements.map { elements ->
        elements.single().asFile
    })
    newArchive.set(tasks.jar.flatMap { it.archiveFile })
    oldRuntimeClasspath.from(upstreamApiRuntime)
    newRuntimeClasspath.from(configurations.runtimeClasspath)
    launcher.set(java25Launcher)
    baselineCoordinate.set("com.googlecode.cqengine:cqengine:3.6.0")
    toolVersion.set(libs.versions.japicmp)
    reportFile.set(layout.buildDirectory.file("reports/api-compatibility/report.txt"))
}

val mockitoAgentArguments = objects.newInstance<JavaAgentArgumentProvider>().apply {
    agentClasspath.from(mockitoAgent)
}

val unsafeJavaOptionEnvironmentVariables = listOf(
    "JAVA_TOOL_OPTIONS",
    "JDK_JAVA_OPTIONS",
    "_JAVA_OPTIONS",
)
val requiredGitIsolationEnvironmentVariables = setOf(
    "GIT_ATTR_NOSYSTEM",
    "GIT_CONFIG_GLOBAL",
    "GIT_CONFIG_NOSYSTEM",
    "GIT_NO_REPLACE_OBJECTS",
)
val trustedToolEnvironmentNames = linkedMapOf(
    "bash" to "BASH",
    "git" to "GIT",
    "java" to "JAVA",
    "nproc" to "NPROC",
    "shell" to "SH",
    "tar" to "TAR",
)
val trustedGitExecutable = layout.file(
    providers.environmentVariable("CQENGINE_TRUSTED_GIT")
        .map(::file)
        .orElse(provider { file("/usr/bin/git") }),
)
val trustedShellExecutable = layout.file(
    providers.environmentVariable("CQENGINE_TRUSTED_SH")
        .map(::file)
        .orElse(provider { file("/usr/bin/sh") }),
)
val trustedBashExecutable = layout.file(
    providers.environmentVariable("CQENGINE_TRUSTED_BASH")
        .map(::file)
        .orElse(provider { file("/usr/bin/bash") }),
)
val explicitlyRequestedJmhTask = gradle.startParameter.taskNames.any { requestedTask ->
    requestedTask.substringAfterLast(':').startsWith("jmh")
}
val inheritedUnsafeJavaOptions = unsafeJavaOptionEnvironmentVariables.filter(System.getenv()::containsKey)
val unsafeReleaseOptionEnvironmentVariables =
    unsafeJavaOptionEnvironmentVariables +
        listOf("JAVA_OPTS", "GRADLE_OPTS") +
        System.getenv().keys.filter { name ->
            (name.startsWith("GIT_") && name !in requiredGitIsolationEnvironmentVariables) ||
                name.startsWith("ORG_GRADLE_PROJECT_")
        }.sorted()
val inheritedUnsafeReleaseOptions = unsafeReleaseOptionEnvironmentVariables.filter(System.getenv()::containsKey)
if (explicitlyRequestedJmhTask && inheritedUnsafeJavaOptions.isNotEmpty()) {
    throw GradleException(
        "JMH qualification rejects inherited Java option environment variables: $inheritedUnsafeJavaOptions",
    )
}

tasks.withType<Test>().configureEach {
    unsafeJavaOptionEnvironmentVariables.forEach { environment.remove(it) }
    jvmArgumentProviders.add(mockitoAgentArguments)
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
}

val functionalShardPattern = "**/IndexedCollectionFunctionalShards\$Shard*.class"
val functionalTestPatterns = arrayOf(
    "**/IndexedCollectionFunctionalTest.class",
    functionalShardPattern,
)
val persistenceTestPatterns = listOf(
    "**/persistence/**/*Test.class",
    "**/index/sqlite/**/*Test.class",
    "**/IndexedCollectionCloseFailureTest.class",
    "**/ConcurrentIndexedCollectionTest.class",
    "**/ObjectLockingIndexedCollectionTest.class",
    "**/TransactionalIndexedCollectionFailureRecoveryTest.class",
    "**/TransactionalIndexedCollectionTest.class",
    "**/functional/IndexOrderingTest.class",
    "**/index/support/PartialIndexTest.class",
)
val functionalScenarioCount = 7_654
val functionalShardCount = 4
fun functionalResultFileName(shardNumber: Int): String =
    "TEST-com.googlecode.cqengine.IndexedCollectionFunctionalShards\$Shard$shardNumber.xml"

fun Test.configureRuntime(launcher: Provider<JavaLauncher>, heap: String = "2g") {
    javaLauncher.set(launcher)
    maxHeapSize = heap
}

fun VerifyTestTaskResults.configureInventory(
    execution: TaskProvider<Test>,
    runtime: Int,
    suites: Int,
    tests: Int,
    skippedTests: List<String> = emptyList(),
) {
    dependsOn(execution)
    resultDirectory.set(layout.buildDirectory.dir("test-results/${execution.name}"))
    expectedSuiteCount.set(suites)
    expectedTestCount.set(tests)
    expectedSkippedTests.set(skippedTests)
    runtimeVersion.set(runtime)
    inventoryReport.set(layout.buildDirectory.file("reports/test-inventory/${execution.name}.txt"))
}

tasks.test {
    description = "Runs the normal, non-persistence unit suite on Java 25."
    configureRuntime(java25Launcher)
    exclude(*functionalTestPatterns)
    exclude(*persistenceTestPatterns.toTypedArray())
}

val verifyFastTestJava25 by tasks.registering(VerifyTestTaskResults::class) {
    description = "Verifies the exact Java 25 normal test inventory."
    group = "verification"
    configureInventory(tasks.named<Test>("test"), 25, 79, 426)
}

val fastTest by tasks.registering {
    description = "Runs and verifies the normal, non-persistence suite on Java 25."
    group = "verification"
    dependsOn(verifyFastTestJava25)
}

val persistenceTestJava25Execution by tasks.registering(Test::class) {
    description = "Runs SQLite, persistence and transaction integration tests on Java 25."
    group = "verification"
    configureRuntime(java25Launcher)
    include(*persistenceTestPatterns.toTypedArray())
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    mustRunAfter(tasks.test)
}

val expectedPersistenceSkips = listOf(
    "com.googlecode.cqengine.persistence.disk.DiskPersistenceTest#testReadFromDisk()",
    "com.googlecode.cqengine.persistence.disk.DiskPersistenceTest#testSaveToDisk()",
)
val verifyPersistenceTestJava25 by tasks.registering(VerifyTestTaskResults::class) {
    description = "Verifies the exact Java 25 persistence test inventory."
    group = "verification"
    configureInventory(
        persistenceTestJava25Execution,
        25,
        40,
        538,
        expectedPersistenceSkips,
    )
}

val persistenceTestJava25 by tasks.registering {
    description = "Runs and verifies SQLite, persistence and transaction tests on Java 25."
    group = "verification"
    dependsOn(verifyPersistenceTestJava25)
}

val fullTestJava25Execution by tasks.registering(Test::class) {
    description = "Runs four deterministic functional scenario shards in parallel on Java 25."
    group = "verification"
    configureRuntime(java25Launcher, "1g")
    include(functionalShardPattern)
    maxParallelForks = functionalShardCount
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("cqengine.skip.slow.scenarios", "false")
    mustRunAfter(persistenceTestJava25Execution)
}

val verifyFullTestJava25 by tasks.registering(VerifyFunctionalShardResults::class) {
    description = "Verifies the complete, gap-free Java 25 functional scenario union."
    group = "verification"
    dependsOn(fullTestJava25Execution)
    resultFiles.from((1..functionalShardCount).map { shardNumber ->
        layout.buildDirectory.file(
            "test-results/fullTestJava25Execution/${functionalResultFileName(shardNumber)}",
        )
    })
    expectedScenarioCount.set(functionalScenarioCount)
    expectedShardCount.set(functionalShardCount)
    runtimeVersion.set(25)
    inventoryReport.set(layout.buildDirectory.file("reports/test-inventory/full-test-java25.txt"))
}

val fullTest by tasks.registering {
    description = "Runs and verifies every functional scenario on Java 25 in four shards."
    group = "verification"
    dependsOn(verifyFullTestJava25)
}

val fastTestJava21Execution by tasks.registering(Test::class) {
    description = "Runs the normal, non-persistence unit suite on Java 21."
    group = "verification"
    configureRuntime(java21Launcher)
    exclude(*functionalTestPatterns)
    exclude(*persistenceTestPatterns.toTypedArray())
}

val verifyFastTestJava21 by tasks.registering(VerifyTestTaskResults::class) {
    description = "Verifies the exact Java 21 normal test inventory."
    group = "verification"
    configureInventory(fastTestJava21Execution, 21, 79, 426)
}

val fastTestJava21 by tasks.registering {
    description = "Runs and verifies the normal, non-persistence suite on Java 21."
    group = "verification"
    dependsOn(verifyFastTestJava21)
}

val persistenceTestJava21Execution by tasks.registering(Test::class) {
    description = "Runs SQLite, persistence and transaction integration tests on Java 21."
    group = "verification"
    configureRuntime(java21Launcher)
    include(*persistenceTestPatterns.toTypedArray())
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    mustRunAfter(fastTestJava21Execution)
}

val verifyPersistenceTestJava21 by tasks.registering(VerifyTestTaskResults::class) {
    description = "Verifies the exact Java 21 persistence test inventory."
    group = "verification"
    configureInventory(
        persistenceTestJava21Execution,
        21,
        40,
        538,
        expectedPersistenceSkips,
    )
}

val persistenceTestJava21 by tasks.registering {
    description = "Runs and verifies SQLite, persistence and transaction tests on Java 21."
    group = "verification"
    dependsOn(verifyPersistenceTestJava21)
}

val fullTestJava21Execution by tasks.registering(Test::class) {
    description = "Runs four deterministic functional scenario shards in parallel on Java 21."
    group = "verification"
    configureRuntime(java21Launcher, "1g")
    include(functionalShardPattern)
    maxParallelForks = functionalShardCount
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("cqengine.skip.slow.scenarios", "false")
    mustRunAfter(persistenceTestJava21Execution)
}

val verifyFullTestJava21 by tasks.registering(VerifyFunctionalShardResults::class) {
    description = "Verifies the complete, gap-free Java 21 functional scenario union."
    group = "verification"
    dependsOn(fullTestJava21Execution)
    resultFiles.from((1..functionalShardCount).map { shardNumber ->
        layout.buildDirectory.file(
            "test-results/fullTestJava21Execution/${functionalResultFileName(shardNumber)}",
        )
    })
    expectedScenarioCount.set(functionalScenarioCount)
    expectedShardCount.set(functionalShardCount)
    runtimeVersion.set(21)
    inventoryReport.set(layout.buildDirectory.file("reports/test-inventory/full-test-java21.txt"))
}

val fullTestJava21 by tasks.registering {
    description = "Runs and verifies every functional scenario on Java 21 in four shards."
    group = "verification"
    dependsOn(verifyFullTestJava21)
}

val persistenceTest by tasks.registering {
    description = "Runs persistence integration tests on Java 21 and Java 25."
    group = "verification"
    dependsOn(persistenceTestJava25, persistenceTestJava21)
}

val integrationTest by tasks.registering {
    description = "Alias for the complete Java 21 and Java 25 persistence integration matrix."
    group = "verification"
    dependsOn(persistenceTest)
}

val testJava25 by tasks.registering {
    description = "Runs normal, persistence and full functional tests on Java 25."
    group = "verification"
    dependsOn(fastTest, persistenceTestJava25, fullTest)
}

val testJava21 by tasks.registering {
    description = "Runs normal, persistence and full functional tests on Java 21."
    group = "verification"
    dependsOn(fastTestJava21, persistenceTestJava21, fullTestJava21)
    mustRunAfter(testJava25)
}

fastTestJava21Execution.configure {
    mustRunAfter(testJava25)
}

val coverageExclusions = listOf(
    "com/googlecode/cqengine/query/parser/*/grammar/**",
    "com/googlecode/cqengine/query/parser/cqn/support/ApacheSolrDataMathParser*",
)
val coverageClassDirectories = sourceSets.main.get().output.asFileTree.matching {
    exclude(coverageExclusions)
}
val java25CoverageExecutionData = files(
    layout.buildDirectory.file("jacoco/test.exec"),
    layout.buildDirectory.file("jacoco/persistenceTestJava25Execution.exec"),
    layout.buildDirectory.file("jacoco/fullTestJava25Execution.exec"),
)

tasks.jacocoTestReport {
    dependsOn(tasks.test, persistenceTestJava25Execution, fullTestJava25Execution)
    executionData.setFrom(java25CoverageExecutionData)
    classDirectories.setFrom(coverageClassDirectories)
    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(false)
    }
}

val verifyCoverageEvidence by tasks.registering(VerifyCoverageEvidence::class) {
    description = "Verifies complete, non-empty Java 25 JaCoCo evidence and root counters."
    group = "verification"
    dependsOn(
        tasks.jacocoTestReport,
        verifyFastTestJava25,
        verifyPersistenceTestJava25,
        verifyFullTestJava25,
    )
    executionData.from(java25CoverageExecutionData)
    expectedExecutionDataNames.set(
        listOf(
            "fullTestJava25Execution.exec",
            "persistenceTestJava25Execution.exec",
            "test.exec",
        ),
    )
    xmlReport.set(layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml"))
    inventoryReport.set(layout.buildDirectory.file("reports/jacoco/test/evidence.txt"))
}

tasks.jacocoTestCoverageVerification {
    dependsOn(verifyCoverageEvidence)
    executionData.setFrom(java25CoverageExecutionData)
    classDirectories.setFrom(coverageClassDirectories)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.77".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.67".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(testJava21)
    dependsOn(testJava25)
    dependsOn(tasks.jacocoTestCoverageVerification)
    dependsOn(jdkUsageCheck)
    dependsOn(apiCompatibility)
}

tasks.withType<Javadoc>().configureEach {
    javadocTool.set(javaToolchains.javadocToolFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("Xdoclint:all,-missing", true)
        addBooleanOption("Werror", true)
        addStringOption("Xmaxerrs", "10000")
        addStringOption("Xmaxwarns", "10000")
    }
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

tasks.withType<Jar>().configureEach {
    from(layout.projectDirectory.file("LICENSE.txt")) {
        into("META-INF")
    }
    from(layout.projectDirectory.file("THIRD-PARTY-NOTICES")) {
        into("META-INF")
    }
    from(layout.projectDirectory.dir("third-party-licenses")) {
        into("META-INF/THIRD-PARTY-LICENSES")
    }
}

fun osgiVersion(mavenVersion: String): String {
    val components = Regex("""([0-9]+)[.]([0-9]+)[.]([0-9]+)(?:-(.+))?""")
        .matchEntire(mavenVersion)
        ?: throw GradleException("Cannot convert project version to OSGi syntax: $mavenVersion")
    val qualifier = components.groupValues[4]
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
    return components.groupValues.subList(1, 4).joinToString(".") +
        if (qualifier.isEmpty()) "" else ".$qualifier"
}

class NormalizeGradleModuleStatusAction(
    private val expectedStatus: String,
) : Action<Task>, Serializable {

    override fun execute(task: Task) {
        val metadataTask = task as? GenerateModuleMetadata
            ?: throw GradleException("Gradle module status normalizer received ${task.javaClass.name}")
        val moduleFile = metadataTask.outputFile.get().asFile
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
            .createParser(moduleFile)
            .use { parser -> while (parser.nextToken() != null) Unit }
        val root = JsonSlurper().parse(moduleFile) as? Map<*, *>
            ?: throw GradleException("Generated Gradle module metadata is not an object")
        val component = root["component"] as? Map<*, *>
            ?: throw GradleException("Generated Gradle module metadata has no component object")
        val attributes = component["attributes"] as? Map<*, *>
            ?: throw GradleException("Generated Gradle module metadata has no component attributes")
        if (attributes.keys != setOf("org.gradle.status")) {
            throw GradleException("Unexpected generated Gradle component attributes: $attributes")
        }
        val generatedStatus = attributes["org.gradle.status"] as? String
            ?: throw GradleException("Generated Gradle module status is not a string")
        val acceptedGeneratedStatuses = if (expectedStatus == "milestone") {
            setOf("release", "milestone")
        }
        else {
            setOf(expectedStatus)
        }
        if (generatedStatus !in acceptedGeneratedStatuses) {
            throw GradleException(
                "Generated Gradle module status $generatedStatus cannot be normalized to $expectedStatus",
            )
        }
        if (generatedStatus == expectedStatus) return

        val text = moduleFile.readText(StandardCharsets.UTF_8)
        val statusPattern = Regex("""("org[.]gradle[.]status"[ \t]*:[ \t]*)"([A-Za-z]+)"""")
        val matches = statusPattern.findAll(text).toList()
        if (matches.size != 1 || matches.single().groupValues[2] != generatedStatus) {
            throw GradleException("Could not identify the one generated Gradle module status field")
        }
        val match = matches.single()
        val replacement = match.groupValues[1] + "\"$expectedStatus\""
        moduleFile.writeText(text.replaceRange(match.range, replacement), StandardCharsets.UTF_8)

        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
            .createParser(moduleFile)
            .use { parser -> while (parser.nextToken() != null) Unit }
        val normalizedRoot = JsonSlurper().parse(moduleFile) as? Map<*, *>
            ?: throw GradleException("Normalized Gradle module metadata is not an object")
        val normalizedComponent = normalizedRoot["component"] as? Map<*, *>
            ?: throw GradleException("Normalized Gradle module metadata has no component object")
        val normalizedAttributes = normalizedComponent["attributes"] as? Map<*, *>
            ?: throw GradleException("Normalized Gradle module metadata has no component attributes")
        if (normalizedAttributes != mapOf("org.gradle.status" to expectedStatus)) {
            throw GradleException("Gradle module status normalization failed: $normalizedAttributes")
        }
    }
}

tasks.jar {
    manifest {
        attributes(
            "Automatic-Module-Name" to "cqengine",
            "Implementation-Title" to "CQEngine",
            "Implementation-Version" to project.version,
            "Bundle-Version" to osgiVersion(project.version.toString()),
        )
    }
}

listOf("sourcesJar", "javadocJar").forEach { taskName ->
    tasks.named<Jar>(taskName) {
        manifest {
            attributes(
                "Implementation-Title" to "CQEngine",
                "Implementation-Version" to project.version,
            )
        }
    }
}

val bndBaseline = tasks.named<Baseline>("baseline") {
    description = "Rejects OSGi package changes not reflected in the CQEngine semantic version."
    group = "verification"
    setBaseline(upstreamOsgiBaseline)
    reportFile.set(layout.buildDirectory.file("reports/osgi-baseline/report.txt"))
}

val shadowJar by tasks.existing(ShadowJar::class) {
    archiveClassifier.set("all")
    failOnDuplicateEntries = true
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    transform(SqlDriverServiceTransformer::class.java)
    filesNotMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    relocate(
        "com.googlecode.concurrenttrees",
        "com.googlecode.cqengine.lib.com.googlecode.concurrenttrees",
    )
    relocate(
        "com.esotericsoftware",
        "com.googlecode.cqengine.lib.com.esotericsoftware",
    )
    relocate(
        "javassist",
        "com.googlecode.cqengine.lib.javassist",
    )
    relocate(
        "org.antlr.v4",
        "com.googlecode.cqengine.lib.org.antlr.v4",
    )
    relocate(
        "org.objenesis",
        "com.googlecode.cqengine.lib.org.objenesis",
    )

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
    exclude("module-info.class", "META-INF/versions/*/module-info.class")

    setManifest(
        StandaloneShadowManifest(
            project.extensions.getByType<JavaPluginExtension>().manifest {
                attributes(
                    "Automatic-Module-Name" to "cqengine",
                    "Implementation-Title" to "CQEngine",
                    "Implementation-Version" to project.version,
                )
            },
        ),
    )
}

tasks.cyclonedxDirectBom {
    includeConfigs = listOf("runtimeClasspath")
    projectType = Component.Type.LIBRARY
    includeBomSerialNumber = false
    includeLicenseText = false
    includeMetadataResolution = true
    includeBuildEnvironment = false
    includeBuildSystem = false
    componentName = "cqengine"
    componentGroup = project.group.toString()
    componentVersion = project.version.toString()
    externalReferences.set(emptyList())
    jsonOutput.set(layout.buildDirectory.file("reports/sbom/cqengine.cdx.json"))
    xmlOutput.set(layout.buildDirectory.file("reports/sbom/cqengine.cdx.xml"))
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("The release SBOM contains time-sensitive provenance") { true }
}

// The SBOM is retained as release evidence, not exposed as a project-dependency variant.
configurations.named("cyclonedxDirectBom") {
    isCanBeConsumed = false
}

project(":benchmarks").tasks.named("cyclonedxDirectBom") {
    enabled = false
}

licenseReport {
    outputDir = layout.buildDirectory.dir("reports/dependency-license").get().asFile.absolutePath
    projects = arrayOf(project)
    buildScriptProjects = emptyArray()
    configurations = arrayOf("runtimeClasspath")
    excludeOwnGroup = true
    unionParentPomLicenses = true
    filters = arrayOf(SpdxLicenseBundleNormalizer())
    renderers = arrayOf<ReportRenderer>(
        InventoryHtmlReportRenderer("index.html", "CQEngine runtime dependencies"),
        CsvReportRenderer("dependencies.csv"),
        JsonReportRenderer("dependencies.json", false),
    )
    allowedLicensesFile = layout.projectDirectory.file("config/license/allowed-licenses.json")
}

val nvdApiKey = providers.environmentVariable("NVD_API_KEY")
val verifyNvdApiKey by tasks.registering {
    description = "Requires a non-empty NVD API key without exposing its value."
    group = "verification"
    inputs.property("nvdApiKeyPresent", nvdApiKey.map(String::isNotBlank).orElse(false))
    doLast {
        if (!nvdApiKey.isPresent || nvdApiKey.get().isBlank()) {
            throw GradleException("NVD_API_KEY must be set for authenticated vulnerability analysis")
        }
    }
}

configure<DependencyCheckExtension> {
    formats = listOf("HTML", "JSON", "XML")
    outputDirectory.set(layout.buildDirectory.dir("reports/dependency-check"))
    scanConfigurations = listOf("runtimeClasspath")
    scanBuildEnv = false
    scanDependencies = true
    skipTestGroups = true
    failOnError = true
    failBuildOnCVSS = 7.0F
    showSummary = true
    nvd.apiKey.set(nvdApiKey)
    nvd.validForHours.set(0)
    analyzers.assemblyEnabled = false
    analyzers.ossIndex.enabled.set(false)
    setScanSet(shadowJar.flatMap { it.archiveFile }.get().asFile)
}

tasks.named("dependencyCheckAnalyze") {
    dependsOn(verifyNvdApiKey)
    dependsOn(shadowJar)
    outputs.upToDateWhen { false }
    notCompatibleWithConfigurationCache("Dependency-Check 12.2.2 does not support Gradle's configuration cache")
}

tasks.named("generateLicenseReport") {
    notCompatibleWithConfigurationCache("Gradle License Report 3.1.4 does not support the configuration cache")
}

tasks.named("checkLicensePreparation") {
    notCompatibleWithConfigurationCache("Gradle License Report 3.1.4 does not support the configuration cache")
}

tasks.named("checkLicense") {
    mustRunAfter("generateLicenseReport")
    notCompatibleWithConfigurationCache("Gradle License Report 3.1.4 does not support the configuration cache")
}

data class OsvScannerAsset(val name: String, val size: Long, val sha256: String)

val expectedRuntimeCoordinates = listOf(
    "com.esotericsoftware:kryo:5.6.2",
    "com.esotericsoftware:minlog:1.3.1",
    "com.esotericsoftware:reflectasm:1.11.9",
    "com.googlecode.concurrent-trees:concurrent-trees:2.6.1",
    "org.antlr:antlr4-runtime:4.13.2",
    "org.javassist:javassist:3.32.0-GA",
    "org.objenesis:objenesis:3.4",
    "org.xerial:sqlite-jdbc:3.53.2.0",
)
val expectedDirectRuntimeDependencyCoordinates = listOf(
    "com.esotericsoftware:kryo:5.6.2",
    "com.googlecode.concurrent-trees:concurrent-trees:2.6.1",
    "org.antlr:antlr4-runtime:4.13.2",
    "org.javassist:javassist:3.32.0-GA",
    "org.xerial:sqlite-jdbc:3.53.2.0",
)
val expectedRuntimeDependencyEdges = listOf(
    "com.esotericsoftware:kryo:5.6.2 -> com.esotericsoftware:minlog:1.3.1",
    "com.esotericsoftware:kryo:5.6.2 -> com.esotericsoftware:reflectasm:1.11.9",
    "com.esotericsoftware:kryo:5.6.2 -> org.objenesis:objenesis:3.4",
)

val expectedRuntimeArtifactNameByCoordinate = linkedMapOf(
    "com.esotericsoftware:kryo:5.6.2" to "kryo-5.6.2.jar",
    "com.esotericsoftware:minlog:1.3.1" to "minlog-1.3.1.jar",
    "com.esotericsoftware:reflectasm:1.11.9" to "reflectasm-1.11.9.jar",
    "com.googlecode.concurrent-trees:concurrent-trees:2.6.1" to "concurrent-trees-2.6.1.jar",
    "org.antlr:antlr4-runtime:4.13.2" to "antlr4-runtime-4.13.2.jar",
    "org.javassist:javassist:3.32.0-GA" to "javassist-3.32.0-GA.jar",
    "org.objenesis:objenesis:3.4" to "objenesis-3.4.jar",
    "org.xerial:sqlite-jdbc:3.53.2.0" to "sqlite-jdbc-3.53.2.0.jar",
)
val expectedDependencyCheckArtifactNames = expectedRuntimeArtifactNameByCoordinate.values.toList()
val expectedRuntimeLicenseIdsByCoordinate = linkedMapOf(
    "com.esotericsoftware:kryo:5.6.2" to "BSD-3-Clause",
    "com.esotericsoftware:minlog:1.3.1" to "BSD-3-Clause",
    "com.esotericsoftware:reflectasm:1.11.9" to "BSD-3-Clause",
    "com.googlecode.concurrent-trees:concurrent-trees:2.6.1" to "Apache-2.0",
    "org.antlr:antlr4-runtime:4.13.2" to "BSD-3-Clause",
    "org.javassist:javassist:3.32.0-GA" to "Apache-2.0,LGPL-2.1-only,MPL-1.1",
    "org.objenesis:objenesis:3.4" to "Apache-2.0",
    "org.xerial:sqlite-jdbc:3.53.2.0" to "Apache-2.0",
)

val osvScannerVersion = "2.3.8"
val osvScannerAsset = providers.provider {
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
    when {
        os.contains("linux") && arch in setOf("amd64", "x86_64") ->
            OsvScannerAsset("osv-scanner_linux_amd64", 58_335_394, "bc98e15319ed0d515e3f9235287ba53cdc5535d576d24fd573978ecfe9ab92dc")
        os.contains("linux") && arch in setOf("aarch64", "arm64") ->
            OsvScannerAsset("osv-scanner_linux_arm64", 54_460_578, "8158b18edd2d03b1a30d905ca91b032bc62262167be8f206c27114f08823e27c")
        os.contains("mac") && arch in setOf("amd64", "x86_64") ->
            OsvScannerAsset("osv-scanner_darwin_amd64", 56_621_648, "b8a80a9f14ca4c0cd0fc2d351b28f740da9e6a5b18385ac9f9d083360b5b504e")
        os.contains("mac") && arch in setOf("aarch64", "arm64") ->
            OsvScannerAsset("osv-scanner_darwin_arm64", 53_233_074, "a8cd6507b06239f463a7642430cfd2d154882f150f6e30cdc0653e28dfc34216")
        os.contains("windows") && arch in setOf("amd64", "x86_64") ->
            OsvScannerAsset("osv-scanner_windows_amd64.exe", 56_680_960, "cb04e79dd9698a7bc821bbfdddec916a416d1409fda79c927c509d37d00c9716")
        os.contains("windows") && arch in setOf("aarch64", "arm64") ->
            OsvScannerAsset("osv-scanner_windows_arm64.exe", 52_259_840, "285d1fbcf2c69ab5ee38ae3a850ab46e83f32ef1cd5f3c4c9eb161cc493f6d52")
        else -> throw GradleException("OSV-Scanner 2.3.8 has no pinned binary for $os/$arch")
    }
}

val downloadOsvScanner by tasks.registering(DownloadVerifiedExecutable::class) {
    description = "Downloads the pinned OSV-Scanner executable and verifies its release checksum."
    group = "build setup"
    downloadUri.set(osvScannerAsset.map { "https://github.com/google/osv-scanner/releases/download/v$osvScannerVersion/${it.name}" })
    expectedSha256.set(osvScannerAsset.map(OsvScannerAsset::sha256))
    expectedSize.set(osvScannerAsset.map(OsvScannerAsset::size))
    executableFile.set(layout.buildDirectory.file(osvScannerAsset.map { "tools/osv-scanner/$osvScannerVersion/${it.name}" }))
}

val osvScan by tasks.registering(OsvScan::class) {
    description = "Scans the production CycloneDX SBOM against OSV.dev."
    group = "verification"
    dependsOn(downloadOsvScanner)
    dependsOn(tasks.cyclonedxDirectBom)
    scannerExecutable.set(downloadOsvScanner.flatMap { it.executableFile })
    expectedSha256.set(osvScannerAsset.map(OsvScannerAsset::sha256))
    sbomFile.set(layout.buildDirectory.file("reports/sbom/cqengine.cdx.json"))
    expectedCoordinates.set(expectedRuntimeCoordinates)
    reportFile.set(layout.buildDirectory.file("reports/osv/osv-results.json"))
    outputs.upToDateWhen { false }
}

val verifySecurityInventories by tasks.registering(VerifySecurityInventories::class) {
    description = "Verifies that security reports cover the complete production dependency and artifact inventory."
    group = "verification"
    dependsOn("generateLicenseReport", "checkLicense", "dependencyCheckAnalyze")
    licenseReport.set(layout.buildDirectory.file("reports/dependency-license/dependencies.json"))
    dependencyCheckReport.set(layout.buildDirectory.file("reports/dependency-check/dependency-check-report.json"))
    expectedCoordinates.set(expectedRuntimeCoordinates)
    expectedDependencyCheckFiles.set(
        provider {
            expectedDependencyCheckArtifactNames + "cqengine-${project.version}-all.jar"
        },
    )
}

tasks.register("securityCheck") {
    description = "Runs runtime SBOM, licence and authenticated multi-feed vulnerability gates."
    group = "verification"
    dependsOn(tasks.cyclonedxDirectBom)
    dependsOn("generateLicenseReport", "checkLicense")
    dependsOn("dependencyCheckAnalyze", osvScan, verifySecurityInventories)
    notCompatibleWithConfigurationCache("The live vulnerability and licence plugins do not support it")
}

fun gitEvidenceProvider(propertyName: String, vararg arguments: String): Provider<String> =
    if (layout.projectDirectory.file(".git").asFile.exists()) {
        providers.exec {
            commandLine(trustedGitExecutable.get().asFile.absolutePath, *arguments)
        }.standardOutput.asText.map(String::trim)
    }
    else {
        providers.gradleProperty(propertyName)
    }

val releaseSourceCommit = gitEvidenceProvider("releaseSourceCommit", "rev-parse", "HEAD")
val releaseSourceTree = gitEvidenceProvider("releaseSourceTree", "rev-parse", "HEAD^{tree}")
val releaseSourceEpochSeconds = gitEvidenceProvider(
    "releaseSourceEpochSeconds",
    "show",
    "-s",
    "--format=%ct",
    "HEAD",
).map { value -> value.toLong() }
val releaseEvidencePropertyNames = listOf(
    "releaseSourceCommit",
    "releaseSourceTree",
    "releaseSourceEpochSeconds",
)
val generatedReleaseEvidenceDirectory = layout.buildDirectory.dir("generated-release-evidence/publishable")
val stagedReleaseEvidenceDirectory = layout.buildDirectory.dir("local-release-evidence/publishable")
val reviewedThirdPartyLicenseNames = listOf(
    "antlr4-runtime-4.13.2.txt",
    "kryo-5.6.2.txt",
    "minlog-1.3.1.txt",
    "reflectasm-1.11.9.txt",
)
val expectedDeterministicReleaseEvidencePaths = listOf(
    GenerateDeterministicReleaseEvidence.SBOM_JSON,
    GenerateDeterministicReleaseEvidence.SBOM_XML,
    GenerateDeterministicReleaseEvidence.SBOM_ALL_JSON,
    GenerateDeterministicReleaseEvidence.SBOM_ALL_XML,
    GenerateDeterministicReleaseEvidence.LICENSE_INVENTORY,
    GenerateDeterministicReleaseEvidence.LICENSE_FILE,
    GenerateDeterministicReleaseEvidence.NOTICES_FILE,
    GenerateDeterministicReleaseEvidence.SOURCE_PROVENANCE,
    GenerateDeterministicReleaseEvidence.BUILD_PROVENANCE,
    GenerateDeterministicReleaseEvidence.LEGAL_MANIFEST,
    GenerateDeterministicReleaseEvidence.SHA256_SUMS,
    GenerateDeterministicReleaseEvidence.SHA512_SUMS,
) + reviewedThirdPartyLicenseNames.map { fileName ->
    "${GenerateDeterministicReleaseEvidence.THIRD_PARTY_DIRECTORY}/$fileName"
}

val generateReleaseEvidence by tasks.registering(GenerateDeterministicReleaseEvidence::class) {
    description = "Generates path- and time-independent release SBOM, licence, legal and provenance evidence."
    group = "publishing"
    dependsOn(tasks.cyclonedxDirectBom, "generateLicenseReport", "checkLicense", tasks.jar, shadowJar)
    rawSbomJson.set(layout.buildDirectory.file("reports/sbom/cqengine.cdx.json"))
    rawLicenseInventory.set(layout.buildDirectory.file("reports/dependency-license/dependencies.json"))
    licenseFile.set(layout.projectDirectory.file("LICENSE.txt"))
    noticesFile.set(layout.projectDirectory.file("THIRD-PARTY-NOTICES"))
    thirdPartyLicenseDirectory.set(layout.projectDirectory.dir("third-party-licenses"))
    wrapperProperties.set(layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties"))
    thinJar.set(tasks.jar.flatMap { it.archiveFile })
    allJar.set(shadowJar.flatMap { it.archiveFile })
    publicationGroup.set(provider { project.group.toString() })
    publicationArtifact.set("cqengine")
    publicationVersion.set(provider { project.version.toString() })
    sourceCommit.set(releaseSourceCommit)
    sourceTree.set(releaseSourceTree)
    sourceEpochSeconds.set(releaseSourceEpochSeconds)
    gradleVersion.set(gradle.gradleVersion)
    operatingSystem.set(providers.systemProperty("os.name"))
    architecture.set(providers.systemProperty("os.arch"))
    outputDirectory.set(generatedReleaseEvidenceDirectory)
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Release evidence is regenerated from the current source and dependency graph") { true }
}

val stageLocalReleaseEvidence by tasks.registering(Sync::class) {
    description = "Stages deterministic release evidence beside, but outside, the Maven repository."
    group = "publishing"
    dependsOn(generateReleaseEvidence)
    from(generatedReleaseEvidenceDirectory)
    into(stagedReleaseEvidenceDirectory)
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

val releaseEvidenceRuntimeCoordinates = expectedRuntimeCoordinates
val verifyStagedReleaseEvidence by tasks.registering(VerifyStagedReleaseEvidence::class) {
    description = "Verifies the exact staged deterministic release-evidence inventory and strong checksums."
    group = "verification"
    dependsOn(stageLocalReleaseEvidence)
    evidenceDirectory.set(stagedReleaseEvidenceDirectory)
    generatedEvidenceDirectory.set(generatedReleaseEvidenceDirectory)
    licenseFile.set(layout.projectDirectory.file("LICENSE.txt"))
    noticesFile.set(layout.projectDirectory.file("THIRD-PARTY-NOTICES"))
    thirdPartyLicenseDirectory.set(layout.projectDirectory.dir("third-party-licenses"))
    wrapperProperties.set(layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties"))
    thinJar.set(tasks.jar.flatMap { it.archiveFile })
    allJar.set(shadowJar.flatMap { it.archiveFile })
    publicationCoordinate.set(
        provider { "${project.group}:cqengine:${project.version}" },
    )
    sourceCommit.set(releaseSourceCommit)
    sourceTree.set(releaseSourceTree)
    sourceEpochSeconds.set(releaseSourceEpochSeconds)
    gradleVersion.set(gradle.gradleVersion)
    operatingSystem.set(providers.systemProperty("os.name"))
    architecture.set(providers.systemProperty("os.arch"))
    expectedRuntimeCoordinates.set(releaseEvidenceRuntimeCoordinates)
    expectedDirectRuntimeCoordinates.set(expectedDirectRuntimeDependencyCoordinates)
    expectedTransitiveEdges.set(expectedRuntimeDependencyEdges)
    runtimeArtifacts.from(configurations.named("runtimeClasspath"))
    expectedRuntimeArtifactNames.set(
        expectedRuntimeArtifactNameByCoordinate.map { (coordinate, fileName) -> "$coordinate|$fileName" },
    )
    expectedRuntimeLicenseIds.set(
        expectedRuntimeLicenseIdsByCoordinate.map { (coordinate, ids) -> "$coordinate|$ids" },
    )
    expectedThirdPartyLicenseNames.set(reviewedThirdPartyLicenseNames)
    forbiddenPathFragments.set(
        listOf(
            layout.projectDirectory.asFile.absolutePath,
            System.getProperty("user.home"),
            gradle.gradleUserHomeDir.absolutePath,
            System.getProperty("java.io.tmpdir"),
        ).filter(String::isNotBlank).distinct(),
    )
    inventoryReport.set(layout.buildDirectory.file("reports/release-evidence/inventory.txt"))
}

val verifyReproducibleBuild by tasks.registering(VerifyReproducibleRelease::class) {
    description = "Byte-compares publishable outputs from different paths and isolated Gradle homes."
    group = "verification"
    dependsOn(
        tasks.jar,
        shadowJar,
        tasks.named("sourcesJar"),
        tasks.named("javadocJar"),
        tasks.named("generatePomFileForMavenJavaPublication"),
        tasks.named("generateMetadataFileForMavenJavaPublication"),
        generateReleaseEvidence,
    )
    sourceDirectory.set(layout.projectDirectory)
    gitExecutable.set(trustedGitExecutable)
    shellExecutable.set(trustedShellExecutable)
    gradleExecutable.set(
        layout.file(provider {
            val executableName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                "gradle.bat"
            }
            else {
                "gradle"
            }
            requireNotNull(gradle.gradleHomeDir) { "Gradle home directory is unavailable" }
                .resolve("bin/$executableName")
        }),
    )
    readOnlyDependencyCache.set(
        layout.dir(
            providers.environmentVariable("GRADLE_RO_DEP_CACHE")
                .map(::file)
                .orElse(provider { gradle.gradleUserHomeDir.resolve("caches") }),
        ),
    )
    canonicalThinJar.set(tasks.jar.flatMap { it.archiveFile })
    canonicalAllJar.set(shadowJar.flatMap { it.archiveFile })
    canonicalSourcesJar.set(tasks.named<Jar>("sourcesJar").flatMap { it.archiveFile })
    canonicalJavadocJar.set(tasks.named<Jar>("javadocJar").flatMap { it.archiveFile })
    canonicalPom.set(layout.buildDirectory.file("publications/mavenJava/pom-default.xml"))
    canonicalModuleMetadata.set(layout.buildDirectory.file("publications/mavenJava/module.json"))
    canonicalEvidenceDirectory.set(generatedReleaseEvidenceDirectory)
    expectedEvidencePaths.set(expectedDeterministicReleaseEvidencePaths)
    publicationVersion.set(provider { project.version.toString() })
    sourceCommit.set(releaseSourceCommit)
    sourceTree.set(releaseSourceTree)
    sourceEpochSeconds.set(releaseSourceEpochSeconds)
    offline.set(gradle.startParameter.isOffline)
    workspaceDirectory.set(layout.buildDirectory.dir("reproducibility/workspaces"))
    reportFile.set(layout.buildDirectory.file("reports/reproducibility/artifact-hashes.txt"))
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Reproducibility must execute two fresh isolated builds") { true }
}

val verifyPublishedJars by tasks.registering(VerifyPublishedJars::class) {
    description = "Verifies the canonical thin JAR, all classifier, and every bundled SQLite native."
    group = "verification"
    thinJar.set(tasks.jar.flatMap { it.archiveFile })
    allJar.set(shadowJar.flatMap { it.archiveFile })
    expectedBundleVersion.set(osgiVersion(project.version.toString()))
    expectedPublicationVersion.set(provider { project.version.toString() })
    expectedSQLiteVersion.set(libs.versions.sqlite)
    sqliteNativeChecksums.set(layout.projectDirectory.file("config/sqlite-native-checksums.properties"))
    inventoryReport.set(layout.buildDirectory.file("reports/publication/jar-inventory.txt"))
}

val formatRatchetCheck by tasks.registering(VerifyFormatRatchet::class) {
    description = "Rejects formatting regressions in text changed after the exact upstream baseline."
    group = "verification"
    sourceDirectory.set(layout.projectDirectory)
    gitExecutable.set(trustedGitExecutable)
    baselineCommit.set("a06923bca69719c51c622543fa0c2d63e71e8fab")
    reportFile.set(layout.buildDirectory.file("reports/qualification/format-ratchet.txt"))
    outputs.upToDateWhen { false }
}

val qualifyCandidateEarlyFailureTest by tasks.registering(Exec::class) {
    description = "Verifies that early qualification failures invalidate stale success evidence safely."
    group = "verification"
    commandLine(
        trustedBashExecutable.get().asFile.absolutePath,
        "-p",
        layout.projectDirectory.file("scripts/test-qualify-candidate-early-failures.sh"),
    )
    environment("JAVA_HOME", System.getProperty("java.home"))
    inputs.files(
        layout.projectDirectory.file("scripts/qualify-candidate.sh"),
        layout.projectDirectory.file("scripts/test-qualify-candidate-early-failures.sh"),
    )
    outputs.upToDateWhen { false }
}

val verifySourceAndLegalProvenance by tasks.registering(VerifySourceAndLegalProvenance::class) {
    description = "Verifies clean source provenance, Gradle-only lineage and byte-identical packaged legal files."
    group = "verification"
    dependsOn(tasks.jar, shadowJar, tasks.named("sourcesJar"), tasks.named("javadocJar"))
    sourceDirectory.set(layout.projectDirectory)
    gitExecutable.set(trustedGitExecutable)
    baselineCommit.set("a06923bca69719c51c622543fa0c2d63e71e8fab")
    baselineTag.set("upstream-npgall-a06923bc")
    expectedOriginUrl.set("https://github.com/shuaibrao/cqengine.git")
    expectedUpstreamUrl.set("https://github.com/npgall/cqengine.git")
    requiredLegalPaths.set(
        listOf(
            "LICENSE.txt",
            "THIRD-PARTY-NOTICES",
            "third-party-licenses/antlr4-runtime-4.13.2.txt",
            "third-party-licenses/kryo-5.6.2.txt",
            "third-party-licenses/minlog-1.3.1.txt",
            "third-party-licenses/reflectasm-1.11.9.txt",
        ),
    )
    thinJar.set(tasks.jar.flatMap { it.archiveFile })
    allJar.set(shadowJar.flatMap { it.archiveFile })
    sourcesJar.set(tasks.named<Jar>("sourcesJar").flatMap { it.archiveFile })
    javadocJar.set(tasks.named<Jar>("javadocJar").flatMap { it.archiveFile })
    reportFile.set(layout.buildDirectory.file("reports/qualification/source-legal-provenance.txt"))
    outputs.upToDateWhen { false }
}

val verifyReleaseInvocation by tasks.registering(VerifyReleaseInvocation::class) {
    description = "Rejects non-hermetic release qualification invocation settings."
    group = "verification"
    dependencyVerificationMode.set(gradle.startParameter.dependencyVerificationMode.name)
    excludedTaskNames.set(gradle.startParameter.excludedTaskNames.sorted())
    buildCacheEnabled.set(gradle.startParameter.isBuildCacheEnabled)
    parallelEnabled.set(gradle.startParameter.isParallelProjectExecutionEnabled)
    writeDependencyVerification.set(gradle.startParameter.writeDependencyVerifications)
    dependencyLocksToUpdate.set(gradle.startParameter.lockedDependenciesToUpdate)
    dependencyLockWriteEnabled.set(gradle.startParameter.isWriteDependencyLocks)
    refreshKeysEnabled.set(gradle.startParameter.isRefreshKeys)
    exportKeysEnabled.set(gradle.startParameter.isExportKeys)
    includedBuilds.set(gradle.startParameter.includedBuilds.map { it.absoluteFile.normalize().path }.sorted())
    initScripts.set(gradle.startParameter.allInitScripts.map { it.absoluteFile.normalize().path }.sorted())
    gradleUserHomePath.set(gradle.startParameter.gradleUserHomeDir.absoluteFile.normalize().path)
    environmentGradleUserHomePath.set(System.getenv("GRADLE_USER_HOME") ?: "")
    projectRootPath.set(layout.projectDirectory.asFile.absoluteFile.normalize().path)
    releaseInvocationMarker.set(System.getenv("CQENGINE_RELEASE_INVOCATION") ?: "")
    unsafeOptionEnvironmentVariables.set(
        inheritedUnsafeReleaseOptions,
    )
    gitConfigNoSystem.set(System.getenv("GIT_CONFIG_NOSYSTEM") ?: "")
    gitConfigGlobal.set(System.getenv("GIT_CONFIG_GLOBAL") ?: "")
    gitNoReplaceObjects.set(System.getenv("GIT_NO_REPLACE_OBJECTS") ?: "")
    gitAttrNoSystem.set(System.getenv("GIT_ATTR_NOSYSTEM") ?: "")
    trustedPath.set(System.getenv("CQENGINE_TRUSTED_PATH") ?: "")
    trustedToolBindings.set(
        trustedToolEnvironmentNames.map { (name, environmentSuffix) ->
            val path = System.getenv("CQENGINE_TRUSTED_$environmentSuffix") ?: ""
            val sha256 = System.getenv("CQENGINE_TRUSTED_${environmentSuffix}_SHA256") ?: ""
            "$name|$path|$sha256"
        },
    )
    releaseEvidenceOverrides.set(
        releaseEvidencePropertyNames.filter { propertyName ->
            providers.gradleProperty(propertyName).isPresent
        },
    )
    reportFile.set(layout.buildDirectory.file("reports/qualification/release-invocation.txt"))
    outputs.upToDateWhen { false }
    mustRunAfter(tasks.clean)
}

tasks.check {
    dependsOn(formatRatchetCheck, qualifyCandidateEarlyFailureTest, verifyPublishedJars)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "cqengine"
            pom {
                name.set("CQEngine")
                description.set(
                    "Collection Query Engine: indexed queries over Java collections",
                )
                url.set("https://github.com/shuaibrao/cqengine")
                licenses {
                    license {
                        name.set("The Apache Software License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("npgall")
                        name.set("Niall Gallagher")
                    }
                    developer {
                        id.set("shuaibrao")
                        name.set("Shuaib Rao")
                        url.set("https://github.com/shuaibrao")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/shuaibrao/cqengine.git")
                    developerConnection.set("scm:git:ssh://git@github.com/shuaibrao/cqengine.git")
                    url.set("https://github.com/shuaibrao/cqengine")
                }
                issueManagement {
                    system.set("GitHub")
                    url.set("https://github.com/shuaibrao/cqengine/issues")
                }
            }
        }
    }
    repositories {
        maven {
            name = "localTest"
            url = uri(layout.buildDirectory.dir("local-repository"))
        }
    }
}

tasks.named<GenerateModuleMetadata>("generateMetadataFileForMavenJavaPublication") {
    doLast(NormalizeGradleModuleStatusAction(cqenginePublicationStatus))
}

tasks.withType<PublishToMavenLocal>().configureEach {
    enabled = false
}

tasks.named("publishToMavenLocal") {
    enabled = false
}

val cleanLocalTestRepository by tasks.registering(Delete::class) {
    description = "Removes the project-local Maven repository before staging a consumer candidate."
    group = "publishing"
    delete(layout.buildDirectory.dir("local-repository"))
}

val publishLocalTest = tasks.named("publishAllPublicationsToLocalTestRepository") {
    mustRunAfter(cleanLocalTestRepository)
}
tasks.named("publishMavenJavaPublicationToLocalTestRepository") {
    mustRunAfter(cleanLocalTestRepository)
}

val stageLocalPublication by tasks.registering {
    description = "Cleans and stages the complete local Maven publication."
    group = "publishing"
    dependsOn(cleanLocalTestRepository, publishLocalTest)
}

val verifyPublication by tasks.registering(VerifyLocalPublication::class) {
    description = "Verifies the structured Maven publication and separately staged release evidence."
    group = "verification"
    dependsOn(stageLocalPublication, verifyPublishedJars, verifyStagedReleaseEvidence)
    repositoryDirectory.set(layout.buildDirectory.dir("local-repository"))
    publicationVersion.set(provider { project.version.toString() })
    thinJar.set(tasks.jar.flatMap { it.archiveFile })
    allJar.set(shadowJar.flatMap { it.archiveFile })
    sourcesJar.set(tasks.named<Jar>("sourcesJar").flatMap { it.archiveFile })
    javadocJar.set(tasks.named<Jar>("javadocJar").flatMap { it.archiveFile })
    generatedPom.set(layout.buildDirectory.file("publications/mavenJava/pom-default.xml"))
    generatedModuleMetadata.set(layout.buildDirectory.file("publications/mavenJava/module.json"))
    expectedGradleVersion.set(gradle.gradleVersion)
    licenseFile.set(layout.projectDirectory.file("LICENSE.txt"))
    noticesFile.set(layout.projectDirectory.file("THIRD-PARTY-NOTICES"))
    thirdPartyLicenseDirectory.set(layout.projectDirectory.dir("third-party-licenses"))
    inventoryReport.set(layout.buildDirectory.file("reports/publication/inventory.txt"))
}

val generateConsumerVerificationMetadata by tasks.registering(GenerateConsumerVerificationMetadata::class) {
    description = "Adds a version-scoped trust rule for the separately verified local CQEngine publication."
    group = "verification"
    sourceMetadata.set(layout.projectDirectory.file("gradle/verification-metadata.xml"))
    publicationVersion.set(provider { project.version.toString() })
    outputMetadata.set(layout.buildDirectory.file("consumer-verification/verification-metadata.xml"))
}

val externalConsumerBuildDirectory = layout.buildDirectory.dir("consumer-tests")
val externalConsumerGradleUserHome = objects.directoryProperty().apply {
    set(File(gradle.gradleUserHomeDir, "cqengine-isolated-consumer-home"))
}
val cleanExternalConsumerGradleUserHome by tasks.registering(Delete::class) {
    description = "Removes the isolated external-consumer Gradle user home before qualification."
    group = "verification"
    delete(externalConsumerGradleUserHome)
    mustRunAfter(tasks.clean)
}

val stageExternalConsumerBuild by tasks.registering(Sync::class) {
    description = "Stages the standalone consumer build with strict dependency-verification metadata."
    group = "verification"
    from(layout.projectDirectory.dir("consumer-tests")) {
        exclude(".gradle/**", "**/build/**")
    }
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from(generateConsumerVerificationMetadata.flatMap { it.outputMetadata }) {
        into("gradle")
    }
    from(layout.projectDirectory.file("gradle/verification-keyring.keys")) {
        into("gradle")
    }
    into(externalConsumerBuildDirectory)
}

fun externalConsumerCommand(projectName: String, pomOnly: Boolean = false): List<String> = buildList {
    add(layout.projectDirectory.file("gradlew").asFile.absolutePath)
    add("-p")
    add(externalConsumerBuildDirectory.get().asFile.absolutePath)
    add("--no-daemon")
    add("--no-build-cache")
    add("--no-parallel")
    add("--no-configuration-cache")
    add("--dependency-verification")
    add("strict")
    add("--refresh-dependencies")
    add("--rerun-tasks")
    add("--console=plain")
    if (gradle.startParameter.isOffline) {
        add("--offline")
    }
    add(":$projectName:clean")
    add(":$projectName:consumerTest")
    add("-PcqengineRepository=${layout.buildDirectory.dir("local-repository").get().asFile.absolutePath}")
    add("-PproducerRoot=${layout.projectDirectory.asFile.absolutePath}")
    add("-PcqengineVersion=${project.version}")
    if (pomOnly) {
        add("-PpomOnly=true")
    }
}

fun Exec.configureExternalConsumerEnvironment() {
    dependsOn(cleanExternalConsumerGradleUserHome)
    environment("GRADLE_USER_HOME", externalConsumerGradleUserHome.get().asFile.absolutePath)
    unsafeJavaOptionEnvironmentVariables.forEach { environment.remove(it) }
}

val thinConsumerTest by tasks.registering(Exec::class) {
    description = "Runs the staged thin artifact in an external Java 21/25 consumer build."
    group = "verification"
    dependsOn(stageExternalConsumerBuild, verifyPublication)
    configureExternalConsumerEnvironment()
    workingDir(layout.projectDirectory)
    commandLine(externalConsumerCommand("thin"))
}

val allConsumerTest by tasks.registering(Exec::class) {
    description = "Runs the staged all classifier in an external Java 21/25 consumer build."
    group = "verification"
    dependsOn(stageExternalConsumerBuild, verifyPublication)
    configureExternalConsumerEnvironment()
    mustRunAfter(thinConsumerTest)
    workingDir(layout.projectDirectory)
    commandLine(externalConsumerCommand("all"))
}

val thinPomConsumerTest by tasks.registering(Exec::class) {
    description = "Runs the staged thin artifact from POM metadata in an external Java 21/25 consumer build."
    group = "verification"
    dependsOn(stageExternalConsumerBuild, verifyPublication)
    configureExternalConsumerEnvironment()
    mustRunAfter(allConsumerTest)
    workingDir(layout.projectDirectory)
    commandLine(externalConsumerCommand("thin", pomOnly = true))
}

val allPomConsumerTest by tasks.registering(Exec::class) {
    description = "Runs the staged all classifier from POM metadata in an external Java 21/25 consumer build."
    group = "verification"
    dependsOn(stageExternalConsumerBuild, verifyPublication)
    configureExternalConsumerEnvironment()
    mustRunAfter(thinPomConsumerTest)
    workingDir(layout.projectDirectory)
    commandLine(externalConsumerCommand("all", pomOnly = true))
}

val thinModuleConsumerTest by tasks.registering(Exec::class) {
    description = "Runs the staged thin artifact as a named module on Java 21/25."
    group = "verification"
    dependsOn(stageExternalConsumerBuild, verifyPublication)
    configureExternalConsumerEnvironment()
    mustRunAfter(allPomConsumerTest)
    workingDir(layout.projectDirectory)
    commandLine(externalConsumerCommand("thin-module"))
}

val allModuleConsumerTest by tasks.registering(Exec::class) {
    description = "Runs the staged all classifier as a named module on Java 21/25."
    group = "verification"
    dependsOn(stageExternalConsumerBuild, verifyPublication)
    configureExternalConsumerEnvironment()
    mustRunAfter(thinModuleConsumerTest)
    workingDir(layout.projectDirectory)
    commandLine(externalConsumerCommand("all-module"))
}

fun TaskProvider<Exec>.retainConsumerEvidence(
    projectName: String,
    evidenceName: String,
    expectedMetadata: String,
) {
    configure {
        val sqliteNativeChecksums = layout.projectDirectory.file("config/sqlite-native-checksums.properties")
        inputs.file(sqliteNativeChecksums).withPathSensitivity(PathSensitivity.RELATIVE)
        doLast {
            val source = externalConsumerBuildDirectory.get().asFile
                .resolve("$projectName/build/reports/consumer/resolved-graph.txt")
            if (!source.isFile || source.length() == 0L) {
                throw GradleException("External consumer produced no graph evidence: $source")
            }
            val contents = source.readText(StandardCharsets.UTF_8)
            if (!contents.lineSequence().any { it == "metadata=$expectedMetadata" }) {
                throw GradleException(
                    "External consumer evidence has the wrong metadata mode; expected $expectedMetadata: $source",
                )
            }
            val expectedArtifactMode = projectName.removeSuffix("-module")
            val expectedLaunchMode = if (projectName.endsWith("-module")) "module" else "classpath"
            val expectedDriverModule = when {
                expectedLaunchMode == "classpath" -> "unnamed"
                expectedArtifactMode == "thin" -> "org.xerial.sqlitejdbc"
                else -> "cqengine"
            }
            val expectedDriverArtifact = if (expectedArtifactMode == "thin") {
                "sqlite-jdbc-${libs.versions.sqlite.get()}.jar"
            }
            else {
                "cqengine-${project.version}-all.jar"
            }
            val reviewedNativeChecksums = Properties().apply {
                sqliteNativeChecksums.asFile.inputStream().buffered().use(::load)
            }
            val nativeEvidence = listOf(21, 25).associateWith { javaVersion ->
                val nativeSource = externalConsumerBuildDirectory.get().asFile.resolve(
                    "$projectName/build/reports/consumer/sqlite-native-java$javaVersion.properties",
                )
                if (!nativeSource.isFile || nativeSource.length() == 0L) {
                    throw GradleException("External consumer produced no SQLite native evidence: $nativeSource")
                }
                val values = linkedMapOf<String, String>()
                nativeSource.readLines(StandardCharsets.UTF_8).forEachIndexed { index, line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0 || separator == line.lastIndex) {
                        throw GradleException(
                            "Malformed SQLite native evidence at ${nativeSource.name}:${index + 1}",
                        )
                    }
                    val key = line.substring(0, separator)
                    val value = line.substring(separator + 1)
                    if (values.put(key, value) != null) {
                        throw GradleException("Duplicate SQLite native evidence key $key in $nativeSource")
                    }
                }
                val expectedKeys = setOf(
                    "artifact.mode",
                    "java.feature",
                    "launch.mode",
                    "native.extracted",
                    "native.extracted.sha256",
                    "native.folder",
                    "native.loaded",
                    "native.resource",
                    "native.resource.sha256",
                    "os.arch",
                    "os.name",
                    "sqlite.compile-option.ENABLE_FTS5",
                    "sqlite.compile-option.THREADSAFE_1",
                    "sqlite.compile-options.count",
                    "sqlite.compile-options.sha256",
                    "sqlite.driver.artifact",
                    "sqlite.driver.module",
                    "sqlite.driver.version",
                    "sqlite.integrity",
                    "sqlite.version",
                    "status",
                )
                if (values.keys != expectedKeys) {
                    throw GradleException(
                        "Unexpected SQLite native evidence keys in $nativeSource: ${values.keys}",
                    )
                }
                val exactValues = mapOf(
                    "artifact.mode" to expectedArtifactMode,
                    "java.feature" to javaVersion.toString(),
                    "launch.mode" to expectedLaunchMode,
                    "native.extracted" to "true",
                    "native.loaded" to "true",
                    "sqlite.compile-option.ENABLE_FTS5" to "true",
                    "sqlite.compile-option.THREADSAFE_1" to "true",
                    "sqlite.driver.artifact" to expectedDriverArtifact,
                    "sqlite.driver.module" to expectedDriverModule,
                    "sqlite.driver.version" to libs.versions.sqlite.get(),
                    "sqlite.integrity" to "ok",
                    "sqlite.version" to libs.versions.sqlite.get().substringBeforeLast('.'),
                    "status" to "verified",
                )
                exactValues.forEach { (key, expected) ->
                    if (values[key] != expected) {
                        throw GradleException(
                            "SQLite native evidence $key must be $expected in $nativeSource, found ${values[key]}",
                        )
                    }
                }
                val nativeResource = values.getValue("native.resource")
                if (!nativeResource.matches(
                        Regex(
                            "org/sqlite/native/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+/" +
                                "(?:lib)?sqlitejdbc[.](?:so|dylib|dll)",
                        ),
                    )
                ) {
                    throw GradleException("Unexpected current-platform SQLite resource: $nativeResource")
                }
                val resourceSha256 = values.getValue("native.resource.sha256")
                if (!resourceSha256.matches(Regex("[0-9a-f]{64}")) ||
                    values["native.extracted.sha256"] != resourceSha256
                ) {
                    throw GradleException(
                        "Extracted SQLite native does not match its resource in $nativeSource",
                    )
                }
                if (values.getValue("native.folder") != nativeResource
                        .removePrefix("org/sqlite/native/")
                        .substringBeforeLast('/')
                ) {
                    throw GradleException("SQLite native folder does not match its resource in $nativeSource")
                }
                if (reviewedNativeChecksums.getProperty(nativeResource) != resourceSha256) {
                    throw GradleException(
                        "Current-platform SQLite native does not match the reviewed checksum in $nativeSource",
                    )
                }
                if (!values.getValue("sqlite.compile-options.sha256").matches(Regex("[0-9a-f]{64}")) ||
                    values.getValue("sqlite.compile-options.count").toIntOrNull()?.let { it > 0 } != true
                ) {
                    throw GradleException("Invalid SQLite compile-option evidence in $nativeSource")
                }
                if (values.getValue("os.name").isBlank() || values.getValue("os.arch").isBlank()) {
                    throw GradleException("SQLite native evidence has no host OS/architecture in $nativeSource")
                }
                values.toSortedMap()
            }
            val destination = layout.buildDirectory.file("reports/consumer/$evidenceName.txt").get().asFile
            destination.parentFile.mkdirs()
            destination.writeText(
                buildString {
                    append(contents.trimEnd())
                    appendLine()
                    nativeEvidence.forEach { (javaVersion, values) ->
                        values.forEach { (key, value) ->
                            appendLine("sqlite.native.java$javaVersion.$key=$value")
                        }
                    }
                },
                StandardCharsets.UTF_8,
            )
        }
    }
}

thinConsumerTest.retainConsumerEvidence("thin", "thin-gradle", "gradle")
allConsumerTest.retainConsumerEvidence("all", "all-gradle", "gradle")
thinPomConsumerTest.retainConsumerEvidence("thin", "thin-pom", "pom")
allPomConsumerTest.retainConsumerEvidence("all", "all-pom", "pom")
thinModuleConsumerTest.retainConsumerEvidence("thin-module", "thin-module", "gradle")
allModuleConsumerTest.retainConsumerEvidence("all-module", "all-module", "gradle")

tasks.register("consumerTest") {
    description = "Runs isolated thin and all classpath and module-path external consumer suites."
    group = "verification"
    dependsOn(
        thinConsumerTest,
        allConsumerTest,
        thinPomConsumerTest,
        allPomConsumerTest,
        thinModuleConsumerTest,
        allModuleConsumerTest,
    )
}

tasks.register("concurrencySmoke") {
    description = "Runs the short JCStress and concurrent read/write soak lanes."
    group = "verification"
    dependsOn(":stress-tests:concurrencySmoke")
}

val concurrencyQualification by tasks.registering(Sync::class) {
    description = "Runs full dual-JDK JCStress and the fixed-duration soak, then stages their reports."
    group = "verification"
    dependsOn(":stress-tests:concurrencyQualification")
    into(layout.buildDirectory.dir("reports/concurrency"))
    from(project(":stress-tests").layout.buildDirectory.file("reports/jcstressJava21/report.txt")) {
        rename { "jcstress-java21.txt" }
    }
    from(project(":stress-tests").layout.buildDirectory.file("reports/jcstressJava25/report.txt")) {
        rename { "jcstress-java25.txt" }
    }
    from(project(":stress-tests").layout.buildDirectory.file("reports/soakQualification/report.properties")) {
        rename { "soak.properties" }
    }
}

tasks.register("jmhSmoke") {
    description = "Runs the short JMH discovery and lifecycle-correctness gate."
    group = "verification"
    dependsOn(":benchmarks:jmhSmoke")
}

tasks.register("jmhBaseline") {
    description = "Runs the release-wrapper-only exact dual-JDK JMH baseline and generates publication views."
    group = "verification"
    dependsOn(":benchmarks:jmhBaseline")
}

tasks.register("jmh") {
    description = "Runs the release-wrapper-only Java 21 and Java 25 JMH baseline."
    group = "benchmark"
    dependsOn(":benchmarks:jmhBaseline")
}

tasks.register("jmhJava21") {
    description = "Runs the report-only Java 21 JMH benchmark suite."
    group = "benchmark"
    dependsOn(":benchmarks:jmhJava21")
}

tasks.register("jmhJava25") {
    description = "Runs the report-only Java 25 JMH benchmark suite."
    group = "benchmark"
    dependsOn(":benchmarks:jmhJava25")
}

tasks.register("syncBenchmarkDocumentation") {
    description = "Copies the last qualified JMH tables and SVGs into tracked benchmark documentation."
    group = "documentation"
    dependsOn(":benchmarks:syncBenchmarkDocumentation")
}

val releaseVersionOverrideSources = provider {
    buildList {
        if (gradle.startParameter.projectProperties.containsKey("version")) add("-Pversion")
        if (gradle.startParameter.systemPropertiesArgs.containsKey("org.gradle.project.version")) {
            add("-Dorg.gradle.project.version")
        }
        if (System.getenv().containsKey("ORG_GRADLE_PROJECT_version")) add("ORG_GRADLE_PROJECT_version")
        val userHomeProperties = gradle.startParameter.gradleUserHomeDir.resolve("gradle.properties")
        if (userHomeProperties.isFile) {
            val properties = Properties().apply { userHomeProperties.inputStream().use(::load) }
            if (properties.containsKey("version")) add("GRADLE_USER_HOME/gradle.properties")
        }
    }
}

val releaseVersionCheck by tasks.registering(VerifyReleaseVersion::class) {
    description = "Verifies the fixed release-candidate coordinate across every publishable format."
    group = "verification"
    dependsOn(
        tasks.jar,
        shadowJar,
        tasks.named("sourcesJar"),
        tasks.named("javadocJar"),
        tasks.named("generatePomFileForMavenJavaPublication"),
        tasks.named("generateMetadataFileForMavenJavaPublication"),
        generateReleaseEvidence,
    )
    projectPropertiesFile.set(layout.projectDirectory.file("gradle.properties"))
    expectedGroup.set("io.github.shuaibrao")
    expectedArtifact.set("cqengine")
    expectedVersion.set(
        providers.fileContents(layout.projectDirectory.file("gradle.properties")).asText.map { text ->
            text.lineSequence()
                .map(String::trim)
                .filter { line -> line.startsWith("version=") }
                .map { line -> line.removePrefix("version=") }
                .singleOrNull()
                ?: throw GradleException("gradle.properties must declare exactly one version assignment")
        },
    )
    configuredGroup.set(provider { project.group.toString() })
    configuredArtifact.set(provider { rootProject.name })
    configuredVersion.set(provider { project.version.toString() })
    versionOverrideSources.set(releaseVersionOverrideSources)
    thinJar.set(tasks.jar.flatMap { it.archiveFile })
    allJar.set(shadowJar.flatMap { it.archiveFile })
    sourcesJar.set(tasks.named<Jar>("sourcesJar").flatMap { it.archiveFile })
    javadocJar.set(tasks.named<Jar>("javadocJar").flatMap { it.archiveFile })
    generatedPom.set(layout.buildDirectory.file("publications/mavenJava/pom-default.xml"))
    generatedModuleMetadata.set(layout.buildDirectory.file("publications/mavenJava/module.json"))
    releaseSbomJson.set(
        layout.buildDirectory.file("generated-release-evidence/publishable/cqengine.cdx.json"),
    )
    releaseSbomXml.set(
        layout.buildDirectory.file("generated-release-evidence/publishable/cqengine.cdx.xml"),
    )
    reportFile.set(layout.buildDirectory.file("reports/qualification/release-version.txt"))
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Release coordinate verification must inspect the current invocation") { true }
    mustRunAfter(tasks.clean)
}

val candidateQualification by tasks.registering {
    description = "Runs every version-independent local release-candidate qualification gate."
    group = "verification"
    dependsOn(
        tasks.check,
        "integrationTest",
        "securityCheck",
        "apiCompatibility",
        "verifySpotBugsReview",
        "jmhSmoke",
        "jmhBaseline",
        concurrencyQualification,
        verifyReproducibleBuild,
        "verifySourceAndLegalProvenance",
        "formatRatchetCheck",
        "verifyReleaseInvocation",
    )
}

stageLocalPublication.configure {
    mustRunAfter(candidateQualification)
}
stageLocalReleaseEvidence.configure {
    mustRunAfter(candidateQualification)
}
listOf(
    thinConsumerTest,
    allConsumerTest,
    thinPomConsumerTest,
    allPomConsumerTest,
    thinModuleConsumerTest,
    allModuleConsumerTest,
).forEach { consumerTask ->
    consumerTask.configure { mustRunAfter(candidateQualification) }
}

val localReadinessEvidencePaths = listOf(
    "root:reports/qualification/source-legal-provenance.txt",
    "root:reports/qualification/format-ratchet.txt",
    "root:reports/qualification/release-invocation.txt",
    "root:reports/qualification/release-version.txt",
    "root:reports/osgi-baseline/report.txt",
    "root:reports/test-inventory/test.txt",
    "root:reports/test-inventory/persistenceTestJava25Execution.txt",
    "root:reports/test-inventory/full-test-java25.txt",
    "root:reports/test-inventory/fastTestJava21Execution.txt",
    "root:reports/test-inventory/persistenceTestJava21Execution.txt",
    "root:reports/test-inventory/full-test-java21.txt",
    "root:reports/jacoco/test/evidence.txt",
    "root:reports/jdk-usage/jdeprscan.txt",
    "root:reports/jdk-usage/jdeps-jdk-internals.txt",
    "root:reports/api-compatibility/report.txt",
    "root:reports/spotbugs/main-inventory.txt",
    "root:reports/spotbugs/review-inventory.txt",
    "root:reports/dependency-check/dependency-check-report.json",
    "root:reports/dependency-license/dependencies.json",
    "root:reports/sbom/cqengine.cdx.json",
    "root:reports/sbom/cqengine.cdx.xml",
    "root:reports/osv/osv-results.json",
    "root:reports/publication/jar-inventory.txt",
    "root:reports/publication/inventory.txt",
    "root:reports/release-evidence/inventory.txt",
    "root:reports/reproducibility/artifact-hashes.txt",
    "root:reports/consumer/thin-gradle.txt",
    "root:reports/consumer/all-gradle.txt",
    "root:reports/consumer/thin-pom.txt",
    "root:reports/consumer/all-pom.txt",
    "root:reports/consumer/thin-module.txt",
    "root:reports/consumer/all-module.txt",
    "root:reports/concurrency/jcstress-java21.txt",
    "root:reports/concurrency/jcstress-java25.txt",
    "root:reports/concurrency/soak.properties",
    "benchmarks:reports/jmh-selection/inventory.txt",
    "benchmarks:reports/jmh-smoke-java21/results.json",
    "benchmarks:reports/jmh-smoke-java21/human.txt",
    "benchmarks:reports/jmh-smoke-java25/results.json",
    "benchmarks:reports/jmh-smoke-java25/human.txt",
    "benchmarks:reports/jmh-query-java21/results.json",
    "benchmarks:reports/jmh-query-java21/human.txt",
    "benchmarks:reports/jmh-query-scenarios-java21/results.json",
    "benchmarks:reports/jmh-query-scenarios-java21/human.txt",
    "benchmarks:reports/jmh-mutation-java21/results.json",
    "benchmarks:reports/jmh-mutation-java21/human.txt",
    "benchmarks:reports/jmh-mutation-allocation-java21/results.json",
    "benchmarks:reports/jmh-mutation-allocation-java21/human.txt",
    "benchmarks:reports/jmh-persistence-java21/results.json",
    "benchmarks:reports/jmh-persistence-java21/human.txt",
    "benchmarks:reports/jmh-concurrency-java21/results.json",
    "benchmarks:reports/jmh-concurrency-java21/human.txt",
    "benchmarks:reports/jmh-latency-java21/results.json",
    "benchmarks:reports/jmh-latency-java21/human.txt",
    "benchmarks:reports/jmh/results.json",
    "benchmarks:reports/jmh/human.txt",
    "benchmarks:reports/jmh-query-scenarios/results.json",
    "benchmarks:reports/jmh-query-scenarios/human.txt",
    "benchmarks:reports/jmh-mutation/results.json",
    "benchmarks:reports/jmh-mutation/human.txt",
    "benchmarks:reports/jmh-mutation-allocation/results.json",
    "benchmarks:reports/jmh-mutation-allocation/human.txt",
    "benchmarks:reports/jmh-persistence/results.json",
    "benchmarks:reports/jmh-persistence/human.txt",
    "benchmarks:reports/jmh-concurrency/results.json",
    "benchmarks:reports/jmh-concurrency/human.txt",
    "benchmarks:reports/jmh-latency-java25/results.json",
    "benchmarks:reports/jmh-latency-java25/human.txt",
    "benchmarks:reports/jmh-baseline/environment.properties",
    "benchmarks:reports/jmh-baseline/summary.txt",
    "benchmarks:reports/jmh-baseline/inventory.txt",
    "benchmarks:reports/jmh-publication/README.md",
    "benchmarks:reports/jmh-publication/allocation.svg",
    "benchmarks:reports/jmh-publication/environment.properties",
    "benchmarks:reports/jmh-publication/query-lifecycle.svg",
    "benchmarks:reports/jmh-publication/query-scenarios.svg",
    "benchmarks:reports/jmh-publication/representative-results.md",
    "benchmarks:reports/jmh-publication/results.csv",
    "benchmarks:reports/jmh-publication/sampled-latency.svg",
    "benchmarks:reports/jmh-publication/source-inputs.sha256",
    "benchmarks:reports/jmh-publication/SHA256SUMS",
)

val generateLocalReadinessManifest by tasks.registering(GenerateLocalReadinessManifest::class) {
    description = "Hashes the complete local qualification, publication and consumer evidence set."
    group = "verification"
    dependsOn(candidateQualification, "consumerTest", verifyPublication, releaseVersionCheck, bndBaseline)
    mustRunAfter(candidateQualification, "consumerTest", verifyPublication, releaseVersionCheck, bndBaseline)
    rootBuildDirectory.set(layout.buildDirectory)
    benchmarkBuildDirectory.set(project(":benchmarks").layout.buildDirectory)
    requiredEvidencePaths.set(localReadinessEvidencePaths)
    publicationCoordinate.set(provider { "${project.group}:cqengine:${project.version}" })
    sourceCommit.set(releaseSourceCommit)
    sourceTree.set(releaseSourceTree)
    java21Runtime.set(java21Launcher.map { launcher ->
        "${launcher.metadata.vendor} ${launcher.metadata.jvmVersion}"
    })
    java25Runtime.set(java25Launcher.map { launcher ->
        "${launcher.metadata.vendor} ${launcher.metadata.jvmVersion}"
    })
    gradleVersion.set(gradle.gradleVersion)
    operatingSystem.set(providers.systemProperty("os.name"))
    architecture.set(providers.systemProperty("os.arch"))
    manifestFile.set(
        layout.buildDirectory.file("local-release-evidence/qualification/local-readiness-manifest.txt"),
    )
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Local-readiness evidence records a fresh qualification time") { true }
}

tasks.register("releaseCheck") {
    description = "Authoritative local release gate; publication and consumers follow qualification."
    group = "verification"
    dependsOn(generateLocalReadinessManifest)
}

allprojects {
    tasks.configureEach {
        if (path != ":verifyReleaseInvocation" && !name.startsWith("clean")) {
            mustRunAfter(rootProject.tasks.named("verifyReleaseInvocation"))
        }
    }
}
