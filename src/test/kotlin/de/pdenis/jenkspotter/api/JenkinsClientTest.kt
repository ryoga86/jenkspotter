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
    fun `stageLog fetches the steps of a stage with their plain-text logs`() {
        handler = { ex ->
            when (ex.requestURI.path) {
                "/job/app/7/execution/node/4/wfapi/describe" -> respond(ex, 200, """{"id":"4","name":"Build","stageFlowNodes":[
                    {"id":"7","name":"Shell Script","parameterDescription":"make all","status":"SUCCESS"},
                    {"id":"8","name":"Print Message","parameterDescription":"done","status":"FAILED"}]}""")
                "/job/app/7/execution/node/7/wfapi/log" -> respond(ex, 200, """{"nodeId":"7","hasMore":true,"text":"+ make all\n<span class=\"x\">a &lt;b&gt; &amp; c</span>\n"}""")
                "/job/app/7/execution/node/8/wfapi/log" -> respond(ex, 404, "")
                else -> respond(ex, 404, "")
            }
        }
        val steps = client().stageLog("$foreignRoot" + "job/app/7/", Stage("4", "Build", "SUCCESS", 0, 0, 0))!!
        assertEquals(listOf("Shell Script", "Print Message"), steps.map { it.name })
        assertEquals("make all", steps[0].description)
        assertEquals("+ make all\na <b> & c\n", steps[0].log)
        assertTrue(steps[0].truncated)
        // A step without a log does not fail the whole stage.
        assertEquals("", steps[1].log)
        assertEquals("FAILED", steps[1].status)
        assertEquals(null, client().stageLog("$foreignRoot" + "job/app/7/", Stage("99", "None", "SUCCESS", 0, 0, 0)))
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

    @Test
    fun `log tail keeps only the end of the log, cut at a line start`() {
        val log = (1..2000).joinToString("") { "line $it\n" }
        handler = { respond(it, 200, log) }
        val tail = client().logTail("$base/job/app/3/", 100)
        assertTrue(tail.length <= 100)
        assertTrue(tail.endsWith("line 1999\nline 2000\n"))
        assertTrue(tail.startsWith("line "))
        assertTrue(requests.single().endsWith("/job/app/3/consoleText"))
        // A log shorter than the limit comes back whole.
        assertEquals(log, client().logTail("$base/job/app/3/", 1_000_000))
    }

    @Test
    fun `a login page instead of JSON is reported as sign-in needed, also for optional data`() {
        handler = { respond(it, 200, "<html><body>Sign in</body></html>", mapOf("Content-Type" to "text/html;charset=utf-8")) }
        val whoAmI = runCatching { client().whoAmI() }.exceptionOrNull() as JenkinsException
        assertEquals(401, whoAmI.statusCode)
        // Stages are optional (plugin may be missing), but a login page must not look like "no stages".
        val stages = runCatching { client().stages("$base/job/app/3/") }.exceptionOrNull() as JenkinsException
        assertEquals(401, stages.statusCode)
    }

    @Test
    fun `redirects are followed, but the token is not sent to another host`() {
        var foreignAuth: String? = "not asked"
        val other = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        other.createContext("/") { ex ->
            foreignAuth = ex.requestHeaders.getFirst("Authorization")
            respond(ex, 200, """{"id":"mallory"}""")
        }
        other.start()
        try {
            handler = { ex ->
                when (ex.requestURI.path) {
                    "/me/api/json" -> respond(ex, 302, "", mapOf("Location" to "/moved/api/json"))
                    "/moved/api/json" -> respond(ex, 200, """{"id":"alice","fullName":"Alice"}""")
                    else -> respond(ex, 302, "", mapOf("Location" to "http://127.0.0.1:${other.address.port}/login"))
                }
            }
            // Same host: followed with the token (the test server answers 401 without it).
            assertEquals("alice", client().whoAmI().id)
            // Other host: followed, without the token.
            client().queueItem("$base/queue/item/42/")
            assertEquals(null, foreignAuth)
        } finally {
            other.stop(0)
        }
    }

    @Test
    fun `stage log falls back to Stage View when a build has no graph data`() {
        handler = { ex ->
            when (ex.requestURI.path) {
                "/job/app/7/execution/node/4/wfapi/describe" -> respond(ex, 200, """{"id":"4","name":"Build","stageFlowNodes":[
                    {"id":"5","name":"Shell Script","status":"SUCCESS","parameterDescription":"make"},
                    {"id":"6","name":"Shell Script","status":"FAILED","parameterDescription":"make test"}]}""")
                "/job/app/7/execution/node/6/wfapi/log" -> respond(ex, 200, """{"text":"boom\n","hasMore":false}""")
                else -> respond(ex, 404, "")
            }
        }
        // Only the failed step is fetched when asked for the broken ones.
        val steps = client().stageLog("$base/job/app/7/", Stage("4", "Build", "FAILED", 0, 0, 0), failedOnly = true, limit = 5)!!
        assertEquals(listOf("6"), steps.map { it.id })
        assertTrue(requests.none { it.contains("/node/5/wfapi/log") })
    }

    @Test
    fun `recent build refs are collected from jobs in nested folders`() {
        handler = {
            respond(it, 200, """{"jobs":[
                {"builds":[{"url":"${foreignRoot}job/a/2/","timestamp":20,"building":true},{"url":"${foreignRoot}job/a/1/","timestamp":10,"building":false}]},
                {"jobs":[{"builds":[{"url":"${foreignRoot}job/team/job/b/5/","timestamp":15,"building":false}]},{"jobs":[]}]}]}""")
        }
        val refs = client().recentBuildRefs(2)
        assertEquals(listOf(BuildRef("$base/job/a/2/", 20, true), BuildRef("$base/job/a/1/", 10, false), BuildRef("$base/job/team/job/b/5/", 15, false)), refs)
        assertTrue(URLDecoder.decode(requests.single(), StandardCharsets.UTF_8).contains("builds[url,timestamp,building]{0,2}"))
    }

    @Test
    fun `a redirect loop is reported as sign-in needed`() {
        handler = { respond(it, 302, "", mapOf("Location" to "/login?from=" + it.requestURI.path.length)) }
        val error = runCatching { client().whoAmI() }.exceptionOrNull() as JenkinsException
        assertEquals(401, error.statusCode)
        assertTrue(requests.size <= 6)
    }

    @Test
    fun `resetting the session drops the cookie that pins requests to one node`() {
        val cookies = mutableListOf<String?>()
        handler = { ex ->
            cookies += ex.requestHeaders.getFirst("Cookie")
            respond(ex, 200, """{"id":"alice"}""", mapOf("Set-Cookie" to "route=node-7; Path=/"))
        }
        val client = client()
        client.whoAmI()
        client.whoAmI()
        client.resetSession()
        client.whoAmI()
        assertEquals(listOf(null, "route=node-7", null), cookies)
    }
}
