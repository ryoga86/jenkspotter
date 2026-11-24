package de.pdenis.jenkspotter.ui

import de.pdenis.jenkspotter.api.BuildInfo
import de.pdenis.jenkspotter.api.BuildResult
import de.pdenis.jenkspotter.core.BuildSource
import de.pdenis.jenkspotter.core.TrackedBuild
import de.pdenis.jenkspotter.settings.BuildChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildTableLogicTest {
    private fun row(job: String, depth: Int = 0): BuildRow = BuildRow.Build(
        TrackedBuild(
            "s",
            BuildInfo(
                url = "https://ci/job/$job/1/", number = 1, result = BuildResult.SUCCESS, timestamp = 0, durationMillis = 1,
                estimatedDurationMillis = 1, displayName = job, jobFullName = job, jobUrl = "https://ci/job/$job/",
                causeUserIds = emptySet(), causeDescriptions = emptyList(), parameters = emptyList(),
            ),
            setOf(BuildSource.OWN), mine = true,
        ),
        depth,
    )

    /** A failed build of mine. */
    private val failedMine: (BuildChip) -> Boolean = { it == BuildChip.FAILURE || it == BuildChip.MINE }

    @Test
    fun `without chips everything passes`() {
        assertTrue(BuildTableLogic.passes(emptyMap(), failedMine))
    }

    @Test
    fun `a red chip hides what it matches, whatever is green`() {
        assertFalse(BuildTableLogic.passes(mapOf(BuildChip.FAILURE to ChipState.EXCLUDE, BuildChip.MINE to ChipState.INCLUDE), failedMine))
        assertTrue(BuildTableLogic.passes(mapOf(BuildChip.SUCCESS to ChipState.EXCLUDE), failedMine))
    }

    @Test
    fun `green chips of one kind are alternatives, the two kinds must both agree`() {
        // Failed or unstable: the failed build passes.
        assertTrue(BuildTableLogic.passes(mapOf(BuildChip.FAILURE to ChipState.INCLUDE, BuildChip.UNSTABLE to ChipState.INCLUDE), failedMine))
        // Only successful ones: it does not.
        assertFalse(BuildTableLogic.passes(mapOf(BuildChip.SUCCESS to ChipState.INCLUDE), failedMine))
        // Failed and mine: passes. Failed and pinned: it is not pinned.
        assertTrue(BuildTableLogic.passes(mapOf(BuildChip.FAILURE to ChipState.INCLUDE, BuildChip.MINE to ChipState.INCLUDE), failedMine))
        assertFalse(BuildTableLogic.passes(mapOf(BuildChip.FAILURE to ChipState.INCLUDE, BuildChip.PINNED to ChipState.INCLUDE), failedMine))
    }

    @Test
    fun `queued rows pass unless hidden or another status is asked for`() {
        assertTrue(BuildTableLogic.statusPasses(emptyMap(), BuildChip.QUEUED))
        assertTrue(BuildTableLogic.statusPasses(mapOf(BuildChip.MINE to ChipState.INCLUDE), BuildChip.QUEUED))
        assertTrue(BuildTableLogic.statusPasses(mapOf(BuildChip.QUEUED to ChipState.INCLUDE), BuildChip.QUEUED))
        assertFalse(BuildTableLogic.statusPasses(mapOf(BuildChip.QUEUED to ChipState.EXCLUDE), BuildChip.QUEUED))
        assertFalse(BuildTableLogic.statusPasses(mapOf(BuildChip.FAILURE to ChipState.INCLUDE), BuildChip.QUEUED))
    }

    @Test
    fun `follow-ups stay with the build that started them, also across pages`() {
        val rows = listOf(row("a"), row("a-deploy", 1), row("a-smoke", 2), row("b"), row("c"), row("c-deploy", 1))
        val groups = BuildTableLogic.groups(rows)
        assertEquals(listOf(3, 1, 2), groups.map { it.size })
        assertEquals(2, BuildTableLogic.pageCount(groups.size, 2))
        // Page 0 holds a (with its two follow-ups) and b; c-deploy is on page 1 with c.
        assertEquals(4, BuildTableLogic.page(groups, 0, 2).flatten().size)
        assertEquals(1, BuildTableLogic.pageOf(groups, rows[5].key, 2))
        assertEquals(0, BuildTableLogic.pageOf(groups, rows[2].key, 2))
        assertNull(BuildTableLogic.pageOf(groups, "s https://ci/job/gone/1/", 2))
        assertEquals(1, BuildTableLogic.pageCount(0, 2))
    }
}
