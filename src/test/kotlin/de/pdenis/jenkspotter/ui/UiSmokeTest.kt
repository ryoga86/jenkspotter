package de.pdenis.jenkspotter.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.pdenis.jenkspotter.api.BuildInfo
import de.pdenis.jenkspotter.api.BuildResult
import de.pdenis.jenkspotter.api.ParameterDefinition
import de.pdenis.jenkspotter.api.ParameterType
import de.pdenis.jenkspotter.core.BuildSource
import de.pdenis.jenkspotter.core.TrackedBuild
import de.pdenis.jenkspotter.settings.JenkSpotterConfigurable
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import de.pdenis.jenkspotter.settings.JenkSpotterState
import de.pdenis.jenkspotter.settings.ServerConfig
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.notify.BuildNotifier

/** Builds every UI component once so wiring errors surface in CI instead of in the IDE. */
class UiSmokeTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        JenkSpotterSettings.getInstance().update(JenkSpotterState().apply {
            servers = mutableListOf(ServerConfig().apply { id = "s1"; name = "local"; url = "http://127.0.0.1:1/"; username = "dev" })
            soundEnabled = false
        })
    }

    override fun tearDown() {
        try {
            JenkSpotterSettings.getInstance().update(JenkSpotterState())
        } finally {
            super.tearDown()
        }
    }

    fun `test panels, widget and settings page can be created`() {
        val main = JenkSpotterMainPanel(project)
        val widget = JenkSpotterStatusBarWidget(project)
        Disposer.register(testRootDisposable, main)
        Disposer.register(testRootDisposable, widget)
        // Filters and pins are live settings and must not touch the settings page state.
        JenkSpotterSettings.getInstance().saveFilter(de.pdenis.jenkspotter.settings.RegexFilter().apply { name = "tmp"; pattern = "-tmp$" })
        main.filter.serverId = "s1"
        // Include rule, column changes and view toggles rebuild the UI without errors.
        val settings = JenkSpotterSettings.getInstance()
        settings.saveFilter(de.pdenis.jenkspotter.settings.RegexFilter().apply { pattern = "^team/"; mode = de.pdenis.jenkspotter.settings.FilterMode.INCLUDE })
        settings.updateView { buildColumns = "PARAMETERS,STATUS,SERVER"; showJobPath = false; showFolderEntries = false }
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

        val configurable = JenkSpotterConfigurable()
        assertNotNull(configurable.createComponent())
        assertFalse(configurable.isModified)
        configurable.disposeUIResources()
    }

    fun `test trigger dialog collects typed values`() {
        val defs = listOf(
            ParameterDefinition("LOUD", ParameterType.BOOLEAN, "Boolean", null, "false", emptyList()),
            ParameterDefinition("TARGET", ParameterType.CHOICE, "Choice", "where", "dev", listOf("dev", "prod")),
            ParameterDefinition("SECRET", ParameterType.PASSWORD, "Password", null, null, emptyList()),
            ParameterDefinition("MSG", ParameterType.STRING, "String", null, "hi", emptyList()),
        )
        // Active Choices values come from the rendered form, including multi-select checkboxes.
        val form = de.pdenis.jenkspotter.api.BuildForm(null, mapOf(
            "REGION" to de.pdenis.jenkspotter.api.FormChoices(listOf("eu", "us"), listOf("Europe", "US"), setOf("us"), false),
            "FEATURES" to de.pdenis.jenkspotter.api.FormChoices(listOf("a", "b", "c"), listOf("a", "b", "c"), setOf("a", "c"), true),
        ))
        val all = defs + listOf(
            ParameterDefinition("REGION", ParameterType.ACTIVE_CHOICE, "ChoiceParameter", null, "", emptyList()),
            ParameterDefinition("FEATURES", ParameterType.ACTIVE_CHOICE, "ChoiceParameter", null, "", emptyList()),
        )
        val dialog = TriggerBuildDialog(project, "team / app", all, mapOf("TARGET" to "prod", "SECRET" to "never"), form)
        try {
            assertEquals(mapOf("LOUD" to "false", "TARGET" to "prod", "MSG" to "hi", "REGION" to "us", "FEATURES" to "a,c"), dialog.values())
            assertFalse("SECRET" in dialog.rememberableValues())
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun `test filter dialog builds and previews`() {
        val dialog = RegexFilterDialog(project, null, listOf("team/a-prod", "team/b-dev"))
        try {
            assertEquals("", dialog.result().pattern)
            assertNotNull(dialog.preferredFocusedComponent)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun `test tool window wordmark is rendered by the IDE svg loader`() {
        val icon = com.intellij.openapi.util.IconLoader.getIcon("/icons/toolWindow.svg", javaClass)
        val image = java.awt.image.BufferedImage(icon.iconWidth, icon.iconHeight, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        icon.paintIcon(null, g, 0, 0)
        g.dispose()
        var top = 0
        var bottom = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if ((image.getRGB(x, y) ushr 24) > 80) if (y < image.height / 2) top++ else bottom++
        }
        // The butler head is back; the stripe name is split by the IDE ("Jenk" / "Spotter").
        assertTrue("top=$top bottom=$bottom", top > 10 && bottom > 10)
        assertEquals("Jenk Spotter", JenkSpotterToolWindowFactory.STRIPE_TITLE)
        assertEquals("Jenk Spotter", de.pdenis.jenkspotter.JenkSpotterBundle.message("toolwindow.stripe.JenkSpotter"))
    }

    fun `test job info dialog renders without a server`() {
        val job = de.pdenis.jenkspotter.api.JobRef("app", "team/app", "http://127.0.0.1:1/job/team/job/app/", "hudson.model.FreeStyleProject", listOf("team/next"), 2)
        val hit = de.pdenis.jenkspotter.core.JobHit("s1", job, "app", "team", de.pdenis.jenkspotter.core.JobMatcher.Result(0, emptyList(), emptyList()), false, 0)
        val dialog = JobInfoDialog(project, hit)
        Disposer.dispose(dialog.disposable)
    }

    fun `test folder dialog builds a hide rule`() {
        val dialog = FolderFilterDialog(project)
        try {
            assertNotNull(dialog.preferredFocusedComponent)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun `test wizarding mode overrides texts and falls back`() {
        val settings = JenkSpotterSettings.getInstance()
        settings.update(settings.current.copy().apply { wizardingMode = true })
        assertEquals("Accio Log", JenkSpotterBundle.message("action.openLog"))
        assertEquals("Open in Browser", JenkSpotterBundle.message("action.openInBrowser"))
    }

    fun `test builds finishing together become one summary notification`() {
        fun build(job: String, result: BuildResult) = TrackedBuild("s1", BuildInfo(
            "http://127.0.0.1:1/job/$job/1/", 1, result, 0, 1000, 1000, "$job #1", job,
            "http://127.0.0.1:1/job/$job/", setOf("dev"), emptyList(), emptyList(),
        ), setOf(BuildSource.OWN), mine = true)
        val notifier = BuildNotifier()
        notifier.buildFinished(build("grp-a", BuildResult.SUCCESS))
        notifier.buildFinished(build("grp-b", BuildResult.FAILURE))
        val deadline = System.currentTimeMillis() + 6000
        fun ours() = com.intellij.notification.NotificationsManager.getNotificationsManager()
            .getNotificationsOfType(com.intellij.notification.Notification::class.java, null)
            .filter { it.groupId.startsWith("JenkSpotter") && !it.isExpired && it.content.contains("grp-") }
        while (System.currentTimeMillis() < deadline && ours().isEmpty()) {
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(50)
        }
        val shown = ours()
        assertEquals(1, shown.size)
        // Builds of earlier tests in the same JVM may be merged in as well; ours must be in one summary.
        assertTrue(shown.single().title, Regex("^\\d+ builds finished").containsMatchIn(shown.single().title))
        assertTrue(shown.single().content.contains("grp-a") && shown.single().content.contains("grp-b"))
        shown.forEach { it.expire() }
    }

    fun `test notifier handles a finished build`() {
        val build = BuildInfo(
            "http://127.0.0.1:1/job/a/1/", 1, BuildResult.FAILURE, 0, 1000, 1000, "a #1", "a",
            "http://127.0.0.1:1/job/a/", setOf("dev"), emptyList(), emptyList(),
        )
        BuildNotifier().buildFinished(TrackedBuild("s1", build, setOf(BuildSource.OWN), mine = true))
    }
}
