plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":flipper-proto"))
    implementation("com.squareup.wire:wire-runtime:5.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
