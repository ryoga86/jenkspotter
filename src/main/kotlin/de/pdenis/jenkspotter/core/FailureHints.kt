package de.pdenis.jenkspotter.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import de.pdenis.jenkspotter.api.BuildResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Why a build failed, in a few lines: the failed stage (pipelines), failed tests and the telling
 * error lines from the end of its log ([LogErrors.summary]). Loaded in the background the first
 * time it is asked for, e.g. when the mouse rests on a failed build; finished builds do not change,
 * so it is kept.
 */
@Service(Service.Level.APP)
class FailureHints(private val cs: CoroutineScope) {
    sealed class Hint {
        data object Loading : Hint()
        /** [unstable]: the build is unstable, not failed; [tests]: failed tests as "Class.test: message". */
        data class Found(val stage: String?, val lines: List<String>, val unstable: Boolean = false, val tests: List<String> = emptyList(), val failedTests: Int = 0) : Hint()
        data class Unavailable(val reason: String) : Hint()
    }

    private class Entry(val hint: Hint, val at: Long)

    private val hints = ConcurrentHashMap<String, Entry>()

    // A hint costs several requests; two at a time keep a sweep over many red builds from flooding Jenkins.
    // A semaphore instead of Dispatchers.IO.limitedParallelism: the overload the compiler picks with the
    // 2025.2 coroutines does not exist in the coroutines bundled with 2025.1 (NoSuchMethodError there).
    private val loads = Semaphore(2)

    private fun key(build: TrackedBuild) = build.key

    /** What is known without loading anything: null if nothing was asked for yet. */
    fun peek(build: TrackedBuild): Hint? = hints[key(build)]?.hint

    /** The hint for a failed or unstable build; null for others. Starts loading it if not known yet, [loaded] is called when done. */
    fun hint(build: TrackedBuild, loaded: () -> Unit = {}): Hint? {
        val info = build.build
        if (info.building || (info.result != BuildResult.FAILURE && info.result != BuildResult.UNSTABLE)) return null
        val now = System.currentTimeMillis()
        val mine = Entry(Hint.Loading, now)
        // A failed load (Jenkins busy, no connection) is tried again after a while.
        val entry = hints.compute(key(build)) { _, old ->
            if (old == null || (old.hint is Hint.Unavailable && now - old.at > RETRY_MILLIS)) mine else old
        }
        if (entry !== mine) return entry?.hint
        trim()
        cs.launch(Dispatchers.IO) {
            loads.withPermit {
                val hint = runCatching { load(build) }.getOrElse { Hint.Unavailable(it.message ?: it.javaClass.simpleName) }
                hints[key(build)] = Entry(hint, System.currentTimeMillis())
            }
            loaded()
        }
        return Hint.Loading
    }

    /** Forgets the oldest finished entries; loads in flight stay, so they are not started twice. */
    private fun trim() {
        if (hints.size <= MAX_CACHED) return
        hints.entries.filter { it.value.hint !is Hint.Loading }.sortedBy { it.value.at }
            .take(hints.size - MAX_CACHED * 3 / 4).forEach { hints.remove(it.key, it.value) }
    }

    private fun load(build: TrackedBuild): Hint {
        val client = JenkinsConnections.getInstance().client(build.serverId) ?: return Hint.Unavailable("no API token")
        val url = build.build.url
        val unstable = build.build.result == BuildResult.UNSTABLE
        val summarize: (String) -> List<String> = { if (unstable) LogErrors.unstableSummary(it, MAX_LINES) else LogErrors.summary(it, MAX_LINES) }
        // The innermost failed (or unstable) stage says where; its log, if there is one, says why.
        val wanted = if (unstable) setOf("UNSTABLE") else setOf("FAILED", "FAILURE")
        val failed = if (build.build.isPipeline) {
            runCatching { client.stages(url) }.getOrNull()?.flatMap { it.flatten() }?.lastOrNull { it.status in wanted }
        } else {
            null
        }
        // Only the steps that broke: a stage can have hundreds of steps, each with a log of its own.
        val stageLines = failed?.let { stage ->
            runCatching { client.stageLog(url, stage, failedOnly = true, limit = MAX_STEPS) }.getOrNull()
                ?.joinToString("\n") { LogErrors.stripConsoleNotes(it.log) }?.let(summarize)
        }.orEmpty()
        // Failed tests are the usual reason for an unstable build, and often for a failed one.
        val failedTests = runCatching { client.failedTests(url) }.getOrNull().orEmpty()
        val tests = failedTests.take(MAX_LINES).map { t ->
            val message = t.errorDetails?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(120)
            "${t.simpleClassName}.${t.name}" + (message?.let { ": $it" } ?: "")
        }
        // With failed tests listed, the log lines add little for an unstable build.
        val lines = when {
            unstable && tests.isNotEmpty() -> emptyList()
            stageLines.isNotEmpty() -> stageLines
            // The reason for a failure is near the end of the log.
            else -> summarize(LogErrors.stripConsoleNotes(client.logTail(url, TAIL_BYTES)))
        }
        return Hint.Found(failed?.name, lines, unstable, tests, failedTests.size)
    }

    companion object {
        private const val MAX_CACHED = 300
        private const val MAX_LINES = 4
        private const val MAX_STEPS = 5
        private const val TAIL_BYTES = 200_000
        private const val RETRY_MILLIS = 2L * 60 * 1000

        fun getInstance(): FailureHints = service()
    }
}
