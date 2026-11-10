package de.pdenis.jenkspotter.core

import de.pdenis.jenkspotter.settings.BuildColumn
import de.pdenis.jenkspotter.settings.FilterMode
import de.pdenis.jenkspotter.settings.JenkSpotterState
import de.pdenis.jenkspotter.settings.RegexFilter
import de.pdenis.jenkspotter.ui.FilterActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenJobsTest {
    private fun state(vararg rules: Pair<String, FilterMode>, disabled: String? = null) = JenkSpotterState().apply {
        filters = rules.map { (p, m) -> RegexFilter().apply { pattern = p; mode = m; enabled = p != disabled } }.toMutableList()
    }

    @Test
    fun `include rules keep only matching, exclude rules win`() {
        val hidden = HiddenJobs.of(state("^team/" to FilterMode.INCLUDE, "-tmp$" to FilterMode.EXCLUDE))
        assertFalse(hidden.isHidden("team/app"))
        assertTrue(hidden.isHidden("team/app-tmp"))
        assertTrue(hidden.isHidden("other/app"))
    }

    @Test
    fun `several include rules are combined with OR`() {
        val hidden = HiddenJobs.of(state("^a/" to FilterMode.INCLUDE, "^b/" to FilterMode.INCLUDE))
        assertFalse(hidden.isHidden("a/x"))
        assertFalse(hidden.isHidden("b/x"))
        assertTrue(hidden.isHidden("c/x"))
    }

    @Test
    fun `disabled rules do nothing`() {
        val hidden = HiddenJobs.of(state("^team/" to FilterMode.INCLUDE, disabled = "^team/"))
        assertFalse(hidden.isHidden("other/app"))
    }

    @Test
    fun `folder rule hides the folder and everything inside, decoded branch names included`() {
        val hidden = HiddenJobs.of(state(FilterActions.folderRegex("Playground/sub.x") to FilterMode.EXCLUDE))
        assertTrue(hidden.isFolderHidden("Playground/sub.x"))
        assertTrue(hidden.isHidden("Playground/sub.x/app/feature%2Flogin"))
        assertFalse(hidden.isHidden("Playground/subXx/app"))
        assertFalse(hidden.isHidden("Playground/sub.x2/app"))
    }

    @Test
    fun `folders on the way to an included path stay visible only if they match`() {
        val hidden = HiddenJobs.of(state(FilterActions.folderRegex("team") to FilterMode.INCLUDE))
        assertFalse(hidden.isFolderHidden("team"))
        assertTrue(hidden.isFolderHidden("other"))
    }

    @Test
    fun `column list parsing ignores unknown and duplicate names`() {
        assertEquals(listOf(BuildColumn.SOURCE, BuildColumn.STATUS), BuildColumn.parse("SOURCE, status,FOO,SOURCE"))
        assertEquals(BuildColumn.DEFAULT, BuildColumn.parse(JenkSpotterState().buildColumns))
    }

    @Test
    fun `chain is described transitively`() {
        val node = ChainNode("team/middle", "u", listOf(ChainNode("team/last", "u2", emptyList()), ChainNode("team/other", null, emptyList())))
        assertEquals("middle \u2192 (last, other)", node.describe())
        assertEquals(3, node.size)
        assertEquals("a \u2192 b", ChainNode("a", null, listOf(ChainNode("x/b", null, emptyList()))).describe())
    }

    @Test
    fun `a disabled job cuts off the rest of the chain`() {
        val last = ChainNode("team/last", null, emptyList(), disabled = true)
        val chain = ChainNode("team/middle", null, listOf(last, ChainNode("team/other", null, emptyList())))
        assertEquals(3, chain.size)
        assertEquals(2, chain.activeSize)
        assertEquals(0, ChainNode("team/off", null, listOf(ChainNode("team/below", null, emptyList())), disabled = true).activeSize)
    }

    @Test
    fun `disabled jobs are read from the ball color`() {
        val json = { color: String -> kotlinx.serialization.json.Json.parseToJsonElement("""{"name":"a","url":"http://j/job/a/","_class":"hudson.model.FreeStyleProject","color":"$color"}""") as kotlinx.serialization.json.JsonObject }
        assertEquals(true, de.pdenis.jenkspotter.api.JenkinsJson.parseJob("http://j/", json("disabled"))?.disabled)
        assertEquals(false, de.pdenis.jenkspotter.api.JenkinsJson.parseJob("http://j/", json("blue_anime"))?.disabled)
    }
}
