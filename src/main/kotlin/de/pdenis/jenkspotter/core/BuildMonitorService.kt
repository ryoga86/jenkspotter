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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
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

    /** Servers whose "all builds" history still has builds to fetch, see [pollHistory]. */
    private val historyPending = ConcurrentHashMap.newKeySet<String>()

    /** "serverId url" of history builds that could not be fetched -> when; not asked again for a while. */
    private val historyFailed = ConcurrentHashMap<String, Long>()

    /** Multibranch jobs of the branches checked out in open projects (project key -> server + job). */
    private val branchJobs = ConcurrentHashMap<String, List<Pair<String, de.pdenis.jenkspotter.api.JobRef>>>()

    @Volatile
    private var runningServers: List<ServerConfig> = emptyList()

    @Volatile
    private var knownPins: List<de.pdenis.jenkspotter.settings.PinnedJob> = emptyList()

    @Volatile
    private var knownWatched: List<de.pdenis.jenkspotter.settings.PinnedJob> = emptyList()

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
                    current.pinnedJobs != knownPins || current.watchedJobs != knownWatched -> {
                        knownPins = current.pinnedJobs.map { it.copy() }
                        knownWatched = current.watchedJobs.map { it.copy() }
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
        // Only the jobs matter: a project without branch jobs, or one whose jobs were merely re-read, needs no poll.
        fun urls(list: List<Pair<String, de.pdenis.jenkspotter.api.JobRef>>?) = list.orEmpty().map { it.first to it.second.url }.toSet()
        if (urls(previous) != urls(jobs)) refresh()
    }

    fun removeBranchJobs(projectKey: String) {
        branchJobs.remove(projectKey)
    }

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
        knownWatched = JenkSpotterSettings.getInstance().current.watchedJobs.map { it.copy() }
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

    /** Step errors of one poll; [forbidden]: a step was refused, so the sign-in is checked again next time. */
    private class PollErrors : ArrayList<String>() {
        var forbidden = false
        /** A step got a 5xx answer. */
        var serverError = false
    }

    private suspend fun pollLoop(serverId: String, wake: Channel<Boolean>) {
        // The loop's own job: after restart() cancelled it, a poll still in flight must not write any more.
        val job = currentCoroutineContext().job
        var failures = 0
        var lastSlowPoll = 0L
        var lastHistoryPoll = 0L
        var historyCount = -1
        var userId: String? = null
        var loggedUser: String? = null
        var forceSlow = true
        while (job.isActive) {
            val server = JenkSpotterSettings.getInstance().server(serverId)?.takeIf { it.enabled } ?: return
            val errors = PollErrors()
            var unreachable = false
            // Only a poll that failed as a whole backs off; a failing step (e.g. the queue answering
            // 503 once) keeps the normal rhythm, so the light turns green again on the next poll.
            var pollFailed = false
            try {
                val client = JenkinsConnections.getInstance().client(server)
                    ?: throw JenkinsException("No API token configured")
                if (userId == null) {
                    userId = client.whoAmI().id
                    // Asked again after refused steps; only a new answer is worth a log line.
                    if (userId != loggedUser) thisLogger().info("JenkSpotter: ${server.displayName} authenticated as '$userId'")
                    loggedUser = userId
                }
                val now = System.currentTimeMillis()
                pollFast(job, server, client, userId, errors)
                if (forceSlow || now - lastSlowPoll >= server.idlePollSeconds * 1000L) {
                    pollSlow(job, server, client, userId, errors)
                    lastSlowPoll = now
                }
                // The history looks at every job of the Jenkins: on its own, slower rhythm.
                val settings = JenkSpotterSettings.getInstance().current
                val wanted = if (settings.showAllRunning) settings.allBuildsHistory else 0
                val pending = historyPending.remove(serverId)
                if (pending || wanted != historyCount || now - lastHistoryPoll >= HISTORY_INTERVAL_MILLIS) {
                    pollHistory(job, server, client, userId, wanted, errors)
                    lastHistoryPoll = now
                    historyCount = wanted
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e !is JenkinsException) thisLogger().warn("Polling ${server.url} failed", e)
                if ((e as? JenkinsException)?.statusCode == 401) userId = null
                unreachable = e.cause is java.io.IOException
                pollFailed = true
                errors += e.message ?: e.javaClass.simpleName
            }
            if (!job.isActive) return
            // Refused steps may mean the session behind a proxy ended: whoAmI decides on the next poll.
            if (errors.forbidden) userId = null
            // A failed poll or server errors (5xx) may come from a session cookie that pins the requests
            // to a Jenkins node that is gone; without it the next poll starts a fresh session.
            if (pollFailed || errors.serverError) JenkinsConnections.getInstance().resetSessions(serverId)
            failures = if (pollFailed) failures + 1 else 0
            logErrorChange(server, errors)
            val previous = statuses[serverId]
            // Only a poll that failed as a whole means "not connected"; failed parts are warnings.
            statuses[serverId] = ServerStatus(
                serverId,
                online = !pollFailed,
                error = errors.firstOrNull()?.takeIf { pollFailed },
                userId = userId,
                lastUpdate = if (!pollFailed) System.currentTimeMillis() else previous?.lastUpdate ?: 0,
                reachable = !unreachable,
                warnings = if (pollFailed) emptyList() else errors.toList(),
            )
            publish()

            val ended = System.currentTimeMillis()
            val delaySeconds = nextDelaySeconds(server, failures)
            var slow = withTimeoutOrNull(delaySeconds * 1000) { wake.receive() }
            if (slow != null) {
                // Several refresh requests in a row (start-up, a settings change) become one poll.
                delay((MIN_POLL_GAP_MILLIS - (System.currentTimeMillis() - ended)).coerceAtLeast(0))
                while (true) slow = (wake.tryReceive().getOrNull() ?: break) || slow == true
            }
            forceSlow = slow ?: false
        }
    }

    private inline fun step(job: Job, name: String, errors: PollErrors, block: () -> Unit) {
        if (!job.isActive) throw CancellationException("polling stopped")
        try {
            block()
        } catch (e: JenkinsException) {
            // Unauthorized or unreachable: the whole poll failed, not just this step.
            if (e.statusCode == 401 || e.cause is java.io.IOException) throw e
            if (e.statusCode == 403) errors.forbidden = true
            if (e.statusCode >= 500) errors.serverError = true
            errors += "$name: ${e.message}"
        }
    }

    private fun pollFast(job: Job, server: ServerConfig, client: JenkinsClient, userId: String?, errors: PollErrors) {
        val finished = mutableListOf<TrackedBuild>()

        fun interesting(users: Set<String>, upstream: List<de.pdenis.jenkspotter.api.UpstreamRef>) =
            (userId != null && users.any { it.equals(userId, ignoreCase = true) }) ||
                synchronized(lock) { store.isTracked(server.id, upstream) }

        // 1. Own items (and follow-ups of tracked builds) waiting in the queue.
        if (server.trackOwnBuilds && userId != null) {
            step(job, "queue", errors) {
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
            step(job, "queue item", errors) {
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
        val showAll = JenkSpotterSettings.getInstance().current.showAllRunning
        if (showAll) {
            // One listing with full build fields: no request per build, also for the uninteresting ones.
            step(job, "executors", errors) {
                var pending = client.runningBuilds()
                val urls = pending.map { it.url }.toSet()
                if (server.trackOwnBuilds && userId != null) {
                    repeat(MAX_CHAIN_DEPTH) {
                        val next = pending.filterNot { interesting(it.causeUserIds.toSet(), it.upstream) }
                        for (build in pending - next.toSet()) {
                            seen += build.url
                            upsertDiscovered(server.id, build, BuildSource.OWN, userId)?.let(finished::add)
                        }
                        if (next.size == pending.size) return@repeat
                        pending = next
                    }
                }
                val ended = synchronized(lock) {
                    for (build in pending.filter { it.url !in seen }) {
                        seen += build.url
                        store.upsert(server.id, build, BuildSource.RUNNING, userId)
                    }
                    store.endedRunning(server.id, urls)
                }
                // Finished builds stay listed with their result (hidden only by an explicit filter).
                // They are someone else's, so finishing is not notified - unless their job is pinned,
                // watched or a branch job: a short build may never be seen by the slow poll while running.
                val settings = JenkSpotterSettings.getInstance().current
                fun urls(jobs: List<de.pdenis.jenkspotter.settings.PinnedJob>) = jobs.filter { it.serverId == server.id }.map { it.url.trimEnd('/') }.toSet()
                val watched = urls(settings.watchedJobs)
                val pinned = urls(settings.pinnedJobs)
                val branch = branchJobs.values.flatten().filter { it.first == server.id }.map { it.second.url.trimEnd('/') }.toSet()
                for (tracked in ended) {
                    val result = runCatching { client.build(tracked.build.url) }
                    val build = result.getOrNull()
                    if (build == null) {
                        // Deleted: forget it. Anything else is tried again with the next poll.
                        if ((result.exceptionOrNull() as? JenkinsException)?.statusCode == 404) synchronized(lock) { store.remove(tracked.key) }
                        continue
                    }
                    val jobUrl = build.jobUrl.trimEnd('/')
                    val source = when (jobUrl) {
                        in watched -> BuildSource.WATCHED
                        in pinned -> BuildSource.PINNED
                        in branch -> BuildSource.BRANCH
                        else -> BuildSource.RUNNING
                    }
                    val done = synchronized(lock) { store.upsert(server.id, build, source, userId) }
                    if (source != BuildSource.RUNNING) done?.let(finished::add)
                }
            }
        } else {
            synchronized(lock) { store.dropRunningOnly(server.id) }
        }
        if (!showAll && server.trackOwnBuilds && userId != null) {
            step(job, "executors", errors) {
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
            val parentJob = JobIndexService.getInstance().jobByUrl(server.id, parent.build.jobUrl) ?: continue
            for (downstream in parentJob.downstream) {
                step(job, "downstream", errors) {
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
        step(job, "input", errors) { checkInputs(server, client) }

        // 4. Watched builds that left the executors or are not visible there: ask them directly.
        val stale = synchronized(lock) {
            store.running(server.id).filter { it.build.url !in seen && it.sources.any { s -> s != BuildSource.VIEW && s != BuildSource.RUNNING } }
        }
        for (tracked in stale) {
            step(job, "build", errors) {
                try {
                    val build = client.build(tracked.build.url)
                    val source = tracked.sources.first { it != BuildSource.VIEW && it != BuildSource.RUNNING }
                    synchronized(lock) { store.upsert(server.id, build, source, userId) }?.let(finished::add)
                } catch (e: JenkinsException) {
                    if (e.statusCode != 404) throw e
                    // Deleted, renamed or moved while it ran: it would be shown (and polled) as running forever.
                    synchronized(lock) { store.remove(tracked.key) }
                }
            }
        }
        dispatchFinished(finished)
    }

    private fun checkInputs(server: ServerConfig, client: JenkinsClient) {
        val now = System.currentTimeMillis()
        val running = synchronized(lock) { store.running(server.id) }
            .filter { it.build.isPipeline && (it.mine || it.sources.any { s -> s == BuildSource.PINNED || s == BuildSource.WATCHED || s == BuildSource.BRANCH }) }
        val runningKeys = running.map { it.key }.toSet()
        inputs.keys.removeIf { it.startsWith(server.id + " ") && it !in runningKeys }
        lastInputCheck.keys.removeIf { it.startsWith(server.id + " ") && it !in runningKeys }
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
        // Builds only listed by "all running builds" do not speed up polling.
        val running = synchronized(lock) { store.running(server.id) }.filter { it.sources != setOf(BuildSource.RUNNING) }
        val delay = when {
            queuedHere -> active
            running.isNotEmpty() -> {
                val now = System.currentTimeMillis()
                // Close to the estimated end a build is asked often; long before it, and long after it
                // (waiting for an approval, hanging), ever more rarely.
                val distance = running.minOf { r ->
                    val estimate = r.build.estimatedDurationMillis
                    if (estimate <= 0) now - r.build.timestamp else kotlin.math.abs(r.build.timestamp + estimate - now)
                }
                (distance / 1000 / 6).coerceIn(active, maxOf(active, MAX_RUNNING_INTERVAL_SECONDS))
            }
            server.trackOwnBuilds -> {
                val discovery = minOf(server.discoveryPollSeconds, server.idlePollSeconds).toLong()
                if (ApplicationManager.getApplication().isActive) discovery else minOf(discovery * 3, server.idlePollSeconds.toLong())
            }
            else -> server.idlePollSeconds.toLong()
        }
        return delay.coerceAtLeast(MIN_INTERVAL_SECONDS)
    }

    /**
     * Pinned, watched and branch jobs and the views. Each is asked on its own: one that fails (a
     * deleted view, a job without permission) is reported and keeps the builds it had, the others
     * are refreshed as usual.
     */
    private fun pollSlow(job: Job, server: ServerConfig, client: JenkinsClient, userId: String?, errors: PollErrors) {
        val settings = JenkSpotterSettings.getInstance().current
        val results = mutableListOf<Pair<BuildInfo, BuildSource>>()
        val failedJobs = HashMap<BuildSource, MutableSet<String>>()
        var viewsFailed = false
        fun ask(source: BuildSource, name: String, jobUrl: String?, block: () -> Unit) {
            if (!job.isActive) throw CancellationException("polling stopped")
            try {
                block()
            } catch (e: JenkinsException) {
                if (e.statusCode == 401 || e.cause is java.io.IOException) throw e
                // A job that no longer exists just has no builds; a missing view is a wrong setting.
                if (e.statusCode == 404 && jobUrl != null) return
                if (e.statusCode == 403) errors.forbidden = true
                if (e.statusCode >= 500) errors.serverError = true
                errors += "$name: ${e.message}"
                when {
                    jobUrl != null -> failedJobs.getOrPut(source) { HashSet() } += jobUrl.trimEnd('/')
                    e.statusCode != 404 -> viewsFailed = true
                }
            }
        }
        for (pin in settings.pinnedJobs.filter { it.serverId == server.id }) {
            ask(BuildSource.PINNED, pin.fullName, pin.url) {
                client.recentBuilds(pin.url, settings.buildsPerPinnedJob).forEach { results += it to BuildSource.PINNED }
            }
        }
        for (watched in settings.watchedJobs.filter { it.serverId == server.id }) {
            ask(BuildSource.WATCHED, watched.fullName, watched.url) {
                client.recentBuilds(watched.url, settings.buildsPerPinnedJob).forEach { results += it to BuildSource.WATCHED }
            }
        }
        for ((_, branch) in branchJobs.values.flatten().filter { it.first == server.id }.distinctBy { it.second.url }) {
            ask(BuildSource.BRANCH, branch.fullName, branch.url) {
                client.recentBuilds(branch.url, 3).forEach { results += it to BuildSource.BRANCH }
            }
        }
        for (view in server.views.filter { it.isNotBlank() }) {
            ask(BuildSource.VIEW, "view $view", null) {
                client.viewLastBuilds(view).forEach { results += it to BuildSource.VIEW }
            }
        }
        val finished = synchronized(lock) {
            store.applySlowPoll(server.id, results, userId) { tracked, source ->
                if (source == BuildSource.VIEW) viewsFailed else tracked.build.jobUrl.trimEnd('/') in failedJobs[source].orEmpty()
            }.also { store.prune(settings.ownHistoryHours) }
        }
        dispatchFinished(finished)
    }

    /**
     * "All builds": the last [count] finished builds of all jobs. One listing finds them; details
     * are fetched only for builds not known yet (finished builds do not change), at most
     * [MAX_HISTORY_FETCHES] per poll so a first load does not flood Jenkins. It is an extra: a
     * failure is reported but never makes the Jenkins count as unreachable.
     */
    private fun pollHistory(job: Job, server: ServerConfig, client: JenkinsClient, userId: String?, count: Int, errors: PollErrors) {
        if (count <= 0) {
            synchronized(lock) { store.retainHistory(server.id, emptySet()) }
            return
        }
        val refs = try {
            client.recentBuildRefs(HISTORY_PER_JOB)
        } catch (e: JenkinsException) {
            if (e.statusCode == 401) throw e
            if (e.statusCode >= 500) errors.serverError = true
            errors += "recent builds of all jobs: ${e.message}"
            return
        }
        val now = System.currentTimeMillis()
        historyFailed.values.removeIf { now - it > HISTORY_RETRY_MILLIS }
        val latest = refs.filter { !it.building }.sortedByDescending { it.timestamp }.take(count)
        var attempts = 0
        var more = false
        val listed = HashSet<String>()
        for (ref in latest) {
            if (!job.isActive) throw CancellationException("polling stopped")
            val known = synchronized(lock) { store.get(server.id, ref.url) }
            val failedKey = TrackedBuild.keyOf(server.id, ref.url)
            val build = when {
                known != null && !known.build.building -> known.build
                historyFailed.containsKey(failedKey) -> continue
                attempts >= MAX_HISTORY_FETCHES -> {
                    more = true
                    continue
                }
                else -> {
                    attempts++
                    val fetched = runCatching { client.build(ref.url) }.getOrNull()
                    if (fetched == null) {
                        historyFailed[failedKey] = now
                        continue
                    }
                    fetched
                }
            }
            listed += ref.url
            synchronized(lock) { store.upsert(server.id, build, BuildSource.HISTORY, userId) }
        }
        synchronized(lock) { store.retainHistory(server.id, listed) }
        thisLogger().debug("JenkSpotter: ${server.displayName} history: ${refs.size} builds seen, ${listed.size} listed, $attempts fetched")
        // The rest follows right away, without asking pins and views again.
        if (more) {
            historyPending += server.id
            refresh(server.id, includeSlow = false)
        }
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
        /** Newest builds asked for per job; a job building more often than that fills fewer places. */
        private const val HISTORY_PER_JOB = 2
        private const val MAX_HISTORY_FETCHES = 40
        private const val HISTORY_INTERVAL_MILLIS = 2L * 60 * 1000
        private const val HISTORY_RETRY_MILLIS = 10L * 60 * 1000
        /** Refresh requests arriving within this time after a poll wait and are merged into one. */
        private const val MIN_POLL_GAP_MILLIS = 2_000L
        /** A Jenkins that is back is noticed within a minute at the latest. */
        private const val MAX_BACKOFF_SECONDS = 60L
        private const val MIN_INTERVAL_SECONDS = 2L
        private const val MAX_RUNNING_INTERVAL_SECONDS = 30L
        private const val INPUT_CHECK_MILLIS = 8_000L
        private const val MAX_CHAIN_DEPTH = 4
        private const val DOWNSTREAM_WINDOW_MILLIS = 2L * 60 * 1000

        fun getInstance(): BuildMonitorService = service()
    }
}
