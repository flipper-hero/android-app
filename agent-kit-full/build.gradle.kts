plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":agent-kit"))
    implementation(project(":flipper-kit"))
    implementation(project(":flipper-proto"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.json:json:20240303")
    implementation("com.squareup.moshi:moshi:1.15.1")
    implementation("com.squareup.wire:wire-moshi-adapter:5.3.1")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
