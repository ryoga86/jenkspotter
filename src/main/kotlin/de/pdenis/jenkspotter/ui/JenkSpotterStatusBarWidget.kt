package de.pdenis.jenkspotter.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ClickListener
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.JenkSpotterIcons
import de.pdenis.jenkspotter.api.BuildResult
import de.pdenis.jenkspotter.core.BuildMonitorListener
import de.pdenis.jenkspotter.core.BuildMonitorService
import de.pdenis.jenkspotter.core.MonitorSnapshot
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import java.awt.event.MouseEvent
import javax.swing.JComponent

class JenkSpotterStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = JenkSpotterStatusBarWidget.ID
    override fun getDisplayName() = JenkSpotterBundle.message("statusbar.name")
    override fun isAvailable(project: Project) = true
    override fun createWidget(project: Project): StatusBarWidget = JenkSpotterStatusBarWidget(project)
}

/** Butler head whose scar colour shows the overall state, plus "running / failing" counters. */
class JenkSpotterStatusBarWidget(private val project: Project) : CustomStatusBarWidget {
    @Volatile
    private var disposed = false
    private val label = JBLabel().apply { border = JBUI.Borders.empty(0, 4) }

    init {
        BuildMonitorService.getInstance().ensureStarted()
        object : ClickListener() {
            override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                ToolWindowManager.getInstance(project).getToolWindow(LogConsoleManager.TOOL_WINDOW_ID)?.activate(null)
                return true
            }
        }.installOn(label)
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(BuildMonitorListener.TOPIC, object : BuildMonitorListener {
                override fun snapshotChanged(snapshot: MonitorSnapshot) {
                    ApplicationManager.getApplication().invokeLater({ render(snapshot) }, { disposed || project.isDisposed })
                }
            })
        render(BuildMonitorService.getInstance().snapshot)
    }

    override fun ID() = ID
    override fun getComponent(): JComponent = label
    override fun install(statusBar: StatusBar) = Unit
    override fun dispose() {
        disposed = true
    }

    private fun render(snapshot: MonitorSnapshot) {
        val running = snapshot.runningCount
        val failing = snapshot.failingCount
        val lastOwn = snapshot.builds.filter { it.mine && !it.build.building }.maxByOrNull { it.build.timestamp }
        label.icon = when {
            snapshot.offlineServers.isNotEmpty() -> JenkSpotterIcons.StatusOffline
            snapshot.waitingInput.isNotEmpty() -> JenkSpotterIcons.StatusRunning
            running > 0 -> JenkSpotterIcons.StatusRunning
            failing > 0 -> JenkSpotterIcons.StatusFailed
            lastOwn?.build?.result == BuildResult.SUCCESS -> JenkSpotterIcons.StatusSuccess
            else -> JenkSpotterIcons.StatusIdle
        }
        // Latest build of the checked-out branch (multibranch job of this project's repository).
        val branch = BranchJobs.getInstance(project)
        val branchBuild = snapshot.builds.filter { branch.isBranchJob(it.serverId, it.build.jobUrl) }.maxByOrNull { it.build.timestamp }
        val waiting = snapshot.waitingInput.size
        label.text = buildList {
            if (branchBuild != null) {
                val mark = when (branchBuild.build.result) {
                    BuildResult.SUCCESS -> "\u2713"
                    BuildResult.FAILURE -> "\u2717"
                    BuildResult.UNSTABLE -> "!"
                    BuildResult.RUNNING -> "\u25B6"
                    else -> "\u2013"
                }
                add("${de.pdenis.jenkspotter.api.JenkinsPaths.displayName(branchBuild.build.jobFullName.substringAfterLast('/'))} #${branchBuild.build.number} $mark")
            }
            if (waiting > 0) add(JenkSpotterBundle.message("statusbar.waiting", waiting))
            if (running > 0) add(JenkSpotterBundle.message("statusbar.running", running))
            if (failing > 0) add(JenkSpotterBundle.message("statusbar.failing", failing))
        }.joinToString(" · ")

        val tooltip = StringBuilder("<html><b>JenkSpotter</b>")
        if (JenkSpotterSettings.getInstance().activeServers.isEmpty()) {
            tooltip.append("<br>").append(JenkSpotterBundle.message("status.noServers"))
        }
        snapshot.offlineServers.forEach { status ->
            val name = JenkSpotterSettings.getInstance().server(status.serverId)?.displayName ?: status.serverId
            tooltip.append("<br>").append(StringUtil.escapeXmlEntities(JenkSpotterBundle.message("status.offline", name, status.error ?: "")))
        }
        snapshot.builds.filter { it.build.building }.take(8).forEach {
            tooltip.append("<br>&#9654; ").append(StringUtil.escapeXmlEntities(it.build.displayName))
        }
        label.toolTipText = tooltip.append("</html>").toString()
    }

    companion object {
        const val ID = "JenkSpotter.StatusBar"
    }
}
