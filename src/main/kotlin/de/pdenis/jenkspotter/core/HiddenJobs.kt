package de.pdenis.jenkspotter.core

import de.pdenis.jenkspotter.settings.FilterMode
import de.pdenis.jenkspotter.settings.JenkSpotterState
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Active filter chips. A job/build is hidden if an EXCLUDE rule matches, or if INCLUDE rules exist
 * and none of them matches. Exclusions win, so "only team/" plus "hide -tmp" works as expected.
 */
class HiddenJobs(private val excludes: List<Regex>, private val includes: List<Regex> = emptyList()) {
    /** Matched against the decoded full name ("team/app/feature/x") so users can write natural patterns. */
    fun isHidden(fullName: String): Boolean {
        if (excludes.isEmpty() && includes.isEmpty()) return false
        val decoded = decode(fullName)
        if (excludes.any { it.containsMatchIn(decoded) }) return true
        return includes.isNotEmpty() && includes.none { it.containsMatchIn(decoded) }
    }

    /**
     * Folders stay visible while something inside them may still be included, otherwise an INCLUDE
     * rule like ^team/app/ would hide the folders on the way there.
     */
    fun isFolderHidden(fullName: String): Boolean {
        val decoded = decode(fullName)
        if (excludes.any { it.containsMatchIn(decoded) }) return true
        if (includes.isEmpty() || includes.any { it.containsMatchIn(decoded) }) return false
        return true
    }

    private fun decode(fullName: String) =
        runCatching { URLDecoder.decode(fullName.replace("+", "%2B"), StandardCharsets.UTF_8) }.getOrDefault(fullName)

    companion object {
        val NONE = HiddenJobs(emptyList())

        fun of(state: JenkSpotterState): HiddenJobs = of(state.filters)

        fun of(filters: List<de.pdenis.jenkspotter.settings.RegexFilter>): HiddenJobs {
            val active = filters.filter { it.enabled }
            fun regexes(mode: FilterMode) = active.filter { it.mode == mode }.mapNotNull { it.regexOrNull() }
            return HiddenJobs(regexes(FilterMode.EXCLUDE), regexes(FilterMode.INCLUDE))
        }
    }
}
