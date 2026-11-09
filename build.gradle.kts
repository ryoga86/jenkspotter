import java.io.File
import java.io.StringReader
import java.util.Properties
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // Compile against the oldest supported line so newer-only APIs are caught at build time.
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").getOrElse("2025.2.6.4"))
        // Current branch -> matching multibranch job.
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "de.pdenis.jenkspotter"
        name = "JenkSpotter"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            // Oldest supported line, the compile target and current releases.
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1.7.2")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6.4")
            create(IntelliJPlatformType.IntellijIdea, "2025.3.6.2")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.4")
        }
    }
    buildSearchableOptions = false
}

// Installs the built plugin into the local IDE. The plugins directory comes from the Gradle
// property jenkspotter.ideaPluginsDir (e.g. in ~/.gradle/gradle.properties) or from the git-ignored
// local.properties in the project root, e.g. on macOS:
//   jenkspotter.ideaPluginsDir=/Users/me/Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins
// Only the JenkSpotter subfolder is synced, other plugins are never touched. Restart the IDE afterwards.
tasks.register<Sync>("installToIde") {
    group = "intellij platform"
    description = "Builds the plugin and installs it into the local IntelliJ IDEA."
    val localProperties = providers.fileContents(layout.projectDirectory.file("local.properties")).asText.map {
        Properties().apply { load(StringReader(it)) }.getProperty("jenkspotter.ideaPluginsDir") ?: ""
    }.map { it.ifBlank { null } }
    val pluginsDir = providers.gradleProperty("jenkspotter.ideaPluginsDir").orElse(localProperties)
    val zip = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
    from(zipTree(zip)) {
        // The ZIP contains a top-level "JenkSpotter/" folder; strip it so it maps onto the target folder.
        eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
    }
    includeEmptyDirs = false
    into(pluginsDir.map { File(it, "JenkSpotter") })
    doFirst {
        require(pluginsDir.isPresent) { "Set jenkspotter.ideaPluginsDir in local.properties (see build.gradle.kts)" }
    }
}
