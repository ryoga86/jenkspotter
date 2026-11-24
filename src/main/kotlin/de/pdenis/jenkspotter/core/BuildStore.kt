package de.pdenis.jenkspotter.core

import de.pdenis.jenkspotter.api.BuildInfo

/**
 * In-memory state of all watched builds plus the transition detection that decides when a build
 * "just finished". Not thread-safe; [BuildMonitorService] serializes access.
 */
class BuildStore(private val clock: () -> Long = System::currentTimeMillis) {
    private val builds = LinkedHashMap<String, TrackedBuild>()
    private val jobMaxNumber = HashMap<String, Int>()

    fun all(): List<TrackedBuild> = builds.values.sortedByDescending { it.build.timestamp }

    fun get(serverId: String, url: String): TrackedBuild? = builds[TrackedBuild.keyOf(serverId, url)]

    fun parentOf(serverId: String, build: BuildInfo): TrackedBuild? =
        build.upstream.firstNotNullOfOrNull { builds[TrackedBuild.keyOf(serverId, it.buildUrl)] }

    /** True if a build started by [upstream] would be shown as a child. */
    fun isTracked(serverId: String, upstream: List<de.pdenis.jenkspotter.api.UpstreamRef>): Boolean =
        upstream.any { builds.containsKey(TrackedBuild.keyOf(serverId, it.buildUrl)) }

    /** Finished own builds (and their chains) that ended recently; their follow-up jobs may still start. */
    fun recentlyFinishedMine(serverId: String, withinMillis: Long): List<TrackedBuild> {
        val now = clock()
        return builds.values.filter {
            it.serverId == serverId && it.mine && !it.build.building &&
                it.build.timestamp + it.build.durationMillis > now - withinMillis
        }
    }

    fun running(serverId: String): List<TrackedBuild> =
        builds.values.filter { it.serverId == serverId && it.build.building }

    /** Upserts a build and returns it if this observation is the moment it finished. */
    fun upsert(serverId: String, build: BuildInfo, source: BuildSource, userId: String?): TrackedBuild? {
        val key = TrackedBuild.keyOf(serverId, build.url)
        val previous = builds[key]
        val parent = parentOf(serverId, build)
        // A build started by one of my builds belongs to me as well (chains of jobs).
        val mine = source == BuildSource.TRIGGERED || build.startedBy(userId) || parent?.mine == true
        val tracked = TrackedBuild(
            serverId = serverId,
            build = build,
            sources = (previous?.sources ?: emptySet()) + source,
            mine = mine || previous?.mine == true,
            parentKey = parent?.key ?: previous?.parentKey,
        )
        builds[key] = tracked

        val jobKey = TrackedBuild.keyOf(serverId, build.jobUrl)
        val knownMax = jobMaxNumber[jobKey]
        jobMaxNumber[jobKey] = maxOf(knownMax ?: 0, build.number)

        val finishedNow = when {
            previous != null -> previous.build.building && !build.building
            build.building -> false
            source == BuildSource.TRIGGERED -> true
            // A build that started and ended between two polls of a job we already knew.
            else -> knownMax != null && build.number > knownMax
        }
        return tracked.takeIf { finishedNow }
    }

    /**
     * Replaces the pinned/view membership of one server with a fresh poll result. Builds that are
     * no longer referenced by any source are dropped. [keep]: memberships that could not be asked
     * for this time (a job or view that failed to answer) and therefore stay as they are.
     */
    fun applySlowPoll(
        serverId: String,
        results: List<Pair<BuildInfo, BuildSource>>,
        userId: String?,
        keep: (TrackedBuild, BuildSource) -> Boolean = { _, _ -> false },
    ): List<TrackedBuild> {
        val slow = setOf(BuildSource.PINNED, BuildSource.WATCHED, BuildSource.VIEW, BuildSource.BRANCH)
        for ((key, tracked) in builds.entries.toList()) {
            if (tracked.serverId != serverId) continue
            val dropped = tracked.sources.filter { it in slow && !keep(tracked, it) }
            if (dropped.isNotEmpty()) builds[key] = tracked.copy(sources = tracked.sources - dropped.toSet())
        }
        val finished = results.mapNotNull { (build, source) -> upsert(serverId, build, source, userId) }
        builds.entries.removeIf { it.value.serverId == serverId && it.value.sources.isEmpty() }
        return finished
    }

    /**
     * "All running builds": builds that left the executors ([stillRunning] no longer has them).
     * Those listed only because they ran ([BuildSource.RUNNING]) stay listed and are returned, so
     * their result can be fetched once; those also listed for another reason just lose the mark.
     */
    fun endedRunning(serverId: String, stillRunning: Set<String>): List<TrackedBuild> {
        val ended = mutableListOf<TrackedBuild>()
        for ((key, tracked) in builds.entries.toList()) {
            if (tracked.serverId != serverId || BuildSource.RUNNING !in tracked.sources || !tracked.build.building || tracked.build.url in stillRunning) continue
            val rest = tracked.sources - BuildSource.RUNNING
            if (rest.isEmpty()) ended += tracked else builds[key] = tracked.copy(sources = rest)
        }
        return ended
    }

    /**
     * The last finished builds of all jobs: [keep] holds the URLs that are among them now. Builds
     * that dropped out lose the mark, and are forgotten when nothing else lists them.
     */
    fun retainHistory(serverId: String, keep: Set<String>) {
        for ((key, tracked) in builds.entries.toList()) {
            if (tracked.serverId != serverId || BuildSource.HISTORY !in tracked.sources || tracked.build.url in keep) continue
            val rest = tracked.sources - BuildSource.HISTORY
            if (rest.isEmpty()) builds.remove(key) else builds[key] = tracked.copy(sources = rest)
        }
    }

    /** "All running builds" switched off: forgets the builds listed only for it. */
    fun dropRunningOnly(serverId: String) {
        for ((key, tracked) in builds.entries.toList()) {
            if (tracked.serverId != serverId || BuildSource.RUNNING !in tracked.sources) continue
            val rest = tracked.sources - BuildSource.RUNNING
            if (rest.isEmpty()) builds.remove(key) else builds[key] = tracked.copy(sources = rest)
        }
    }

    fun remove(key: String) {
        builds.remove(key)
    }

    /**
     * Drops finished own builds, and finished builds listed only because they ran, older than
     * [hours]; each of the two histories is capped at [maxOwn].
     */
    fun prune(hours: Int, maxOwn: Int = 200) {
        val cutoff = clock() - hours * 60L * 60 * 1000
        val ownOnly = setOf(BuildSource.OWN, BuildSource.TRIGGERED, BuildSource.DOWNSTREAM)
        val runningOnly = setOf(BuildSource.RUNNING)
        for (history in listOf(ownOnly, runningOnly)) {
            builds.entries.removeIf { (_, t) ->
                !t.build.building && t.sources.all { it in history } &&
                    t.build.timestamp + t.build.durationMillis < cutoff
            }
            builds.values.filter { t -> !t.build.building && t.sources.all { it in history } }
                .sortedByDescending { it.build.timestamp }
                .drop(maxOwn)
                .forEach { builds.remove(it.key) }
        }
    }

    fun retainServers(ids: Set<String>) {
        builds.entries.removeIf { it.value.serverId !in ids }
        jobMaxNumber.keys.removeIf { it.substringBefore(' ') !in ids }
    }
}
