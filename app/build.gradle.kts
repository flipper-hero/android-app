plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "hero.flipper.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "hero.flipper.app"
        minSdk = 31
        targetSdk = 35
        versionCode = providers.environmentVariable("ANDROID_VERSION_CODE").orNull?.toIntOrNull() ?: 1
        versionName = providers.environmentVariable("ANDROID_VERSION_NAME").orNull ?: "1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
            buildConfigField("boolean", "FULL_EDITION", "true")
        }
        create("play") {
            dimension = "edition"
            applicationIdSuffix = ".play"
            buildConfigField("boolean", "FULL_EDITION", "false")
        }
    }

    val releaseStore = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
    val releasePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
    if (releaseStore != null && releasePassword != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseStore)
            storePassword = releasePassword
            keyAlias = "flipperhero"
            keyPassword = releasePassword
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
}

dependencies {
    implementation(project(":agent-kit"))
    add("fullImplementation", project(":agent-kit-full"))
    implementation(project(":flipper-kit"))
    implementation("com.squareup.wire:wire-runtime:5.3.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.15.0")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

afterEvaluate {
    tasks.named("testFullDebugUnitTest").configure {
        dependsOn("processFullDebugManifest")
    }
    tasks.named("testPlayDebugUnitTest").configure {
        dependsOn("processPlayDebugManifest")
    }
}
