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
    }

    private val states = ConcurrentHashMap<String, IndexState>()
    private val running = ConcurrentHashMap<String, Job>()

    fun state(serverId: String): IndexState = states.computeIfAbsent(serverId) { loadFromDisk(it) }

    /** Refreshes in the background if forced, empty, or older than [MAX_AGE_MILLIS]. */
    fun refreshIfNeeded(server: ServerConfig, force: Boolean = false) {
        val current = state(server.id)
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

    /** Searches the indexes of [serverIds]; pinned first, then score (plus recency bonus), then name. */
    fun search(serverIds: List<String>, query: String, hidden: HiddenJobs, limit: Int = 300): List<JobHit> {
        val settings = de.pdenis.jenkspotter.settings.JenkSpotterSettings.getInstance().current
        val pinned = settings.pinnedJobs.map { "${it.serverId} ${it.url}" }.toSet()
        val recent = settings.recentJobs.withIndex().associate { (rank, key) -> key to rank }
        val candidates = serverIds.flatMap { id -> state(id).jobs.map { id to it } }.toMutableList()
        // Pinned jobs stay visible even before the index has been loaded.
        for (pin in settings.pinnedJobs.filter { it.serverId in serverIds }) {
            if (candidates.none { it.first == pin.serverId && it.second.url == pin.url }) {
                candidates += pin.serverId to JobRef(pin.fullName.substringAfterLast('/'), pin.fullName, pin.url, "")
            }
        }
        return rank(candidates, query, hidden, pinned, recent, limit, settings.showFolderEntries)
    }

    /** Known jobs of a server by URL, for downstream lookups. */
    fun jobByUrl(serverId: String, url: String): JobRef? = state(serverId).byUrl[url]

    fun jobByFullName(serverId: String, fullName: String): JobRef? = state(serverId).byFullName[fullName]

    /**
     * Follow-up jobs known in advance (freestyle "Build other projects"), transitively, as a tree.
     * Pipeline `build job:` steps are not visible before they run.
     */
    fun chain(serverId: String, job: JobRef, depth: Int = 0, seen: MutableSet<String> = HashSet()): List<ChainNode> {
        if (depth >= MAX_CHAIN_DEPTH || !seen.add(job.fullName)) return emptyList()
        return job.downstream.map { name ->
            val next = jobByFullName(serverId, name)
            ChainNode(name, next?.url, next?.let { chain(serverId, it, depth + 1, seen) }.orEmpty())
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
                    p.size >= 6 -> JobRef(p[0], p[1], p[2], p[3], p[4].split(',').filter { it.isNotEmpty() }, p[5].toIntOrNull() ?: -1)
                    p.size >= 5 -> JobRef(p[0], p[1], p[2], p[3], p[4].split(',').filter { it.isNotEmpty() })
                    p.size == 4 -> JobRef(p[0], p[1], p[2], p[3])
                    else -> null
                }
            }
            // Indexes written by older versions lack fields (parameters); reload them in the background.
            val outdated = jobs.any { it.parameterCount < 0 && it.isBuildable }
            IndexState(jobs, if (outdated) 0 else updatedAt)
        }.getOrElse { IndexState() }
    }

    private fun save(serverId: String, jobs: List<JobRef>) {
        val f = file(serverId)
        Files.createDirectories(f.parent)
        f.writeLines(listOf(System.currentTimeMillis().toString()) + jobs.map { "${it.name}\t${it.fullName}\t${it.url}\t${it.jobClass}\t${it.downstream.joinToString(",")}\t${it.parameterCount}" })
    }

    companion object {
        private const val MAX_AGE_MILLIS = 12L * 60 * 60 * 1000
        private const val MAX_JOBS = 100_000
        private const val PARALLEL_REQUESTS = 4

        fun getInstance(): JobIndexService = service()

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
        ): List<JobHit> {
            val tokens = JobMatcher.tokenize(query)
            return candidates.asSequence()
                .filter { (_, job) -> if (job.isFolder) !hidden.isFolderHidden(job.fullName) else !hidden.isHidden(job.fullName) }
                // Folders are only interesting when searched for explicitly.
                .filter { (_, job) -> job.isBuildable || (showFolders && tokens.isNotEmpty()) }
                .mapNotNull { (serverId, job) ->
                    val name = JenkinsPaths.displayName(job.name)
                    val path = JenkinsPaths.displayName(job.fullName.substringBeforeLast('/', ""))
                    val match = JobMatcher.match(tokens, name, path) ?: return@mapNotNull null
                    val key = "$serverId ${job.url}"
                    val recentBonus = recent[key]?.let { (MAX_RECENT_RANK - it) * RECENT_BONUS } ?: 0
                    val folderPenalty = if (job.isFolder) FOLDER_PENALTY else 0
                    JobHit(serverId, job, name, path, match, key in pinned, match.score + recentBonus - folderPenalty)
                }
                .sortedWith(
                    compareByDescending<JobHit> { it.pinned }
                        .thenByDescending { if (tokens.isEmpty()) -(recent["${it.serverId} ${it.job.url}"] ?: Int.MAX_VALUE) else it.score }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                        .thenBy { it.path },
                )
                .take(limit)
                .toList()
        }

        private const val MAX_CHAIN_DEPTH = 8
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
)

/** One follow-up job in a chain; [url] is null if the job is not in the index. */
data class ChainNode(val fullName: String, val url: String?, val children: List<ChainNode>) {
    val size: Int get() = 1 + children.sumOf { it.size }

    /** "middle -> last" for a straight chain, "a (-> b), c" when it branches. */
    fun describe(): String {
        val name = de.pdenis.jenkspotter.api.JenkinsPaths.displayName(fullName.substringAfterLast('/'))
        return when (children.size) {
            0 -> name
            1 -> "$name \u2192 ${children.single().describe()}"
            else -> "$name \u2192 (${children.joinToString(", ") { it.describe() }})"
        }
    }
}
