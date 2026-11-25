package de.pdenis.jenkspotter.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Key
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.RowIcon
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
import de.pdenis.jenkspotter.core.BuildMonitorListener
import de.pdenis.jenkspotter.core.BuildMonitorService
import de.pdenis.jenkspotter.core.ConnectionState
import de.pdenis.jenkspotter.core.JobHit
import de.pdenis.jenkspotter.core.JobIndexListener
import de.pdenis.jenkspotter.core.JobIndexService
import de.pdenis.jenkspotter.core.MonitorSnapshot
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import de.pdenis.jenkspotter.settings.JobLayout
import de.pdenis.jenkspotter.settings.JobSort
import com.intellij.ui.awt.RelativePoint
import com.intellij.openapi.ui.popup.JBPopupFactory
import de.pdenis.jenkspotter.api.JobRef
import java.awt.BorderLayout
import javax.swing.JComponent
import java.awt.Component
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/** Row of the jobs list: a collapsible Jenkins or folder node (tree layouts) or a job. */
sealed class JobRow {
    data class Server(val serverId: String, val count: Int, val collapsed: Boolean) : JobRow()
    /** [multiBranch]: the folder is a multibranch pipeline, its jobs are branches. */
    /** [path]: shown name (relative to [JobsPanel]'s folder scope); [key]: full display path, for the collapsed state. */
    data class Folder(
        val serverId: String, val path: String, val count: Int, val collapsed: Boolean, val depth: Int,
        val multiBranch: Boolean = false, val fullName: String = "", val key: String = path,
    ) : JobRow()
    /**
     * [folder]: path of the folder node the job is listed under; [depth]: nesting level;
     * [branch]: a branch below its multibranch pipeline node, drawn with a tree connector.
     */
    data class Job(val hit: JobHit, val folder: String? = null, val depth: Int = 0, val branch: JobHitRenderer.Connector? = null) : JobRow()
}

/** Fuzzy search (RepoLeap style) over the locally indexed jobs, with build / pin / watch / open actions. */
class JobsPanel(private val project: Project, private val filter: SharedFilter) : SimpleToolWindowPanel(true, true), Disposable {
    @Volatile
    private var disposed = false

    private val search = SearchTextField(false)
    private val listModel = DefaultListModel<JobRow>()
    private val list = JBList(listModel)
    private val statusLabel = JBLabel().apply {
        border = JBUI.Borders.empty(2, 6)
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = UIUtil.getContextHelpForeground()
    }
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val renderer = Renderer()

    /** Folder or multibranch pipeline the list is narrowed to (double-click), null = every job. */
    private data class FolderScope(val serverId: String, val fullName: String, val multiBranch: Boolean)
    private var scope: FolderScope? = null

    /** Above the list while narrowed to a folder: its path as breadcrumbs and a button back to all jobs. */
    private val scopeBar = JPanel(BorderLayout()).apply {
        isVisible = false
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow.borderColor()), JBUI.Borders.empty(2, 6))
    }

    /** Job under the mouse whose triggers are tinted, as "serverId url". */
    private var hovered: String? = null

    /** "serverId url" of the jobs triggering / triggered by the [hovered] one, directly or not. */
    private var related: Map<String, JobHitRenderer.Relation> = emptyMap()

    /** Search text the listed rows belong to; while typing the list lags a moment behind the field. */
    private var shownQuery: String? = null

    /** Job to select once it shows up in the results, see [reveal]. */
    private var pendingReveal: Pair<String, String>? = null

    /** Jenkins selection, above the search field next to the toolbar (set by the main panel). */
    private val selectorSlot = JPanel(BorderLayout()).apply { isOpaque = false }

    /** Connection lights and filter chips above the status line (set by the main panel). */
    private val bottomSlot = JPanel(BorderLayout()).apply { isOpaque = false }

    private val toolbarComponent = ActionManager.getInstance().createActionToolbar("JenkSpotter.Jobs", actionGroup(), true)
        .also { it.targetComponent = list }.component

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = renderer
        // Fixed cell size: otherwise Swing renders every one of several thousand rows to measure it.
        // Rows wider than the view are shown in full on hover (expandable items of JBList).
        list.fixedCellHeight = maxOf(list.getFontMetrics(list.font).height + JBUI.scale(4), JBUI.scale(22))
        list.fixedCellWidth = JBUI.scale(120)
        list.emptyText.text = JenkSpotterBundle.message("jobs.empty")
        search.textEditor.emptyText.text = JenkSpotterBundle.message("jobs.searchHint")

        // Jenkins selection and actions in one row, the search field below them over the full width.
        val header = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(2, 4)
            add(JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                isOpaque = false
                // As wide as its longest Jenkins name, not stretched when the view gets wider.
                add(selectorSlot, BorderLayout.WEST)
                add(toolbarComponent, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(search, BorderLayout.CENTER)
        }
        toolbar = header
        setContent(JPanel(BorderLayout()).apply {
            add(scopeBar, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                border = JBUI.Borders.customLineTop(JBUI.CurrentTheme.ToolWindow.borderColor())
                add(bottomSlot, BorderLayout.CENTER)
                add(statusLabel, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        })

        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = scheduleSearch(keepSelection = false)
        })
        // The search field keeps the focus; navigation keys are forwarded to the list.
        search.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val handled = when {
                    e.isAltDown && e.keyCode == KeyEvent.VK_UP -> { jumpPrevious(); true }
                    e.isAltDown && e.keyCode == KeyEvent.VK_DOWN -> { jumpNext(); true }
                    else -> null
                } ?: when (e.keyCode) {
                    KeyEvent.VK_DOWN -> { ScrollingUtil.moveDown(list, 0); true }
                    KeyEvent.VK_UP -> { ScrollingUtil.moveUp(list, 0); true }
                    KeyEvent.VK_PAGE_DOWN -> { ScrollingUtil.movePageDown(list); true }
                    KeyEvent.VK_PAGE_UP -> { ScrollingUtil.movePageUp(list); true }
                    // A pasted Jenkins URL is pinned instead of searched for.
                    KeyEvent.VK_ENTER -> {
                        if (search.text.trim().startsWith("http") && PinFromUrl.pin(project, search.text)) search.text = ""
                        // The list lags a moment behind the typing: Enter must not build what was found for the text before.
                        else if (search.text == shownQuery) activate()
                        true
                    }
                    KeyEvent.VK_ESCAPE -> when {
                        search.text.isNotEmpty() -> { search.text = ""; true }
                        scope != null -> { clearScope(); true }
                        else -> false
                    }
                    else -> false
                }
                if (handled) e.consume()
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val row = list.selectedValue
                val handled = when {
                    e.isAltDown && e.keyCode == KeyEvent.VK_UP -> { jumpPrevious(); true }
                    e.isAltDown && e.keyCode == KeyEvent.VK_DOWN -> { jumpNext(); true }
                    else -> null
                } ?: when (e.keyCode) {
                    KeyEvent.VK_ENTER -> { activate(); true }
                    KeyEvent.VK_LEFT -> collapsed(row) == false && setCollapsed(row, true)
                    KeyEvent.VK_RIGHT -> collapsed(row) == true && setCollapsed(row, false)
                    else -> false
                }
                if (handled) e.consume()
            }
        })
        // A single click on a Jenkins node opens or closes it; a folder node only via its arrow,
        // since a double-click on it narrows the list to the folder.
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1 || e.clickCount != 1) return
                val index = list.locationToIndex(e.point).takeIf { it >= 0 && list.getCellBounds(it, it)?.contains(e.point) == true } ?: return
                val row = listModel.get(index)
                if (row is JobRow.Job) {
                    if (onChainsBadge(index, e.point)) showChains(row.hit, RelativePoint(e))
                    return
                }
                if (row is JobRow.Folder) {
                    val arrowEnd = list.getCellBounds(index, index).x + JBUI.scale(2 + row.depth * GROUP_INDENT + 20)
                    if (e.x > arrowEnd) return
                }
                collapsed(row)?.let { setCollapsed(row, !it) }
            }
        })
        list.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val index = list.locationToIndex(e.point)
                val bounds = if (index >= 0) list.getCellBounds(index, index)?.takeIf { it.contains(e.point) } else null
                val hand = bounds != null && onChainsBadge(index, e.point)
                list.cursor = if (hand) java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR) else null
                hover(bounds?.let { (listModel.get(index) as? JobRow.Job)?.hit })
            }
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) = hover(null)
        })
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                // A double click on the chains badge only opens the chains, it does not build.
                val index = list.locationToIndex(event.point)
                if (index >= 0 && onChainsBadge(index, event.point)) return true
                (list.selectedValue as? JobRow.Folder)?.let {
                    scopeTo(it.serverId, it.fullName)
                    return true
                }
                selected()?.let(::trigger) ?: return false
                return true
            }
        }.installOn(list)
        PopupHandler.installPopupMenu(list, DefaultActionGroup().apply {
            add(jobActions())
            add(action("action.recentBuilds", AllIcons.Vcs.History, { selected()?.job?.isBuildable == true }) {
                val hit = selected() ?: return@action
                val index = list.selectedIndex
                val bounds = list.getCellBounds(index, index) ?: return@action
                RecentBuildsPopup.show(project, hit.serverId, hit.job.url, hit.job.fullName, RelativePoint(list, java.awt.Point(bounds.x + JBUI.scale(24), bounds.y + bounds.height)))
            })
            add(action("action.showChains", AllIcons.Vcs.Merge, { selected()?.let { ChainsPopup.hasChains(it.serverId, it.job) } == true }) {
                val hit = selected() ?: return@action
                val bounds = list.getCellBounds(list.selectedIndex, list.selectedIndex) ?: return@action
                showChains(hit, RelativePoint(list, java.awt.Point(bounds.x + JBUI.scale(24), bounds.y + bounds.height)))
            })
            add(action("jobs.scope.enter", AllIcons.Nodes.Folder, { scopeTarget() != null }) {
                scopeTarget()?.let { (serverId, fullName) -> scopeTo(serverId, fullName) }
            })
            addSeparator()
            add(FilterActions.group(project) { selected()?.job?.fullName })
            addSeparator()
            add(object : DefaultActionGroup({ JenkSpotterBundle.message("jobs.server") }, true) {
                override fun getChildren(e: AnActionEvent?): Array<AnAction> {
                    val id = selectedServerId() ?: return AnAction.EMPTY_ARRAY
                    // getChildren(e) is override-only API; the group holds plain actions, no stubs.
                    return ServerStatusUi.actions(project, id).childActionsOrStubs
                }
                override fun update(e: AnActionEvent) {
                    val id = selectedServerId()
                    e.presentation.isEnabledAndVisible = id != null
                    e.presentation.text = id?.let { JenkSpotterSettings.getInstance().server(it)?.displayName } ?: ""
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
        }, "JenkSpotter.JobsPopup")

        BranchJobs.getInstance(project).onUpdate {
            if (JenkSpotterSettings.getInstance().current.projectJobsOnly) {
                ApplicationManager.getApplication().invokeLater({ scheduleSearch(keepSelection = true) }, { disposed || project.isDisposed })
            }
        }
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(JobIndexListener.TOPIC, JobIndexListener { id ->
            if (filter.includes(id)) {
                ApplicationManager.getApplication().invokeLater({ scheduleSearch(keepSelection = true) }, { disposed || project.isDisposed })
            }
        })
        // Connection lights on the Jenkins nodes.
        connection.subscribe(BuildMonitorListener.TOPIC, object : BuildMonitorListener {
            override fun snapshotChanged(snapshot: MonitorSnapshot) {
                ApplicationManager.getApplication().invokeLater({ list.repaint() }, { disposed || project.isDisposed })
            }
        })
        filter.onChange {
            refreshIndexes(force = false)
            scheduleSearch(keepSelection = true)
        }
        refreshIndexes(force = false)
        scheduleSearch(keepSelection = true)
    }

    /** Narrow enough to drag, wide enough for the Jenkins selection and the first toolbar buttons. */
    override fun getMinimumSize(): java.awt.Dimension {
        // Swing asks during construction, before the fields are set.
        @Suppress("SENSELESS_COMPARISON")
        if (toolbarComponent == null || selectorSlot == null) return super.getMinimumSize()
        // Room for the Jenkins selection and a few buttons; the toolbar folds the rest into its ">>" menu.
        val selector = selectorSlot.takeIf { it.isVisible }?.let { it.preferredSize.width + JBUI.scale(4) } ?: 0
        return java.awt.Dimension(selector + JBUI.scale(140), super.getMinimumSize().height)
    }

    fun setServerSelector(component: JComponent?) {
        selectorSlot.removeAll()
        component?.let { selectorSlot.add(it, BorderLayout.CENTER) }
        selectorSlot.isVisible = component != null
        selectorSlot.revalidate()
    }

    fun setBottomBar(component: JComponent) {
        bottomSlot.removeAll()
        bottomSlot.add(component, BorderLayout.CENTER)
        bottomSlot.revalidate()
    }

    fun focusSearch() {
        search.requestFocusInWindow()
        search.selectText()
    }

    /**
     * Shows a job found elsewhere (e.g. the "Go to Jenkins Job" popup): takes over the query,
     * opens its Jenkins node and selects it.
     */
    fun reveal(serverId: String, url: String, query: String) {
        pendingReveal = serverId to url
        scope?.let { s ->
            val fullName = JobIndexService.getInstance().jobByUrl(serverId, url)?.fullName
            if (s.serverId != serverId || fullName == null || !fullName.startsWith(s.fullName + "/")) clearScope(search = false)
        }
        val settings = JenkSpotterSettings.getInstance()
        if (serverId in settings.current.collapsedServers) settings.setServerCollapsed(serverId, false)
        val folder = JobIndexService.getInstance().jobByUrl(serverId, url)?.fullName?.substringBeforeLast('/', "")
            ?.let(de.pdenis.jenkspotter.api.JenkinsPaths::displayName)
        if (folder != null && "$serverId $folder" in settings.current.collapsedFolders) settings.setFolderCollapsed(serverId, folder, false)
        if (search.text != query) search.text = query
        scheduleSearch(keepSelection = false)
        search.requestFocusInWindow()
    }

    private fun refreshIndexes(force: Boolean) {
        val settings = JenkSpotterSettings.getInstance()
        filter.serverIds().mapNotNull(settings::server).forEach { JobIndexService.getInstance().refreshIfNeeded(it, force) }
    }

    private fun scheduleSearch(keepSelection: Boolean) {
        if (disposed) return
        val scope = scope?.takeIf { it.serverId in filter.serverIds() }
        if (scope == null && this.scope != null) clearScope(search = false)
        val serverIds = scope?.let { listOf(it.serverId) } ?: filter.serverIds()
        val hidden = filter.hidden()
        val query = search.text
        val projectOnly = JenkSpotterSettings.getInstance().current.projectJobsOnly
        alarm.cancelAllRequests()
        alarm.addRequest({
            val index = JobIndexService.getInstance()
            val repos = BranchJobs.getInstance(project).repos
            val only = if (projectOnly) serverIds.associateWith { repos.filter(index.state(it).jobs) } else null
            // The list shows every job; only the search popup is capped.
            val results = index.search(serverIds, query, hidden, limit = Int.MAX_VALUE, only = only)
                .let { found -> if (scope == null) found else found.filter { it.job.fullName.startsWith(scope.fullName + "/") } }
            // What the rows show next to the name, worked out here so that painting is only a lookup.
            results.forEach { index.badges(it.serverId, it.job) }
            val states = serverIds.associateWith { index.state(it) }
            val buildable = states.values.flatMap { st -> st.jobs.filter { it.isBuildable } }
            val counts = Counts(buildable.size, buildable.count { !hidden.isHidden(it.fullName) }, results.count { it.job.isBuildable }, query.isNotBlank())
            ApplicationManager.getApplication().invokeLater({
                list.emptyText.text = JenkSpotterBundle.message(
                    when {
                        only == null -> "jobs.empty"
                        repos.isEmpty -> "jobs.project.noRepo"
                        else -> "jobs.project.none"
                    },
                )
                // Jobs exist, but none is left: say so, with the quickest way back.
                if (results.isEmpty() && counts.total > 0 && only == null) {
                    list.emptyText.text = JenkSpotterBundle.message("jobs.empty.filtered", counts.total)
                    if (query.isNotBlank()) {
                        list.emptyText.appendSecondaryText(JenkSpotterBundle.message("jobs.empty.clearSearch"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { search.text = "" }
                    }
                }
                show(serverIds, results, states, counts, keepSelection)
                shownQuery = query
            }, { disposed || project.isDisposed })
        }, SEARCH_DELAY_MS)
    }

    private data class Counts(val total: Int, val visible: Int, val matches: Int, val searching: Boolean)

    private fun jobLayout(): JobLayout = JenkSpotterSettings.getInstance().current.jobLayout

    /** Jenkins nodes are only worth it with several servers in view. */
    private fun serverNodes(serverIds: List<String> = filter.serverIds()) = jobLayout().grouped && serverIds.size > 1

    private fun show(serverIds: List<String>, results: List<JobHit>, states: Map<String, JobIndexService.IndexState>, counts: Counts, keepSelection: Boolean) {
        val previous = list.selectedValue?.let(::rowKey)
        val rows = rows(serverIds, results)
        // One event for all rows: adding them one by one lays out the whole list per row.
        listModel.clear()
        listModel.addAll(rows)
        renderer.serverNodes = serverNodes(serverIds)

        val reveal = pendingReveal
        val revealIndex = reveal?.let { (server, url) -> rows.indexOfFirst { it is JobRow.Job && it.hit.serverId == server && it.hit.job.url == url } } ?: -1
        val index = when {
            revealIndex >= 0 -> revealIndex.also { pendingReveal = null }
            rows.isEmpty() -> -1
            keepSelection -> rows.indexOfFirst { rowKey(it) == previous }.takeIf { it >= 0 } ?: firstJobIndex(rows)
            else -> firstJobIndex(rows)
        }
        // The index may still be loading; keep waiting for the job unless nothing is loading any more.
        if (reveal != null && revealIndex < 0 && states.values.none { it.loading }) pendingReveal = null
        if (index >= 0) {
            list.selectedIndex = index
            ScrollingUtil.ensureIndexIsVisible(list, index, 0)
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
                if (counts.searching) append("  ·  ").append(JenkSpotterBundle.message("jobs.matches", counts.matches))
                append("  ·  ").append(JenkSpotterBundle.message("jobs.updated", DateFormatUtil.formatPrettyDateTime(states.values.minOf { it.updatedAt })))
            }
        }
    }

    /** Rows for the chosen layout; folder nodes keep the order in which their first job was found. */
    private fun rows(serverIds: List<String>, results: List<JobHit>): List<JobRow> {
        val view = JenkSpotterSettings.getInstance().current
        val withServers = serverNodes(serverIds)
        val withFolders = view.jobLayout == JobLayout.TREE_FOLDERS
        val scopePath = scope?.let { de.pdenis.jenkspotter.api.JenkinsPaths.displayName(it.fullName) }
        if (!withServers && !withFolders) return mutableListOf<JobRow>().also { addJobs(it, results, scopePath, 0) }
        val collapsedServers = view.collapsedServers.toSet()
        val collapsedFolders = view.collapsedFolders.toSet()
        val byServer = results.groupBy { it.serverId }
        val depth = if (withServers) 1 else 0
        val rows = mutableListOf<JobRow>()
        for (id in serverIds) {
            val hits = byServer[id].orEmpty()
            if (withServers) {
                val isCollapsed = id in collapsedServers
                rows += JobRow.Server(id, hits.size, isCollapsed)
                if (isCollapsed) continue
            }
            if (!withFolders) {
                addJobs(rows, hits, null, depth)
                continue
            }
            // Narrowed to a folder: its jobs count as top level, subfolders are named relative to it.
            val top = scope?.let { de.pdenis.jenkspotter.api.JenkinsPaths.displayName(it.fullName) }.orEmpty()
            fun relative(path: String) = if (top.isEmpty()) path else path.removePrefix(top).removePrefix("/")
            // A folder or multibranch pipeline with jobs listed below it is its folder node, not also a job row.
            val folderPaths = hits.map { it.path }.toSet()
            val listed = hits.filter { !(it.job.isFolder && de.pdenis.jenkspotter.api.JenkinsPaths.displayName(it.job.fullName) in folderPaths) }
            // Jobs directly on the Jenkins (or in the folder) first, then one node per folder.
            addJobs(rows, listed.filter { relative(it.path).isEmpty() }, top, depth)
            for ((path, inFolder) in listed.filter { relative(it.path).isNotEmpty() }.groupBy { it.path }) {
                val isCollapsed = "$id $path" in collapsedFolders
                val fullName = inFolder.first().job.fullName.substringBeforeLast('/')
                val folderJob = JobIndexService.getInstance().jobByFullName(id, fullName)
                val multiBranch = folderJob?.isMultiBranch == true
                rows += JobRow.Folder(id, relative(path), inFolder.size, isCollapsed, depth, multiBranch, fullName, path)
                if (!isCollapsed) addJobs(rows, inFolder, path, depth + 1, branches = multiBranch)
            }
        }
        return rows
    }

    private fun addJobs(rows: MutableList<JobRow>, hits: List<JobHit>, folder: String?, depth: Int, branches: Boolean = false) {
        hits.mapIndexedTo(rows) { i, hit ->
            val connector = when {
                !branches -> null
                i == hits.lastIndex -> JobHitRenderer.Connector.LAST
                else -> JobHitRenderer.Connector.MIDDLE
            }
            JobRow.Job(hit, folder, depth, connector)
        }
    }

    /** Tints every job leading to [hit] and every job it triggers while the mouse is over it. */
    private fun hover(hit: JobHit?) {
        val on = hit != null && JenkSpotterSettings.getInstance().current.highlightChainOnHover
        val key = if (on) "${hit!!.serverId} ${hit.job.url}" else null
        if (key == hovered) return
        hovered = key
        val result = HashMap<String, JobHitRenderer.Relation>()
        if (on) {
            val index = JobIndexService.getInstance()
            val state = index.state(hit!!.serverId)
            val up = ArrayDeque(listOf(hit.job.fullName))
            val seenUp = hashSetOf(hit.job.fullName)
            while (up.isNotEmpty()) {
                for (job in state.upstream[up.removeFirst()].orEmpty()) {
                    if (seenUp.add(job.fullName)) {
                        result["${hit.serverId} ${job.url}"] = JobHitRenderer.Relation.UPSTREAM
                        up += job.fullName
                    }
                }
            }
            val down = ArrayDeque(listOf(hit.job))
            val seenDown = hashSetOf(hit.job.fullName)
            while (down.isNotEmpty()) {
                for (name in down.removeFirst().downstream) {
                    val job = index.jobByFullName(hit.serverId, name) ?: continue
                    if (seenDown.add(name)) {
                        result.putIfAbsent("${hit.serverId} ${job.url}", JobHitRenderer.Relation.DOWNSTREAM)
                        down += job
                    }
                }
            }
        }
        if (result.isEmpty() && related.isEmpty()) return
        related = result
        list.repaint()
    }

    /** Prefers the first job over a Jenkins node, so Enter builds the best match. */
    private fun firstJobIndex(rows: List<JobRow>) = rows.indexOfFirst { it is JobRow.Job }.takeIf { it >= 0 } ?: 0

    private fun rowKey(row: JobRow): Any = when (row) {
        is JobRow.Server -> row.serverId
        is JobRow.Folder -> "${row.serverId} folder ${row.key}"
        is JobRow.Job -> row.hit.serverId to row.hit.job.url
    }

    private fun selected(): JobHit? = (list.selectedValue as? JobRow.Job)?.hit

    /** Folder or multibranch pipeline of the selected row, as (server id, full name). */
    private fun scopeTarget(): Pair<String, String>? = when (val row = list.selectedValue) {
        is JobRow.Folder -> row.serverId to row.fullName
        is JobRow.Job -> if (row.hit.job.isFolder) row.hit.serverId to row.hit.job.fullName else null
        else -> null
    }

    /** Lists only the jobs inside [fullName] (folder or multibranch pipeline), see [scopeBar]. */
    private fun scopeTo(serverId: String, fullName: String) {
        if (fullName.isEmpty()) return clearScope()
        val job = JobIndexService.getInstance().jobByFullName(serverId, fullName)
        scope = FolderScope(serverId, fullName, job?.isMultiBranch == true)
        updateScopeBar()
        scheduleSearch(keepSelection = false)
    }

    private fun clearScope(search: Boolean = true) {
        scope = null
        updateScopeBar()
        if (search) scheduleSearch(keepSelection = false)
    }

    /** "Jenkins › team › app  ✕": every part but the last narrows to that folder, the first and ✕ show all jobs. */
    private fun updateScopeBar() {
        scopeBar.removeAll()
        val current = scope
        scopeBar.isVisible = current != null
        if (current != null) {
            val crumbs = JPanel(com.intellij.util.ui.WrapLayout(java.awt.FlowLayout.LEFT, JBUI.scale(3), 0)).apply { isOpaque = false }
            fun separator() = JBLabel("\u203A").apply { foreground = UIUtil.getContextHelpForeground() }
            crumbs.add(JBLabel(if (current.multiBranch) JenkSpotterIcons.MultiBranch else JenkSpotterIcons.Folder))
            crumbs.add(com.intellij.ui.components.ActionLink(JenkSpotterSettings.getInstance().server(current.serverId)?.displayName.orEmpty()) { clearScope() })
            val parts = current.fullName.split('/')
            parts.forEachIndexed { i, part ->
                crumbs.add(separator())
                val name = de.pdenis.jenkspotter.api.JenkinsPaths.displayName(part)
                crumbs.add(if (i == parts.lastIndex) JBLabel(name).apply { font = font.deriveFont(java.awt.Font.BOLD) }
                else com.intellij.ui.components.ActionLink(name) { scopeTo(current.serverId, parts.take(i + 1).joinToString("/")) })
            }
            scopeBar.add(crumbs, BorderLayout.CENTER)
            scopeBar.add(com.intellij.ui.InplaceButton(JenkSpotterBundle.message("jobs.scope.clear"), AllIcons.Actions.Close) { clearScope() }, BorderLayout.EAST)
        }
        scopeBar.revalidate()
        scopeBar.repaint()
    }

    /** Whether [point] in the list is on the chains badge of the job row at [index]. */
    private fun onChainsBadge(index: Int, point: java.awt.Point): Boolean {
        val row = listModel.get(index) as? JobRow.Job ?: return false
        val bounds = list.getCellBounds(index, index) ?: return false
        val component = renderer.getListCellRendererComponent(list, row, index, list.isSelectedIndex(index), false)
        return renderer.jobs.isOnChainsBadge(component, bounds.size, java.awt.Point(point.x - bounds.x, point.y - bounds.y))
    }

    /** Every path of follow-up jobs through [hit], see [ChainsPopup]. */
    private fun showChains(hit: JobHit, where: RelativePoint) =
        ChainsPopup.show(hit.serverId, hit.job, where) { jumpTo(hit.serverId, it) }

    /** Selects [job] in the list; the search is kept if the job is listed, otherwise cleared. */
    private fun jumpTo(serverId: String, job: JobRef) {
        val listed = (0 until listModel.size()).any { (listModel.get(it) as? JobRow.Job)?.hit?.let { h -> h.serverId == serverId && h.job.url == job.url } == true }
        reveal(serverId, job.url, if (listed) search.text else "")
    }

    /** Alt+Up: the job that triggers the selected one; with several, a list to choose from. */
    private fun jumpPrevious() {
        val hit = selected() ?: return
        val index = JobIndexService.getInstance()
        // Longest chain first, as in the step shown.
        val longest = index.position(hit.serverId, hit.job)?.path?.lastOrNull() ?: return
        val previous = (listOf(longest) + index.state(hit.serverId).upstream[hit.job.fullName].orEmpty()).distinctBy { it.url }
        chooseAndJump(hit, previous)
    }

    /** Alt+Down: the job triggered by the selected one; with several, a list to choose from. */
    private fun jumpNext() {
        val hit = selected() ?: return
        val index = JobIndexService.getInstance()
        chooseAndJump(hit, hit.job.downstream.mapNotNull { index.jobByFullName(hit.serverId, it) })
    }

    private fun chooseAndJump(hit: JobHit, jobs: List<JobRef>) {
        when (jobs.size) {
            0 -> return
            1 -> jumpTo(hit.serverId, jobs.single())
            else -> {
                val bounds = list.getCellBounds(list.selectedIndex, list.selectedIndex) ?: return
                JBPopupFactory.getInstance().createPopupChooserBuilder(jobs)
                    // Not SimpleListCellRenderer.create(...): scheduled for removal since 2026.2.
                    .setRenderer(object : com.intellij.ui.SimpleListCellRenderer<JobRef>() {
                        override fun customize(list: javax.swing.JList<out JobRef>, value: JobRef?, index: Int, selected: Boolean, hasFocus: Boolean) {
                            text = value?.let { de.pdenis.jenkspotter.api.JenkinsPaths.displayName(it.fullName) }.orEmpty()
                        }
                    })
                    .setItemChosenCallback { jumpTo(hit.serverId, it) }
                    .createPopup()
                    .show(RelativePoint(list, java.awt.Point(bounds.x + JBUI.scale(24), bounds.y + bounds.height)))
            }
        }
    }

    private fun selectedServerId(): String? = when (val row = list.selectedValue) {
        is JobRow.Server -> row.serverId
        is JobRow.Folder -> row.serverId
        is JobRow.Job -> row.hit.serverId
        null -> null
    }

    /** Open/closed state of a node, null for jobs. */
    private fun collapsed(row: JobRow?): Boolean? = when (row) {
        is JobRow.Server -> row.collapsed
        is JobRow.Folder -> row.collapsed
        else -> null
    }

    private fun setCollapsed(row: JobRow?, collapsed: Boolean): Boolean {
        val settings = JenkSpotterSettings.getInstance()
        when (row) {
            is JobRow.Server -> settings.setServerCollapsed(row.serverId, collapsed)
            is JobRow.Folder -> settings.setFolderCollapsed(row.serverId, row.key, collapsed)
            else -> return false
        }
        return true
    }

    /** Enter: builds the selected job, or opens/closes the selected node. */
    private fun activate() {
        when (val row = list.selectedValue) {
            is JobRow.Job -> trigger(row.hit)
            // Like a double-click; Left and Right open and close the node.
            is JobRow.Folder -> scopeTo(row.serverId, row.fullName)
            null -> Unit
            else -> collapsed(row)?.let { setCollapsed(row, !it) }
        }
    }

    private fun trigger(hit: JobHit) {
        if (hit.job.isFolder) return scopeTo(hit.serverId, hit.job.fullName)
        if (!hit.job.canBuild) return
        // Counted as recently built once the build is really started, see BuildActions.trigger.
        BuildActions.trigger(project, hit.serverId, hit.job.url, hit.job.fullName, null)
    }

    /** Actions on the selected job (toolbar and context menu). */
    private fun jobActions() = DefaultActionGroup().apply {
        add(action("action.build", AllIcons.Actions.Execute, { selected()?.job?.canBuild == true }) { selected()?.let(::trigger) })
        add(toggle("action.pin", "action.unpin", JenkSpotterIcons.Pinned, { it.pinned }) { hit ->
            JenkSpotterSettings.getInstance().togglePin(hit.serverId, hit.job.fullName, hit.job.url)
        })
        add(toggle("action.watch", "action.unwatch", JenkSpotterIcons.Watched, { it.watched }) { hit ->
            JenkSpotterSettings.getInstance().toggleWatch(hit.serverId, hit.job.fullName, hit.job.url)
        })
        add(action("action.openInBrowser", AllIcons.General.Web, { selected() != null }) { selected()?.let { BuildActions.openInBrowser(it.job.url) } })
        add(action("action.jobInfo", AllIcons.General.ShowInfos, { selected() != null }) { selected()?.let { JobInfoDialog(project, it).show() } })
    }

    private fun actionGroup() = DefaultActionGroup().apply {
        add(jobActions())
        add(action("action.pinFromUrl", AllIcons.General.Add, { true }) { PinFromUrl.ask(project) })
        addSeparator()
        add(action("action.refreshIndex", AllIcons.Actions.ForceRefresh, { filter.serverIds().isNotEmpty() }) { refreshIndexes(force = true) })
        add(sortMenu())
        add(ViewOptions.menu("jobs.show", listOf(
            ViewOptions.toggle("jobs.show.path", { showJobPath }) { showJobPath = it },
            ViewOptions.toggle("jobs.show.folders", { showFolderEntries }) { showFolderEntries = it },
            ViewOptions.toggle("jobs.show.triggers", { showTriggers }) { showTriggers = it },
            ViewOptions.toggle("jobs.show.workflowColors", { showWorkflowColors }) { showWorkflowColors = it },
            ViewOptions.toggle("jobs.show.hoverChain", { highlightChainOnHover }) { highlightChainOnHover = it },
        )))
    }

    /** Order, favourites on top, layout (tree / list with Jenkins name), expand/collapse. */
    private fun sortMenu(): AnAction = DefaultActionGroup({ JenkSpotterBundle.message("jobs.sort") }, true).apply {
        templatePresentation.icon = AllIcons.ObjectBrowser.Sorted
        JobSort.entries.forEach { sort ->
            add(radio(sort.key, { jobSort == sort }) { jobSort = sort })
        }
        addSeparator()
        add(ViewOptions.toggle("jobs.sort.pinnedFirst", { pinnedFirst }) { pinnedFirst = it })
        add(com.intellij.openapi.actionSystem.Separator.create(JenkSpotterBundle.message("jobs.layout")))
        JobLayout.entries.forEach { layout ->
            add(radio(layout.key, { jobLayout == layout }) { jobLayout = layout })
        }
        addSeparator()
        add(action("jobs.expandAll", AllIcons.Actions.Expandall, { jobLayout().grouped }) {
            JenkSpotterSettings.getInstance().updateView { collapsedServers = mutableListOf(); collapsedFolders = mutableListOf() }
        })
        add(action("jobs.collapseAll", AllIcons.Actions.Collapseall, { serverNodes() }) {
            JenkSpotterSettings.getInstance().updateView { collapsedServers = filter.serverIds().toMutableList() }
        })
    }

    private fun radio(
        key: String,
        isOn: de.pdenis.jenkspotter.settings.JenkSpotterState.() -> Boolean,
        icon: javax.swing.Icon? = null,
        set: de.pdenis.jenkspotter.settings.JenkSpotterState.() -> Unit,
    ) =
        object : DumbAwareToggleAction(JenkSpotterBundle.message(key), null, icon) {
            override fun isSelected(e: AnActionEvent) = JenkSpotterSettings.getInstance().current.isOn()
            override fun setSelected(e: AnActionEvent, state: Boolean) = JenkSpotterSettings.getInstance().updateView(set)
            override fun update(e: AnActionEvent) {
                super.update(e)
                e.presentation.keepPopupOnPerform = KeepPopupOnPerform.Always
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private fun action(key: String, icon: javax.swing.Icon, enabled: () -> Boolean, perform: () -> Unit) =
        object : DumbAwareAction({ JenkSpotterBundle.message(key) }, icon) {
            override fun actionPerformed(e: AnActionEvent) = perform()
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    /** On/off action for the selected job whose text names what a click will do. */
    private fun toggle(onKey: String, offKey: String, icon: javax.swing.Icon, isOn: (JobHit) -> Boolean, perform: (JobHit) -> Unit) =
        object : DumbAwareAction({ JenkSpotterBundle.message(onKey) }, icon) {
            override fun actionPerformed(e: AnActionEvent) {
                selected()?.let(perform)
            }
            override fun update(e: AnActionEvent) {
                val hit = selected()
                e.presentation.isEnabled = hit != null && hit.job.canBuild
                e.presentation.text = JenkSpotterBundle.message(if (hit != null && isOn(hit)) offKey else onKey)
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    override fun dispose() {
        disposed = true
    }

    private inner class Renderer : ListCellRenderer<JobRow> {
        var serverNodes = false
        val jobs = JobHitRenderer(::serverLabel)
        private val servers = ServerRenderer()
        private val folders = FolderRenderer()

        /** The Jenkins name is only needed in list layouts with several servers in view. */
        private fun serverLabel(): JobHitRenderer.ServerLabel {
            if (serverNodes || filter.serverIds().size < 2) return JobHitRenderer.ServerLabel.NONE
            return when (jobLayout()) {
                JobLayout.FLAT_PREFIX -> JobHitRenderer.ServerLabel.PREFIX
                JobLayout.FLAT -> JobHitRenderer.ServerLabel.NONE
                else -> JobHitRenderer.ServerLabel.SUFFIX
            }
        }

        override fun getListCellRendererComponent(list: JList<out JobRow>, value: JobRow, index: Int, selected: Boolean, focus: Boolean): Component =
            // The delegates only read colours and fonts from the list, never its elements.
            @Suppress("UNCHECKED_CAST")
            when (value) {
                is JobRow.Server -> servers.getListCellRendererComponent(list as JList<out JobRow.Server>, value, index, selected, focus)
                    .also { it.background = Stripes.background(index % 2 == 1, selected, focus) }
                is JobRow.Folder -> folders.getListCellRendererComponent(list as JList<out JobRow.Folder>, value, index, selected, focus)
                    .also { it.background = Stripes.background(index % 2 == 1, selected, focus) }
                is JobRow.Job -> {
                    jobs.indent = value.depth * GROUP_INDENT
                    jobs.folderShown = value.folder
                    jobs.connector = value.branch
                    jobs.relation = related["${value.hit.serverId} ${value.hit.job.url}"]
                    jobs.getListCellRendererComponent(list as JList<out JobHit>, value.hit, index, selected, focus)
                }
            }
    }

    /** Folder node of [JobLayout.TREE_FOLDERS]: open/closed arrow, folder icon, path, number of jobs. */
    private class FolderRenderer : ColoredListCellRenderer<JobRow.Folder>() {
        override fun customizeCellRenderer(list: JList<out JobRow.Folder>, value: JobRow.Folder, index: Int, selected: Boolean, hasFocus: Boolean) {
            ipad = JBUI.insets(1, 2 + value.depth * GROUP_INDENT, 1, 2)
            icon = RowIcon(if (value.collapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown, if (value.multiBranch) JenkSpotterIcons.MultiBranch else JenkSpotterIcons.Folder)
            append(value.path)
            append("   " + JenkSpotterBundle.message("jobs.group.count", value.count), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    /** Jenkins node: open/closed arrow, connection light, name, number of jobs, problems. */
    private class ServerRenderer : ColoredListCellRenderer<JobRow.Server>() {
        override fun customizeCellRenderer(list: JList<out JobRow.Server>, value: JobRow.Server, index: Int, selected: Boolean, hasFocus: Boolean) {
            val server = JenkSpotterSettings.getInstance().server(value.serverId) ?: return
            val state = ServerStatusUi.state(server)
            icon = RowIcon(if (value.collapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown, ServerStatusUi.icon(state))
            append(server.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append("   " + JenkSpotterBundle.message("jobs.group.count", value.count), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (state != ConnectionState.CONNECTED) {
                append("   " + ServerStatusUi.label(state), if (state == ConnectionState.ERROR) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            }
            val index = JobIndexService.getInstance().state(value.serverId)
            if (index.loading) append("   " + JenkSpotterBundle.message("jobs.group.loading"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            toolTipText = ServerStatusUi.tooltip(server, BuildMonitorService.getInstance().snapshot)
        }
    }

    companion object {
        val KEY: Key<JenkSpotterMainPanel> = Key.create("JenkSpotter.MainPanel")
        private const val SEARCH_DELAY_MS = 40
        private const val GROUP_INDENT = 16
    }
}
