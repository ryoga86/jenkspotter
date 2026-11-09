package de.pdenis.jenkspotter.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class JenkinsClientTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val requests = CopyOnWriteArrayList<String>()
    private var handler: (HttpExchange) -> Unit = { respond(it, 404, "") }

    // Jenkins reports itself under a different root URL to exercise rebasing.
    private val foreignRoot = "https://jenkins.example.internal/"

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            requests += "${ex.requestMethod} ${ex.requestURI}"
            val expected = "Basic " + Base64.getEncoder().encodeToString("alice:token".toByteArray())
            if (ex.requestHeaders.getFirst("Authorization") != expected) respond(ex, 401, "") else handler(ex)
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() = server.stop(0)

    private fun client(token: String = "token") = JenkinsClient(base, "alice", token)

    private fun respond(ex: HttpExchange, code: Int, body: String, headers: Map<String, String> = emptyMap()) {
        headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
        ex.close()
    }

    @Test
    fun `whoAmI returns user and rejects wrong token`() {
        handler = { respond(it, 200, """{"id":"alice","fullName":"Alice A."}""") }
        assertEquals(JenkinsUser("alice", "Alice A."), client().whoAmI())
        val error = runCatching { client("wrong").whoAmI() }.exceptionOrNull() as JenkinsException
        assertEquals(401, error.statusCode)
    }

    @Test
    fun `listJobsDeep walks nested folders and reports unexplored ones`() {
        handler = {
            respond(
                it, 200,
                """{"jobs":[
                  {"_class":"hudson.model.FreeStyleProject","name":"free","fullName":"free","url":"${foreignRoot}job/free/",
                   "property":[{"_class":"x"},{"_class":"hudson.model.ParametersDefinitionProperty","parameterDefinitions":[{"name":"A"},{"name":"B"}]}]},
                  {"_class":"com.cloudbees.hudson.plugins.folder.Folder","name":"team","fullName":"team","url":"${foreignRoot}job/team/",
                   "jobs":[{"_class":"org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject","name":"app","fullName":"team/app","url":"${foreignRoot}job/team/job/app/",
                     "jobs":[{"_class":"org.jenkinsci.plugins.workflow.job.WorkflowJob","name":"feature%2Fx","fullName":"team/app/feature%2Fx","url":"${foreignRoot}job/team/job/app/job/feature%252Fx/"},
                             {"_class":"com.cloudbees.hudson.plugins.folder.Folder","name":"deep","fullName":"team/app/deep","url":"${foreignRoot}job/team/job/app/job/deep/"}]}]}
                ]}""",
            )
        }
        val (jobs, unexplored) = client().listJobsDeep()
        assertEquals(listOf("free", "team", "team/app", "team/app/feature%2Fx", "team/app/deep"), jobs.map { it.fullName })
        assertEquals(listOf("team/app/deep"), unexplored.map { it.fullName })
        assertEquals(2, jobs[0].parameterCount)
        assertTrue(jobs[0].isParameterized)
        assertEquals(0, jobs[1].parameterCount)
        assertEquals("$base/job/team/job/app/job/feature%252Fx/", jobs[3].url)
        assertTrue(requests.single().contains("tree=jobs%5Bname"))
    }

    @Test
    fun `running builds are parsed with causes and deduplicated`() {
        val build = """{"number":12,"url":"${foreignRoot}job/team/job/app/12/","building":true,"result":null,
            "timestamp":1000,"duration":0,"estimatedDuration":60000,"fullDisplayName":"team » app #12",
            "actions":[{"causes":[{"userId":"alice","shortDescription":"Started by user Alice"}]},{"parameters":[{"name":"DRY","value":true}]},
                       {"_class":"hudson.tasks.junit.TestResultAction","totalCount":10,"failCount":2,"skipCount":1}]}"""
        val placeholder = """{"url":"${foreignRoot}job/team/job/app/12/","number":12}"""
        handler = {
            respond(it, 200, """{"computer":[{"executors":[{"currentExecutable":$placeholder},{"currentExecutable":null}],"oneOffExecutors":[{"currentExecutable":$build}]}]}""")
        }
        val running = client().runningBuilds().single()
        assertEquals("team/app", running.jobFullName)
        assertEquals(BuildResult.RUNNING, running.result)
        assertEquals(setOf("alice"), running.causeUserIds)
        assertEquals(listOf(BuildParameter("DRY", "true")), running.parameters)
        assertEquals(TestCounts(10, 2, 1), running.tests)
        assertEquals("$base/job/team/job/app/", running.jobUrl)
    }

    @Test
    fun `trigger with parameters fetches crumb after 403 and returns queue url`() {
        var crumbSeen = false
        handler = { ex ->
            when {
                ex.requestURI.path == "/crumbIssuer/api/json" ->
                    respond(ex, 200, """{"crumbRequestField":"Jenkins-Crumb","crumb":"abc"}""")
                ex.requestURI.path.endsWith("/buildWithParameters") -> {
                    if (ex.requestHeaders.getFirst("Jenkins-Crumb") != "abc") {
                        respond(ex, 403, "No valid crumb")
                    } else {
                        crumbSeen = true
                        val body = URLDecoder.decode(ex.requestBody.readAllBytes().toString(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
                        assertEquals("BRANCH=feature/x", body)
                        respond(ex, 201, "", mapOf("Location" to "${foreignRoot}queue/item/42/"))
                    }
                }
                else -> respond(ex, 404, "")
            }
        }
        val queue = client().trigger("$base/job/app/", mapOf("BRANCH" to "feature/x"))
        assertTrue(crumbSeen)
        assertEquals("$base/queue/item/42/", queue)
    }

    @Test
    fun `queue item resolves to executable`() {
        handler = { respond(it, 200, """{"cancelled":false,"why":null,"executable":{"number":3,"url":"${foreignRoot}job/app/3/"}}""") }
        val state = client().queueItem("$base/queue/item/42/")
        assertEquals("$base/job/app/3/", state.executableUrl)
        assertFalse(state.cancelled)
    }

    @Test
    fun `progressive log reports offset and more data`() {
        handler = { respond(it, 200, "hello\n", mapOf("X-Text-Size" to "6", "X-More-Data" to "true")) }
        val chunk = client().progressiveLog("$base/job/app/3/", 0)
        assertEquals(LogChunk("hello\n", 6, true), chunk)
        assertTrue(requests.single().endsWith("/job/app/3/logText/progressiveText?start=0"))
    }

    @Test
    fun `parameter definitions are mapped to types`() {
        handler = {
            respond(
                it, 200,
                """{"property":[{},{"parameterDefinitions":[
                  {"name":"DRY","type":"BooleanParameterDefinition","defaultParameterValue":{"value":false}},
                  {"name":"ENV","type":"ChoiceParameterDefinition","choices":["dev","prod"],"defaultParameterValue":{"value":"dev"}},
                  {"name":"SECRET","type":"PasswordParameterDefinition"},
                  {"name":"MSG","type":"StringParameterDefinition","description":"","defaultParameterValue":{"value":"hi"}}]}]}""",
            )
        }
        val defs = client().parameterDefinitions("$base/job/app/")
        assertEquals(
            listOf(ParameterType.BOOLEAN, ParameterType.CHOICE, ParameterType.PASSWORD, ParameterType.STRING),
            defs.map { it.type },
        )
        assertEquals("false", defs[0].defaultValue)
        assertEquals(listOf("dev", "prod"), defs[1].choices)
        assertEquals(null, defs[3].description)
    }
}
