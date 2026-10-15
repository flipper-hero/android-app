plugins {
    kotlin("jvm")
    id("com.squareup.wire")
}

wire {
    kotlin {
        includes = listOf("PB.*", "PB_Storage.*", "PB_System.*", "PB_App.*", "PB_Gui.*", "PB_Gpio.*", "PB_Property.*", "PB_Desktop.*")
    }
    sourcePath {
        srcDir("src/main/proto")
        include("*.proto")
    }
}

dependencies {
    implementation("com.squareup.wire:wire-runtime:5.3.1")
}

kotlin {
    jvmToolchain(21)
}
