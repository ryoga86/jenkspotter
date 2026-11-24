package de.pdenis.jenkspotter.api

import de.pdenis.jenkspotter.api.JenkinsJson.arr
import de.pdenis.jenkspotter.api.JenkinsJson.obj
import de.pdenis.jenkspotter.api.JenkinsJson.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.net.CookieManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

/**
 * Blocking Jenkins REST client for one server. Call it from background threads only.
 *
 * Authentication is Basic with username + API token. Requests authenticated with an API token are
 * exempt from CSRF protection since Jenkins 2.96; a crumb is only fetched if a POST is rejected.
 */
class JenkinsClient(
    baseUrl: String,
    private val username: String,
    private val apiToken: String,
    private val http: HttpClient = defaultHttpClient(),
    private val requestTimeout: Duration = Duration.ofSeconds(30),
) {
    val baseUrl: String = JenkinsPaths.normalizeBase(baseUrl)

    /** Scheme, host and port of the Jenkins: only requests to it carry the API token. */
    private val origin = originOf(URI.create(this.baseUrl))

    private val json = Json { ignoreUnknownKeys = true }
    private val authHeader = "Basic " + Base64.getEncoder()
        .encodeToString("$username:$apiToken".toByteArray(StandardCharsets.UTF_8))

    @Volatile
    private var crumb: Pair<String, String>? = null

    /**
     * Drops cookies and crumb. Polling does not need a session (the token signs every request), but
     * a load balancer may pin one to a Jenkins node by cookie - which keeps failing once that node is gone.
     */
    fun resetSession() {
        http.cookieHandler().ifPresent { (it as? java.net.CookieManager)?.cookieStore?.removeAll() }
        crumb = null
    }

    // ---- identity -------------------------------------------------------------------------

    fun whoAmI(): JenkinsUser {
        val me = getJson("me/api/json?tree=id,fullName")
        val id = me.str("id") ?: throw JenkinsException("Jenkins did not return a user id")
        if (id == "anonymous") throw JenkinsException("Authenticated as anonymous - check user and API token", 401)
        return JenkinsUser(id, me.str("fullName") ?: id)
    }

    // ---- jobs -----------------------------------------------------------------------------

    /**
     * Lists jobs below [folderUrl] (or the root) up to three levels deep in one request.
     * Returns the jobs found plus folders whose children were not part of the response.
     */
    fun listJobsDeep(folderUrl: String? = null): Pair<List<JobRef>, List<JobRef>> {
        val url = (folderUrl?.let { JenkinsPaths.rebase(baseUrl, it) } ?: baseUrl) +
            "api/json?tree=" + encodeTree(JenkinsJson.JOBS_TREE)
        val root = getJsonAbsolute(url)
        val jobs = mutableListOf<JobRef>()
        val unexplored = mutableListOf<JobRef>()
        fun walk(node: JsonObject) {
            node["jobs"].arr()?.forEach { child ->
                val obj = child.obj() ?: return@forEach
                val job = JenkinsJson.parseJob(baseUrl, obj) ?: return@forEach
                jobs += job
                if (obj.containsKey("jobs")) walk(obj) else if (job.isFolder) unexplored += job
            }
        }
        walk(root)
        return jobs to unexplored
    }

    fun recentBuilds(jobUrl: String, limit: Int): List<BuildInfo> {
        val url = JenkinsPaths.rebase(baseUrl, jobUrl) +
            "api/json?tree=" + encodeTree("fullName,builds[${JenkinsJson.BUILD_FIELDS}]{0,$limit}")
        val job = getJsonAbsolute(url)
        val fullName = job.str("fullName")
        return job["builds"].arr()?.mapNotNull { it.obj()?.let { b -> JenkinsJson.parseBuild(baseUrl, b, fullName) } }
            ?: emptyList()
    }

    /** Last build of every job in a view. Nested folders inside the view are not expanded. */
    fun viewLastBuilds(viewNameOrUrl: String): List<BuildInfo> {
        val url = JenkinsPaths.viewUrl(baseUrl, viewNameOrUrl) +
            "api/json?tree=" + encodeTree("jobs[fullName,lastBuild[${JenkinsJson.BUILD_FIELDS}]]")
        val view = getJsonAbsolute(url)
        return view["jobs"].arr()?.mapNotNull { j ->
            val job = j.obj() ?: return@mapNotNull null
            job["lastBuild"].obj()?.let { JenkinsJson.parseBuild(baseUrl, it, job.str("fullName")) }
        } ?: emptyList()
    }

    /**
     * The newest builds of every job (folders four levels deep), URL, start and running state only.
     * Jenkins has to look at every job for it, so ask rarely on a large instance.
     */
    fun recentBuildRefs(perJob: Int): List<BuildRef> {
        val builds = "builds[url,timestamp,building]{0,$perJob}"
        var tree = "jobs[$builds]"
        repeat(3) { tree = "jobs[$builds,${tree}]" }
        val root = getJson("api/json?tree=" + encodeTree(tree))
        val result = mutableListOf<BuildRef>()
        fun walk(node: JsonObject) {
            node["jobs"].arr()?.forEach { child ->
                val job = child.obj() ?: return@forEach
                job["builds"].arr()?.forEach { b ->
                    val build = b.obj() ?: return@forEach
                    val url = build.str("url")?.let { JenkinsPaths.rebase(baseUrl, it) } ?: return@forEach
                    result += BuildRef(url, with(JenkinsJson) { build.long("timestamp") }, with(JenkinsJson) { build.bool("building") })
                }
                walk(job)
            }
        }
        walk(root)
        return result
    }

    fun build(buildUrl: String): BuildInfo {
        val url = JenkinsPaths.rebase(baseUrl, buildUrl) + "api/json?tree=" + encodeTree(JenkinsJson.BUILD_FIELDS)
        return JenkinsJson.parseBuild(baseUrl, getJsonAbsolute(url))
            ?: throw JenkinsException("Unexpected build response for $buildUrl")
    }

    /**
     * Compact view of all running builds: URL plus causes only. Much smaller than [runningBuilds];
     * details are fetched per build only for builds that turn out to be of interest.
     */
    fun runningRefs(): List<RunningRef> {
        val exec = "currentExecutable[url,${JenkinsJson.CAUSE_FIELDS}]"
        val root = getJson("computer/api/json?tree=" + encodeTree("computer[executors[$exec],oneOffExecutors[$exec]]"))
        val result = LinkedHashMap<String, RunningRef>()
        root["computer"].arr()?.forEach { c ->
            val computer = c.obj() ?: return@forEach
            for (key in listOf("executors", "oneOffExecutors")) {
                computer[key].arr()?.forEach { e ->
                    val executable = e.obj()?.get("currentExecutable").obj() ?: return@forEach
                    val url = executable.str("url")?.let { JenkinsPaths.rebase(baseUrl, it) } ?: return@forEach
                    if (!JenkinsPaths.isBuildUrl(url)) return@forEach
                    val (users, upstream) = JenkinsJson.parseCauses(baseUrl, executable)
                    val existing = result[url]
                    // Pipeline "node" placeholders point at the run but carry no causes; keep the richer entry.
                    if (existing == null || (existing.causeUserIds.isEmpty() && existing.upstream.isEmpty())) {
                        result[url] = RunningRef(url, users, upstream)
                    }
                }
            }
        }
        return result.values.toList()
    }

    /** All builds currently occupying an executor, including flyweight (pipeline) executors. */
    fun runningBuilds(): List<BuildInfo> {
        val exec = "currentExecutable[${JenkinsJson.BUILD_FIELDS}]"
        val tree = "computer[executors[$exec],oneOffExecutors[$exec]]"
        val root = getJson("computer/api/json?tree=" + encodeTree(tree))
        val result = LinkedHashMap<String, BuildInfo>()
        root["computer"].arr()?.forEach { c ->
            val computer = c.obj() ?: return@forEach
            for (key in listOf("executors", "oneOffExecutors")) {
                computer[key].arr()?.forEach { e ->
                    val executable = e.obj()?.get("currentExecutable").obj() ?: return@forEach
                    val build = JenkinsJson.parseBuild(baseUrl, executable) ?: return@forEach
                    // Pipeline "node" placeholders point at the run but carry no causes; prefer the richer entry.
                    val existing = result[build.url]
                    if (existing == null || existing.causeUserIds.isEmpty()) result[build.url] = build
                }
            }
        }
        return result.values.toList()
    }

    /** Items currently waiting in the build queue, with the users that caused them. */
    fun queueItems(): List<QueueEntry> {
        val tree = "items[id,url,why,inQueueSince,task[name,url],${JenkinsJson.CAUSE_FIELDS}]"
        val root = getJson("queue/api/json?tree=" + encodeTree(tree))
        return root["items"].arr()?.mapNotNull { i ->
            val item = i.obj() ?: return@mapNotNull null
            val url = item.str("url")?.let { JenkinsPaths.rebase(baseUrl, it) }
                ?: item.str("id")?.let { "${baseUrl}queue/item/$it/" }
                ?: return@mapNotNull null
            val task = item["task"].obj()
            val taskUrl = task?.str("url")?.let { JenkinsPaths.rebase(baseUrl, it) } ?: return@mapNotNull null
            val (users, upstream) = JenkinsJson.parseCauses(baseUrl, item)
            QueueEntry(url, taskUrl, JenkinsPaths.fullNameFromUrl(taskUrl), item.str("why"), item.str("inQueueSince")?.toLongOrNull() ?: 0, users, upstream)
        } ?: emptyList()
    }

    fun parameterDefinitions(jobUrl: String): List<ParameterDefinition> {
        val defs = "parameterDefinitions[name,type,_class,description,choices,projectName,defaultParameterValue[value]]"
        val url = JenkinsPaths.rebase(baseUrl, jobUrl) +
            "api/json?tree=" + encodeTree("property[$defs],actions[$defs]")
        return JenkinsJson.parseParameterDefinitions(getJsonAbsolute(url))
    }

    // ---- build details --------------------------------------------------------------------

    /** Pipeline stages (Pipeline: Stage View plugin); null if not a pipeline or the plugin is missing. */
    fun stages(buildUrl: String): List<Stage>? {
        val build = JenkinsPaths.rebase(baseUrl, buildUrl)
        // Nested stages and parallel branches need the Pipeline Graph View plugin; Stage View lists them flat.
        if (graphView != false) {
            val tree = optional { JenkinsJson.parseStageTree(getJsonAbsolute(build + "stages/tree")) }
            if (tree != null) return tree.also { graphView = true }
        }
        val flat = optional { JenkinsJson.parseStages(getJsonAbsolute(build + "wfapi/describe")) }
        // Only a pipeline that Stage View knows and Graph View does not shows that the plugin is missing;
        // a freestyle or deleted build answers 404 to both.
        if (flat != null && graphView == null) graphView = false
        return flat
    }

    /** Whether the Pipeline Graph View plugin answers; null = not known yet. */
    @Volatile
    private var graphView: Boolean? = null

    /**
     * The steps of one stage with their logs, so a stage's output can be shown on its own. Null if
     * the build has no such stage (or no Pipeline REST API). At most [limit] steps; with
     * [failedOnly] the failed or unstable ones (the last steps if none is marked), which is enough
     * to say why a stage broke without fetching every log.
     */
    fun stageLog(buildUrl: String, stage: Stage, failedOnly: Boolean = false, limit: Int = MAX_STAGE_STEPS): List<StageStep>? {
        val build = JenkinsPaths.rebase(baseUrl, buildUrl)
        fun pick(steps: List<StageStep>): List<StageStep> {
            if (!failedOnly) return steps.take(limit)
            return steps.filter { it.status in BROKEN_STATES }.ifEmpty { steps }.takeLast(limit)
        }
        if (graphView == true) {
            // Steps of the stage and of every stage nested in it, in pipeline order.
            val ids = stage.flatten().map { it.id }.toSet()
            val steps = optional { JenkinsJson.parseAllSteps(getJsonAbsolute(build + "stages/allSteps")) }?.filter { it.stageId in ids }
            // An older build may have no graph data; Stage View still knows it.
            if (steps != null) {
                return pick(steps).map { step ->
                    // Steps without output answer with this text instead of an empty body.
                    val log = optional { getText(build + "stages/log?nodeId=" + encode(step.id)) }.orEmpty()
                    step.copy(log = if (log.trim() == "No logs found") "" else log)
                }
            }
        }
        return optional { wfapiStageLog(build, stage.id, ::pick) }
    }

    private fun wfapiStageLog(build: String, stageId: String, pick: (List<StageStep>) -> List<StageStep>): List<StageStep> {
        val node = build + "execution/node/"
        return pick(JenkinsJson.parseStageSteps(getJsonAbsolute(node + JenkinsPaths.encodeSegment(stageId) + "/wfapi/describe"))).map { step ->
            val (log, truncated) = optional { JenkinsJson.parseStepLog(getJsonAbsolute(node + JenkinsPaths.encodeSegment(step.id) + "/wfapi/log")) } ?: ("" to false)
            step.copy(log = log, truncated = truncated)
        }
    }

    /** Input steps the pipeline waits for; empty if none. */
    fun pendingInputs(buildUrl: String): List<PendingInput> = optional {
        val body = send(request(JenkinsPaths.rebase(baseUrl, buildUrl) + "wfapi/pendingInputActions").GET().build(), expectJson = true).body()
        JenkinsJson.parsePendingInputs(json.parseToJsonElement(body) as kotlinx.serialization.json.JsonArray)
    } ?: emptyList()

    /** Proceeds an input step; [parameters] are only sent for inputs that ask for values. */
    fun proceedInput(buildUrl: String, inputId: String, parameters: Map<String, String>) {
        val build = JenkinsPaths.rebase(baseUrl, buildUrl)
        val id = JenkinsPaths.encodeSegment(inputId)
        if (parameters.isEmpty()) {
            post(build + "input/$id/proceedEmpty", emptyMap())
        } else {
            val params = parameters.entries.joinToString(",", "[", "]") { (k, v) -> """{"name":${quote(k)},"value":${quote(v)}}""" }
            post(build + "wfapi/inputSubmit?inputId=$id", mapOf("json" to """{"parameter":$params}"""))
        }
    }

    fun abortInput(buildUrl: String, inputId: String) {
        post(JenkinsPaths.rebase(baseUrl, buildUrl) + "input/${JenkinsPaths.encodeSegment(inputId)}/abort", emptyMap())
    }

    /** Failed test cases of a build; null if the build has no test report. */
    fun failedTests(buildUrl: String): List<FailedTest>? = optional {
        val cases = "cases[className,name,status,errorDetails,duration]"
        val tree = "suites[$cases],childReports[result[suites[$cases]]]"
        JenkinsJson.parseFailedTests(getJsonAbsolute(JenkinsPaths.rebase(baseUrl, buildUrl) + "testReport/api/json?tree=" + encodeTree(tree)))
    }

    /** Artifacts and commits; fetched on demand only, they are too large for polling. */
    fun details(buildUrl: String): BuildDetails {
        val item = "items[commitId,msg,author[fullName]]"
        val tree = "artifacts[fileName,relativePath],changeSets[$item],changeSet[$item]"
        return JenkinsJson.parseDetails(getJsonAbsolute(JenkinsPaths.rebase(baseUrl, buildUrl) + "api/json?tree=" + encodeTree(tree)))
    }

    fun download(url: String, target: java.nio.file.Path) {
        // No gzip: the body is written to the file as it comes.
        var next = URI.create(JenkinsPaths.rebase(baseUrl, url).removeSuffix("/"))
        repeat(MAX_REDIRECTS) {
            val response = try {
                http.send(request(next.toString(), gzip = false).GET().build(), HttpResponse.BodyHandlers.ofFile(target))
            } catch (e: IOException) {
                throw JenkinsException("Download failed: ${e.message}", cause = e)
            }
            // Artifacts may live elsewhere (e.g. an object store); the token stays with Jenkins.
            next = redirectTarget(response) ?: run {
                if (response.statusCode() !in 200..299) throw JenkinsException("Download failed: HTTP ${response.statusCode()}", response.statusCode())
                return
            }
        }
        throw JenkinsException("Download failed: too many redirects")
    }

    /** Views of the server root and the user's "My Views". */
    fun views(): List<ViewRef> {
        fun read(path: String) = optional {
            getJson(path + "api/json?tree=" + encodeTree("views[name,url]"))["views"].arr()?.mapNotNull { v ->
                val o = v.obj() ?: return@mapNotNull null
                ViewRef(o.str("name") ?: return@mapNotNull null, JenkinsPaths.rebase(baseUrl, o.str("url") ?: return@mapNotNull null))
            }
        }.orEmpty()
        return (read("") + read("me/my-views/")).distinctBy { it.url }
    }

    /**
     * The rendered build form. Fetching it also creates the HTTP session (cookie) that Active
     * Choices cascade updates are bound to, so use the same client for [cascadeChoices].
     */
    fun buildForm(jobUrl: String): BuildForm = optional {
        val url = JenkinsPaths.rebase(baseUrl, jobUrl) + "build?delay=0sec"
        // Jenkins answers GET /build with 405 (only POST builds) but still renders the parameter form.
        BuildFormParser.parse(send(request(url).GET().build(), acceptStatus = setOf(405)).body())
    } ?: BuildForm.EMPTY

    /** New values of an Active Choices cascade parameter for the given referenced values. */
    fun cascadeChoices(form: BuildForm, name: String, referenced: Map<String, String>): FormChoices? {
        val param = form.parameters[name] ?: return null
        val proxy = param.cascade ?: return null
        val base = "${baseUrl}\$stapler/bound/${proxy.proxyId}/"
        val args = proxy.referencedParameters.joinToString("__LESEP__") { "$it=${referenced[it].orEmpty()}" }
        fun call(method: String, body: String): String {
            val builder = request(base + method)
                .header("Content-Type", "application/x-stapler-method-invocation;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
            form.crumb?.let { builder.header("Crumb", it) }
            return send(builder.build()).body()
        }
        call("doUpdate", "[${quote(args)}]")
        val result = json.parseToJsonElement(call("getChoicesForUI", "[]")) as? kotlinx.serialization.json.JsonArray ?: return null
        fun strings(i: Int) = (result.getOrNull(i) as? kotlinx.serialization.json.JsonArray)?.map {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: it.toString()
        }.orEmpty()
        return BuildFormParser.parseCascade(strings(0), strings(1), param.multiple, proxy)
    }

    private inline fun <T> optional(block: () -> T): T? = try {
        block()
    } catch (e: JenkinsException) {
        // A login page is not "feature missing": the caller has to see that sign-in is needed.
        if (e.statusCode != 401 && (e.statusCode == 404 || e.statusCode == 400 || e.cause is kotlinx.serialization.SerializationException ||
                e.cause is ClassCastException)
        ) null else throw e
    } catch (e: ClassCastException) {
        null
    } catch (e: kotlinx.serialization.SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        // A response of another shape than expected, e.g. without stages.
        null
    }

    private fun quote(text: String) = kotlinx.serialization.json.JsonPrimitive(text).toString()

    // ---- actions --------------------------------------------------------------------------

    /** Triggers a build and returns the queue item URL from the Location header. */
    fun trigger(jobUrl: String, parameters: Map<String, String>?): String {
        val job = JenkinsPaths.rebase(baseUrl, jobUrl)
        val response = if (parameters == null) {
            post(job + "build", emptyMap())
        } else {
            post(job + "buildWithParameters", parameters)
        }
        val location = response.headers().firstValue("Location").orElse(null)
            ?: throw JenkinsException("Jenkins accepted the build but returned no queue location", response.statusCode())
        return JenkinsPaths.rebase(baseUrl, location)
    }

    fun queueItem(queueUrl: String): QueueItemState {
        val item = getJsonAbsolute(JenkinsPaths.rebase(baseUrl, queueUrl) + "api/json")
        return QueueItemState(
            executableUrl = item["executable"].obj()?.str("url")?.let { JenkinsPaths.rebase(baseUrl, it) },
            cancelled = item.str("cancelled")?.toBoolean() ?: false,
            why = item.str("why"),
        )
    }

    fun stop(buildUrl: String) {
        post(JenkinsPaths.rebase(baseUrl, buildUrl) + "stop", emptyMap())
    }

    fun progressiveLog(buildUrl: String, start: Long): LogChunk {
        val url = JenkinsPaths.rebase(baseUrl, buildUrl) + "logText/progressiveText?start=$start"
        val response = send(request(url).GET().build())
        val next = response.headers().firstValue("X-Text-Size").map { it.toLongOrNull() }.orElse(null) ?: start
        val more = response.headers().firstValue("X-More-Data").map { it.equals("true", true) }.orElse(false)
        return LogChunk(response.body(), next, more)
    }

    /**
     * The last [maxBytes] of a build's console text, cut at a line start. The log is streamed and
     * only its end is kept, so a huge log costs time but no memory.
     */
    fun logTail(buildUrl: String, maxBytes: Int): String {
        val response = sendFollowing(request(JenkinsPaths.rebase(baseUrl, buildUrl) + "consoleText").GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        val code = response.statusCode()
        if (code !in 200..299) {
            response.body().close()
            throw JenkinsException(statusHint(code, response.request().uri()), code)
        }
        val gzip = response.headers().firstValue("Content-Encoding").map { it.equals("gzip", true) }.orElse(false)
        val ring = ByteArray(maxBytes)
        var total = 0L
        try {
            (if (gzip) GZIPInputStream(response.body()) else response.body()).use { input ->
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    for (i in 0 until read) ring[((total + i) % maxBytes).toInt()] = chunk[i]
                    total += read
                }
            }
        } catch (e: IOException) {
            throw JenkinsException("Cannot read the log: ${e.message ?: e.javaClass.simpleName}", cause = e)
        }
        if (total <= maxBytes) return String(ring, 0, total.toInt(), StandardCharsets.UTF_8)
        val start = (total % maxBytes).toInt()
        val text = String(ring.copyOfRange(start, maxBytes) + ring.copyOfRange(0, start), StandardCharsets.UTF_8)
        // The cut falls into the middle of a line (and maybe of a character).
        return text.substringAfter('\n', text)
    }

    // ---- http -----------------------------------------------------------------------------

    private fun getJson(path: String): JsonObject = getJsonAbsolute(baseUrl + path)

    private fun getText(url: String): String = send(request(url).GET().build()).body()

    private fun getJsonAbsolute(url: String): JsonObject {
        val body = send(request(url).header("Accept", "application/json").GET().build(), expectJson = true).body()
        return runCatching { json.parseToJsonElement(body) as JsonObject }.getOrElse {
            throw JenkinsException("Response from $url is not JSON - is the URL pointing to Jenkins?", cause = it)
        }
    }

    private fun post(url: String, form: Map<String, String>): HttpResponse<String> {
        val body = form.entries.joinToString("&") { (k, v) -> encode(k) + "=" + encode(v) }
        fun build(): HttpRequest {
            val builder = request(url)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
            crumb?.let { (field, value) -> builder.header(field, value) }
            return builder.build()
        }
        return try {
            send(build())
        } catch (e: JenkinsException) {
            if (e.statusCode != 403 || crumb != null) throw e
            crumb = fetchCrumb() ?: throw e
            send(build())
        }
    }

    private fun fetchCrumb(): Pair<String, String>? = try {
        val obj = getJson("crumbIssuer/api/json")
        val field = obj.str("crumbRequestField")
        val value = obj.str("crumb")
        if (field != null && value != null) field to value else null
    } catch (_: JenkinsException) {
        null
    }

    /** The API token only goes to the Jenkins itself, never to a host a link or redirect points at. */
    private fun request(url: String, gzip: Boolean = true): HttpRequest.Builder {
        val uri = URI.create(url)
        val builder = HttpRequest.newBuilder(uri).timeout(requestTimeout)
        if (originOf(uri) == origin) builder.header("Authorization", authHeader)
        // JSON from Jenkins compresses ~10x; the JDK client does not negotiate this on its own.
        if (gzip) builder.header("Accept-Encoding", "gzip")
        return builder
    }

    /** Where a redirect response points, null if it is none. */
    private fun redirectTarget(response: HttpResponse<*>): URI? {
        if (response.statusCode() !in REDIRECTS) return null
        return response.headers().firstValue("Location").map { response.request().uri().resolve(it) }.orElse(null)
    }

    /**
     * Sends [request]; GET requests follow redirects here instead of in the HTTP client, so that
     * [request] decides for every hop whether the token is sent along.
     */
    private fun <T> sendFollowing(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        var current = request
        repeat(MAX_REDIRECTS) {
            val response = try {
                http.send(current, handler)
            } catch (e: IOException) {
                throw JenkinsException("Cannot reach ${current.uri().host}: ${e.message ?: e.javaClass.simpleName}", cause = e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw JenkinsException("Request interrupted", cause = e)
            }
            val target = redirectTarget(response)?.takeIf { current.method() == "GET" } ?: return response
            (response.body() as? java.io.Closeable)?.close()
            val next = request(target.toString())
            current.headers().firstValue("Accept").ifPresent { next.header("Accept", it) }
            current = next.GET().build()
        }
        // Data requests are not redirected in circles; a sign-in in front of Jenkins that does not accept the token is.
        throw JenkinsException("${request.uri().host} keeps redirecting instead of answering - sign-in needed, check the API token", 401)
    }

    /**
     * [expectJson]: an HTML answer is the login page of a proxy or single sign-on in front of
     * Jenkins, reported as 401 so callers treat it as "sign-in needed" and not as missing data.
     */
    private fun send(request: HttpRequest, acceptStatus: Set<Int> = emptySet(), expectJson: Boolean = false): HttpResponse<String> {
        val response = sendFollowing(request, GzipStringHandler)
        val code = response.statusCode()
        val html = response.headers().firstValue("Content-Type").map { it.startsWith("text/html", ignoreCase = true) }.orElse(false)
        if (expectJson && html && code in 200..299) {
            throw JenkinsException("${response.request().uri().host} answered with a web page instead of data - sign-in needed, check the API token", 401)
        }
        if (code in 200..399 || code in acceptStatus) return response
        throw JenkinsException(statusHint(code, request.uri()), code)
    }

    private fun statusHint(code: Int, uri: URI) = when (code) {
        401 -> "Unauthorized - check user name and API token"
        403 -> "Forbidden - missing permission"
        404 -> "Not found: $uri"
        else -> "HTTP $code from $uri"
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    // Keep the tree syntax readable for Jenkins but escape characters the URI parser rejects.
    private fun encodeTree(tree: String) = tree
        .replace("[", "%5B").replace("]", "%5D").replace("{", "%7B").replace("}", "%7D")

    /** Decodes gzip responses; Jenkins sends text as UTF-8. */
    private object GzipStringHandler : HttpResponse.BodyHandler<String> {
        override fun apply(info: HttpResponse.ResponseInfo): HttpResponse.BodySubscriber<String> {
            val gzip = info.headers().firstValue("Content-Encoding").map { it.equals("gzip", true) }.orElse(false)
            return HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofByteArray()) { bytes ->
                val raw = if (gzip && bytes.isNotEmpty()) GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } else bytes
                String(raw, StandardCharsets.UTF_8)
            }
        }
    }

    companion object {
        private const val MAX_STAGE_STEPS = 200
        private const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val BROKEN_STATES = setOf("FAILED", "FAILURE", "UNSTABLE")

        private fun originOf(uri: URI): String {
            val port = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
            return "${uri.scheme?.lowercase()}://${uri.host?.lowercase()}:$port"
        }

        /** Redirects are followed by the client itself, see [sendFollowing]. */
        fun defaultHttpClient(configure: HttpClient.Builder.() -> Unit = {}): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(CookieManager())
            .apply(configure)
            .build()
    }
}
