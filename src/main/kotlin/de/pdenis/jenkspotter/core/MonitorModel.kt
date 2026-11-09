package de.pdenis.jenkspotter.core

import de.pdenis.jenkspotter.api.BuildInfo
import de.pdenis.jenkspotter.api.BuildResult
import com.intellij.util.messages.Topic

enum class BuildSource { OWN, TRIGGERED, PINNED, VIEW, DOWNSTREAM, BRANCH }

data class TrackedBuild(
    val serverId: String,
    val build: BuildInfo,
    val sources: Set<BuildSource>,
    val mine: Boolean,
    /** Key of the tracked build that started this one, if any. */
    val parentKey: String? = null,
) {
    val key: String get() = keyOf(serverId, build.url)

    companion object {
        /** Builds are keyed per server: two server entries may point to the same Jenkins. */
        fun keyOf(serverId: String, url: String) = "$serverId $url"
    }
}

data class QueuedTrigger(
    val serverId: String,
    val queueUrl: String,
    val jobFullName: String,
    val jobUrl: String,
    val since: Long,
    val why: String?,
    /** False if the item was discovered in the Jenkins queue (started elsewhere by the user). */
    val fromIde: Boolean = true,
)

data class ServerStatus(
    val serverId: String,
    val online: Boolean,
    val error: String?,
    val userId: String?,
    val lastUpdate: Long,
)

data class MonitorSnapshot(
    val builds: List<TrackedBuild>,
    val queued: List<QueuedTrigger>,
    val servers: Map<String, ServerStatus>,
    /** Builds (by [TrackedBuild.key]) whose pipeline waits for an input step. */
    val waitingInput: Map<String, de.pdenis.jenkspotter.api.PendingInput> = emptyMap(),
) {
    val runningCount: Int get() = builds.count { it.build.building } + queued.size

    /** Jobs whose most recent visible build ended red or yellow. */
    val failingCount: Int
        get() = builds.groupBy { it.build.jobUrl }.values.count { group ->
            val latest = group.filter { !it.build.building }.maxByOrNull { it.build.number }
            latest != null && latest.build.result in setOf(BuildResult.FAILURE, BuildResult.UNSTABLE)
        }

    val offlineServers: List<ServerStatus> get() = servers.values.filter { !it.online && it.error != null }

    companion object {
        val EMPTY = MonitorSnapshot(emptyList(), emptyList(), emptyMap())
    }
}

interface BuildMonitorListener {
    /** Called on a background thread. */
    fun snapshotChanged(snapshot: MonitorSnapshot) {}

    /** Called on a background thread once per build that transitioned to a final result. */
    fun buildFinished(build: TrackedBuild) {}

    /** Called on a background thread when a watched pipeline starts waiting for an input step. */
    fun inputRequested(build: TrackedBuild, input: de.pdenis.jenkspotter.api.PendingInput) {}

    companion object {
        @Topic.AppLevel
        val TOPIC = Topic.create("JenkSpotter builds", BuildMonitorListener::class.java)
    }
}
