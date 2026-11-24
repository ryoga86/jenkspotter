package de.pdenis.jenkspotter.core

import de.pdenis.jenkspotter.api.JobRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChainPositionTest {
    private fun job(name: String, vararg next: String, disabled: Boolean = false) =
        JobRef(name, name, "u/$name", "hudson.model.FreeStyleProject", next.toList(), disabled = disabled)

    private fun position(vararg jobs: JobRef, of: String): ChainPosition {
        val state = JobIndexService.IndexState(jobs.toList())
        return ChainPosition(JobIndexService.upstreamPaths(state, state.byFullName.getValue(of)), state.byFullName.getValue(of))
    }

    private fun ChainPosition.names() = paths.map { p -> p.joinToString(">") { it.name } }

    @Test
    fun `a job triggered by two jobs is in two chains, the longest first`() {
        val p = position(job("trigger", "x"), job("build", "test"), job("test", "x"), job("x"), of = "x")
        assertEquals(listOf("build>test", "trigger"), p.names())
        assertEquals(listOf("build", "trigger"), p.chains.map { it.first().name })
    }

    @Test
    fun `two paths from the same starting job are one chain`() {
        val p = position(job("root", "a", "b"), job("a", "x"), job("b", "x"), job("x"), of = "x")
        assertEquals(2, p.paths.size)
        assertEquals(1, p.chains.size)
    }

    @Test
    fun `a disabled job does not block while another job still triggers it`() {
        val p = position(job("old", "x", disabled = true), job("trigger", "x"), job("x"), of = "x")
        assertNull(p.blockedBy)
    }

    @Test
    fun `the longest way up is found in a diamond, and a wide graph stays cheap`() {
        // Ten layers of three jobs, every job triggering all three of the next layer: 3^9 paths to the last one.
        val layers = (0 until 10).map { l -> (0 until 3).map { "l$l-$it" } }
        val jobs = layers.mapIndexed { l, names -> names.map { job(it, *(layers.getOrNull(l + 1)?.toTypedArray() ?: emptyArray())) } }.flatten()
        val state = JobIndexService.IndexState(jobs)
        val path = JobIndexService.upstreamPath(state, state.byFullName.getValue("l9-0"))
        assertEquals(7, path.size)
        assertEquals("l8", path.last().name.substringBefore('-'))
    }

    @Test
    fun `a job is not triggered when every path has a disabled job`() {
        val p = position(job("a", "b"), job("b", "x", disabled = true), job("c", "x", disabled = true), job("x"), of = "x")
        assertEquals("b", p.blockedBy?.name)
    }

    @Test
    fun `paths survive cycles and a job without triggers has none`() {
        val jobs = listOf(job("build", "test"), job("test", "deploy"), job("hotfix", "deploy"), job("deploy", "build"))
        val state = JobIndexService.IndexState(jobs)
        val paths = JobIndexService.upstreamPaths(state, state.byFullName.getValue("deploy"))
        assertEquals(listOf("build>test", "hotfix"), paths.map { p -> p.joinToString(">") { it.name } })
        assertEquals(emptyList<List<JobRef>>(), JobIndexService.upstreamPaths(state, job("lonely")))
    }
}
