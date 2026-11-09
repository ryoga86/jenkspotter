package de.pdenis.jenkspotter.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.project.DumbAwareToggleAction
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import de.pdenis.jenkspotter.settings.JenkSpotterState

/** "Show" menus (RepoLeap style): toggles stay open so several details can be switched at once. */
object ViewOptions {
    fun menu(key: String, children: List<AnAction>): AnAction =
        DefaultActionGroup({ JenkSpotterBundle.message(key) }, true).apply {
            templatePresentation.icon = AllIcons.Actions.Show
            addAll(children)
        }

    fun toggle(key: String, get: JenkSpotterState.() -> Boolean, set: JenkSpotterState.(Boolean) -> Unit): AnAction =
        object : DumbAwareToggleAction({ JenkSpotterBundle.message(key) }) {
            override fun isSelected(e: AnActionEvent) = JenkSpotterSettings.getInstance().current.get()
            override fun setSelected(e: AnActionEvent, state: Boolean) =
                JenkSpotterSettings.getInstance().updateView { set(state) }
            override fun update(e: AnActionEvent) {
                super.update(e)
                e.presentation.keepPopupOnPerform = KeepPopupOnPerform.Always
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
}
