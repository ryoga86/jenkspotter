package de.pdenis.jenkspotter.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Key
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollingUtil
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.JenkSpotterIcons
import de.pdenis.jenkspotter.api.JenkinsPaths
import de.pdenis.jenkspotter.core.JobHit
import de.pdenis.jenkspotter.core.JobIndexListener
import de.pdenis.jenkspotter.core.JobIndexService
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/** Fuzzy search (RepoLeap style) over the locally indexed jobs, with build / pin / open actions. */
class JobsPanel(private val project: Project, private val filter: SharedFilter) : SimpleToolWindowPanel(true, true), Disposable {
    @Volatile
    private var disposed = false

    private val search = SearchTextField(false)
    private val listModel = DefaultListModel<JobHit>()
    private val list = JBList(listModel)
    private val statusLabel = JBLabel().apply {
        border = JBUI.Borders.empty(2, 6)
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = UIUtil.getContextHelpForeground()
    }
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = Renderer()
        list.emptyText.text = JenkSpotterBundle.message("jobs.empty")
        search.textEditor.emptyText.text = JenkSpotterBundle.message("jobs.searchHint")

        val header = JPanel(BorderLayout(4, 0)).apply {
            border = JBUI.Borders.empty(2, 4)
            add(search, BorderLayout.CENTER)
            add(
                ActionManager.getInstance().createActionToolbar("JenkSpotter.Jobs", actionGroup(), true)
                    .also { it.targetComponent = list }.component,
                BorderLayout.EAST,
            )
        }
        toolbar = header
        setContent(JPanel(BorderLayout()).apply {
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        })

        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = scheduleSearch(keepSelection = false)
        })
        // The search field keeps the focus; navigation keys are forwarded to the list.
        search.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val handled = when (e.keyCode) {
                    KeyEvent.VK_DOWN -> { ScrollingUtil.moveDown(list, 0); true }
                    KeyEvent.VK_UP -> { ScrollingUtil.moveUp(list, 0); true }
                    KeyEvent.VK_PAGE_DOWN -> { ScrollingUtil.movePageDown(list); true }
                    KeyEvent.VK_PAGE_UP -> { ScrollingUtil.movePageUp(list); true }
                    // A pasted Jenkins URL is pinned instead of searched for.
                    KeyEvent.VK_ENTER -> {
                        if (search.text.trim().startsWith("http") && PinFromUrl.pin(project, search.text)) search.text = ""
                        else selected()?.let(::trigger)
                        true
                    }
                    KeyEvent.VK_ESCAPE -> if (search.text.isNotEmpty()) { search.text = ""; true } else false
                    else -> false
                }
                if (handled) e.consume()
            }
        })
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                selected()?.let(::trigger) ?: return false
                return true
            }
        }.installOn(list)
        PopupHandler.installPopupMenu(list, DefaultActionGroup().apply {
            add(actionGroup())
            addSeparator()
            add(FilterActions.group(project) { selected()?.job?.fullName })
        }, "JenkSpotter.JobsPopup")

        ApplicationManager.getApplication().messageBus.connect(this).subscribe(JobIndexListener.TOPIC, JobIndexListener { id ->
            if (filter.includes(id)) {
                ApplicationManager.getApplication().invokeLater({ scheduleSearch(keepSelection = true) }, { disposed || project.isDisposed })
            }
        })
        filter.onChange {
            refreshIndexes(force = false)
            scheduleSearch(keepSelection = true)
        }
        refreshIndexes(force = false)
        scheduleSearch(keepSelection = true)
    }

    fun focusSearch() {
        search.requestFocusInWindow()
        search.selectText()
    }

    private fun refreshIndexes(force: Boolean) {
        val settings = JenkSpotterSettings.getInstance()
        filter.serverIds().mapNotNull(settings::server).forEach { JobIndexService.getInstance().refreshIfNeeded(it, force) }
    }

    private fun scheduleSearch(keepSelection: Boolean) {
        if (disposed) return
        val serverIds = filter.serverIds()
        val hidden = filter.hidden()
        val query = search.text
        alarm.cancelAllRequests()
        alarm.addRequest({
            val index = JobIndexService.getInstance()
            val results = index.search(serverIds, query, hidden)
            val states = serverIds.associateWith { index.state(it) }
            val buildable = states.values.flatMap { st -> st.jobs.filter { it.isBuildable } }
            val counts = Counts(buildable.size, buildable.count { !hidden.isHidden(it.fullName) }, results.count { it.job.isBuildable }, query.isNotBlank())
            ApplicationManager.getApplication().invokeLater({ show(results, states, counts, keepSelection) }, { disposed || project.isDisposed })
        }, SEARCH_DELAY_MS)
    }

    private data class Counts(val total: Int, val visible: Int, val matches: Int, val searching: Boolean)

    private fun show(results: List<JobHit>, states: Map<String, JobIndexService.IndexState>, counts: Counts, keepSelection: Boolean) {
        val previous = list.selectedValue?.let { it.serverId to it.job.url }
        listModel.clear()
        results.forEach(listModel::addElement)
        if (!listModel.isEmpty) {
            val index = if (keepSelection) results.indexOfFirst { (it.serverId to it.job.url) == previous }.coerceAtLeast(0) else 0
            list.selectedIndex = index
            list.ensureIndexIsVisible(index)
        }
        val settings = JenkSpotterSettings.getInstance()
        statusLabel.text = when {
            states.isEmpty() -> JenkSpotterBundle.message("status.noServers")
            states.values.any { it.loading } -> JenkSpotterBundle.message("jobs.indexing", states.values.sumOf { it.jobs.size })
            else -> states.entries.firstNotNullOfOrNull { (id, s) ->
                s.error?.let { JenkSpotterBundle.message("jobs.indexError", settings.server(id)?.displayName.orEmpty(), it) }
            } ?: buildString {
                // Updates with every filter change: what is left after the filter chips.
                append(JenkSpotterBundle.message("jobs.visible", counts.visible, counts.total))
                if (counts.searching) append("  \u00B7  ").append(JenkSpotterBundle.message("jobs.matches", counts.matches))
                append("  \u00B7  ").append(JenkSpotterBundle.message("jobs.updated", DateFormatUtil.formatPrettyDateTime(states.values.minOf { it.updatedAt })))
            }
        }
    }

    private fun selected(): JobHit? = list.selectedValue

    private fun trigger(hit: JobHit) {
        if (hit.job.isFolder) return
        JenkSpotterSettings.getInstance().markRecent(hit.serverId, hit.job.url)
        BuildActions.trigger(project, hit.serverId, hit.job.url, hit.job.fullName, null)
    }

    private fun actionGroup() = DefaultActionGroup().apply {
        add(action("action.build", AllIcons.Actions.Execute, { selected()?.job?.isBuildable == true }) { selected()?.let(::trigger) })
        add(object : DumbAwareAction({ JenkSpotterBundle.message("action.pin") }, JenkSpotterIcons.Pinned) {
            override fun actionPerformed(e: AnActionEvent) {
                val hit = selected() ?: return
                JenkSpotterSettings.getInstance().togglePin(hit.serverId, hit.job.fullName, hit.job.url)
            }
            override fun update(e: AnActionEvent) {
                val hit = selected()
                e.presentation.isEnabled = hit != null && hit.job.isBuildable
                e.presentation.text = JenkSpotterBundle.message(if (hit?.pinned == true) "action.unpin" else "action.pin")
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        add(action("action.openInBrowser", AllIcons.General.Web, { selected() != null }) { selected()?.let { BuildActions.openInBrowser(it.job.url) } })
        add(action("action.jobInfo", AllIcons.General.ShowInfos, { selected() != null }) { selected()?.let { JobInfoDialog(project, it).show() } })
        add(action("action.pinFromUrl", AllIcons.General.Add, { true }) { PinFromUrl.ask(project) })
        addSeparator()
        add(action("action.refreshIndex", AllIcons.Actions.Refresh, { filter.serverIds().isNotEmpty() }) { refreshIndexes(force = true) })
        add(ViewOptions.menu("jobs.show", listOf(
            ViewOptions.toggle("jobs.show.path", { showJobPath }) { showJobPath = it },
            ViewOptions.toggle("jobs.show.folders", { showFolderEntries }) { showFolderEntries = it },
            ViewOptions.toggle("jobs.show.downstream", { showDownstream }) { showDownstream = it },
            ViewOptions.toggle("jobs.show.server", { showJobServer }) { showJobServer = it },
        )))
    }

    private fun action(key: String, icon: javax.swing.Icon, enabled: () -> Boolean, perform: () -> Unit) =
        object : DumbAwareAction({ JenkSpotterBundle.message(key) }, icon) {
            override fun actionPerformed(e: AnActionEvent) = perform()
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    override fun dispose() {
        disposed = true
    }

    private inner class Renderer : ColoredListCellRenderer<JobHit>() {
        override fun customizeCellRenderer(list: JList<out JobHit>, value: JobHit, index: Int, selected: Boolean, hasFocus: Boolean) {
            val base = when {
                value.job.isFolder -> JenkSpotterIcons.Folder
                value.job.isParameterized -> JenkSpotterIcons.JobWithParameters
                else -> JenkSpotterIcons.Job
            }
            icon = if (value.pinned) com.intellij.ui.RowIcon(base, JenkSpotterIcons.Pinned) else base
            val view = JenkSpotterSettings.getInstance().current
            appendHighlighted(value.name, value.match.nameRanges, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES, selected)
            if (view.showJobPath && value.path.isNotEmpty()) {
                append("   ")
                appendHighlighted(value.path, value.match.pathRanges, SimpleTextAttributes.GRAYED_ATTRIBUTES, selected)
            }
            val chain = if (view.showDownstream && value.job.downstream.isNotEmpty()) {
                JobIndexService.getInstance().chain(value.serverId, value.job)
            } else {
                emptyList()
            }
            val tips = mutableListOf<String>()
            if (value.job.isParameterized) tips += JenkSpotterBundle.message("jobs.parameters", value.job.parameterCount)
            if (chain.isNotEmpty()) {
                // Only the count here; the whole tree is in the tooltip and in "Job Info".
                append("   \u2192 ${chain.sumOf { it.size }}", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                tips += chainTooltip(chain)
            }
            toolTipText = if (tips.isEmpty()) null else "<html>" + tips.joinToString("<br>") + "</html>"
            if (view.showJobServer && filter.serverId == null && view.servers.size > 1) {
                append("   " + JenkSpotterSettings.getInstance().server(value.serverId)?.displayName.orEmpty(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }

        private fun appendHighlighted(text: String, ranges: List<IntRange>, plain: SimpleTextAttributes, selected: Boolean) {
            val highlight = if (selected) {
                SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD or SimpleTextAttributes.STYLE_UNDERLINE, null)
            } else {
                SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, MATCH_COLOR)
            }
            var position = 0
            for (range in ranges.sortedBy { it.first }) {
                val start = range.first.coerceIn(position, text.length)
                val end = (range.last + 1).coerceIn(start, text.length)
                if (start > position) append(text.substring(position, start), plain)
                if (end > start) append(text.substring(start, end), highlight)
                position = end
            }
            if (position < text.length) append(text.substring(position), plain)
        }
    }

    /** Indented tree of the whole chain (HTML fragment). */
    private fun chainTooltip(chain: List<de.pdenis.jenkspotter.core.ChainNode>): String =
        JenkSpotterBundle.message("jobs.chain", chain.sumOf { it.size }) + JobInfoDialog.chainHtml(chain)

    companion object {
        val KEY: Key<JenkSpotterMainPanel> = Key.create("JenkSpotter.MainPanel")
        private const val SEARCH_DELAY_MS = 40
        private val MATCH_COLOR = JBColor.namedColor("JenkSpotter.matchForeground", JBColor(0xC25A0A, 0xE08855))
    }
}
