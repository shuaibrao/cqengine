import org.gradle.api.initialization.resolve.RepositoriesMode

val cqengineRepository = providers.gradleProperty("cqengineRepository").orNull
    ?: throw GradleException("Pass -PcqengineRepository=<absolute path to build/local-repository>")
val pomOnly = providers.gradleProperty("pomOnly").map { value ->
    value.toBooleanStrictOrNull()
        ?: throw GradleException("-PpomOnly must be true or false, received: $value")
}.orElse(false)

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "cqengineLocal"
                    url = uri(cqengineRepository)
                    metadataSources {
                        if (pomOnly.get()) {
                            mavenPom()
                            artifact()
                            ignoreGradleMetadataRedirection()
                        }
                        else {
                            gradleMetadata()
                            mavenPom()
                            artifact()
                        }
                    }
                }
            }
            filter {
                includeModule("io.github.shuaibrao", "cqengine")
            }
        }
        mavenCentral {
            content {
                excludeGroup("io.github.shuaibrao")
            }
        }
    }
}

rootProject.name = "cqengine-external-consumers"

include("thin", "all", "thin-module", "all-module")
