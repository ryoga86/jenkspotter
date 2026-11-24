package de.pdenis.jenkspotter.ui

import de.pdenis.jenkspotter.api.JobRef
import de.pdenis.jenkspotter.core.JobIndexService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainsGraphTest {
    private fun job(name: String, vararg next: String, disabled: Boolean = false) =
        JobRef(name, name, "u/$name", "hudson.model.FreeStyleProject", next.toList(), disabled = disabled)

    private fun model(vararg jobs: JobRef, of: String, root: String? = null): ChainsPopup.GraphModel {
        val state = JobIndexService.IndexState(jobs.toList())
        return ChainsPopup.GraphModel(state, state.byFullName.getValue(of), root?.let(state.byFullName::getValue))
    }

    private fun ChainsPopup.GraphModel.layers() = nodes.values.groupBy({ it.layer }, { it.fullName }).toSortedMap().values.map { it.sorted() }

    private fun ChainsPopup.GraphModel.edgeNames() = edges.map { "${it.first.fullName}>${it.second.fullName}" }.sorted()

    @Test
    fun `jobs before and after the job are laid out in steps, the longest way deciding`() {
        // build -> test -> deploy -> smoke, and hotfix -> deploy directly.
        val m = model(job("build", "test"), job("test", "deploy"), job("hotfix", "deploy"), job("deploy", "smoke"), job("smoke"), of = "deploy")
        assertEquals(listOf(listOf("build"), listOf("hotfix", "test"), listOf("deploy"), listOf("smoke")), m.layers())
        assertEquals(listOf("build>test", "deploy>smoke", "hotfix>deploy", "test>deploy"), m.edgeNames())
        assertFalse(m.truncated)
    }

    @Test
    fun `a job triggered directly and through another job sits behind both`() {
        val m = model(job("a", "b", "c"), job("b", "c"), job("c"), of = "a")
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), m.layers())
        assertEquals(listOf("a>b", "a>c", "b>c"), m.edgeNames())
    }

    @Test
    fun `a cycle does not hang and the job appears once`() {
        val m = model(job("a", "b"), job("b", "c"), job("c", "a"), of = "b")
        assertEquals(3, m.nodes.size)
        assertEquals(1, m.nodes.values.count { m.isCenter(it) })
    }

    @Test
    fun `nothing behind a disabled job is reached`() {
        val m = model(job("a", "b"), job("b", "c", disabled = true), job("c"), of = "a")
        assertTrue(m.nodes.getValue("a").reached)
        assertFalse(m.nodes.getValue("b").reached)
        assertFalse(m.nodes.getValue("c").reached)
    }

    @Test
    fun `a workflow shows everything its start triggers and marks what passes the job`() {
        // start -> left -> end, start -> right; seen from "left".
        val m = model(job("start", "left", "right"), job("left", "end"), job("right"), job("end"), of = "left", root = "start")
        assertEquals(listOf(listOf("start"), listOf("left", "right"), listOf("end")), m.layers())
        assertTrue(m.isOnPath(m.nodes.getValue("start")))
        assertTrue(m.isOnPath(m.nodes.getValue("end")))
        assertFalse(m.isOnPath(m.nodes.getValue("right")))
        // Complete paths, those through the job first.
        assertEquals(listOf("start>left>end", "start>right"), m.paths().map { p -> p.joinToString(">") { it.fullName } })
    }

    @Test
    fun `mermaid export lists jobs and edges and marks the job`() {
        val text = model(job("a", "b"), job("b"), of = "b").mermaid()
        assertTrue(text.startsWith("flowchart TD\n"))
        assertTrue(text.contains("j0[\"a\"]") && text.contains("j0 --> j1"))
        assertTrue(text.contains("style j1 fill:"))
    }
}
