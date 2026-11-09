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
}
