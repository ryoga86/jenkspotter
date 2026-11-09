package de.pdenis.jenkspotter.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegexFilterDialogTest {
    private fun hides(pattern: String, name: String) = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(name)

    @Test
    fun `escape makes special characters literal`() {
        assertEquals("a\\.b\\(1\\)\\$", RegexFilterDialog.escape("a.b(1)$"))
        assertTrue(hides(RegexFilterDialog.escape("v1.2"), "app-v1.2"))
        assertFalse(hides(RegexFilterDialog.escape("v1.2"), "app-v1x2"))
    }

    @Test
    fun `and requires both parts in any order and stays flat`() {
        val p = RegexFilterDialog.and(RegexFilterDialog.and("aws", "deploy"), "-prod$")
        assertEquals("^(?=.*(?:aws))(?=.*(?:deploy))(?=.*(?:-prod$))", p)
        assertTrue(hides(p, "team/deploy-aws-prod"))
        assertFalse(hides(p, "team/deploy-aws-dev"))
        assertTrue(hides(RegexFilterDialog.and("^team/", "-prod$"), "team/x-prod"))
        assertFalse(hides(RegexFilterDialog.and("^team/", "-prod$"), "other/team/x-prod"))
    }

    @Test
    fun `examples do what their description says`() {
        val ex = RegexFilterDialog.EXAMPLES.toMap()
        assertTrue(hides(ex.getValue("filter.example.folder"), "Playground/x/y"))
        assertTrue(hides(ex.getValue("filter.example.folder"), "Playground"))
        assertFalse(hides(ex.getValue("filter.example.folder"), "Playground2/x"))
        assertTrue(hides(ex.getValue("filter.example.not"), "other/job"))
        assertFalse(hides(ex.getValue("filter.example.not"), "team/job"))
        assertTrue(hides(ex.getValue("filter.example.or"), "a-staging"))
    }
}
