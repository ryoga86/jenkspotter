package de.pdenis.jenkspotter.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Maps Jenkins JSON to the model. The tree API is used instead of generated serializers because the
 * kotlinx.serialization runtime is provided by the IDE and its version varies between IDE releases.
 */
internal object JenkinsJson {
    const val BUILD_FIELDS =
        "_class,number,url,result,building,timestamp,duration,estimatedDuration,fullDisplayName,description," +
            "actions[causes[userId,shortDescription,upstreamUrl,upstreamBuild],parameters[name,value],totalCount,failCount,skipCount]"
    const val CAUSE_FIELDS = "actions[causes[userId,upstreamUrl,upstreamBuild]]"
    const val JOB_FIELDS = "name,fullName,url,_class,color,disabled,downstreamProjects[fullName],property[parameterDefinitions[name]]," +
        "scm[userRemoteConfigs[url]],lastBuild[actions[remoteUrls]]"
    const val JOBS_TREE = "jobs[$JOB_FIELDS,jobs[$JOB_FIELDS,jobs[$JOB_FIELDS]]]"

    fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    fun JsonObject.bool(key: String): Boolean = str(key)?.toBooleanStrictOrNull() ?: false
    fun JsonObject.long(key: String): Long = str(key)?.toLongOrNull() ?: 0L

    fun parseBuild(base: String, json: JsonObject, jobFullNameHint: String? = null): BuildInfo? {
        val rawUrl = json.str("url") ?: return null
        val url = JenkinsPaths.rebase(base, rawUrl)
        val number = json.str("number")?.toIntOrNull() ?: JenkinsPaths.buildNumberFromUrl(url) ?: return null
        val jobUrl = JenkinsPaths.jobUrlFromBuildUrl(url) ?: return null
        val (userIds, upstream) = parseCauses(base, json)
        val causeTexts = mutableListOf<String>()
        val params = mutableListOf<BuildParameter>()
        json["actions"].arr()?.forEach { action ->
            val a = action.obj() ?: return@forEach
            a["causes"].arr()?.forEach { c -> c.obj()?.str("shortDescription")?.let(causeTexts::add) }
            a["parameters"].arr()?.forEach { p ->
                val param = p.obj() ?: return@forEach
                val name = param.str("name") ?: return@forEach
                params += BuildParameter(name, param["value"]?.let(::valueToString))
            }
        }
        return BuildInfo(
            url = url,
            number = number,
            result = BuildResult.of(json.str("result"), json.bool("building")),
            timestamp = json.long("timestamp"),
            durationMillis = json.long("duration"),
            estimatedDurationMillis = json.long("estimatedDuration"),
            displayName = json.str("fullDisplayName") ?: "#$number",
            jobFullName = jobFullNameHint ?: JenkinsPaths.fullNameFromUrl(jobUrl),
            jobUrl = jobUrl,
            causeUserIds = userIds,
            causeDescriptions = causeTexts,
            parameters = params,
            upstream = upstream,
            description = json.str("description")?.takeIf { it.isNotBlank() },
            buildClass = json.str("_class").orEmpty(),
            tests = json["actions"].arr()?.firstNotNullOfOrNull { a ->
                val o = a.obj() ?: return@firstNotNullOfOrNull null
                val total = o.str("totalCount")?.toIntOrNull() ?: return@firstNotNullOfOrNull null
                TestCounts(total, o.str("failCount")?.toIntOrNull() ?: 0, o.str("skipCount")?.toIntOrNull() ?: 0)
            },
        )
    }

    /** User ids and upstream builds from all CauseActions of a build or queue item. */
    fun parseCauses(base: String, json: JsonObject): Pair<Set<String>, List<UpstreamRef>> {
        val userIds = linkedSetOf<String>()
        val upstream = mutableListOf<UpstreamRef>()
        json["actions"].arr()?.forEach { action ->
            action.obj()?.get("causes").arr()?.forEach { c ->
                val cause = c.obj() ?: return@forEach
                cause.str("userId")?.let(userIds::add)
                val url = cause.str("upstreamUrl")
                val number = cause.str("upstreamBuild")?.toIntOrNull()
                if (url != null && number != null) upstream += UpstreamRef(JenkinsPaths.rebase(base, url), number)
            }
        }
        return userIds to upstream.distinct()
    }

    fun parseJob(base: String, json: JsonObject): JobRef? {
        val name = json.str("name") ?: return null
        val url = json.str("url")?.let { JenkinsPaths.rebase(base, it) } ?: return null
        return JobRef(
            name = name,
            fullName = json.str("fullName") ?: JenkinsPaths.fullNameFromUrl(url),
            url = url,
            jobClass = json.str("_class") ?: "",
            downstream = json["downstreamProjects"].arr()?.mapNotNull { it.obj()?.str("fullName") } ?: emptyList(),
            parameterCount = json["property"].arr()?.sumOf { p -> p.obj()?.get("parameterDefinitions").arr()?.size ?: 0 } ?: 0,
            // "disabled" is not exported by every job type; the ball color is ("disabled", "disabled_anime").
            disabled = json["disabled"]?.toString() == "true" || json.str("color")?.startsWith("disabled") == true,
            repos = (
                json["scm"].obj()?.get("userRemoteConfigs").arr()?.mapNotNull { it.obj()?.str("url") }.orEmpty() +
                    json["lastBuild"].obj()?.get("actions").arr()?.flatMap { a -> a.obj()?.get("remoteUrls").arr()?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty() }.orEmpty()
                ).distinct(),
        )
    }

    fun parseParameterDefinitions(json: JsonObject): List<ParameterDefinition> {
        val definitions = mutableListOf<JsonObject>()
        // Newer Jenkins exposes them under "property", older ones under "actions".
        for (key in listOf("property", "actions")) {
            json[key].arr()?.forEach { holder ->
                holder.obj()?.get("parameterDefinitions").arr()?.forEach { d -> d.obj()?.let(definitions::add) }
            }
        }
        return definitions.distinctBy { it.str("name") }.mapNotNull { d ->
            val name = d.str("name") ?: return@mapNotNull null
            // _class identifies plugin types (uno-choice) whose "type" alone is ambiguous ("ChoiceParameter").
            val rawType = listOfNotNull(d.str("_class")?.takeIf { it.contains("unochoice") }, d.str("type"), d.str("_class")).firstOrNull() ?: ""
            val choices = d["choices"].arr()?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
            ParameterDefinition(
                name = name,
                type = parameterType(rawType, choices),
                rawType = rawType,
                description = d.str("description")?.takeIf { it.isNotBlank() },
                defaultValue = d["defaultParameterValue"].obj()?.get("value")?.let(::valueToString),
                choices = choices,
                projectName = d.str("projectName"),
            )
        }
    }

    private fun parameterType(rawType: String, choices: List<String>): ParameterType = when {
        rawType.contains("unochoice", ignoreCase = true) || rawType in ACTIVE_CHOICE_TYPES -> ParameterType.ACTIVE_CHOICE
        rawType.contains("Credentials", ignoreCase = true) -> ParameterType.CREDENTIALS
        rawType.contains("RunParameter", ignoreCase = true) -> ParameterType.RUN
        rawType.contains("FileParameter", ignoreCase = true) -> ParameterType.FILE
        rawType.contains("Checkbox", ignoreCase = true) || rawType.contains("ExtendedChoice", ignoreCase = true) ||
            rawType.contains("MultiSelect", ignoreCase = true) -> ParameterType.MULTI_CHOICE
        rawType.contains("Boolean", ignoreCase = true) -> ParameterType.BOOLEAN
        rawType.contains("Choice", ignoreCase = true) || choices.isNotEmpty() -> ParameterType.CHOICE
        rawType.contains("Password", ignoreCase = true) -> ParameterType.PASSWORD
        rawType.contains("Text", ignoreCase = true) -> ParameterType.TEXT
        rawType.contains("String", ignoreCase = true) -> ParameterType.STRING
        else -> ParameterType.UNSUPPORTED
    }

    private val ACTIVE_CHOICE_TYPES = setOf("ChoiceParameter", "CascadeChoiceParameter", "DynamicReferenceParameter")

    fun parseStages(json: JsonObject): List<Stage> = json["stages"].arr()?.mapNotNull { s ->
        val o = s.obj() ?: return@mapNotNull null
        Stage(
            o.str("id") ?: return@mapNotNull null, o.str("name").orEmpty(), o.str("status").orEmpty(),
            o.long("startTimeMillis"), o.long("durationMillis"), o.long("pauseDurationMillis"),
        )
    } ?: emptyList()

    /** Nested stages from `stages/tree` (Pipeline Graph View plugin). */
    fun parseStageTree(json: JsonObject): List<Stage> {
        fun parse(node: JsonElement): Stage? {
            val o = node.obj() ?: return null
            return Stage(
                o.str("id") ?: return null, o.str("name").orEmpty(), graphState(o.str("state")),
                o.long("startTimeMillis"), o.long("totalDurationMillis"), o.long("pauseDurationMillis"),
                o["children"].arr()?.mapNotNull(::parse).orEmpty(), o.str("type") == "PARALLEL",
            )
        }
        return json["data"].obj()?.get("stages").arr()?.mapNotNull(::parse) ?: throw IllegalArgumentException("no stages")
    }

    /** Every step of a build from `stages/allSteps` (Pipeline Graph View plugin), without logs. */
    fun parseAllSteps(json: JsonObject): List<StageStep> = json["data"].obj()?.get("steps").arr()?.mapNotNull { s ->
        val o = s.obj() ?: return@mapNotNull null
        StageStep(o.str("id") ?: return@mapNotNull null, o.str("title").orEmpty(), o.str("name").orEmpty(), graphState(o.str("state")), "", false, o.str("stageId").orEmpty())
    } ?: emptyList()

    /** Pipeline Graph View states mapped to the Stage View names [Stage.status] uses. */
    private fun graphState(state: String?): String = when (state?.lowercase()) {
        "success" -> "SUCCESS"
        "failure" -> "FAILED"
        "unstable" -> "UNSTABLE"
        "aborted" -> "ABORTED"
        "not_built", "skipped" -> "NOT_EXECUTED"
        "running" -> "IN_PROGRESS"
        "paused" -> "PAUSED_PENDING_INPUT"
        else -> state.orEmpty().uppercase()
    }

    /** Steps of a stage from `execution/node/<id>/wfapi/describe`, without their logs: id, name, description, status. */
    fun parseStageSteps(json: JsonObject): List<StageStep> = json["stageFlowNodes"].arr()?.mapNotNull { n ->
        val o = n.obj() ?: return@mapNotNull null
        StageStep(o.str("id") ?: return@mapNotNull null, o.str("name").orEmpty(), o.str("parameterDescription").orEmpty(), o.str("status").orEmpty(), "", false)
    } ?: emptyList()

    /** Log text of one step from `execution/node/<id>/wfapi/log`, console notes stripped; and whether it was cut. */
    fun parseStepLog(json: JsonObject): Pair<String, Boolean> = plainLog(json.str("text").orEmpty()) to json.bool("hasMore")

    /** The log text may carry console annotations as HTML; plain text is wanted. */
    fun plainLog(text: String): String {
        if ('<' !in text && '&' !in text) return text
        return text.replace(Regex("<[^>]*>"), "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
    }

    fun parsePendingInputs(array: JsonArray): List<PendingInput> = array.mapNotNull { i ->
        val o = i.obj() ?: return@mapNotNull null
        PendingInput(
            id = o.str("id") ?: return@mapNotNull null,
            message = o.str("message").orEmpty(),
            proceedText = o.str("proceedText") ?: "Proceed",
            parameters = o["inputs"].arr()?.mapNotNull { p ->
                val param = p.obj() ?: return@mapNotNull null
                val definition = param["definition"].obj()
                InputParameter(
                    name = param.str("name") ?: definition?.str("name") ?: return@mapNotNull null,
                    type = param.str("type") ?: definition?.str("type").orEmpty(),
                    description = param.str("description")?.takeIf { it.isNotBlank() },
                    defaultValue = param["definition"].obj()?.get("defaultVal")?.let(::valueToString)
                        ?: param["defaultValue"]?.let(::valueToString),
                    choices = (definition?.get("choices") ?: param["choices"]).arr()?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
                )
            } ?: emptyList(),
        )
    }

    fun parseFailedTests(json: JsonObject): List<FailedTest> {
        val cases = mutableListOf<FailedTest>()
        // Plain JUnit report: suites[cases]; aggregated (matrix/maven) reports: childReports[result[suites]].
        val suites = json["suites"].arr().orEmpty() +
            json["childReports"].arr().orEmpty().flatMap { it.obj()?.get("result").obj()?.get("suites").arr().orEmpty() }
        for (suite in suites) {
            for (c in suite.obj()?.get("cases").arr().orEmpty()) {
                val o = c.obj() ?: continue
                val status = o.str("status").orEmpty()
                if (status != "FAILED" && status != "REGRESSION") continue
                cases += FailedTest(o.str("className").orEmpty(), o.str("name").orEmpty(), o.str("errorDetails"), status, o.str("duration")?.toDoubleOrNull() ?: 0.0)
            }
        }
        return cases
    }

    fun parseDetails(json: JsonObject): BuildDetails {
        val artifacts = json["artifacts"].arr()?.mapNotNull { a ->
            val o = a.obj() ?: return@mapNotNull null
            Artifact(o.str("fileName").orEmpty(), o.str("relativePath") ?: return@mapNotNull null)
        } ?: emptyList()
        // Freestyle: changeSet{items}, Pipeline: changeSets[{items}].
        val sets = json["changeSets"].arr().orEmpty() + listOfNotNull(json["changeSet"].obj())
        val changes = sets.flatMap { set ->
            set.obj()?.get("items").arr().orEmpty().mapNotNull { item ->
                val o = item.obj() ?: return@mapNotNull null
                Change(o.str("commitId").orEmpty(), o.str("msg").orEmpty(), o["author"].obj()?.str("fullName"))
            }
        }
        return BuildDetails(artifacts, changes)
    }

    private fun valueToString(value: JsonElement): String? = when (value) {
        is JsonNull -> null
        is JsonPrimitive -> value.content
        else -> value.toString()
    }
}
