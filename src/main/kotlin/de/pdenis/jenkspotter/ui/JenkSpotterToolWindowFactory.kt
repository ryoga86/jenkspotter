package de.pdenis.jenkspotter.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.core.BuildMonitorService
import javax.swing.JComponent

class JenkSpotterToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun init(toolWindow: ToolWindow) {
        // The New UI stripe splits the name at the first space: "Jenk" over "Spotter".
        toolWindow.stripeTitle = STRIPE_TITLE
        toolWindow.title = "JenkSpotter"
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        BuildMonitorService.getInstance().ensureStarted()
        toolWindow.setTitleActions(listOf(JenkSpotterMainPanel.refreshAction(), JenkSpotterMainPanel.configureAction(project)))
        toolWindow.setAdditionalGearActions(DefaultActionGroup(ServersToggleGroup(), DiagnoseAction()))

        val main = JenkSpotterMainPanel(project)
        // The filter bar lives in the header, next to the tool window name.
        (toolWindow as? ToolWindowEx)?.setTabActions(HeaderBarAction(main.headerBar))

        val contents = toolWindow.contentManager
        val overview = contents.factory.createContent(main, "", false).apply {
            isCloseable = false
            setDisposer(main)
            putUserData(JobsPanel.KEY, main)
        }
        contents.addContent(overview)
        // No tab name while the overview is the only tab; once logs are open it needs one.
        contents.addContentManagerListener(object : ContentManagerListener {
            override fun contentAdded(event: ContentManagerEvent) = rename()
            override fun contentRemoved(event: ContentManagerEvent) = rename()
            private fun rename() {
                if (overview.manager == null) return
                overview.displayName = if (contents.contentCount > 1) JenkSpotterBundle.message("tab.overview") else ""
            }
        })
    }

    private class HeaderBarAction(private val bar: JComponent) : AnAction(), CustomComponentAction, DumbAware {
        override fun actionPerformed(e: AnActionEvent) = Unit
        override fun createCustomComponent(presentation: Presentation, place: String): JComponent = bar
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    companion object {
        const val STRIPE_TITLE = "Jenk Spotter"
    }
}
