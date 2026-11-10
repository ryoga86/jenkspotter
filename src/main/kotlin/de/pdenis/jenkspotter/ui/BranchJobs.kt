package de.pdenis.jenkspotter.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import de.pdenis.jenkspotter.api.JenkinsPaths
import de.pdenis.jenkspotter.api.JobRef
import de.pdenis.jenkspotter.core.BuildMonitorService
import de.pdenis.jenkspotter.core.JobIndexListener
import de.pdenis.jenkspotter.core.JobIndexService
import de.pdenis.jenkspotter.core.RepoMatch
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import de.pdenis.jenkspotter.settings.SettingsListener
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager

/**
 * Finds the Jenkins jobs that build the branches checked out in this project: the branch job of a
 * multibranch project whose name matches the repository (remote name or folder name). The result
 * is handed to the monitor, which then polls those jobs like pins.
 */
@Service(Service.Level.PROJECT)
class BranchJobs(private val project: Project) : Disposable {
    data class Match(val serverId: String, val job: JobRef, val branch: String, val repository: String)

    @Volatile
    var matches: List<Match> = emptyList()
        private set

    @Volatile
    var branches: Set<String> = emptySet()
        private set

    /** Git repositories of the project (remote URLs and root folder names), for "this project's jobs". */
    @Volatile
    var repos: RepoMatch = RepoMatch(emptyList(), emptyList())
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Called (on a pooled thread) after the repositories or matching jobs were looked up again. */
    fun onUpdate(listener: () -> Unit) {
        listeners += listener
    }

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val key = "project:" + project.locationHash

    fun start() {
        val bus = project.messageBus.connect(this)
        bus.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { schedule() })
        ApplicationManager.getApplication().messageBus.connect(this).apply {
            subscribe(JobIndexListener.TOPIC, JobIndexListener { schedule() })
            subscribe(SettingsListener.TOPIC, SettingsListener { schedule() })
        }
        schedule()
    }

    private fun schedule() {
        alarm.cancelAllRequests()
        alarm.addRequest(::update, 500)
    }

    private fun update() {
        if (project.isDisposed) return
        val repos = GitRepositoryManager.getInstance(project).repositories.mapNotNull { repo ->
            val branch = repo.currentBranchName ?: return@mapNotNull null
            val names = buildSet {
                repo.remotes.flatMap { it.urls }.forEach { add(repoName(it)) }
                add(repo.root.name)
            }.filter { it.isNotEmpty() }
            Triple(branch, names, repo.root.name)
        }
        branches = repos.map { it.first }.toSet()
        val all = GitRepositoryManager.getInstance(project).repositories
        this.repos = RepoMatch(all.flatMap { r -> r.remotes.flatMap { it.urls } }, all.map { it.root.name })
        val index = JobIndexService.getInstance()
        val result = mutableListOf<Match>()
        for (server in JenkSpotterSettings.getInstance().activeServers) {
            val state = index.state(server.id)
            val multibranch = state.jobs.filter { it.jobClass.contains("MultiBranch", ignoreCase = true) }
            for ((branch, names, root) in repos) {
                for (project in multibranch.filter { mb -> names.any { sameRepo(it, mb.name) } }) {
                    val fullName = project.fullName + "/" + JenkinsPaths.encodeSegment(branch).replace("%20", " ")
                    val job = state.byFullName[fullName]
                        ?: state.byFullName[project.fullName + "/" + branch.replace("/", "%2F")]
                        ?: continue
                    result += Match(server.id, job, branch, root)
                }
            }
        }
        matches = result.distinctBy { it.serverId to it.job.url }
        BuildMonitorService.getInstance().setBranchJobs(key, matches.map { it.serverId to it.job })
        listeners.forEach { it() }
    }

    fun isBranchJob(serverId: String, jobUrl: String) = matches.any { it.serverId == serverId && it.job.url == jobUrl }

    override fun dispose() {
        BuildMonitorService.getInstance().removeBranchJobs(key)
    }

    companion object {
        fun getInstance(project: Project): BranchJobs = project.service()

        /** "git@host:team/My-App.git" / "https://host/scm/team/my-app.git" -> "my-app". */
        fun repoName(url: String): String = url.trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git")

        /** Repository and multibranch project names usually match up to case and separators. */
        fun sameRepo(a: String, b: String): Boolean {
            fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
            val x = norm(a)
            val y = norm(b)
            return x.isNotEmpty() && x == y
        }
    }
}
