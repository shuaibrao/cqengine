plugins {
    java
}

val cqengineCoordinate: String by rootProject.extra

dependencies {
    implementation(cqengineCoordinate) {
        isTransitive = false
        artifact {
            classifier = "all"
            type = "jar"
        }
    }
}
