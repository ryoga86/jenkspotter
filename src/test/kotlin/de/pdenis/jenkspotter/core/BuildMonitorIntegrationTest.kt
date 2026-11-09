package de.pdenis.jenkspotter.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.pdenis.jenkspotter.ItEnv
import de.pdenis.jenkspotter.api.BuildResult
import de.pdenis.jenkspotter.api.JenkinsClient
import de.pdenis.jenkspotter.api.JenkinsPaths
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import de.pdenis.jenkspotter.settings.JenkSpotterState
import de.pdenis.jenkspotter.settings.ServerConfig
import de.pdenis.jenkspotter.settings.TokenStore
import java.util.concurrent.CopyOnWriteArrayList

/**
 * End-to-end check of settings -> PasswordSafe -> polling -> transition detection -> message bus
 * against the Docker Jenkins. Does nothing unless the environment of docker/.env is loaded.
 */
class BuildMonitorIntegrationTest : BasePlatformTestCase() {
    private val url: String? = ItEnv.url.takeIf { ItEnv.enabled }
    private val token: String get() = ItEnv.token!!

    fun `test triggered and pinned builds are reported when they finish`() {
        val baseUrl = url ?: return
        val server = ServerConfig().apply {
            name = "docker"; this.url = baseUrl; username = ItEnv.USER; activePollSeconds = 2; discoveryPollSeconds = 3; idlePollSeconds = 30
            views = mutableListOf("JenkSpotter")
        }
        TokenStore.set(server.id, server.username, token)
        val flaky = JenkinsPaths.jobUrl(baseUrl, "team/flaky")
        val state = JenkSpotterState().apply {
            servers = mutableListOf(server)
            soundEnabled = false
            systemNotificationWhenInactive = false
        }
        JenkSpotterSettings.getInstance().update(state)
        JenkSpotterSettings.getInstance().togglePin(server.id, "team/flaky", flaky)

        val finished = CopyOnWriteArrayList<TrackedBuild>()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(BuildMonitorListener.TOPIC, object : BuildMonitorListener {
                override fun buildFinished(build: TrackedBuild) {
                    finished += build
                }
            })
        val monitor = BuildMonitorService.getInstance()
        monitor.ensureStarted()

        // Wait for the first (silent) slow poll so the pinned job is known before we build it.
        waitFor { monitor.snapshot.servers[server.id]?.online == true }

        val client = JenkinsClient(baseUrl, ItEnv.USER, token)
        // Short build started outside the IDE (browser, CLI): must be found via the queue/executors.
        client.trigger(JenkinsPaths.jobUrl(baseUrl, "always-green"), null)
        // Triggered outside the IDE and pinned.
        client.trigger(flaky, null)
        // Triggered from the IDE.
        val paramsUrl = JenkinsPaths.jobUrl(baseUrl, "team/hello-params")
        monitor.trackQueued(server.id, client.trigger(paramsUrl, mapOf("GREETING" to "it")), "team/hello-params", paramsUrl)

        waitFor { finished.any { it.build.jobFullName == "always-green" } }
        val green = finished.first { it.build.jobFullName == "always-green" }
        assertEquals(BuildResult.SUCCESS, green.build.result)
        assertTrue(green.mine)

        waitFor { finished.any { it.build.jobFullName == "team/flaky" } }
        val flakyBuild = finished.first { it.build.jobFullName == "team/flaky" }
        assertTrue(flakyBuild.mine)
        assertTrue(flakyBuild.build.result in setOf(BuildResult.SUCCESS, BuildResult.FAILURE, BuildResult.UNSTABLE))
        waitFor { finished.any { it.build.jobFullName == "team/hello-params" } }
        assertTrue(BuildSource.TRIGGERED in finished.first { it.build.jobFullName == "team/hello-params" }.sources)
        assertEquals("each build must be reported exactly once", finished.size, finished.map { it.key }.toSet().size)

        JenkSpotterSettings.getInstance().update(JenkSpotterState())
        TokenStore.set(server.id, server.username, null)
    }

    fun `test follow-up builds of a chain are tracked as children`() {
        val baseUrl = url ?: return
        val server = ServerConfig().apply {
            name = "docker"; this.url = baseUrl; username = ItEnv.USER; activePollSeconds = 2; discoveryPollSeconds = 3; idlePollSeconds = 30
        }
        TokenStore.set(server.id, server.username, token)
        JenkSpotterSettings.getInstance().update(JenkSpotterState().apply {
            servers = mutableListOf(server); soundEnabled = false; systemNotificationWhenInactive = false
        })
        // The post-build trigger of chain-middle is only known through the job index.
        JobIndexService.getInstance().refreshIfNeeded(server, force = true)
        waitFor { JobIndexService.getInstance().jobByUrl(server.id, JenkinsPaths.jobUrl(baseUrl, "team/chain-middle"))?.downstream?.isNotEmpty() == true }

        val monitor = BuildMonitorService.getInstance()
        monitor.ensureStarted()
        waitFor { monitor.snapshot.servers[server.id]?.online == true }

        val client = JenkinsClient(baseUrl, ItEnv.USER, token)
        val start = JenkinsPaths.jobUrl(baseUrl, "team/chain-start")
        monitor.trackQueued(server.id, client.trigger(start, null), "team/chain-start", start)

        waitFor(120_000) {
            val names = monitor.snapshot.builds.filter { !it.build.building }.map { it.build.jobFullName }
            "team/chain-last" in names && "team/chain-middle" in names && "team/chain-start" in names
        }
        val byName = monitor.snapshot.builds.associateBy { it.build.jobFullName }
        val startBuild = byName.getValue("team/chain-start")
        val middle = byName.getValue("team/chain-middle")
        val last = byName.getValue("team/chain-last")
        assertEquals(startBuild.key, middle.parentKey)
        assertEquals(middle.key, last.parentKey)
        assertTrue(middle.mine && last.mine)
        assertTrue(BuildSource.DOWNSTREAM in last.sources)

        JenkSpotterSettings.getInstance().update(JenkSpotterState())
        TokenStore.set(server.id, server.username, null)
    }

    fun `test waiting input step is reported, can be answered, and disabled servers are not polled`() {
        val baseUrl = url ?: return
        val server = ServerConfig().apply {
            name = "docker"; this.url = baseUrl; username = ItEnv.USER; activePollSeconds = 2; discoveryPollSeconds = 3; idlePollSeconds = 30
        }
        TokenStore.set(server.id, server.username, token)
        JenkSpotterSettings.getInstance().update(JenkSpotterState().apply {
            servers = mutableListOf(server); soundEnabled = false; systemNotificationWhenInactive = false
        })
        val requested = CopyOnWriteArrayList<String>()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(BuildMonitorListener.TOPIC, object : BuildMonitorListener {
                override fun inputRequested(build: TrackedBuild, input: de.pdenis.jenkspotter.api.PendingInput) {
                    requested += input.message
                }
            })
        val monitor = BuildMonitorService.getInstance()
        monitor.ensureStarted()
        waitFor { monitor.snapshot.servers[server.id]?.online == true }

        val client = JenkinsClient(baseUrl, ItEnv.USER, token)
        val job = JenkinsPaths.jobUrl(baseUrl, "team/release")
        monitor.trackQueued(server.id, client.trigger(job, null), "team/release", job)
        waitFor(120_000) { requested.isNotEmpty() }
        assertEquals("Deploy to production?", requested.first())
        val waiting = monitor.snapshot.builds.first { it.key in monitor.snapshot.waitingInput }

        client.abortInput(waiting.build.url, monitor.snapshot.waitingInput.getValue(waiting.key).id)
        waitFor(60_000) { monitor.snapshot.builds.any { it.key == waiting.key && !it.build.building } }
        assertEquals(BuildResult.ABORTED, monitor.snapshot.builds.first { it.key == waiting.key }.build.result)
        assertTrue(waiting.key !in monitor.snapshot.waitingInput)

        // Switching the server off stops polling and removes its builds.
        JenkSpotterSettings.getInstance().setServerEnabled(server.id, false)
        waitFor { monitor.snapshot.builds.none { it.serverId == server.id } }

        JenkSpotterSettings.getInstance().update(JenkSpotterState())
        TokenStore.set(server.id, server.username, null)
    }

    private fun waitFor(timeoutMillis: Long = 90_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(500)
        }
        fail("Condition not met within $timeoutMillis ms")
    }
}
