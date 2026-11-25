package de.pdenis.jenkspotter.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.memberProperties

class JenkSpotterStateTest {
    @Test
    fun `the old status filter becomes red chips`() {
        val old = JenkSpotterState().apply { hiddenStatuses = mutableListOf("SUCCESS", "ABORTED", "NO_SUCH_STATUS") }
        val migrated = JenkSpotterSettings.migrate(old)
        assertEquals(listOf("SUCCESS=EXCLUDE", "ABORTED=EXCLUDE"), migrated.buildChips)
        assertTrue(migrated.hiddenStatuses.isEmpty())
        assertEquals(JenkSpotterSettings.STATE_VERSION, migrated.stateVersion)
    }

    @Test
    fun `chips set by a newer version are not overwritten`() {
        val state = JenkSpotterState().apply {
            hiddenStatuses = mutableListOf("SUCCESS")
            buildChips = mutableListOf("MINE=INCLUDE")
        }
        assertEquals(listOf("MINE=INCLUDE"), JenkSpotterSettings.migrate(state).buildChips)
        // An up-to-date state is left alone.
        val current = JenkSpotterState().apply { stateVersion = JenkSpotterSettings.STATE_VERSION; buildChips = mutableListOf() }
        assertTrue(JenkSpotterSettings.migrate(current).buildChips.isEmpty())
    }

    /** copy() is written by hand: a field forgotten there is silently reset by every settings change. */
    @Test
    fun `copy carries every field`() {
        val state = JenkSpotterState()
        val changed = mutableListOf<String>()
        @Suppress("UNCHECKED_CAST")
        for (property in JenkSpotterState::class.memberProperties.filterIsInstance<KMutableProperty1<JenkSpotterState, Any?>>()) {
            val value = property.get(state)
            val other: Any? = when (value) {
                is Boolean -> !value
                is Int -> value + 7
                is String -> value + "x"
                is Enum<*> -> value.javaClass.enumConstants.first { it != value }
                else -> null
            }
            // Lists and maps are checked by the tests of the features using them.
            if (other == null || property.name == "hiddenStatuses") continue
            property.set(state, other)
            changed += property.name
        }
        val copy = state.copy()
        for (property in JenkSpotterState::class.memberProperties.filter { it.name in changed }) {
            assertEquals(property.name, property.get(state), property.get(copy))
        }
        assertTrue(changed.size > 20)
    }
}
