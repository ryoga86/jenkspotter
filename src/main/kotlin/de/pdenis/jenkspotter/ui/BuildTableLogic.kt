package de.pdenis.jenkspotter.ui

import de.pdenis.jenkspotter.settings.BuildChip

/** The rules of the builds table that need no UI: filter chips, grouping and pages. */
object BuildTableLogic {
    /**
     * Red chips hide what they match. Green chips of one kind (status / scope) let through only
     * what one of them matches; both kinds must agree.
     */
    fun passes(states: Map<BuildChip, ChipState>, matches: (BuildChip) -> Boolean): Boolean {
        if (states.any { it.value == ChipState.EXCLUDE && matches(it.key) }) return false
        return listOf(true, false).all { status ->
            val wanted = states.filter { it.value == ChipState.INCLUDE && it.key.status == status }.keys
            wanted.isEmpty() || wanted.any(matches)
        }
    }

    /** Whether rows that only have [chip]'s status (queued, expected) pass the status chips. */
    fun statusPasses(states: Map<BuildChip, ChipState>, chip: BuildChip): Boolean = when (states[chip]) {
        ChipState.EXCLUDE -> false
        ChipState.INCLUDE -> true
        else -> states.none { it.value == ChipState.INCLUDE && it.key.status }
    }

    /** A build with its follow-up and expected rows below it; such a group is sorted and paged as one. */
    fun groups(rows: List<BuildRow>): MutableList<MutableList<BuildRow>> {
        val groups = mutableListOf<MutableList<BuildRow>>()
        for (row in rows) {
            val nested = row is BuildRow.Expected || (row is BuildRow.Build && row.depth > 0)
            if (groups.isEmpty() || !nested) groups += mutableListOf(row) else groups.last() += row
        }
        return groups
    }

    fun pageCount(groups: Int, pageSize: Int): Int = ((groups + pageSize - 1) / pageSize).coerceAtLeast(1)

    /** Page (0-based) holding the row with [key], null if it is not listed. */
    fun pageOf(groups: List<List<BuildRow>>, key: String, pageSize: Int): Int? =
        groups.indexOfFirst { group -> group.any { it.key == key } }.takeIf { it >= 0 }?.let { it / pageSize }

    fun page(groups: List<List<BuildRow>>, page: Int, pageSize: Int): List<List<BuildRow>> = groups.drop(page * pageSize).take(pageSize)
}
