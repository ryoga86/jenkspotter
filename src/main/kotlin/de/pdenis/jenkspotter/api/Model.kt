package de.pdenis.jenkspotter.api

enum class BuildResult {
    SUCCESS, UNSTABLE, FAILURE, ABORTED, NOT_BUILT, RUNNING, UNKNOWN;

    val isFinished: Boolean get() = this != RUNNING

    companion object {
        fun of(result: String?, building: Boolean): BuildResult = when {
            building -> RUNNING
            result == null -> UNKNOWN
            else -> entries.firstOrNull { it.name == result } ?: UNKNOWN
        }
    }
}

data class JenkinsUser(val id: String, val fullName: String)

data class JobRef(
    val name: String,
    /** Slash-separated path including folders, e.g. "team/app/main". */
    val fullName: String,
    val url: String,
    val jobClass: String,
    /** Full names of jobs this job triggers after it finishes (freestyle "Build other projects"). */
    val downstream: List<String> = emptyList(),
    /** Number of build parameters; -1 = unknown (index from an older version). */
    val parameterCount: Int = -1,
    /** Switched off in Jenkins: cannot be started, and is not triggered as a follow-up either. */
    val disabled: Boolean = false,
    /** Git remote URLs: the job's SCM configuration and those its last build checked out. */
    val repos: List<String> = emptyList(),
) {
    val isParameterized: Boolean get() = parameterCount > 0

    /** A job that can be started now (not a folder, not disabled). */
    val canBuild: Boolean get() = isBuildable && !disabled

    val isFolder: Boolean get() = FOLDER_CLASS_HINTS.any { jobClass.contains(it) }
    val isBuildable: Boolean get() = !isFolder

    companion object {
        private val FOLDER_CLASS_HINTS = listOf(
            "Folder", "MultiBranchProject", "OrganizationFolder", "WorkflowMultiBranchProject",
        )
    }
}

data class BuildParameter(val name: String, val value: String?)

data class TestCounts(val total: Int, val failed: Int, val skipped: Int) {
    val passed: Int get() = total - failed - skipped
}

/** The build that started another build (UpstreamCause). */
data class UpstreamRef(val jobUrl: String, val number: Int) {
    val buildUrl: String get() = "$jobUrl$number/"
}

data class BuildInfo(
    val url: String,
    val number: Int,
    val result: BuildResult,
    val timestamp: Long,
    val durationMillis: Long,
    val estimatedDurationMillis: Long,
    val displayName: String,
    val jobFullName: String,
    val jobUrl: String,
    val causeUserIds: Set<String>,
    val causeDescriptions: List<String>,
    val parameters: List<BuildParameter>,
    val upstream: List<UpstreamRef> = emptyList(),
    val description: String? = null,
    val tests: TestCounts? = null,
    val buildClass: String = "",
) {
    val isPipeline: Boolean get() = buildClass.contains("WorkflowRun")

    val building: Boolean get() = result == BuildResult.RUNNING

    /** Jenkins user ids are case-insensitive with most security realms (LDAP, AD, many SSO setups). */
    fun startedBy(userId: String?): Boolean = userId != null && causeUserIds.any { it.equals(userId, ignoreCase = true) }
}

enum class ParameterType {
    BOOLEAN, CHOICE, MULTI_CHOICE, PASSWORD, TEXT, STRING, CREDENTIALS, RUN, FILE,
    /** Active Choices (uno-choice): values are only known from the rendered build form. */
    ACTIVE_CHOICE,
    UNSUPPORTED,
}

data class ParameterDefinition(
    val name: String,
    val type: ParameterType,
    val rawType: String,
    val description: String?,
    val defaultValue: String?,
    val choices: List<String>,
    /** Run parameter: the job whose builds can be chosen. */
    val projectName: String? = null,
) {
    /** Types whose possible values come from the build form instead of the JSON API. */
    val needsForm: Boolean get() = type in setOf(ParameterType.ACTIVE_CHOICE, ParameterType.CREDENTIALS, ParameterType.RUN, ParameterType.UNSUPPORTED)
}

/** Values of one parameter as rendered by Jenkins' build form. */
data class FormChoices(
    val values: List<String>,
    val labels: List<String>,
    val selected: Set<String>,
    val multiple: Boolean,
    /** Active Choices cascade: proxy to ask for new values when referenced parameters change. */
    val cascade: CascadeProxy? = null,
)

data class CascadeProxy(val proxyId: String, val referencedParameters: List<String>)

/** Parsed build form; [crumb] and the HTTP session are needed for cascade updates. */
data class BuildForm(val crumb: String?, val parameters: Map<String, FormChoices>) {
    companion object {
        val EMPTY = BuildForm(null, emptyMap())
    }
}

data class Stage(val id: String, val name: String, val status: String, val startMillis: Long, val durationMillis: Long, val pauseMillis: Long) {
    val result: BuildResult get() = when (status) {
        "SUCCESS" -> BuildResult.SUCCESS
        "FAILED" -> BuildResult.FAILURE
        "UNSTABLE" -> BuildResult.UNSTABLE
        "ABORTED" -> BuildResult.ABORTED
        "NOT_EXECUTED" -> BuildResult.NOT_BUILT
        "IN_PROGRESS", "PAUSED_PENDING_INPUT" -> BuildResult.RUNNING
        else -> BuildResult.UNKNOWN
    }
    val waitingForInput: Boolean get() = status == "PAUSED_PENDING_INPUT"
}

data class InputParameter(val name: String, val type: String, val description: String?, val defaultValue: String?, val choices: List<String>)

data class PendingInput(
    val id: String,
    val message: String,
    val proceedText: String,
    val parameters: List<InputParameter>,
)

data class FailedTest(val className: String, val name: String, val errorDetails: String?, val status: String, val durationSeconds: Double) {
    val simpleClassName: String get() = className.substringAfterLast('.')
}

data class Artifact(val fileName: String, val relativePath: String)

data class Change(val commitId: String, val message: String, val author: String?)

data class BuildDetails(val artifacts: List<Artifact>, val changes: List<Change>)

data class ViewRef(val name: String, val url: String)

data class QueueItemState(
    val executableUrl: String?,
    val cancelled: Boolean,
    val why: String?,
)

data class QueueEntry(
    val url: String,
    val taskUrl: String,
    val taskFullName: String,
    val why: String?,
    val inQueueSince: Long,
    val causeUserIds: Set<String>,
    val upstream: List<UpstreamRef> = emptyList(),
)

/** Minimal executor entry: just enough to decide whether a running build is of interest. */
data class RunningRef(val url: String, val causeUserIds: Set<String>, val upstream: List<UpstreamRef>)

data class LogChunk(val text: String, val nextOffset: Long, val hasMore: Boolean)

class JenkinsException(message: String, val statusCode: Int = -1, cause: Throwable? = null) :
    Exception(message, cause)
