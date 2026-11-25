import java.io.File
import java.io.StringReader
import java.util.Properties
import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // Compiled against 2025.2; the older 2025.1 line (sinceBuild) is covered by the plugin verifier.
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        // Current branch -> matching multibranch job.
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
}

// https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    buildSearchableOptions = providers.gradleProperty("buildSearchableOptions").map { it.toBoolean() }.orElse(true)

    pluginConfiguration {
        id = providers.gradleProperty("pluginId")
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        vendor {
            name = providers.gradleProperty("pluginVendor")
            email = providers.gradleProperty("pluginVendorEmail")
            url = providers.gradleProperty("pluginRepositoryUrl")
        }

        // Change notes are taken from CHANGELOG.md
        val changelog = project.changelog
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    // https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
    // CI: environment variables with the PEM contents. Local: file paths + password in ~/.gradle/gradle.properties.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
            .orElse(providers.gradleProperty("jenkspotter.signing.password"))
        certificateChainFile = layout.file(providers.gradleProperty("jenkspotter.signing.certificateChainFile").map(::File))
        privateKeyFile = layout.file(providers.gradleProperty("jenkspotter.signing.privateKeyFile").map(::File))
    }

    // https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
            .orElse(providers.gradleProperty("jenkspotter.publishToken"))
        // A pre-release version like 1.2.3-beta.1 is published to the "beta" channel, everything else to "default"
        channels = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            listOf(pluginVersion.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    // https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html
    pluginVerification {
        // The defaults (compatibility problems, internal and override-only API) plus API that is marked as
        // scheduled for removal: the Marketplace reports it for early access builds, so fail here before an upload
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
        )

        // Known false positive since 2026.3 (verifier 1.411): com.intellij.dvcs.repo.Repository, the super interface
        // of git4idea.repo.GitRepository, moved into a content module of the new "Version Control" plugin, which the
        // verifier does not put on the class path. At runtime GitRepository is loaded by Git4Idea, which sees it.
        // Remove the entry once a verifier release resolves it.
        ignoredProblemsFile = layout.projectDirectory.file("gradle/verifier-ignored-problems.txt")

        ides {
            // Oldest supported line, the compile target and current releases.
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1.7.2")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6.4")
            create(IntelliJPlatformType.IntellijIdea, "2025.3.6.2")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.4")
            create(IntelliJPlatformType.IntellijIdea, "2026.3")
            // Latest early access build (2026.3 = 263 and newer): API is marked as scheduled for removal there first
            select {
                types = listOf(IntelliJPlatformType.IntellijIdea)
                channels = listOf(ProductRelease.Channel.EAP)
                sinceBuild = "263"
            }
        }
    }
}

// https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    versionPrefix = "" // tags and GitHub releases are named like the version (1.0.0), see build.yml
}

tasks {
    publishPlugin {
        dependsOn(patchChangelog)
    }

    // verifyPluginSignature checks the output of signPlugin, but doesn't declare the dependency itself
    verifyPluginSignature {
        dependsOn(signPlugin)
    }
}

// Installs the built plugin into the local IDE. The plugins directory comes from the Gradle
// property jenkspotter.ideaPluginsDir (e.g. in ~/.gradle/gradle.properties) or from the git-ignored
// local.properties in the project root, e.g. on macOS:
//   jenkspotter.ideaPluginsDir=/Users/me/Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins
// Only the JenkSpotter subfolder is synced, other plugins are never touched. Close the IDE first: a
// running IDE reads classes and icons from the jar that is being replaced and fails with zip errors.
// The task refuses to run while an IntelliJ IDEA process is found (override: -Pjenkspotter.installWhileRunning).
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
    val installWhileRunning = providers.gradleProperty("jenkspotter.installWhileRunning").isPresent
    doFirst {
        require(pluginsDir.isPresent) { "Set jenkspotter.ideaPluginsDir in local.properties (see build.gradle.kts)" }
        val launchers = setOf("idea", "idea64", "idea.sh", "idea64.exe")
        val running = ProcessHandle.allProcesses().anyMatch { process ->
            process.info().command().map { File(it).name in launchers }.orElse(false)
        }
        require(installWhileRunning || !running) { "IntelliJ IDEA is running - close it first, then run installToIde again" }
    }
}
