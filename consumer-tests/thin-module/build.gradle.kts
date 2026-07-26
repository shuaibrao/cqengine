plugins {
    java
}

val cqengineCoordinate: String by rootProject.extra

dependencies {
    implementation(cqengineCoordinate)
}
