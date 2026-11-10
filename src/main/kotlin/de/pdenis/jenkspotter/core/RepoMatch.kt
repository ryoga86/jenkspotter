package de.pdenis.jenkspotter.core

import de.pdenis.jenkspotter.api.JobRef

/**
 * Decides whether a Jenkins job builds one of the Git repositories of the open project. Remote URLs
 * are compared by their last two path segments ("team/app"), so https, ssh and scp-like URLs of the
 * same repository match, also across Bitbucket's "/scm/" prefix and ports. Jobs Jenkins knows no
 * repository for (never built, Pipeline from inline script) fall back to their name.
 */
class RepoMatch(projectRemotes: Collection<String>, projectNames: Collection<String>) {
    private val keys = projectRemotes.mapNotNull(::key).toSet()
    private val names = (projectNames + projectRemotes.map(::name)).map(::norm).filter { it.isNotEmpty() }.toSet()

    val isEmpty: Boolean get() = keys.isEmpty() && names.isEmpty()

    fun matches(job: JobRef): Boolean =
        if (job.repos.isNotEmpty()) job.repos.any { key(it) in keys } else norm(job.name) in names

    /**
     * Jobs of [jobs] building the project, plus the folders above them (a multibranch project is
     * kept when one of its branches matches, or by name when it has no branch jobs indexed).
     */
    fun filter(jobs: List<JobRef>): Set<String> {
        val direct = jobs.filter { !it.isFolder && matches(it) }
        val result = direct.mapTo(HashSet()) { it.url }
        val fullNames = direct.map { it.fullName }
        for (folder in jobs.filter { it.isFolder }) {
            if (fullNames.any { it.startsWith(folder.fullName + "/") } || norm(folder.name) in names) result += folder.url
        }
        // Branch jobs of a multibranch project matched by name only, e.g. before their first build.
        val matchedFolders = jobs.filter { it.isFolder && it.url in result }.map { it.fullName + "/" }
        jobs.filter { j -> j.repos.isEmpty() && matchedFolders.any { j.fullName.startsWith(it) } }.forEach { result += it.url }
        return result
    }

    companion object {
        /** "git@host:7999/Team/App.git", "https://u@host/scm/team/app" -> "team/app"; null if it has no path. */
        fun key(url: String): String? {
            var rest = url.trim().removeSuffix("/").removeSuffix(".git")
            rest = rest.substringAfter("://", rest)
            rest = rest.substringAfter('@', rest)
            // scp-like "host:team/app" and "host:7999/team/app"
            rest = rest.replaceFirst(':', '/')
            val segments = rest.split('/').drop(1).filter { it.isNotEmpty() && it.toIntOrNull() == null }
            if (segments.size < 2) return null
            return segments.takeLast(2).joinToString("/").lowercase()
        }

        fun name(url: String): String = url.trim().removeSuffix("/").substringAfterLast('/').substringAfterLast(':').removeSuffix(".git")

        private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    }
}
