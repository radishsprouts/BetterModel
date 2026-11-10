pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()

        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

buildscript {
    dependencies {
        classpath("org.jetbrains.kotlinx:kotlinx-serialization-core:1.12.0")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.codemc.org/repository/maven-public/")
        maven("https://repo.alessiodp.com/releases/")
        maven("https://maven.blamejared.com/") // molang-compiler
        maven("https://repo.purpurmc.org/snapshots")
        maven("https://maven.citizensnpcs.co/repo/")
        maven("https://mvn.lumine.io/repository/maven-public/")
        maven("https://repo.nexomc.com/releases/")
        // for development builds
        maven(url = "https://central.sonatype.com/repository/maven-snapshots/") {
            name = "central-snapshots"
            mavenContent { snapshotsOnly() }
        }
    }
}

rootProject.name = "bettermodel"

val published = setOf(
    "api",
    "api:bukkit-api",

    "core",
    "core:bukkit-core",

    "platform:paper",
)

include(published)
include(
    "purpur",

    //nms
    "nms:v26_R1", //26.1
    "nms:v26_R2", //26.2
    "nms:v26_R3", //26.3

    //test
    "test-plugin"
)

published.forEach { target ->
    findProject(":$target")?.let {
        it.name = "${rootProject.name}-${it.name}"
    }
}
