package de.pdenis.jenkspotter.ui

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.core.BuildMonitorService

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

    companion object {
        const val STRIPE_TITLE = "JenkSpotter"
    }
}
