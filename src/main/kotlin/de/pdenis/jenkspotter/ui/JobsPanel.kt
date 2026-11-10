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
import de.pdenis.jenkspotter.settings.JobIcon
import de.pdenis.jenkspotter.settings.FollowUpIcon
import de.pdenis.jenkspotter.settings.ChainStepIcon
import de.pdenis.jenkspotter.settings.ChainDisplay
import de.pdenis.jenkspotter.settings.VisibilityToggle
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
    data class Folder(val serverId: String, val path: String, val count: Int, val collapsed: Boolean, val depth: Int) : JobRow()
    /**
     * [folder]: path of the folder node the job is listed under; [depth]: nesting level;
     * [chainDepth], [expanded], [lane]: see [JobHitRenderer].
     */
    data class Job(
        val hit: JobHit,
        val folder: String? = null,
        val depth: Int = 0,
        val chainDepth: Int = 0,
        val expanded: Boolean? = null,
        val lane: JobHitRenderer.Lane = JobHitRenderer.Lane.NONE,
        val laneDashedIn: Boolean = false,
        val laneDashedOut: Boolean = false,
    ) : JobRow()
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

    /** Session state of [ChainDisplay.TREE]: "serverId url" of jobs whose follow-ups are shown. */
    private val expandedChains = HashSet<String>()

    /** [ChainDisplay.HIGHLIGHT]: "serverId url" of the jobs before / after the selected one. */
    private var related: Map<String, JobHitRenderer.Relation> = emptyMap()

    /** [ChainDisplay.HIGHLIGHT]: the selected job's chain as clickable names below the list. */
    private val chainPath = JPanel(com.intellij.util.ui.WrapLayout(java.awt.FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
        border = JBUI.Borders.empty(2, 6)
        isVisible = false
        toolTipText = JenkSpotterBundle.message("chain.path.hint")
    }

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
        list.emptyText.text = JenkSpotterBundle.message("jobs.empty")
        search.textEditor.emptyText.text = JenkSpotterBundle.message("jobs.searchHint")

        // Jenkins selection and actions in one row, the search field below them over the full width.
        val header = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(2, 4)
            add(JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                isOpaque = false
                // As wide as its longest Jenkins name, not stretched when the view gets wider.
                add(selectorSlot, BorderLayout.WEST)
                add(toolbarComponent, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(search, BorderLayout.CENTER)
        }
        toolbar = header
        setContent(JPanel(BorderLayout()).apply {
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                border = JBUI.Borders.customLineTop(JBUI.CurrentTheme.ToolWindow.borderColor())
                add(chainPath, BorderLayout.NORTH)
                add(bottomSlot, BorderLayout.CENTER)
                add(statusLabel, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        })

        list.addListSelectionListener { if (!it.valueIsAdjusting) updateChainHighlight() }
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
                        else activate()
                        true
                    }
                    KeyEvent.VK_ESCAPE -> if (search.text.isNotEmpty()) { search.text = ""; true } else false
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
        // A single click on a Jenkins or folder node opens or closes it.
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1 || e.clickCount != 1) return
                val index = list.locationToIndex(e.point).takeIf { it >= 0 && list.getCellBounds(it, it)?.contains(e.point) == true } ?: return
                val row = listModel.get(index)
                if (row is JobRow.Job) {
                    // Jobs only open and close via their arrow; a click elsewhere selects them.
                    val bounds = list.getCellBounds(index, index)
                    val arrowEnd = bounds.x + JBUI.scale(2 + row.depth * GROUP_INDENT + row.chainDepth * 16 + 18)
                    if (e.x > arrowEnd) return
                }
                collapsed(row)?.let { setCollapsed(row, !it) }
            }
        })
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
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
            add(chainGroup())
            addSeparator()
            add(FilterActions.group(project) { selected()?.job?.fullName })
            addSeparator()
            add(object : DefaultActionGroup({ JenkSpotterBundle.message("jobs.server") }, true) {
                override fun getChildren(e: AnActionEvent?): Array<AnAction> {
                    val id = selectedServerId() ?: return AnAction.EMPTY_ARRAY
                    return ServerStatusUi.actions(project, id).getChildren(e)
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

    /** Narrow enough to drag, wide enough that the toolbar and the whole Jenkins selection stay visible. */
    override fun getMinimumSize(): java.awt.Dimension {
        // Swing asks during construction, before the fields are set.
        @Suppress("SENSELESS_COMPARISON")
        if (toolbarComponent == null || selectorSlot == null) return super.getMinimumSize()
        val selector = selectorSlot.takeIf { it.isVisible }?.let { it.preferredSize.width + JBUI.scale(4) } ?: 0
        return java.awt.Dimension(toolbarComponent.preferredSize.width + selector + JBUI.scale(8), super.getMinimumSize().height)
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
        // Tree display: open the jobs above it, so a nested follow-up job is listed.
        JobIndexService.getInstance().jobByUrl(serverId, url)?.let { job ->
            JobIndexService.getInstance().position(serverId, job)?.path?.forEach { expandedChains += "$serverId ${it.url}" }
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
        val serverIds = filter.serverIds()
        val hidden = filter.hidden()
        val query = search.text
        val projectOnly = JenkSpotterSettings.getInstance().current.projectJobsOnly
        alarm.cancelAllRequests()
        alarm.addRequest({
            val index = JobIndexService.getInstance()
            val repos = BranchJobs.getInstance(project).repos
            val only = if (projectOnly) serverIds.associateWith { repos.filter(index.state(it).jobs) } else null
            val results = index.search(serverIds, query, hidden, only = only)
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
                show(serverIds, results, states, counts, keepSelection)
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
        listModel.clear()
        rows.forEach(listModel::addElement)
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
        updateChainHighlight()
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
    private fun rows(serverIds: List<String>, found: List<JobHit>): List<JobRow> {
        val view = JenkSpotterSettings.getInstance().current
        val results = if (view.chainDisplay == ChainDisplay.LANE) chainOrder(found) else found
        return withLanes(groupedRows(serverIds, results))
    }

    private fun groupedRows(serverIds: List<String>, results: List<JobHit>): List<JobRow> {
        val view = JenkSpotterSettings.getInstance().current
        val withServers = serverNodes(serverIds)
        val withFolders = view.jobLayout == JobLayout.TREE_FOLDERS
        if (!withServers && !withFolders) return mutableListOf<JobRow>().also { addJobs(it, results, null, 0) }
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
            // Jobs directly on the Jenkins first, then one node per folder.
            addJobs(rows, hits.filter { it.path.isEmpty() }, "", depth)
            for ((path, inFolder) in hits.filter { it.path.isNotEmpty() }.groupBy { it.path }) {
                val isCollapsed = "$id $path" in collapsedFolders
                rows += JobRow.Folder(id, path, inFolder.size, isCollapsed, depth)
                if (!isCollapsed) addJobs(rows, inFolder, path, depth + 1)
            }
        }
        return rows
    }

    /**
     * Adds job rows; with [ChainDisplay.TREE] follow-up jobs are nested below the job triggering
     * them, and a triggered job is only listed on its own when the job starting its chain is not.
     */
    private fun addJobs(rows: MutableList<JobRow>, hits: List<JobHit>, folder: String?, depth: Int) {
        if (JenkSpotterSettings.getInstance().current.chainDisplay != ChainDisplay.TREE) {
            hits.mapTo(rows) { JobRow.Job(it, folder, depth) }
            return
        }
        val index = JobIndexService.getInstance()
        val listed = hits.map { it.serverId to it.job.url }.toSet()
        fun add(hit: JobHit, chainDepth: Int, seen: Set<String>) {
            val key = "${hit.serverId} ${hit.job.url}"
            val next = hit.job.downstream.mapNotNull { index.jobByFullName(hit.serverId, it) }.filter { it.fullName !in seen }
            val open = next.isNotEmpty() && key in expandedChains
            rows += JobRow.Job(hit, folder, depth, chainDepth, if (next.isEmpty()) null else open)
            if (open && chainDepth < 8) next.forEach { add(chainHit(hit.serverId, it), chainDepth + 1, seen + it.fullName) }
        }
        for (hit in hits) {
            val root = index.position(hit.serverId, hit.job)?.path?.firstOrNull()
            if (root != null && (hit.serverId to root.url) in listed) continue
            add(hit, 0, setOf(hit.job.fullName))
        }
    }

    /** A job shown because of its chain, not because it matched the search. */
    private fun chainHit(serverId: String, job: JobRef): JobHit {
        val settings = JenkSpotterSettings.getInstance()
        return JobHit(
            serverId, job,
            de.pdenis.jenkspotter.api.JenkinsPaths.displayName(job.name),
            de.pdenis.jenkspotter.api.JenkinsPaths.displayName(job.fullName.substringBeforeLast('/', "")),
            de.pdenis.jenkspotter.core.JobMatcher.Result(0, emptyList(), emptyList()),
            settings.isPinned(serverId, job.url), 0, settings.isWatched(serverId, job.url),
        )
    }

    /** First job of the chain [hit] belongs to, null if it is in none. */
    private fun chainRoot(hit: JobHit): JobRef? {
        if (hit.job.isFolder) return null
        return JobIndexService.getInstance().position(hit.serverId, hit.job)?.path?.firstOrNull()
            ?: hit.job.takeIf { it.downstream.isNotEmpty() }
    }

    /** [ChainDisplay.LANE]: the found jobs of one chain one after another, in chain order. */
    private fun chainOrder(results: List<JobHit>): List<JobHit> {
        val index = JobIndexService.getInstance()
        val byKey = results.associateBy { it.serverId to it.job.fullName }
        val placed = HashSet<JobHit>()
        val ordered = mutableListOf<JobHit>()
        for (hit in results) {
            if (hit in placed) continue
            val root = chainRoot(hit)
            if (root == null) {
                placed += hit
                ordered += hit
                continue
            }
            fun visit(fullName: String, nodes: List<de.pdenis.jenkspotter.core.ChainNode>) {
                byKey[hit.serverId to fullName]?.takeIf { placed.add(it) }?.let(ordered::add)
                nodes.forEach { visit(it.fullName, it.children) }
            }
            visit(root.fullName, index.chain(hit.serverId, root))
            if (placed.add(hit)) ordered += hit
        }
        return ordered
    }

    /** [ChainDisplay.LANE]: joins consecutive jobs of one chain with a line. */
    private fun withLanes(rows: List<JobRow>): List<JobRow> {
        if (JenkSpotterSettings.getInstance().current.chainDisplay != ChainDisplay.LANE) return rows
        val keys = rows.map { row -> (row as? JobRow.Job)?.hit?.let { hit -> chainRoot(hit)?.let { hit.serverId to it.url } } }
        // A disabled job is not triggered, nor is anything after it: the line into it is dashed.
        val index = JobIndexService.getInstance()
        val notTriggered = rows.map { row ->
            val hit = (row as? JobRow.Job)?.hit ?: return@map false
            val position = index.position(hit.serverId, hit.job)
            position != null && (hit.job.disabled || position.blockedBy != null)
        }
        return rows.mapIndexed { i, row ->
            val key = keys[i]
            if (row !is JobRow.Job || key == null) return@mapIndexed row
            val before = keys.getOrNull(i - 1) == key
            val after = keys.getOrNull(i + 1) == key
            val lane = when {
                before && after -> JobHitRenderer.Lane.MIDDLE
                before -> JobHitRenderer.Lane.END
                after -> JobHitRenderer.Lane.START
                else -> JobHitRenderer.Lane.NONE
            }
            row.copy(lane = lane, laneDashedIn = before && notTriggered[i], laneDashedOut = after && notTriggered[i + 1])
        }
    }

    /** Prefers the first job over a Jenkins node, so Enter builds the best match. */
    private fun firstJobIndex(rows: List<JobRow>) = rows.indexOfFirst { it is JobRow.Job }.takeIf { it >= 0 } ?: 0

    private fun rowKey(row: JobRow): Any = when (row) {
        is JobRow.Server -> row.serverId
        is JobRow.Folder -> "${row.serverId} folder ${row.path}"
        is JobRow.Job -> row.hit.serverId to row.hit.job.url
    }

    private fun selected(): JobHit? = (list.selectedValue as? JobRow.Job)?.hit

    /** [ChainDisplay.HIGHLIGHT]: tints the jobs before and after the selected one and shows its path. */
    private fun updateChainHighlight() {
        val hit = selected()
        val on = JenkSpotterSettings.getInstance().current.chainDisplay == ChainDisplay.HIGHLIGHT
        val index = JobIndexService.getInstance()
        val position = hit?.takeIf { on }?.let { index.position(it.serverId, it.job) }
        val next = hit?.takeIf { on }?.job?.downstream?.mapNotNull { index.jobByFullName(hit.serverId, it) }.orEmpty()
        if (hit == null || !on || (position == null && next.isEmpty())) {
            if (related.isNotEmpty()) list.repaint()
            related = emptyMap()
            chainPath.isVisible = false
            return
        }
        val state = index.state(hit.serverId)
        val result = HashMap<String, JobHitRenderer.Relation>()
        // Everything triggering it, on any path, and everything it triggers.
        val queue = ArrayDeque(listOf(hit.job.fullName))
        val seen = hashSetOf(hit.job.fullName)
        while (queue.isNotEmpty()) {
            for (up in state.upstream[queue.removeFirst()].orEmpty()) {
                if (seen.add(up.fullName)) {
                    result["${hit.serverId} ${up.url}"] = JobHitRenderer.Relation.UPSTREAM
                    queue += up.fullName
                }
            }
        }
        // Disabled follow-ups and everything below them are not triggered.
        fun down(nodes: List<de.pdenis.jenkspotter.core.ChainNode>, reached: Boolean) {
            for (node in nodes) {
                val triggered = reached && !node.disabled
                node.url?.let { result["${hit.serverId} $it"] = if (triggered) JobHitRenderer.Relation.DOWNSTREAM else JobHitRenderer.Relation.NOT_TRIGGERED }
                down(node.children, triggered)
            }
        }
        down(index.chain(hit.serverId, hit.job), !hit.job.disabled && position?.blockedBy == null)
        related = result
        list.repaint()

        chainPath.removeAll()
        fun link(job: JobRef) = com.intellij.ui.components.ActionLink(
            de.pdenis.jenkspotter.api.JenkinsPaths.displayName(job.name) + if (job.disabled) " " + JenkSpotterBundle.message("jobs.disabled") else "",
        ) { jumpTo(hit.serverId, job) }.apply {
            toolTipText = de.pdenis.jenkspotter.api.JenkinsPaths.displayName(job.fullName)
            if (job.disabled) foreground = com.intellij.util.ui.UIUtil.getInactiveTextColor()
        }
        fun arrow() = com.intellij.ui.components.JBLabel("\u203A").apply { foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground() }
        position?.path?.forEach { chainPath.add(link(it)); chainPath.add(arrow()) }
        chainPath.add(com.intellij.ui.components.JBLabel(hit.name).apply { font = font.deriveFont(java.awt.Font.BOLD) })
        if (next.isNotEmpty()) chainPath.add(arrow())
        next.forEachIndexed { i, job ->
            if (i > 0) chainPath.add(com.intellij.ui.components.JBLabel("|").apply { foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground() })
            chainPath.add(link(job))
        }
        chainPath.isVisible = true
        chainPath.revalidate()
        chainPath.repaint()
    }

    /** Selects [job] in the list; the search is kept if the job is listed, otherwise cleared. */
    private fun jumpTo(serverId: String, job: JobRef) {
        val listed = (0 until listModel.size()).any { (listModel.get(it) as? JobRow.Job)?.hit?.let { h -> h.serverId == serverId && h.job.url == job.url } == true }
        reveal(serverId, job.url, if (listed) search.text else "")
    }

    /** Alt+Up: the job that triggers the selected one (along the longest chain). */
    private fun jumpPrevious() {
        val hit = selected() ?: return
        val previous = JobIndexService.getInstance().position(hit.serverId, hit.job)?.path?.lastOrNull() ?: return
        jumpTo(hit.serverId, previous)
    }

    /** Alt+Down: the job triggered by the selected one; with several, a list to choose from. */
    private fun jumpNext() {
        val hit = selected() ?: return
        val index = JobIndexService.getInstance()
        val next = hit.job.downstream.mapNotNull { index.jobByFullName(hit.serverId, it) }
        when (next.size) {
            0 -> return
            1 -> jumpTo(hit.serverId, next.single())
            else -> {
                val bounds = list.getCellBounds(list.selectedIndex, list.selectedIndex) ?: return
                JBPopupFactory.getInstance().createPopupChooserBuilder(next)
                    .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { de.pdenis.jenkspotter.api.JenkinsPaths.displayName(it.fullName) })
                    .setItemChosenCallback { jumpTo(hit.serverId, it) }
                    .createPopup()
                    .show(RelativePoint(list, java.awt.Point(bounds.x + JBUI.scale(24), bounds.y + bounds.height)))
            }
        }
    }

    /**
     * Context menu "Follow-up Chain": previous / next job and the whole chain from the job that
     * starts it, the selected job marked; each entry selects that job.
     */
    private fun chainGroup(): AnAction = object : com.intellij.openapi.actionSystem.ActionGroup({ JenkSpotterBundle.message("chain.menu") }, true) {
        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            val hit = selected() ?: return AnAction.EMPTY_ARRAY
            val index = JobIndexService.getInstance()
            val root = index.position(hit.serverId, hit.job)?.path?.firstOrNull() ?: hit.job
            val result = mutableListOf<AnAction>(
                action("chain.previous", AllIcons.Actions.PreviousOccurence, { index.position(hit.serverId, hit.job) != null }) { jumpPrevious() }
                    .also { it.shortcutSet = com.intellij.openapi.actionSystem.CustomShortcutSet(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_UP, java.awt.event.InputEvent.ALT_DOWN_MASK)) },
                action("chain.next", AllIcons.Actions.NextOccurence, { hit.job.downstream.isNotEmpty() }) { jumpNext() }
                    .also { it.shortcutSet = com.intellij.openapi.actionSystem.CustomShortcutSet(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, java.awt.event.InputEvent.ALT_DOWN_MASK)) },
                com.intellij.openapi.actionSystem.Separator.create(),
            )
            fun entry(fullName: String, job: JobRef?, depth: Int) {
                val name = "\u2003".repeat(depth) + (if (depth > 0) "\u21B3 " else "") + de.pdenis.jenkspotter.api.JenkinsPaths.displayName(fullName) +
                    (if (job?.disabled == true) "  " + JenkSpotterBundle.message("jobs.disabled") else "")
                val current = job?.url == hit.job.url
                result += object : DumbAwareAction(name, null, if (current) AllIcons.Actions.Checked else null) {
                    override fun actionPerformed(e: AnActionEvent) {
                        if (job != null && !current) jumpTo(hit.serverId, job)
                    }
                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = job != null
                    }
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                }
            }
            fun walk(nodes: List<de.pdenis.jenkspotter.core.ChainNode>, depth: Int) {
                for (node in nodes) {
                    entry(node.fullName, index.jobByFullName(hit.serverId, node.fullName), depth)
                    walk(node.children, depth + 1)
                }
            }
            val chain = index.chain(hit.serverId, root)
            if (chain.isEmpty()) return result.toTypedArray()
            entry(root.fullName, root, 0)
            walk(chain, 1)
            return result.toTypedArray()
        }

        override fun update(e: AnActionEvent) {
            val hit = selected()
            e.presentation.isEnabledAndVisible = hit != null &&
                (hit.job.downstream.isNotEmpty() || JobIndexService.getInstance().position(hit.serverId, hit.job) != null)
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
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
        is JobRow.Job -> row.expanded?.not()
        else -> null
    }

    private fun setCollapsed(row: JobRow?, collapsed: Boolean): Boolean {
        val settings = JenkSpotterSettings.getInstance()
        when (row) {
            is JobRow.Server -> settings.setServerCollapsed(row.serverId, collapsed)
            is JobRow.Folder -> settings.setFolderCollapsed(row.serverId, row.path, collapsed)
            is JobRow.Job -> {
                if (row.expanded == null) return false
                val key = "${row.hit.serverId} ${row.hit.job.url}"
                if (collapsed) expandedChains.remove(key) else expandedChains.add(key)
                scheduleSearch(keepSelection = true)
            }
            else -> return false
        }
        return true
    }

    /** Enter: builds the selected job, or opens/closes the selected node. */
    private fun activate() {
        when (val row = list.selectedValue) {
            is JobRow.Job -> trigger(row.hit)
            null -> Unit
            else -> collapsed(row)?.let { setCollapsed(row, !it) }
        }
    }

    private fun trigger(hit: JobHit) {
        if (!hit.job.canBuild) return
        JenkSpotterSettings.getInstance().markRecent(hit.serverId, hit.job.url)
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
        add(action("action.refreshIndex", AllIcons.Actions.Refresh, { filter.serverIds().isNotEmpty() }) { refreshIndexes(force = true) })
        add(sortMenu())
        add(ViewOptions.menu("jobs.show", listOf(
            ViewOptions.toggle("jobs.show.path", { showJobPath }) { showJobPath = it },
            ViewOptions.toggle("jobs.show.folders", { showFolderEntries }) { showFolderEntries = it },
            ViewOptions.toggle("jobs.show.downstream", { showDownstream }) { showDownstream = it },
            ViewOptions.toggle("jobs.show.chainStep", { showChainStep }) { showChainStep = it },
            jobIconMenu(),
            chainDisplayMenu(),
            followUpIconMenu(),
            chainStepIconMenu(),
            visibilityToggleMenu(),
        )))
    }

    /** Icon in front of the jobs; the choice is shown live while the menu stays open. */
    private fun jobIconMenu(): AnAction = DefaultActionGroup({ JenkSpotterBundle.message("jobs.icon") }, true).apply {
        JobIcon.entries.forEach { style ->
            if (style.ordinal > 0 && JobIcon.entries[style.ordinal - 1].group != style.group) addSeparator()
            add(radio(style.key, { jobIcon == style }, JenkSpotterIcons.job(style, parameterized = false)) { jobIcon = style })
        }
    }

    private fun visibilityToggleMenu(): AnAction = DefaultActionGroup({ JenkSpotterBundle.message("jenkins.toggle") }, true).apply {
        VisibilityToggle.entries.forEach { style ->
            val icon = ServerStatusUi.toggleIcon(style, hidden = false)
            add(radio(style.key, { visibilityToggle == style }, icon) { visibilityToggle = style })
        }
    }

    private fun chainDisplayMenu(): AnAction = DefaultActionGroup({ JenkSpotterBundle.message("jobs.chainDisplay") }, true).apply {
        ChainDisplay.entries.forEach { mode -> add(radio(mode.key, { chainDisplay == mode }) { chainDisplay = mode }) }
    }

    private fun chainStepIconMenu(): AnAction = DefaultActionGroup({ JenkSpotterBundle.message("jobs.chainStepIcon") }, true).apply {
        ChainStepIcon.entries.forEach { style ->
            val icon = JenkSpotterIcons.chainStep(style, JenkSpotterSettings.getInstance().current.followUpIcon)
            add(radio(style.key, { chainStepIcon == style }, icon) { chainStepIcon = style })
        }
    }

    private fun followUpIconMenu(): AnAction = DefaultActionGroup({ JenkSpotterBundle.message("jobs.followUpIcon") }, true).apply {
        FollowUpIcon.entries.forEach { style ->
            add(radio(style.key, { followUpIcon == style }, JenkSpotterIcons.followUp(style)) { followUpIcon = style })
        }
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
        private val jobs = JobHitRenderer(::serverLabel)
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
                is JobRow.Folder -> folders.getListCellRendererComponent(list as JList<out JobRow.Folder>, value, index, selected, focus)
                is JobRow.Job -> {
                    jobs.indent = value.depth * GROUP_INDENT
                    jobs.folderShown = value.folder
                    jobs.chainDepth = value.chainDepth
                    jobs.expanded = value.expanded
                    jobs.lane = value.lane
                    jobs.laneDashedIn = value.laneDashedIn
                    jobs.laneDashedOut = value.laneDashedOut
                    jobs.relation = related["${value.hit.serverId} ${value.hit.job.url}"]
                    jobs.getListCellRendererComponent(list as JList<out JobHit>, value.hit, index, selected, focus)
                }
            }
    }

    /** Folder node of [JobLayout.TREE_FOLDERS]: open/closed arrow, folder icon, path, number of jobs. */
    private class FolderRenderer : ColoredListCellRenderer<JobRow.Folder>() {
        override fun customizeCellRenderer(list: JList<out JobRow.Folder>, value: JobRow.Folder, index: Int, selected: Boolean, hasFocus: Boolean) {
            ipad = JBUI.insets(1, 2 + value.depth * GROUP_INDENT, 1, 2)
            icon = RowIcon(if (value.collapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown, JenkSpotterIcons.Folder)
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
                append("   " + ServerStatusUi.label(state), if (state == ConnectionState.ERROR || state == ConnectionState.OFFLINE) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
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
