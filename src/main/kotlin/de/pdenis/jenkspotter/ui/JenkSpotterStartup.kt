package de.pdenis.jenkspotter.ui

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.core.BuildMonitorService
import java.awt.datatransfer.StringSelection

class JenkSpotterStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        BuildMonitorService.getInstance().ensureStarted()
        BranchJobs.getInstance(project).start()
    }
}

/** Tools menu / Find Action entry that jumps straight into the job search. */
class SearchJobsAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(LogConsoleManager.TOOL_WINDOW_ID) ?: return
        toolWindow.activate {
            val content = toolWindow.contentManager.contents.firstOrNull { it.getUserData(JobsPanel.KEY) != null } ?: return@activate
            toolWindow.contentManager.setSelectedContent(content)
            content.getUserData(JobsPanel.KEY)?.jobs?.focusSearch()
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** Global shortcut: fuzzy job search popup; the chosen job is shown and selected in the tool window. */
class GotoJobAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        JobSearchPopup.show(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** Opens the JenkSpotter tool window and selects a job in its jobs list. */
object JobNavigator {
    fun reveal(project: Project, serverId: String, url: String, query: String) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(LogConsoleManager.TOOL_WINDOW_ID) ?: return
        toolWindow.activate {
            val content = toolWindow.contentManager.contents.firstOrNull { it.getUserData(JobsPanel.KEY) != null } ?: return@activate
            toolWindow.contentManager.setSelectedContent(content)
            content.getUserData(JobsPanel.KEY)?.reveal(serverId, url, query)
        }
    }
}

class RefreshBuildsAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = BuildMonitorService.getInstance().refresh()
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** Writes what JenkSpotter sees on each server to idea.log and the clipboard. */
class DiagnoseAction : DumbAwareAction({ JenkSpotterBundle.message("action.diagnose") }) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        ApplicationManager.getApplication().executeOnPooledThread {
            val report = BuildMonitorService.getInstance().diagnose()
            ApplicationManager.getApplication().invokeLater {
                CopyPasteManager.getInstance().setContents(StringSelection(report))
                BuildActions.notify(project, JenkSpotterBundle.message("notification.diagnosed"), NotificationType.INFORMATION)
            }
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** Gear menu / Tools menu: switch configured Jenkins servers on and off without opening the settings. */
class ServersToggleGroup : com.intellij.openapi.actionSystem.ActionGroup({ JenkSpotterBundle.message("action.servers") }, true), com.intellij.openapi.project.DumbAware {
    override fun getChildren(e: AnActionEvent?): Array<com.intellij.openapi.actionSystem.AnAction> =
        de.pdenis.jenkspotter.settings.JenkSpotterSettings.getInstance().current.servers.map { server ->
            object : com.intellij.openapi.project.DumbAwareToggleAction(server.displayName) {
                override fun isSelected(e: AnActionEvent) =
                    de.pdenis.jenkspotter.settings.JenkSpotterSettings.getInstance().server(server.id)?.enabled == true
                override fun setSelected(e: AnActionEvent, state: Boolean) =
                    de.pdenis.jenkspotter.settings.JenkSpotterSettings.getInstance().setServerEnabled(server.id, state)
                override fun update(e: AnActionEvent) {
                    super.update(e)
                    e.presentation.keepPopupOnPerform = com.intellij.openapi.actionSystem.KeepPopupOnPerform.Always
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            }
        }.toTypedArray()

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

/** Pins the job behind a URL copied from the browser (classic or Blue Ocean, job or build page). */
object PinFromUrl {
    /** Returns true if [text] was a URL of a configured server and the job got pinned. */
    fun pin(project: Project?, text: String): Boolean {
        val settings = de.pdenis.jenkspotter.settings.JenkSpotterSettings.getInstance()
        val servers = settings.activeServers
        val parsed = JenkinsUi.parse(text, servers.map { it.url }) ?: return false
        val server = servers.firstOrNull { de.pdenis.jenkspotter.api.JenkinsPaths.normalizeBase(it.url).equals(parsed.baseUrl, true) } ?: return false
        val index = de.pdenis.jenkspotter.core.JobIndexService.getInstance()
        val job = index.jobByFullName(server.id, parsed.fullName)
        val url = job?.url ?: de.pdenis.jenkspotter.api.JenkinsPaths.jobUrl(server.url, parsed.fullName)
        if (!settings.isPinned(server.id, url)) settings.togglePin(server.id, parsed.fullName, url)
        BuildActions.notify(project, JenkSpotterBundle.message("notification.pinned", de.pdenis.jenkspotter.api.JenkinsPaths.displayName(parsed.fullName)), NotificationType.INFORMATION)
        return true
    }

    fun ask(project: Project) {
        val clipboard = runCatching {
            CopyPasteManager.getInstance().getContents<String>(java.awt.datatransfer.DataFlavor.stringFlavor)
        }.getOrNull()?.takeIf { it.startsWith("http") }
        val text = com.intellij.openapi.ui.Messages.showInputDialog(
            project, JenkSpotterBundle.message("pinUrl.prompt"), JenkSpotterBundle.message("pinUrl.title"), null, clipboard, null,
        ) ?: return
        if (!pin(project, text)) {
            com.intellij.openapi.ui.Messages.showWarningDialog(project, JenkSpotterBundle.message("pinUrl.unknown"), JenkSpotterBundle.message("pinUrl.title"))
        }
    }
}
