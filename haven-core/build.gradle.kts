// Haven's plain Java logic: no Android, so it builds and tests on any JVM. The app calls it from
// Kotlin and keeps everything that touches Android (UI, the package manager, settings) itself.
plugins {
    `java-library`
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    // @NotNull/@Nullable, which Kotlin reads, so Java values arrive with the right nullability.
    api("org.jetbrains:annotations:24.1.0")
    testImplementation("junit:junit:4.13.2")
}
