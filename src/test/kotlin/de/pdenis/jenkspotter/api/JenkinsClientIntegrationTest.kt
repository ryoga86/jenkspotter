package de.pdenis.jenkspotter.api

import de.pdenis.jenkspotter.ItEnv
import de.pdenis.jenkspotter.core.JobIndexService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs against the Docker Jenkins in docker/ (see README). Skipped unless the environment of
 * docker/.env is loaded:
 *   python3 docker/setup_env.py && docker compose -f docker/compose.yml up -d --build
 *   set -a; . docker/.env; set +a; ./gradlew test
 */
class JenkinsClientIntegrationTest {
    private val url: String? = ItEnv.url
    private lateinit var client: JenkinsClient

    @Before
    fun setUp() {
        assumeTrue("docker/.env not loaded (JENKSPOTTER_IT_URL, JENKINS_TEST_TOKEN)", ItEnv.enabled)
        client = JenkinsClient(url!!, ItEnv.USER, ItEnv.token!!)
    }

    @Test
    fun `identity and job crawl including deep folders and branches`() = runBlocking {
        assertEquals("dev", client.whoAmI().id)
        val jobs = JobIndexService.crawl { folder -> client.listJobsDeep(folder?.url) }
        val names = jobs.map { it.fullName }
        assertTrue(names.toString(), "team/deep/deeper/deepest/hidden-job" in names)
        assertTrue(names.toString(), "team/app/feature%2Flogin" in names)
        assertTrue(jobs.all { it.url.startsWith(client.baseUrl) })
        assertEquals(4, jobs.first { it.fullName == "team/hello-params" }.parameterCount)
        assertEquals(0, jobs.first { it.fullName == "always-green" }.parameterCount)
    }

    @Test
    fun `trigger with parameters, follow queue, tail log, see own running build, abort`() {
        val job = JenkinsPaths.jobUrl(client.baseUrl, "team/hello-params")
        val definitions = client.parameterDefinitions(job)
        assertEquals(listOf("GREETING", "LOUD", "TARGET", "NOTES"), definitions.map { it.name })

        val queue = client.trigger(job, mapOf("GREETING" to "Hi", "TARGET" to "staging", "LOUD" to "true"))
        val buildUrl = waitFor { client.queueItem(queue).executableUrl }
        val running = waitFor { client.runningBuilds().firstOrNull { it.url == buildUrl } }
        assertTrue(running.causeUserIds.toString(), "dev" in running.causeUserIds)
        assertEquals("staging", running.parameters.first { it.name == "TARGET" }.value)

        val log = waitFor { client.progressiveLog(buildUrl, 0).takeIf { it.text.contains("Hi from staging") } }
        assertTrue(log.nextOffset > 0)

        client.stop(buildUrl)
        val finished = waitFor { client.build(buildUrl).takeIf { !it.building } }
        assertEquals(BuildResult.ABORTED, finished.result)
    }

    @Test
    fun `password auth needs a crumb for POST and gets one`() {
        assumeTrue("JENKINS_DEV_PASSWORD not set", ItEnv.devPassword != null)
        val passwordClient = JenkinsClient(url!!, ItEnv.USER, ItEnv.devPassword!!)
        val queue = passwordClient.trigger(JenkinsPaths.jobUrl(client.baseUrl, "always-green"), null)
        assertTrue(queue.contains("/queue/item/"))
    }

    @Test
    fun `view returns last builds`() {
        client.trigger(JenkinsPaths.jobUrl(client.baseUrl, "always-green"), null)
        val builds = waitFor { client.viewLastBuilds("JenkSpotter").takeIf { it.isNotEmpty() } }
        assertTrue(builds.any { it.jobFullName == "always-green" })
    }

    @Test
    fun `stages, failed tests, artifacts and input step of a pipeline`() {
        val job = JenkinsPaths.jobUrl(client.baseUrl, "team/release")
        val queue = client.trigger(job, null)
        val buildUrl = waitFor { client.queueItem(queue).executableUrl }
        val input = waitFor(120_000) { client.pendingInputs(buildUrl).firstOrNull() }
        assertEquals("Deploy to production?", input.message)
        assertEquals("Deploy", input.proceedText)

        val stages = client.stages(buildUrl)!!
        assertEquals(listOf("Build", "Test", "Package", "Approve"), stages.map { it.name }.take(4))
        assertTrue(stages.any { it.waitingForInput })

        val failed = client.failedTests(buildUrl)!!
        assertEquals(listOf("com.example.service.OrderServiceTest.rejectsEmptyCart"), failed.map { "${it.className}.${it.name}" })
        assertTrue(failed.single().errorDetails!!.contains("expected"))

        val details = client.details(buildUrl)
        assertEquals(listOf("dist/app.jar"), details.artifacts.map { it.relativePath })
        val target = kotlin.io.path.createTempFile("artifact", ".jar")
        client.download(buildUrl + "artifact/dist/app.jar", target)
        assertEquals("jar", target.toFile().readText())

        client.proceedInput(buildUrl, input.id, emptyMap())
        val done = waitFor(120_000) { client.build(buildUrl).takeIf { !it.building } }
        // The failing test marks the build unstable; the input was passed, so it reached the end.
        assertEquals(BuildResult.UNSTABLE, done.result)
        assertTrue(client.progressiveLog(buildUrl, 0).text.contains("deploying"))
    }

    @Test
    fun `active choices values and cascades through the build form`() {
        val job = JenkinsPaths.jobUrl(client.baseUrl, "team/all-params")
        val defs = client.parameterDefinitions(job)
        assertEquals(ParameterType.ACTIVE_CHOICE, defs.first { it.name == "REGION" }.type)
        assertEquals(ParameterType.CREDENTIALS, defs.first { it.name == "TOKEN" }.type)
        assertEquals(ParameterType.RUN, defs.first { it.name == "UPSTREAM" }.type)
        assertEquals("team/release", defs.first { it.name == "UPSTREAM" }.projectName)

        val form = waitFor { client.buildForm(job).takeIf { it.parameters["REGION"]?.values?.isNotEmpty() == true } }
        assertEquals(listOf("eu-central-1", "us-east-1", "ap-south-1"), form.parameters.getValue("REGION").values)
        val zones = client.cascadeChoices(form, "ZONE", mapOf("REGION" to "ap-south-1"))!!
        assertEquals(listOf("ap-south-1a", "ap-south-1b"), zones.values)

        val queue = client.trigger(job, mapOf("REGION" to "ap-south-1", "FEATURES" to "metrics,logging", "ZONE" to "ap-south-1b", "TOKEN" to "deploy-token"))
        val buildUrl = waitFor { client.queueItem(queue).executableUrl }
        val log = waitFor(60_000) { client.progressiveLog(buildUrl, 0).takeIf { !it.hasMore }?.text }
        assertTrue(log, log.contains("region=ap-south-1 features=metrics,logging zone=ap-south-1b"))
    }

    @Test
    fun `views of the server are listed`() {
        assertTrue(client.views().any { it.name == "JenkSpotter" })
    }

    private fun <T : Any> waitFor(timeoutMillis: Long = 60_000, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            Thread.sleep(1000)
        }
        throw AssertionError("Condition not met within $timeoutMillis ms")
    }
}
