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
     * no longer referenced by any source are dropped.
     */
    fun applySlowPoll(
        serverId: String,
        results: List<Pair<BuildInfo, BuildSource>>,
        userId: String?,
    ): List<TrackedBuild> {
        val slow = setOf(BuildSource.PINNED, BuildSource.VIEW, BuildSource.BRANCH)
        for ((key, tracked) in builds.entries.toList()) {
            if (tracked.serverId == serverId && tracked.sources.any { it in slow }) {
                builds[key] = tracked.copy(sources = tracked.sources - slow)
            }
        }
        val finished = results.mapNotNull { (build, source) -> upsert(serverId, build, source, userId) }
        builds.entries.removeIf { it.value.serverId == serverId && it.value.sources.isEmpty() }
        return finished
    }

    /** Drops finished own builds older than [hours] and caps the own history size. */
    fun prune(hours: Int, maxOwn: Int = 200) {
        val cutoff = clock() - hours * 60L * 60 * 1000
        val ownOnly = setOf(BuildSource.OWN, BuildSource.TRIGGERED, BuildSource.DOWNSTREAM)
        builds.entries.removeIf { (_, t) ->
            !t.build.building && t.sources.all { it in ownOnly } &&
                t.build.timestamp + t.build.durationMillis < cutoff
        }
        val own = builds.values.filter { t -> !t.build.building && t.sources.all { it in ownOnly } }
            .sortedByDescending { it.build.timestamp }
        own.drop(maxOwn).forEach { builds.remove(it.key) }
    }

    fun removeServer(serverId: String) {
        builds.entries.removeIf { it.value.serverId == serverId }
    }

    fun retainServers(ids: Set<String>) {
        builds.entries.removeIf { it.value.serverId !in ids }
    }
}
