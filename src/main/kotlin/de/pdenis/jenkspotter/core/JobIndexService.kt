package de.pdenis.jenkspotter.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.messages.Topic
import de.pdenis.jenkspotter.api.JenkinsException
import de.pdenis.jenkspotter.api.JenkinsPaths
import de.pdenis.jenkspotter.api.JobRef
import de.pdenis.jenkspotter.settings.JobSort
import de.pdenis.jenkspotter.settings.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeLines

fun interface JobIndexListener {
    fun indexChanged(serverId: String)

    companion object {
        @Topic.AppLevel
        val TOPIC = Topic.create("JenkSpotter job index", JobIndexListener::class.java)
    }
}

/**
 * Local, persisted list of all jobs per server for fuzzy search. Large instances are crawled
 * breadth-first, three folder levels per request, with limited parallelism.
 */
@Service(Service.Level.APP)
class JobIndexService(private val cs: CoroutineScope) {
    data class IndexState(
        val jobs: List<JobRef> = emptyList(),
        val updatedAt: Long = 0,
        val loading: Boolean = false,
        val error: String? = null,
    ) {
        val byUrl: Map<String, JobRef> by lazy { jobs.associateBy { it.url } }
        val byFullName: Map<String, JobRef> by lazy { jobs.associateBy { it.fullName } }

        /** Full name -> jobs that trigger it after they finish. */
        val upstream: Map<String, List<JobRef>> by lazy {
            jobs.flatMap { job -> job.downstream.map { it to job } }.groupBy({ it.first }, { it.second })
        }

        /** Rendering asks for these per row and repaint; a new index state starts empty. */
        internal val chains = ConcurrentHashMap<String, List<ChainNode>>()
        internal val positions = ConcurrentHashMap<String, java.util.Optional<ChainPosition>>()
        internal val longestUp = ConcurrentHashMap<String, List<JobRef>>()
        internal val badges = ConcurrentHashMap<String, JobBadges>()
    }

    private val states = ConcurrentHashMap<String, IndexState>()
    private val running = ConcurrentHashMap<String, Job>()

    /** Servers whose index file is being read for the UI thread; completed with what was read. */
    private val reading = ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<IndexState>>()

    /**
     * The index of a server. The first call reads its file. The UI thread never waits for that: it
     * gets an empty, loading state and [JobIndexListener] fires once the file is read. Background
     * threads get the read index.
     */
    fun state(serverId: String): IndexState {
        val onUiThread = ApplicationManager.getApplication().isDispatchThread
        if (!onUiThread) reading[serverId]?.let { return it.join() }
        states[serverId]?.let { return it }
        if (!onUiThread) return states.computeIfAbsent(serverId) { loadFromDisk(it) }
        val future = java.util.concurrent.CompletableFuture<IndexState>()
        val placeholder = IndexState(loading = true)
        reading.putIfAbsent(serverId, future)?.let { return states[serverId] ?: placeholder }
        states.putIfAbsent(serverId, placeholder)?.let { existing ->
            // A background thread read it in the meantime.
            reading.remove(serverId, future)
            future.complete(existing)
            return existing
        }
        cs.launch(Dispatchers.IO) {
            val loaded = loadFromDisk(serverId)
            states[serverId] = loaded
            reading.remove(serverId, future)
            ApplicationManager.getApplication().messageBus.syncPublisher(JobIndexListener.TOPIC).indexChanged(serverId)
            future.complete(loaded)
        }
        return placeholder
    }

    /** Refreshes in the background if forced, empty, or older than [MAX_AGE_MILLIS]. */
    fun refreshIfNeeded(server: ServerConfig, force: Boolean = false) {
        // Starts reading the file if this is the first use; whether a refresh is due is only known afterwards.
        state(server.id)
        reading[server.id]?.let { pending ->
            pending.thenRun { refreshIfNeeded(server, force) }
            return
        }
        val current = states[server.id] ?: return
        val stale = System.currentTimeMillis() - current.updatedAt > MAX_AGE_MILLIS
        if (!force && !stale && current.jobs.isNotEmpty()) return
        if (running[server.id]?.isActive == true) return
        update(server.id) { it.copy(loading = true, error = null) }
        running[server.id] = cs.launch(Dispatchers.IO) {
            try {
                val client = JenkinsConnections.getInstance().client(server)
                    ?: throw JenkinsException("No API token configured")
                val jobs = crawl { folder -> client.listJobsDeep(folder?.url) }
                save(server.id, jobs)
                update(server.id) { IndexState(jobs, System.currentTimeMillis(), false, null) }
            } catch (e: JenkinsException) {
                update(server.id) { it.copy(loading = false, error = e.message) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                thisLogger().warn("Job index refresh failed", e)
                update(server.id) { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Searches the indexes of [serverIds], ordered as chosen in the jobs list (see [JobSort]). */
    /** [only]: server id -> URLs of the jobs that may be listed (e.g. this project's jobs); null = all. */
    fun search(serverIds: List<String>, query: String, hidden: HiddenJobs, limit: Int = 300, only: Map<String, Set<String>>? = null): List<JobHit> {
        val settings = de.pdenis.jenkspotter.settings.JenkSpotterSettings.getInstance().current
        val pinned = settings.pinnedJobs.map { "${it.serverId} ${it.url}" }.toSet()
        val watched = settings.watchedJobs.map { "${it.serverId} ${it.url}" }.toSet()
        val recent = settings.recentJobs.withIndex().associate { (rank, key) -> key to rank }
        val candidates = serverIds.flatMap { id -> state(id).jobs.map { id to it } }.toMutableList()
        // Pinned and watched jobs stay visible even before the index has been loaded.
        for (pin in (settings.pinnedJobs + settings.watchedJobs).filter { it.serverId in serverIds }) {
            if (candidates.none { it.first == pin.serverId && it.second.url == pin.url }) {
                candidates += pin.serverId to JobRef(pin.fullName.substringAfterLast('/'), pin.fullName, pin.url, "")
            }
        }
        if (only != null) candidates.retainAll { (id, job) -> only[id]?.contains(job.url) == true }
        val serverNames = settings.servers.filter { it.id in serverIds }.associate { it.id to it.displayName }
        return rank(candidates, query, hidden, pinned, recent, limit, settings.showFolderEntries, settings.jobSort, settings.pinnedFirst, watched, serverNames)
    }

    /** Known jobs of a server by URL, for downstream lookups. */
    fun jobByUrl(serverId: String, url: String): JobRef? = state(serverId).byUrl[url]

    fun jobByFullName(serverId: String, fullName: String): JobRef? = state(serverId).byFullName[fullName]

    /**
     * Follow-up jobs known in advance (freestyle "Build other projects"), transitively, as a tree.
     * Pipeline `build job:` steps are not visible before they run.
     */
    fun chain(serverId: String, job: JobRef): List<ChainNode> =
        state(serverId).chains.getOrPut(job.fullName) { chain(serverId, job, 0, HashSet()) }

    private fun chain(serverId: String, job: JobRef, depth: Int, seen: MutableSet<String>): List<ChainNode> {
        if (depth >= MAX_CHAIN_DEPTH || !seen.add(job.fullName)) return emptyList()
        return job.downstream.map { name ->
            val next = jobByFullName(serverId, name)
            ChainNode(name, next?.url, next?.let { chain(serverId, it, depth + 1, seen) }.orEmpty(), next?.disabled == true)
        }
    }

    /**
     * Where [job] sits in chains of follow-up jobs: every path of upstream jobs leading to it, the
     * longest first (first entry of a path = the job starting that chain), and how many steps follow
     * below it. A job triggered by several jobs is in several chains. Null if no job triggers [job].
     */
    fun position(serverId: String, job: JobRef): ChainPosition? =
        state(serverId).positions.getOrPut(job.fullName) { java.util.Optional.ofNullable(computePosition(serverId, job)) }.orElse(null)

    private fun computePosition(serverId: String, job: JobRef): ChainPosition? {
        val paths = upstreamPaths(state(serverId), job)
        return if (paths.isEmpty()) null else ChainPosition(paths, job)
    }

    /**
     * What the jobs list shows next to [job]. Kept per index state, so painting a row is a lookup;
     * the list asks for all its rows in the background before it shows them.
     */
    fun badges(serverId: String, job: JobRef): JobBadges {
        val state = state(serverId)
        return state.badges.getOrPut(job.fullName) {
            val triggeredBy = if (job.isFolder) 0 else state.upstream[job.fullName]?.size ?: 0
            val position = if (triggeredBy > 0) position(serverId, job) else null
            val chain = if (job.downstream.isEmpty()) emptyList() else chain(serverId, job)
            val parent = job.fullName.substringBeforeLast('/', "")
            JobBadges(
                triggeredBy = triggeredBy,
                blocked = position?.blockedBy != null,
                followUps = chain.sumOf { it.size },
                activeFollowUps = chain.sumOf { it.activeSize },
                workflow = if (job.isFolder) null else position?.path?.firstOrNull()?.fullName ?: job.fullName.takeIf { chain.isNotEmpty() },
                inMultiBranch = parent.isNotEmpty() && state.byFullName[parent]?.isMultiBranch == true,
            )
        }
    }

    fun forget(serverId: String) {
        running.remove(serverId)?.cancel()
        states.remove(serverId)
        Files.deleteIfExists(file(serverId))
    }

    private fun update(serverId: String, transform: (IndexState) -> IndexState) {
        states.compute(serverId) { _, old -> transform(old ?: IndexState()) }
        ApplicationManager.getApplication().messageBus.syncPublisher(JobIndexListener.TOPIC).indexChanged(serverId)
    }

    private fun file(serverId: String): Path =
        PathManager.getSystemDir().resolve("jenkspotter").resolve("jobs-$serverId.tsv")

    private fun loadFromDisk(serverId: String): IndexState {
        val f = file(serverId)
        if (!f.exists()) return IndexState()
        return runCatching {
            val lines = f.readLines()
            val updatedAt = lines.firstOrNull()?.toLongOrNull() ?: 0L
            val jobs = lines.drop(1).mapNotNull { line ->
                val p = line.split('\t')
                when {
                    p.size >= 8 -> JobRef(p[0], p[1], p[2], p[3], p[4].split(',').filter { it.isNotEmpty() }, p[5].toIntOrNull() ?: -1, p[6] == "disabled", p[7].split(' ').filter { it.isNotEmpty() })
                    p.size >= 7 -> JobRef(p[0], p[1], p[2], p[3], p[4].split(',').filter { it.isNotEmpty() }, p[5].toIntOrNull() ?: -1, p[6] == "disabled")
                    p.size >= 6 -> JobRef(p[0], p[1], p[2], p[3], p[4].split(',').filter { it.isNotEmpty() }, p[5].toIntOrNull() ?: -1)
                    p.size >= 5 -> JobRef(p[0], p[1], p[2], p[3], p[4].split(',').filter { it.isNotEmpty() })
                    p.size == 4 -> JobRef(p[0], p[1], p[2], p[3])
                    else -> null
                }
            }
            // Indexes written by older versions lack fields (parameters, disabled, repositories); reload them in the background.
            val outdated = lines.drop(1).any { it.split('\t').size < 8 }
            IndexState(jobs, if (outdated) 0 else updatedAt)
        }.getOrElse { IndexState() }
    }

    private fun save(serverId: String, jobs: List<JobRef>) {
        val f = file(serverId)
        Files.createDirectories(f.parent)
        // Written next to it and moved into place: a crash half-way must not leave a cut-off index that looks fresh.
        val temp = f.resolveSibling(f.fileName.toString() + ".tmp")
        temp.writeLines(listOf(System.currentTimeMillis().toString()) + jobs.map { "${it.name}\t${it.fullName}\t${it.url}\t${it.jobClass}\t${it.downstream.joinToString(",")}\t${it.parameterCount}\t${if (it.disabled) "disabled" else ""}\t${it.repos.joinToString(" ")}" })
        Files.move(temp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        private const val MAX_AGE_MILLIS = 12L * 60 * 60 * 1000
        private const val MAX_JOBS = 100_000
        private const val PARALLEL_REQUESTS = 4

        fun getInstance(): JobIndexService = service()

        /**
         * Longest chain of jobs triggering [job], starting with the first one; empty if none does.
         * The longest way up is kept per job ([IndexState.longestUp]): without that, every job
         * reached over several paths would be walked once per path.
         */
        fun upstreamPath(state: IndexState, job: JobRef): List<JobRef> {
            val inProgress = HashSet<String>()
            // Jobs skipped because they are on the way down already (A triggers B triggers A).
            var cuts = 0
            fun longestUp(name: String): List<JobRef> {
                state.longestUp[name]?.let { return it }
                inProgress += name
                val before = cuts
                val ups = state.upstream[name].orEmpty().filter { up -> (up.fullName !in inProgress).also { if (!it) cuts++ } }
                val best = ups.map { longestUp(it.fullName) + it }.maxByOrNull { it.size }.orEmpty().takeLast(MAX_CHAIN_DEPTH - 1)
                inProgress -= name
                // A result shortened by a cycle depends on where the walk started: not kept.
                if (cuts == before) state.longestUp[name] = best
                return best
            }
            return longestUp(job.fullName)
        }

        /**
         * All paths of jobs triggering [job], each starting with the job that starts it; the longest
         * ([upstreamPath]) first, then by length. At most [MAX_PATHS], since fan-in multiplies them.
         */
        fun upstreamPaths(state: IndexState, job: JobRef): List<List<JobRef>> {
            val longest = upstreamPath(state, job)
            if (longest.isEmpty()) return emptyList()
            val found = LinkedHashSet<List<String>>()
            val result = mutableListOf<List<JobRef>>()
            // Walks upwards; [below] is the path from the current job down to [job] (excluded).
            fun walk(name: String, below: List<JobRef>, seen: Set<String>) {
                if (result.size >= MAX_PATHS) return
                val ups = if (seen.size >= MAX_CHAIN_DEPTH) emptyList() else state.upstream[name].orEmpty().filter { it.fullName !in seen }
                if (ups.isEmpty()) {
                    if (below.isNotEmpty() && found.add(below.map { it.fullName })) result += below
                    return
                }
                for (up in ups) walk(up.fullName, listOf(up) + below, seen + up.fullName)
            }
            walk(job.fullName, emptyList(), setOf(job.fullName))
            val others = result.filter { it.map(JobRef::fullName) != longest.map(JobRef::fullName) }
            return listOf(longest) + others.sortedByDescending { it.size }.take(MAX_PATHS - 1)
        }

        /** Breadth-first crawl; [fetch] returns jobs below a folder (null = root) plus unexplored folders. */
        suspend fun crawl(fetch: (JobRef?) -> Pair<List<JobRef>, List<JobRef>>): List<JobRef> = coroutineScope {
            val semaphore = Semaphore(PARALLEL_REQUESTS)
            val result = LinkedHashMap<String, JobRef>()
            var frontier: List<JobRef?> = listOf(null)
            while (frontier.isNotEmpty() && result.size < MAX_JOBS) {
                val responses = frontier.map { folder -> async(Dispatchers.IO) { semaphore.withPermit { fetch(folder) } } }.awaitAll()
                val next = mutableListOf<JobRef>()
                for ((jobs, unexplored) in responses) {
                    jobs.forEach { result.putIfAbsent(it.url, it) }
                    next += unexplored
                }
                frontier = next
            }
            result.values.toList()
        }

        fun rank(
            candidates: List<Pair<String, JobRef>>,
            query: String,
            hidden: HiddenJobs = HiddenJobs.NONE,
            pinned: Set<String> = emptySet(),
            recent: Map<String, Int> = emptyMap(),
            limit: Int = 300,
            showFolders: Boolean = true,
            sort: JobSort = JobSort.RELEVANCE,
            pinnedFirst: Boolean = true,
            watched: Set<String> = emptySet(),
            /** Jenkins names by server id, for `@name`; with several also matched as a weak third field. */
            serverNames: Map<String, String> = emptyMap(),
        ): List<JobHit> {
            val parsed = JobMatcher.parse(query)
            val serverAsField = serverNames.size > 1
            val tokens = parsed.tokens
            return candidates.asSequence()
                .filter { (_, job) -> if (job.isFolder) !hidden.isFolderHidden(job.fullName) else !hidden.isHidden(job.fullName) }
                // Folders are only interesting when searched for explicitly.
                .filter { (_, job) -> job.isBuildable || (showFolders && tokens.isNotEmpty()) }
                .mapNotNull { (serverId, job) ->
                    val name = JenkinsPaths.displayName(job.name)
                    val path = JenkinsPaths.displayName(job.fullName.substringBeforeLast('/', ""))
                    val match = JobMatcher.match(tokens, name, path, serverNames[serverId].orEmpty(), parsed.serverTokens, serverAsField) ?: return@mapNotNull null
                    val key = "$serverId ${job.url}"
                    val recentBonus = recent[key]?.let { (MAX_RECENT_RANK - it) * RECENT_BONUS } ?: 0
                    val folderPenalty = if (job.isFolder) FOLDER_PENALTY else 0
                    JobHit(serverId, job, name, path, match, key in pinned, match.score + recentBonus - folderPenalty, key in watched)
                }
                .sortedWith(order(sort, pinnedFirst, !parsed.isEmpty, recent))
                .take(limit)
                .toList()
        }

        private fun order(sort: JobSort, pinnedFirst: Boolean, searching: Boolean, recent: Map<String, Int>): Comparator<JobHit> {
            fun recentRank(hit: JobHit) = recent["${hit.serverId} ${hit.job.url}"] ?: Int.MAX_VALUE
            val byName = compareBy<JobHit, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.path }
            val order = when (sort) {
                JobSort.RELEVANCE -> compareByDescending<JobHit> { if (searching) it.score else -recentRank(it) }.then(byName)
                JobSort.NAME -> byName
                JobSort.PATH -> compareBy<JobHit, String>(String.CASE_INSENSITIVE_ORDER) { it.path }.then(byName)
                JobSort.RECENT -> compareBy<JobHit> { recentRank(it) }.thenByDescending { it.score }.then(byName)
            }
            return if (pinnedFirst) compareByDescending<JobHit> { it.pinned }.then(order) else order
        }

        private const val MAX_CHAIN_DEPTH = 8
        private const val MAX_PATHS = 50
        private const val MAX_RECENT_RANK = 30
        private const val RECENT_BONUS = 5
        private const val FOLDER_PENALTY = 500
    }
}

/** One search result; [name] and [path] are the decoded texts the match ranges refer to. */
data class JobHit(
    val serverId: String,
    val job: JobRef,
    val name: String,
    val path: String,
    val match: JobMatcher.Result,
    val pinned: Boolean,
    val score: Int,
    val watched: Boolean = false,
)

/** A job inside one or more follow-up chains, see [JobIndexService.position]. */
data class ChainPosition(
    /** Every path of jobs before it, each starting with the job that starts it; the longest first. */
    val paths: List<List<JobRef>>,
    val job: JobRef,
) {
    /** The longest path. */
    val path: List<JobRef> get() = paths.first()

    /** The longest path per starting job, longest first: one entry per chain [job] is in. */
    val chains: List<List<JobRef>> get() = paths.groupBy { it.first().fullName }.values.map { it.first() }

    /**
     * First disabled job before it when every path has one: then nothing triggers [job]. If one
     * path is free, another job still triggers it, so it is null.
     */
    val blockedBy: JobRef?
        get() = if (paths.isNotEmpty() && paths.all { p -> p.any { it.disabled } }) paths.first().first { it.disabled } else null
}

/** One follow-up job in a chain; [url] is null if the job is not in the index. */
data class ChainNode(
    val fullName: String,
    val url: String?,
    val children: List<ChainNode>,
    /** Disabled in Jenkins: it is not triggered, so nothing below it runs either. */
    val disabled: Boolean = false,
) {
    val size: Int get() = 1 + children.sumOf { it.size }

    /** Jobs of this subtree that will actually run (a disabled job cuts off everything below it). */
    val activeSize: Int get() = if (disabled) 0 else 1 + children.sumOf { it.activeSize }
}

/** Trigger information of a job as the jobs list shows it, see [JobIndexService.badges]. */
data class JobBadges(
    /** Number of jobs triggering it directly. */
    val triggeredBy: Int,
    /** A disabled job on every way to it: nothing triggers it at the moment. */
    val blocked: Boolean,
    /** Follow-up jobs, direct and indirect; [activeFollowUps] of them will actually run. */
    val followUps: Int,
    val activeFollowUps: Int,
    /** Full name of the job starting the workflow it belongs to; null if it is in none. */
    val workflow: String?,
    /** A branch of a multibranch pipeline. */
    val inMultiBranch: Boolean,
)
