import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "JenkSpotter"

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.1.21"
    }
}

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.20.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
