package de.pdenis.jenkspotter.core

import de.pdenis.jenkspotter.api.BuildInfo
import de.pdenis.jenkspotter.api.BuildResult
import de.pdenis.jenkspotter.api.JobRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildStoreTest {
    private fun build(job: String, number: Int, result: BuildResult, user: String? = "alice", ts: Long = 1_000) = BuildInfo(
        url = "https://ci/job/$job/$number/", number = number, result = result, timestamp = ts,
        durationMillis = 10, estimatedDurationMillis = 10, displayName = "$job #$number", jobFullName = job,
        jobUrl = "https://ci/job/$job/", causeUserIds = setOfNotNull(user), causeDescriptions = emptyList(),
        parameters = emptyList(),
    )

    @Test
    fun `running to finished is reported once`() {
        val store = BuildStore()
        assertNull(store.upsert("s", build("a", 1, BuildResult.RUNNING), BuildSource.OWN, "alice"))
        assertNotNull(store.upsert("s", build("a", 1, BuildResult.FAILURE), BuildSource.OWN, "alice"))
        assertNull(store.upsert("s", build("a", 1, BuildResult.FAILURE), BuildSource.OWN, "alice"))
        assertTrue(store.get("s", "https://ci/job/a/1/")!!.mine)
    }

    @Test
    fun `first slow poll is silent, later new finished builds are reported`() {
        val store = BuildStore()
        assertTrue(store.applySlowPoll("s", listOf(build("a", 5, BuildResult.SUCCESS) to BuildSource.PINNED), null).isEmpty())
        val finished = store.applySlowPoll("s", listOf(build("a", 6, BuildResult.FAILURE, user = "bob") to BuildSource.PINNED), "alice")
        assertEquals(listOf(6), finished.map { it.build.number })
        assertTrue(!finished.single().mine)
        // Build 5 is no longer part of the pinned result and therefore dropped.
        assertNull(store.get("s", "https://ci/job/a/5/"))
    }

    @Test
    fun `triggered build seen finished for the first time is reported`() {
        val store = BuildStore()
        assertNotNull(store.upsert("s", build("a", 1, BuildResult.SUCCESS, user = null), BuildSource.TRIGGERED, "alice"))
    }

    @Test
    fun `own history is pruned by age`() {
        val now = 10L * 60 * 60 * 1000
        val store = BuildStore { now }
        store.upsert("s", build("a", 1, BuildResult.SUCCESS, ts = 0), BuildSource.OWN, "alice")
        store.upsert("s", build("a", 2, BuildResult.SUCCESS, ts = now - 1000), BuildSource.OWN, "alice")
        store.prune(hours = 1)
        assertEquals(listOf(2), store.all().map { it.build.number })
    }

    @Test
    fun `crawl follows unexplored folders`() = runBlocking {
        fun job(name: String, cls: String = "hudson.model.FreeStyleProject") = JobRef(name.substringAfterLast('/'), name, "https://ci/$name/", cls)
        val folder = job("f", "com.cloudbees.hudson.plugins.folder.Folder")
        val result = JobIndexService.crawl { parent ->
            if (parent == null) listOf(job("a"), folder) to listOf(folder) else listOf(job("f/b")) to emptyList()
        }
        assertEquals(listOf("a", "f", "f/b"), result.map { it.fullName })
    }

    @Test
    fun `ranking puts pinned first, hides filtered, prefers name matches`() {
        val jobs = listOf(
            JobRef("deploy", "platform/deploy", "u1", "hudson.model.FreeStyleProject"),
            JobRef("deploy-tools", "deploy-tools", "u2", "com.cloudbees.hudson.plugins.folder.Folder"),
            JobRef("build", "platform/build", "u3", "hudson.model.FreeStyleProject"),
            JobRef("deploy", "playground/deploy", "u4", "hudson.model.FreeStyleProject"),
        ).map { "s" to it }
        val hidden = HiddenJobs(listOf(Regex("^playground/", RegexOption.IGNORE_CASE)))
        val ranked = JobIndexService.rank(jobs, "depl", hidden)
        assertEquals(listOf("u1", "u2"), ranked.map { it.job.url })
        assertEquals(listOf(0..3), ranked.first().match.nameRanges)
        val pinnedFirst = JobIndexService.rank(jobs, "", pinned = setOf("s u3"))
        assertEquals("u3", pinnedFirst.first().job.url)
        // Folders only show up when searching.
        assertTrue(pinnedFirst.none { it.job.isFolder })
    }

    @Test
    fun `sort modes order by name, path or recent use and can keep pins on top`() {
        val jobs = listOf(
            JobRef("zeta", "a/zeta", "u1", "hudson.model.FreeStyleProject"),
            JobRef("alpha", "b/alpha", "u2", "hudson.model.FreeStyleProject"),
            JobRef("mid", "c/mid", "u3", "hudson.model.FreeStyleProject"),
        ).map { "s" to it }
        fun urls(sort: de.pdenis.jenkspotter.settings.JobSort, pinnedFirst: Boolean = true) =
            JobIndexService.rank(jobs, "", pinned = setOf("s u3"), recent = mapOf("s u1" to 0), sort = sort, pinnedFirst = pinnedFirst).map { it.job.url }
        assertEquals(listOf("u3", "u2", "u1"), urls(de.pdenis.jenkspotter.settings.JobSort.NAME))
        assertEquals(listOf("u2", "u3", "u1"), urls(de.pdenis.jenkspotter.settings.JobSort.NAME, pinnedFirst = false))
        assertEquals(listOf("u1", "u2", "u3"), urls(de.pdenis.jenkspotter.settings.JobSort.PATH, pinnedFirst = false))
        assertEquals(listOf("u1", "u2", "u3"), urls(de.pdenis.jenkspotter.settings.JobSort.RECENT, pinnedFirst = false))
    }

    @Test
    fun `same job on two Jenkins is told apart by part of the Jenkins name`() {
        val job = JobRef("job-a", "job-a", "", "hudson.model.FreeStyleProject")
        val jobs = listOf("a" to job.copy(url = "ua"), "b" to job.copy(url = "ub"))
        val names = mapOf("a" to "Jenkins Alpha", "b" to "Jenkins Beta")
        fun urls(query: String) = JobIndexService.rank(jobs, query, serverNames = names).map { it.job.url }
        assertEquals(listOf("ub"), urls("beta job-a"))
        assertEquals(listOf("ub"), urls("@bet job"))
        assertEquals(listOf("ua"), urls("alpha: job"))
        // The matched part of the Jenkins name is highlighted.
        assertEquals(listOf(8..11), JobIndexService.rank(jobs, "beta job", serverNames = names).single().match.serverRanges)
        // With a single Jenkins its name is no search field, only @name picks it.
        assertEquals(emptyList<String>(), JobIndexService.rank(listOf(jobs[1]), "beta", serverNames = mapOf("b" to "Jenkins Beta")).map { it.job.url })
        assertEquals(listOf("ub"), JobIndexService.rank(listOf(jobs[1]), "@beta", serverNames = mapOf("b" to "Jenkins Beta")).map { it.job.url })
    }

    @Test
    fun `durations are shown without milliseconds`() {
        assertEquals("0 s", de.pdenis.jenkspotter.ui.BuildsPanel.formatDuration(120))
        assertEquals("45 s", de.pdenis.jenkspotter.ui.BuildsPanel.formatDuration(45_200))
        assertEquals("12 m 30 s", de.pdenis.jenkspotter.ui.BuildsPanel.formatDuration(750_000))
        assertEquals("1 h 5 m", de.pdenis.jenkspotter.ui.BuildsPanel.formatDuration(3_930_000))
    }

    @Test
    fun `watched builds are replaced by the next slow poll like pinned ones`() {
        val store = BuildStore()
        store.applySlowPoll("s", listOf(build("a", 1, BuildResult.SUCCESS, user = "bob") to BuildSource.WATCHED), "alice")
        assertTrue(BuildSource.WATCHED in store.get("s", "https://ci/job/a/1/")!!.sources)
        store.applySlowPoll("s", emptyList(), "alice")
        assertNull(store.get("s", "https://ci/job/a/1/"))
    }

    @Test
    fun `connection state distinguishes disabled, offline and errors`() {
        fun status(online: Boolean, reachable: Boolean) = ServerStatus("s", online, if (online) null else "x", "alice", 0, reachable)
        assertEquals(ConnectionState.DISABLED, ConnectionState.of(false, status(true, true)))
        assertEquals(ConnectionState.CONNECTING, ConnectionState.of(true, null))
        assertEquals(ConnectionState.CONNECTED, ConnectionState.of(true, status(true, true)))
        assertEquals(ConnectionState.ERROR, ConnectionState.of(true, status(false, true)))
        assertEquals(ConnectionState.OFFLINE, ConnectionState.of(true, status(false, false)))
    }

    @Test
    fun `user id comparison ignores case`() {
        val store = BuildStore()
        store.upsert("s", build("a", 1, BuildResult.RUNNING, user = "PDenis"), BuildSource.OWN, "pdenis")
        assertTrue(store.get("s", "https://ci/job/a/1/")!!.mine)
    }

    @Test
    fun `same build on two server entries is tracked separately`() {
        val store = BuildStore()
        store.upsert("s1", build("a", 1, BuildResult.RUNNING), BuildSource.OWN, "alice")
        store.upsert("s2", build("a", 1, BuildResult.RUNNING), BuildSource.OWN, "alice")
        store.applySlowPoll("s2", emptyList(), "alice")
        assertEquals(2, store.all().size)
    }

    @Test
    fun `builds listed only as running stay listed when they leave the executors`() {
        val store = BuildStore()
        val other = build("other", 1, BuildResult.RUNNING, user = "bob")
        val pinnedToo = build("pinned", 2, BuildResult.RUNNING, user = "bob")
        store.upsert("s", other, BuildSource.RUNNING, "alice")
        store.upsert("s", pinnedToo, BuildSource.RUNNING, "alice")
        store.upsert("s", pinnedToo, BuildSource.PINNED, "alice")
        assertEquals(emptyList<TrackedBuild>(), store.endedRunning("s", setOf(other.url, pinnedToo.url)))

        // Both left the executors: the running-only one is returned to fetch its result, nothing is dropped.
        assertEquals(listOf(other.url), store.endedRunning("s", emptySet()).map { it.build.url })
        assertEquals(2, store.all().size)
        assertEquals(setOf(BuildSource.PINNED), store.all().first { it.build.url == pinnedToo.url }.sources)

        // With its result it is finished and is not asked for again.
        store.upsert("s", build("other", 1, BuildResult.SUCCESS, user = "bob"), BuildSource.RUNNING, "alice")
        assertEquals(emptyList<TrackedBuild>(), store.endedRunning("s", emptySet()))
        assertEquals(BuildResult.SUCCESS, store.all().first { it.build.url == other.url }.build.result)

        store.dropRunningOnly("s")
        assertEquals(listOf(pinnedToo.url), store.all().map { it.build.url })
    }

    @Test
    fun `finished builds listed only as running are pruned like the own history`() {
        val now = System.currentTimeMillis()
        val store = BuildStore()
        store.upsert("s", build("old", 1, BuildResult.SUCCESS, ts = 0, user = "bob"), BuildSource.RUNNING, "alice")
        store.upsert("s", build("new", 1, BuildResult.SUCCESS, ts = now - 1000, user = "bob"), BuildSource.RUNNING, "alice")
        store.prune(hours = 1)
        assertEquals(listOf("new"), store.all().map { it.build.jobFullName })
    }

    @Test
    fun `retainHistory drops builds that left the latest ones but keeps other sources`() {
        val store = BuildStore()
        store.upsert("s", build("a", 1, BuildResult.SUCCESS, user = "bob"), BuildSource.HISTORY, "alice")
        store.upsert("s", build("b", 1, BuildResult.FAILURE, user = "bob"), BuildSource.HISTORY, "alice")
        store.upsert("s", build("b", 1, BuildResult.FAILURE, user = "bob"), BuildSource.PINNED, "alice")
        store.upsert("s", build("c", 1, BuildResult.SUCCESS, user = "bob"), BuildSource.HISTORY, "alice")
        store.retainHistory("s", setOf("https://ci/job/c/1/"))
        assertNull(store.get("s", "https://ci/job/a/1/"))
        assertEquals(setOf(BuildSource.PINNED), store.get("s", "https://ci/job/b/1/")!!.sources)
        assertEquals(setOf(BuildSource.HISTORY), store.get("s", "https://ci/job/c/1/")!!.sources)
    }

    @Test
    fun `slow poll keeps the builds of a job that could not be asked`() {
        val store = BuildStore()
        store.applySlowPoll("s", listOf(build("a", 1, BuildResult.SUCCESS) to BuildSource.PINNED, build("b", 1, BuildResult.SUCCESS) to BuildSource.PINNED), null)
        // Job a failed to answer this time, job b answered with nothing.
        store.applySlowPoll("s", emptyList(), null) { tracked, source -> source == BuildSource.PINNED && tracked.build.jobFullName == "a" }
        assertNotNull(store.get("s", "https://ci/job/a/1/"))
        assertNull(store.get("s", "https://ci/job/b/1/"))
    }
}
