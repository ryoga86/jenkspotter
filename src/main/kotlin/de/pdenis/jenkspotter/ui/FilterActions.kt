package de.pdenis.jenkspotter.ui

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.api.JenkinsPaths
import de.pdenis.jenkspotter.core.JobIndexService
import de.pdenis.jenkspotter.settings.FilterMode
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import de.pdenis.jenkspotter.settings.RegexFilter

/** Creating filter rules, from the "+ Filter" chip or straight from a job's context menu. */
object FilterActions {
    fun edit(project: Project, existing: RegexFilter?, preset: RegexFilter? = null) {
        val others = JenkSpotterSettings.getInstance().current.filters.filter { it.enabled && it.id != existing?.id }
        val dialog = RegexFilterDialog(project, existing ?: preset, knownJobs(), others, isNew = existing == null)
        if (dialog.showAndGet()) JenkSpotterSettings.getInstance().saveFilter(dialog.result())
    }

    /** Full names of all indexed buildable jobs (all servers, duplicates removed). */
    fun knownJobs(): List<String> {
        val index = JobIndexService.getInstance()
        return JenkSpotterSettings.getInstance().activeServers
            .flatMap { server -> index.state(server.id).jobs.filter { it.isBuildable }.map { it.fullName } }
            .distinct()
    }

    /** "team/app/main" (decoded) for display and regex matching. */
    fun decoded(fullName: String) = JenkinsPaths.displayName(fullName).replace(" / ", "/")

    /**
     * Context menu group for a job: hide it, hide one of its folders with everything inside, or
     * show only one of its folders. Each entry opens the filter dialog pre-filled, so it can be
     * adjusted before saving.
     */
    fun group(project: Project, fullName: () -> String?): ActionGroup =
        object : ActionGroup({ JenkSpotterBundle.message("filter.menu") }, true) {
            override fun getChildren(e: AnActionEvent?): Array<AnAction> {
                val name = fullName()?.let(::decoded) ?: return AnAction.EMPTY_ARRAY
                val folders = name.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" }
                val result = mutableListOf<AnAction>()
                result += preset(project, "filter.menu.hideJob", name, "^${RegexFilterDialog.escape(name)}$", FilterMode.EXCLUDE)
                if (folders.isNotEmpty()) result += Separator.create()
                folders.asReversed().forEach { folder ->
                    result += preset(project, "filter.menu.hideFolder", folder, folderRegex(folder), FilterMode.EXCLUDE)
                }
                if (folders.isNotEmpty()) result += Separator.create()
                folders.asReversed().forEach { folder ->
                    result += preset(project, "filter.menu.onlyFolder", folder, folderRegex(folder), FilterMode.INCLUDE)
                }
                return result.toTypedArray()
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = fullName() != null
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    fun folderRegex(folder: String) = "^${RegexFilterDialog.escape(folder)}(/|$)"

    private fun preset(project: Project, key: String, label: String, pattern: String, mode: FilterMode) =
        object : DumbAwareAction(JenkSpotterBundle.message(key, label)) {
            override fun actionPerformed(e: AnActionEvent) {
                edit(project, null, RegexFilter().apply { name = label; this.pattern = pattern; this.mode = mode })
            }
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        }
}
