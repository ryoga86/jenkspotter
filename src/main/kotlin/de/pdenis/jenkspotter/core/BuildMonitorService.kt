package de.pdenis.jenkspotter.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import de.pdenis.jenkspotter.api.BuildInfo
import de.pdenis.jenkspotter.api.JenkinsClient
import de.pdenis.jenkspotter.api.JenkinsException
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import de.pdenis.jenkspotter.settings.ServerConfig
import de.pdenis.jenkspotter.settings.SettingsListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Polls every configured Jenkins in its own coroutine.
 *
 * Fast poll (every [ServerConfig.activePollSeconds] while something runs, otherwise every
 * [ServerConfig.discoveryPollSeconds]):
 *  - queue items: own items found in the Jenkins queue plus builds triggered from the IDE are
 *    followed via their queue item until they become a build (also catches very short builds),
 *  - own running builds found via executors and filtered by `causes.userId`,
 *  - watched running builds are refreshed until they finish.
 * Slow poll (every [ServerConfig.idlePollSeconds]): pinned jobs and configured views.
 * Every step is isolated, so e.g. a forbidden executor API does not stop queue tracking.
 */
@Service(Service.Level.APP)
class BuildMonitorService(private val cs: CoroutineScope) {
    private val store = BuildStore()
    private val lock = Any()
    private val queued = ConcurrentHashMap<String, QueuedTrigger>()
    private val statuses = ConcurrentHashMap<String, ServerStatus>()
    private val loops = ConcurrentHashMap<String, Job>()
    private val wakeups = ConcurrentHashMap<String, Channel<Boolean>>()
    private val started = AtomicBoolean(false)
    private val lastLoggedError = ConcurrentHashMap<String, String>()
    private val inputs = ConcurrentHashMap<String, de.pdenis.jenkspotter.api.PendingInput>()
    private val lastInputCheck = ConcurrentHashMap<String, Long>()

    /** Multibranch jobs of the branches checked out in open projects (project key -> server + job). */
    private val branchJobs = ConcurrentHashMap<String, List<Pair<String, de.pdenis.jenkspotter.api.JobRef>>>()

    @Volatile
    private var runningServers: List<ServerConfig> = emptyList()

    @Volatile
    private var knownPins: List<de.pdenis.jenkspotter.settings.PinnedJob> = emptyList()

    @Volatile
    var snapshot: MonitorSnapshot = MonitorSnapshot.EMPTY
        private set

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        ApplicationManager.getApplication().messageBus.connect(cs)
            .subscribe(SettingsListener.TOPIC, SettingsListener {
                // View options, filters and recents change often and need no Jenkins request at all.
                val current = JenkSpotterSettings.getInstance().current
                when {
                    current.servers.filter { it.enabled } != runningServers -> restart()
                    current.pinnedJobs != knownPins -> {
                        knownPins = current.pinnedJobs.map { it.copy() }
                        refresh()
                    }
                }
            })
        restart()
    }

    /** Forces an immediate poll; [includeSlow] also refreshes pinned jobs and views. */
    fun refresh(serverId: String? = null, includeSlow: Boolean = true) {
        val targets = serverId?.let { listOfNotNull(wakeups[it]) } ?: wakeups.values.toList()
        targets.forEach { it.trySend(includeSlow) }
    }

    /** Called by [de.pdenis.jenkspotter.ui.BranchJobs] when the checked-out branch of a project changes. */
    fun setBranchJobs(projectKey: String, jobs: List<Pair<String, de.pdenis.jenkspotter.api.JobRef>>) {
        val previous = branchJobs.put(projectKey, jobs)
        if (previous != jobs) refresh()
    }

    fun removeBranchJobs(projectKey: String) {
        branchJobs.remove(projectKey)
    }

    fun branchJobUrls(projectKey: String): Set<String> = branchJobs[projectKey].orEmpty().map { it.second.url }.toSet()

    /** The input step was answered from the IDE; stop showing it right away. */
    fun inputHandled(build: TrackedBuild) {
        inputs.remove(build.key)
        lastInputCheck.remove(build.key)
        publish()
        refresh(build.serverId, includeSlow = false)
    }

    fun trackQueued(serverId: String, queueUrl: String, jobFullName: String, jobUrl: String) {
        queued[TrackedBuild.keyOf(serverId, queueUrl)] =
            QueuedTrigger(serverId, queueUrl, jobFullName, jobUrl, System.currentTimeMillis(), null, fromIde = true)
        thisLogger().info("JenkSpotter: tracking queue item $queueUrl for $jobFullName")
        publish()
        refresh(serverId, includeSlow = false)
    }

    @Synchronized
    private fun restart() {
        val servers = JenkSpotterSettings.getInstance().activeServers.map(ServerConfig::copy)
        runningServers = servers
        knownPins = JenkSpotterSettings.getInstance().current.pinnedJobs.map { it.copy() }
        loops.values.forEach { it.cancel() }
        loops.clear()
        wakeups.clear()
        val ids = servers.map { it.id }.toSet()
        synchronized(lock) { store.retainServers(ids) }
        statuses.keys.retainAll(ids)
        queued.values.removeIf { it.serverId !in ids }
        for (server in servers) {
            val wake = Channel<Boolean>(Channel.CONFLATED)
            wakeups[server.id] = wake
            loops[server.id] = cs.launch(Dispatchers.IO) { pollLoop(server.id, wake) }
        }
        publish()
    }

    private suspend fun pollLoop(serverId: String, wake: Channel<Boolean>) {
        var failures = 0
        var lastSlowPoll = 0L
        var userId: String? = null
        var forceSlow = true
        while (cs.isActive) {
            val server = JenkSpotterSettings.getInstance().server(serverId)?.takeIf { it.enabled } ?: return
            val errors = mutableListOf<String>()
            try {
                val client = JenkinsConnections.getInstance().client(server)
                    ?: throw JenkinsException("No API token configured")
                if (userId == null) {
                    userId = client.whoAmI().id
                    thisLogger().info("JenkSpotter: ${server.displayName} authenticated as '$userId'")
                }
                val now = System.currentTimeMillis()
                pollFast(server, client, userId, errors)
                if (forceSlow || now - lastSlowPoll >= server.idlePollSeconds * 1000L) {
                    step("pinned jobs / views", errors) { pollSlow(server, client, userId) }
                    lastSlowPoll = now
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e !is JenkinsException) thisLogger().warn("Polling ${server.url} failed", e)
                if ((e as? JenkinsException)?.statusCode == 401) userId = null
                errors += e.message ?: e.javaClass.simpleName
            }
            val error = errors.firstOrNull()
            failures = if (error == null) 0 else failures + 1
            logErrorChange(server, errors)
            val previous = statuses[serverId]
            statuses[serverId] = ServerStatus(
                serverId, error == null, error, userId,
                if (error == null) System.currentTimeMillis() else previous?.lastUpdate ?: 0,
            )
            publish()

            val delaySeconds = nextDelaySeconds(server, failures)
            forceSlow = withTimeoutOrNull(delaySeconds * 1000) { wake.receive() } ?: false
        }
    }

    private inline fun step(name: String, errors: MutableList<String>, block: () -> Unit) {
        try {
            block()
        } catch (e: JenkinsException) {
            if (e.statusCode == 401) throw e
            errors += "$name: ${e.message}"
        }
    }

    private fun pollFast(server: ServerConfig, client: JenkinsClient, userId: String?, errors: MutableList<String>) {
        val finished = mutableListOf<TrackedBuild>()

        fun interesting(users: Set<String>, upstream: List<de.pdenis.jenkspotter.api.UpstreamRef>) =
            (userId != null && users.any { it.equals(userId, ignoreCase = true) }) ||
                synchronized(lock) { store.isTracked(server.id, upstream) }

        // 1. Own items (and follow-ups of tracked builds) waiting in the queue.
        if (server.trackOwnBuilds && userId != null) {
            step("queue", errors) {
                for (entry in client.queueItems()) {
                    if (!interesting(entry.causeUserIds, entry.upstream)) continue
                    val key = TrackedBuild.keyOf(server.id, entry.url)
                    queued.putIfAbsent(
                        key,
                        QueuedTrigger(server.id, entry.url, entry.taskFullName, entry.taskUrl, entry.inQueueSince.takeIf { it > 0 } ?: System.currentTimeMillis(), entry.why, fromIde = false),
                    )
                }
            }
        }

        // 2. Follow queue items until they become a build.
        for (trigger in queued.values.filter { it.serverId == server.id }) {
            val key = TrackedBuild.keyOf(server.id, trigger.queueUrl)
            step("queue item", errors) {
                try {
                    val state = client.queueItem(trigger.queueUrl)
                    when {
                        state.cancelled -> queued.remove(key)
                        state.executableUrl != null -> {
                            val build = client.build(state.executableUrl)
                            queued.remove(key)
                            upsertDiscovered(server.id, build, if (trigger.fromIde) BuildSource.TRIGGERED else BuildSource.OWN, userId)?.let(finished::add)
                        }
                        else -> queued[key] = trigger.copy(why = state.why)
                    }
                } catch (e: JenkinsException) {
                    // Jenkins forgets queue items a few minutes after they started.
                    if (e.statusCode == 404) queued.remove(key) else throw e
                }
            }
        }

        // 3. Running builds: a compact executor listing (URL + causes) decides what is of interest;
        //    full details are only fetched once per newly discovered build.
        val seen = HashSet<String>()
        if (server.trackOwnBuilds && userId != null) {
            step("executors", errors) {
                var pending = client.runningRefs()
                // Repeat so grandchildren are picked up once their parent became tracked in this poll.
                repeat(MAX_CHAIN_DEPTH) {
                    val next = mutableListOf<de.pdenis.jenkspotter.api.RunningRef>()
                    for (ref in pending) {
                        if (!interesting(ref.causeUserIds, ref.upstream)) {
                            next += ref
                            continue
                        }
                        seen += ref.url
                        val known = synchronized(lock) { store.get(server.id, ref.url) }
                        if (known != null && known.build.building) continue
                        upsertDiscovered(server.id, client.build(ref.url), BuildSource.OWN, userId)?.let(finished::add)
                    }
                    if (next.size == pending.size) return@step
                    pending = next
                }
            }
        }

        // 3b. Follow-ups triggered after a build finished ("Build other projects"): these can start
        //     and end between two polls, so check the downstream jobs of recently finished builds.
        val recent = synchronized(lock) { store.recentlyFinishedMine(server.id, DOWNSTREAM_WINDOW_MILLIS) }
        for (parent in recent) {
            val job = JobIndexService.getInstance().jobByUrl(server.id, parent.build.jobUrl) ?: continue
            for (downstream in job.downstream) {
                step("downstream", errors) {
                    try {
                        val url = de.pdenis.jenkspotter.api.JenkinsPaths.jobUrl(client.baseUrl, downstream)
                        for (build in client.recentBuilds(url, 3)) {
                            if (build.upstream.none { it.buildUrl == parent.build.url }) continue
                            if (synchronized(lock) { store.get(server.id, build.url) }?.build?.building == false) continue
                            upsertDiscovered(server.id, build, BuildSource.DOWNSTREAM, userId)?.let(finished::add)
                        }
                    } catch (e: JenkinsException) {
                        if (e.statusCode != 404) throw e
                    }
                }
            }
        }

        // 3c. Running pipelines that may wait for an input step (approval). Checked every few
        //     seconds per build only; the executor listing does not show the paused state.
        step("input", errors) { checkInputs(server, client) }

        // 4. Watched builds that left the executors or are not visible there: ask them directly.
        val stale = synchronized(lock) {
            store.running(server.id).filter { it.build.url !in seen && it.sources.any { s -> s != BuildSource.VIEW } }
        }
        for (tracked in stale) {
            step("build", errors) {
                try {
                    val build = client.build(tracked.build.url)
                    val source = tracked.sources.first { it != BuildSource.VIEW }
                    synchronized(lock) { store.upsert(server.id, build, source, userId) }?.let(finished::add)
                } catch (e: JenkinsException) {
                    if (e.statusCode != 404) throw e
                }
            }
        }
        dispatchFinished(finished)
    }

    private fun checkInputs(server: ServerConfig, client: JenkinsClient) {
        val now = System.currentTimeMillis()
        val running = synchronized(lock) { store.running(server.id) }
            .filter { it.build.isPipeline && (it.mine || BuildSource.PINNED in it.sources || BuildSource.BRANCH in it.sources) }
        val runningKeys = running.map { it.key }.toSet()
        inputs.keys.removeIf { it.startsWith(server.id + " ") && it !in runningKeys }
        for (tracked in running) {
            if (now - (lastInputCheck[tracked.key] ?: 0) < INPUT_CHECK_MILLIS) continue
            lastInputCheck[tracked.key] = now
            val pending = client.pendingInputs(tracked.build.url).firstOrNull()
            if (pending == null) {
                inputs.remove(tracked.key)
            } else if (inputs.put(tracked.key, pending)?.id != pending.id) {
                ApplicationManager.getApplication().messageBus.syncPublisher(BuildMonitorListener.TOPIC).inputRequested(tracked, pending)
            }
        }
    }

    /** Records a newly seen build; builds started by a tracked build are marked as DOWNSTREAM. */
    private fun upsertDiscovered(serverId: String, build: BuildInfo, source: BuildSource, userId: String?): TrackedBuild? =
        synchronized(lock) {
            val effective = if (source == BuildSource.OWN && !build.startedBy(userId) && store.parentOf(serverId, build) != null) {
                BuildSource.DOWNSTREAM
            } else {
                source
            }
            store.upsert(serverId, build, effective, userId)
        }

    /**
     * Seconds until the next poll. While builds run the interval grows with the remaining estimated
     * time (a 20 minute build does not need 5 second polls), and discovery slows down while the
     * IDE is in the background. Failures back off exponentially.
     */
    private fun nextDelaySeconds(server: ServerConfig, failures: Int): Long {
        if (failures > 0) return minOf(server.idlePollSeconds.toLong() shl minOf(failures - 1, 4), MAX_BACKOFF_SECONDS)
        val active = server.activePollSeconds.toLong()
        val queuedHere = queued.values.any { it.serverId == server.id }
        val running = synchronized(lock) { store.running(server.id) }
        val delay = when {
            queuedHere -> active
            running.isNotEmpty() -> {
                val now = System.currentTimeMillis()
                val remaining = running.minOf { r ->
                    val estimate = r.build.estimatedDurationMillis
                    if (estimate <= 0) 0L else (r.build.timestamp + estimate - now).coerceAtLeast(0)
                }
                (remaining / 1000 / 6).coerceIn(active, maxOf(active, MAX_RUNNING_INTERVAL_SECONDS))
            }
            server.trackOwnBuilds -> {
                val discovery = minOf(server.discoveryPollSeconds, server.idlePollSeconds).toLong()
                if (ApplicationManager.getApplication().isActive) discovery else minOf(discovery * 3, server.idlePollSeconds.toLong())
            }
            else -> server.idlePollSeconds.toLong()
        }
        return delay.coerceAtLeast(MIN_INTERVAL_SECONDS)
    }

    private fun pollSlow(server: ServerConfig, client: JenkinsClient, userId: String?) {
        val settings = JenkSpotterSettings.getInstance().current
        val results = mutableListOf<Pair<BuildInfo, BuildSource>>()
        for (pin in settings.pinnedJobs.filter { it.serverId == server.id }) {
            try {
                client.recentBuilds(pin.url, settings.buildsPerPinnedJob).forEach { results += it to BuildSource.PINNED }
            } catch (e: JenkinsException) {
                if (e.statusCode != 404) throw e
            }
        }
        for ((_, job) in branchJobs.values.flatten().filter { it.first == server.id }.distinctBy { it.second.url }) {
            try {
                client.recentBuilds(job.url, 3).forEach { results += it to BuildSource.BRANCH }
            } catch (e: JenkinsException) {
                if (e.statusCode != 404) throw e
            }
        }
        for (view in server.views.filter { it.isNotBlank() }) {
            client.viewLastBuilds(view).forEach { results += it to BuildSource.VIEW }
        }
        val finished = synchronized(lock) {
            store.applySlowPoll(server.id, results, userId).also { store.prune(settings.ownHistoryHours) }
        }
        dispatchFinished(finished)
    }

    private fun logErrorChange(server: ServerConfig, errors: List<String>) {
        val text = errors.joinToString("; ")
        if (lastLoggedError.put(server.id, text) == text) return
        if (text.isEmpty()) thisLogger().info("JenkSpotter: ${server.displayName} polling OK again")
        else thisLogger().info("JenkSpotter: ${server.displayName} polling problems: $text")
    }

    private fun dispatchFinished(finished: List<TrackedBuild>) {
        if (finished.isEmpty()) return
        val publisher = ApplicationManager.getApplication().messageBus.syncPublisher(BuildMonitorListener.TOPIC)
        finished.forEach { publisher.buildFinished(it) }
    }

    private fun publish() {
        val builds = synchronized(lock) { store.all() }
        val running = builds.filter { it.build.building }.map { it.key }.toSet()
        val next = MonitorSnapshot(
            builds = builds,
            queued = queued.values.sortedByDescending { it.since },
            servers = HashMap(statuses),
            // A build that just finished no longer waits, even before the next input check.
            waitingInput = inputs.filterKeys { it in running },
        )
        snapshot = next
        ApplicationManager.getApplication().messageBus.syncPublisher(BuildMonitorListener.TOPIC).snapshotChanged(next)
    }

    /**
     * Writes what JenkSpotter sees to idea.log: identity, own queue items, running builds and the
     * user ids in their causes. Helps when a build does not show up. Blocking, call off the EDT.
     */
    fun diagnose(): String {
        val out = StringBuilder("JenkSpotter diagnostics\n")
        for (server in JenkSpotterSettings.getInstance().activeServers) {
            out.append("== ${server.displayName} (${server.url}) trackOwn=${server.trackOwnBuilds}\n")
            try {
                val client = JenkinsConnections.getInstance().client(server)
                if (client == null) {
                    out.append("  no API token\n"); continue
                }
                val me = client.whoAmI()
                out.append("  whoAmI: id='${me.id}'\n")
                val queue = runCatching { client.queueItems() }
                out.append("  queue: ").append(queue.fold({ q -> "${q.size} items, mine=${q.count { e -> e.causeUserIds.any { it.equals(me.id, true) } }}" }, { "ERROR ${it.message}" })).append('\n')
                queue.getOrNull()?.take(20)?.forEach { out.append("    ${it.taskFullName} users=${it.causeUserIds} why=${it.why}\n") }
                val running = runCatching { client.runningBuilds() }
                out.append("  running: ").append(running.fold({ "${it.size} builds" }, { "ERROR ${it.message}" })).append('\n')
                running.getOrNull()?.take(50)?.forEach { out.append("    ${it.displayName} users=${it.causeUserIds} mine=${it.startedBy(me.id)}\n") }
            } catch (e: Exception) {
                out.append("  ERROR ${e.message}\n")
            }
            val tracked = snapshot.builds.filter { it.serverId == server.id }
            out.append("  tracked: ${tracked.size} builds, ${snapshot.queued.count { it.serverId == server.id }} queued, status=${snapshot.servers[server.id]}\n")
        }
        thisLogger().info(out.toString())
        return out.toString()
    }

    companion object {
        private const val MAX_BACKOFF_SECONDS = 300L
        private const val MIN_INTERVAL_SECONDS = 2L
        private const val MAX_RUNNING_INTERVAL_SECONDS = 30L
        private const val INPUT_CHECK_MILLIS = 8_000L
        private const val MAX_CHAIN_DEPTH = 4
        private const val DOWNSTREAM_WINDOW_MILLIS = 2L * 60 * 1000

        fun getInstance(): BuildMonitorService = service()
    }
}
