package de.pdenis.jenkspotter.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollingUtil
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.JenkSpotterIcons
import de.pdenis.jenkspotter.api.BuildInfo
import de.pdenis.jenkspotter.api.JenkinsPaths
import de.pdenis.jenkspotter.core.TrackedBuild
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.ListSelectionModel

/**
 * "Show Recent Builds" of a job from the jobs list: the last builds (number configurable in the
 * settings) in a popup. Enter opens the log, Shift+Enter the build in the browser.
 */
object RecentBuildsPopup {
    fun show(project: Project, serverId: String, jobUrl: String, fullName: String, where: RelativePoint) {
        JenkSpotterProjectScope.of(project).launch {
            BuildActions.runReporting(project) {
                val client = BuildActions.clientFor(serverId)
                val count = JenkSpotterSettings.getInstance().current.recentBuildsCount
                val builds = withBackgroundProgress(project, JenkSpotterBundle.message("recent.loading", JenkinsPaths.displayName(fullName))) {
                    withContext(Dispatchers.IO) { client.recentBuilds(jobUrl, count) }
                }
                withContext(Dispatchers.EDT) {
                    if (builds.isEmpty()) {
                        BuildActions.notify(project, JenkSpotterBundle.message("recent.none", JenkinsPaths.displayName(fullName)), com.intellij.notification.NotificationType.INFORMATION)
                    } else {
                        popup(project, serverId, fullName, builds).show(where)
                    }
                }
            }
        }
    }

    private fun popup(project: Project, serverId: String, fullName: String, builds: List<BuildInfo>): JBPopup {
        val list = JBList(builds).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = Renderer()
            selectedIndex = 0
            visibleRowCount = builds.size.coerceAtMost(15)
        }
        lateinit var popup: JBPopup
        fun choose(browser: Boolean) {
            val build = list.selectedValue ?: return
            popup.cancel()
            if (browser) BuildActions.openBuild(build)
            else BuildActions.openLog(project, TrackedBuild(serverId, build, emptySet(), mine = false))
        }
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    choose(browser = e.isShiftDown)
                    e.consume()
                }
            }
        })
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                choose(browser = false)
                return true
            }
        }.installOn(list)
        ScrollingUtil.installActions(list)
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(JBScrollPane(list).apply { border = JBUI.Borders.empty() }, list)
            .setProject(project)
            .setTitle(JenkSpotterBundle.message("recent.title", JenkinsPaths.displayName(fullName.substringAfterLast('/'))))
            .setAdText(JenkSpotterBundle.message("recent.hint"))
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .createPopup()
        return popup
    }

    /** Result icon, number, start, duration, first cause. Also used by the build info. */
    class Renderer : ColoredListCellRenderer<BuildInfo>() {
        override fun customizeCellRenderer(list: JList<out BuildInfo>, value: BuildInfo, index: Int, selected: Boolean, hasFocus: Boolean) {
            icon = JenkSpotterIcons.forResult(value.result)
            append("#${value.number}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append("   " + DateFormatUtil.formatPrettyDateTime(value.timestamp))
            val duration = if (value.building) JenkSpotterBundle.message("result.RUNNING") else BuildsPanel.formatDuration(value.durationMillis)
            append("   $duration", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            value.causeDescriptions.firstOrNull()?.let { append("   $it", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
        }
    }
}
