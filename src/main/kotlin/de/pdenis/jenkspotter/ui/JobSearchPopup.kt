package de.pdenis.jenkspotter.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollingUtil
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.core.HiddenJobs
import de.pdenis.jenkspotter.core.JobHit
import de.pdenis.jenkspotter.core.JobIndexService
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * "Go to Jenkins Job": fuzzy search over all enabled Jenkins from anywhere in the IDE. Words may
 * also match the Jenkins name ("dev deploy"), `@dev` or `dev:` requires it. Enter shows the job
 * selected in the JenkSpotter jobs list, Shift+Enter builds it right away.
 */
class JobSearchPopup private constructor(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val search = SearchTextField(false)
    private val model = DefaultListModel<JobHit>()
    private val list = JBList(model)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private var popup: JBPopup? = null

    init {
        border = JBUI.Borders.empty(4)
        search.textEditor.emptyText.text = JenkSpotterBundle.message("goto.searchHint")
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        // Always with the Jenkins name (and its matched letters) when there is more than one.
        list.cellRenderer = JobHitRenderer {
            if (JenkSpotterSettings.getInstance().activeServers.size > 1) JobHitRenderer.ServerLabel.PREFIX else JobHitRenderer.ServerLabel.NONE
        }
        list.emptyText.text = JenkSpotterBundle.message("jobs.empty")
        add(search, BorderLayout.NORTH)
        add(JBScrollPane(list).apply { border = JBUI.Borders.emptyTop(4) }, BorderLayout.CENTER)
        preferredSize = JBUI.size(640, 380)

        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = scheduleSearch()
        })
        search.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val handled = when (e.keyCode) {
                    KeyEvent.VK_DOWN -> { ScrollingUtil.moveDown(list, 0); true }
                    KeyEvent.VK_UP -> { ScrollingUtil.moveUp(list, 0); true }
                    KeyEvent.VK_PAGE_DOWN -> { ScrollingUtil.movePageDown(list); true }
                    KeyEvent.VK_PAGE_UP -> { ScrollingUtil.movePageUp(list); true }
                    KeyEvent.VK_ENTER -> { choose(build = e.isShiftDown); true }
                    else -> false
                }
                if (handled) e.consume()
            }
        })
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                choose(build = false)
                return true
            }
        }.installOn(list)

        val settings = JenkSpotterSettings.getInstance()
        settings.activeServers.forEach { JobIndexService.getInstance().refreshIfNeeded(it) }
        scheduleSearch()
    }

    private fun scheduleSearch() {
        val serverIds = JenkSpotterSettings.getInstance().activeServers.map { it.id }
        // Same filter chips as the jobs list, otherwise the chosen job could not be shown there.
        val hidden = HiddenJobs.of(JenkSpotterSettings.getInstance().current)
        val query = search.text
        alarm.cancelAllRequests()
        alarm.addRequest({
            val results = JobIndexService.getInstance().search(serverIds, query, hidden, limit = MAX_RESULTS)
            ApplicationManager.getApplication().invokeLater({
                model.clear()
                results.forEach(model::addElement)
                if (!model.isEmpty) list.selectedIndex = 0
            }, { Disposer.isDisposed(this) })
        }, SEARCH_DELAY_MS)
    }

    private fun choose(build: Boolean) {
        val hit = list.selectedValue ?: return
        val query = search.text
        popup?.cancel()
        if (build && hit.job.canBuild) {
            BuildActions.trigger(project, hit.serverId, hit.job.url, hit.job.fullName, null)
        } else {
            JobNavigator.reveal(project, hit.serverId, hit.job.url, query)
        }
    }

    override fun dispose() = Unit

    companion object {
        private const val SEARCH_DELAY_MS = 40
        private const val MAX_RESULTS = 100

        fun show(project: Project) {
            val panel = JobSearchPopup(project)
            val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel.search.textEditor)
                .setProject(project)
                .setTitle(JenkSpotterBundle.message("goto.title"))
                .setAdText(JenkSpotterBundle.message("goto.hint"))
                .setMovable(true)
                .setResizable(true)
                .setRequestFocus(true)
                .setFocusable(true)
                .setCancelOnClickOutside(true)
                .setCancelKeyEnabled(true)
                .setDimensionServiceKey(project, "JenkSpotter.GotoJob", true)
                .createPopup()
            panel.popup = popup
            Disposer.register(popup, panel)
            popup.showCenteredInCurrentWindow(project)
        }
    }
}
