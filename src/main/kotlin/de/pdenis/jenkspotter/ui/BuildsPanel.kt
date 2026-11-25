package de.pdenis.jenkspotter.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.TableView
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.JenkSpotterIcons
import de.pdenis.jenkspotter.api.BuildResult
import de.pdenis.jenkspotter.api.JenkinsPaths
import de.pdenis.jenkspotter.core.BuildMonitorListener
import de.pdenis.jenkspotter.core.BuildMonitorService
import de.pdenis.jenkspotter.core.BuildSource
import de.pdenis.jenkspotter.core.MonitorSnapshot
import de.pdenis.jenkspotter.core.QueuedTrigger
import de.pdenis.jenkspotter.core.TrackedBuild
import de.pdenis.jenkspotter.settings.JenkSpotterConfigurable
import de.pdenis.jenkspotter.settings.BuildColumn
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import de.pdenis.jenkspotter.settings.BuildChip
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.Timer

sealed class BuildRow {
    abstract val key: String
    abstract val serverId: String

    data class Build(val tracked: TrackedBuild, val depth: Int = 0) : BuildRow() {
        override val key get() = tracked.key
        override val serverId get() = tracked.serverId
    }

    data class Queued(val trigger: QueuedTrigger) : BuildRow() {
        override val key get() = trigger.queueUrl
        override val serverId get() = trigger.serverId
    }

    /** A follow-up job known from the job configuration that has not started yet. */
    data class Expected(override val serverId: String, val parentKey: String, val jobFullName: String, val jobUrl: String?, val depth: Int) : BuildRow() {
        override val key get() = "$parentKey -> $jobFullName"
    }
}

class BuildsPanel(private val project: Project, private val filter: SharedFilter) : SimpleToolWindowPanel(true, true), Disposable {
    @Volatile
    private var disposed = false
    private val model = ListTableModel<BuildRow>()

    // Declared before init: render() in init already builds the columns.
    private val jobColumn = object : ColumnInfo<BuildRow, BuildRow>(JenkSpotterBundle.message("column.job")) {
        override fun valueOf(item: BuildRow) = item
        override fun getRenderer(item: BuildRow?) = jobRenderer
    }
    private var shownColumns: List<BuildColumn>? = null
    /** Per table row: tinted background. A build and its follow-ups share one, so a chain reads as one block. */
    private var stripes = BooleanArray(0)
    private val table = object : TableView<BuildRow>(model) {
        override fun prepareRenderer(renderer: javax.swing.table.TableCellRenderer, row: Int, column: Int): java.awt.Component {
            val component = super.prepareRenderer(renderer, row, column)
            if (!isRowSelected(row)) component.background = Stripes.background(stripes.getOrElse(row) { false }, false, false)
            return component
        }

        // Built when asked for, not for every row with every repaint.
        override fun getToolTipText(event: MouseEvent): String? {
            val row = rowAtPoint(event.point)
            val column = columnAtPoint(event.point)
            if (row >= 0 && column >= 0 && convertColumnIndexToModel(column) == 0) {
                (this@BuildsPanel.model.items.getOrNull(convertRowIndexToModel(row)) as? BuildRow.Build)?.let { return buildTooltip(it.tracked) }
            }
            return super.getToolTipText(event)
        }
    }
    /** Build status and scope chips, like the filter chips of the Jenkins view. */
    private val chipBar = JPanel(com.intellij.util.ui.WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply { isOpaque = false }
    private var shownChips: Map<BuildChip, ChipState>? = null

    /** Current page (0-based); one page holds [pageSize] builds, each with its follow-ups. */
    private var page = 0
    private val pageLabel = JBLabel().apply { foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground() }
    private var pageCount = 1
    private val search = com.intellij.ui.SearchTextField(false).apply {
        textEditor.emptyText.text = JenkSpotterBundle.message("builds.searchHint")
        textEditor.columns = 12
    }

    /** Finished builds that ended before this time are hidden by "Clear Finished" (pins/views excepted). */
    private var clearedBefore = 0L

    /** True while [render] replaces the rows and restores the selection. */
    private var rendering = false
    private val details = BuildDetailsPanel(project)
    private val tableScroll = JBScrollPane(table)
    private val pager = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBUI.CurrentTheme.ToolWindow.borderColor()), JBUI.Borders.empty(0, 4))
        add(pageLabel, BorderLayout.CENTER)
        add(ActionManager.getInstance().createActionToolbar("JenkSpotter.BuildsPager", DefaultActionGroup().apply {
            add(action("builds.page.previous", AllIcons.Actions.Back, enabled = { page > 0 }) { goToPage(page - 1) })
            add(action("builds.page.next", AllIcons.Actions.Forward, enabled = { page < pageCount - 1 }) { goToPage(page + 1) })
            add(pageSizeMenu())
        }, true).also { it.targetComponent = table }.component, BorderLayout.EAST)
    }
    private val tablePanel = JPanel(BorderLayout()).apply {
        add(tableScroll, BorderLayout.CENTER)
        add(pager, BorderLayout.SOUTH)
    }
    private val detailsSplitter = com.intellij.ui.OnePixelSplitter(true, "JenkSpotter.BuildDetails", 0.62f)
    private val content = JPanel(BorderLayout())
    private val hoverAlarm = com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.SWING_THREAD, this)
    private val ticker = Timer(1000) { if (model.items.any { it is BuildRow.Build && it.tracked.build.building }) table.repaint() }

    init {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.setShowGrid(false)
        table.intercellSpacing = java.awt.Dimension(0, 0)
        table.rowHeight = JBUI.scale(20)
        table.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        table.tableHeader.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                saveColumnOrder()
                saveColumnWidths()
            }

            // Not fired after dragging a column or its edge, so moving and resizing do not sort.
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                val index = table.columnModel.getColumnIndexAtX(e.x).takeIf { it >= 0 } ?: return
                toggleSort(sortKey(model.columnInfos.getOrNull(table.columnModel.getColumn(index).modelIndex) ?: return))
            }
        })

        // Search and actions in one row, the filter chips below.
        val north = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(1, 2)
            add(JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                isOpaque = false
                border = JBUI.Borders.emptyLeft(2)
                add(search, BorderLayout.CENTER)
                add(createToolbar(), BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(chipBar, BorderLayout.CENTER)
        }
        model.isSortable = false
        toolbar = north
        tableScroll.border = JBUI.Borders.empty()
        setContent(content)
        details.onCollapsedChanged = { collapsed ->
            JenkSpotterSettings.getInstance().updateView { buildInfoCollapsed = collapsed }
            layoutDetails()
        }
        layoutDetails()
        table.selectionModel.addListSelectionListener {
            // While render() swaps the rows the selection is briefly empty; passing that on would make
            // the build info forget the build and load everything again. render() hands it over once.
            if (it.valueIsAdjusting || rendering) return@addListSelectionListener
            val build = selectedBuild()
            // Picking a build opens the build info; keeping the selection across a refresh does not.
            if (build != null && details.collapsed) {
                details.setCollapsed(false)
                details.onCollapsedChanged(false)
            }
            details.show(build)
        }

        filter.onChange {
            rebuildChips()
            render(BuildMonitorService.getInstance().snapshot)
        }
        rebuildChips()
        search.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                page = 0
                render(BuildMonitorService.getInstance().snapshot)
            }
        })
        // Enter opens the log of the selected build, like a double-click.
        table.inputMap.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "jenkspotter.openLog")
        table.actionMap.put("jenkspotter.openLog", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                selectedBuild()?.let { BuildActions.openLog(project, it) }
            }
        })

        // Resting on a failed build loads why it failed, for its tooltip (see [buildTooltip]).
        table.addMouseMotionListener(object : java.awt.event.MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                hoverAlarm.cancelAllRequests()
                val row = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                val build = (model.items.getOrNull(table.convertRowIndexToModel(row)) as? BuildRow.Build)?.tracked ?: return
                // Only a mouse that stays: sweeping over the table must not load every red build on the way.
                val at = e.point
                hoverAlarm.addRequest({
                    de.pdenis.jenkspotter.core.FailureHints.getInstance().hint(build) {
                        ApplicationManager.getApplication().invokeLater({ refreshTooltip(at) }, { disposed || project.isDisposed })
                    }
                }, HOVER_DELAY_MILLIS)
            }
        })
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                selectedBuild()?.let { BuildActions.openLog(project, it) } ?: return false
                return true
            }
        }.installOn(table)
        PopupHandler.installPopupMenu(table, DefaultActionGroup().apply {
            add(actionGroup())
            addSeparator()
            add(action("input.proceedAction", AllIcons.Actions.Resume, enabled = { selectedInput() != null }) {
                val build = selectedBuild() ?: return@action Unit
                selectedInput()?.let { BuildActions.proceedInput(project, build, it) }
            })
            add(action("input.abortAction", AllIcons.Actions.Cancel, enabled = { selectedInput() != null }) {
                val build = selectedBuild() ?: return@action Unit
                selectedInput()?.let { BuildActions.abortInput(project, build, it) }
            })
            add(action("action.openChanges", AllIcons.Vcs.Changelist, enabled = { selectedBuild() != null }) {
                selectedBuild()?.let { BuildActions.openInBrowser(it.build.url + "changes") }
            })
            add(action("action.openTests", AllIcons.RunConfigurations.TestState.Run, enabled = { selectedBuild()?.build?.tests != null }) {
                selectedBuild()?.let { BuildActions.openInBrowser(it.build.url + "testReport") }
            })
            add(action("action.openJob", AllIcons.Nodes.Folder, enabled = { selectedBuild() != null }) {
                selectedBuild()?.let { BuildActions.openInBrowser(it.build.jobUrl) }
            })
            add(action("action.copyUrl", AllIcons.Actions.Copy, enabled = { selectedBuild() != null }) {
                selectedBuild()?.let {
                    com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(it.build.url))
                }
            })
            add(action("action.buildAgain", AllIcons.Actions.Execute, enabled = { selectedBuild() != null }) {
                // Fresh build with the job's default parameters (Rebuild reuses the previous values).
                selectedBuild()?.let { BuildActions.trigger(project, it.serverId, it.build.jobUrl, it.build.jobFullName, null) }
            })
            addSeparator()
            add(jobToggle("action.pin", "action.unpin", JenkSpotterIcons.Pinned, JenkSpotterSettings::isPinned, JenkSpotterSettings::togglePin))
            add(jobToggle("action.watch", "action.unwatch", JenkSpotterIcons.Watched, JenkSpotterSettings::isWatched, JenkSpotterSettings::toggleWatch))
            addSeparator()
            add(FilterActions.group(project) {
                when (val row = table.selectedObject) {
                    is BuildRow.Build -> row.tracked.build.jobFullName
                    is BuildRow.Queued -> row.trigger.jobFullName
                    is BuildRow.Expected -> row.jobFullName
                    null -> null
                }
            })
        }, "JenkSpotter.BuildsPopup")

        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(BuildMonitorListener.TOPIC, object : BuildMonitorListener {
            override fun snapshotChanged(snapshot: MonitorSnapshot) {
                // render() hands the selected build on to the build info.
                ApplicationManager.getApplication().invokeLater({ render(snapshot) }, { disposed || project.isDisposed })
            }
        })
        ticker.start()
        render(BuildMonitorService.getInstance().snapshot)
    }

    private fun render(snapshot: MonitorSnapshot) {
        applyColumns()
        val selectedKey = table.selectedObject?.key
        val hidden = filter.hidden()
        val chips = chipStates()

        val builds = snapshot.builds.asSequence()
            .filter { filter.includes(it.serverId) && !hidden.isHidden(it.build.jobFullName) }
            .filter { tracked -> BuildTableLogic.passes(chips) { chip -> matches(chip, tracked, snapshot) } }
            .filter { it.build.building || it.sources.any { s -> s == BuildSource.PINNED || s == BuildSource.WATCHED || s == BuildSource.VIEW } ||
                it.build.timestamp + it.build.durationMillis >= clearedBefore }
            .filter { matchesSearch(it.build.jobFullName, it.build.number.toString(), it.build.parameters.joinToString(" ") { p -> "${p.name}=${p.value}" }, it.build.description.orEmpty()) }
            .toList()
        val queued = snapshot.queued.asSequence()
            // Builds started from here: queued, and mine.
            .filter { filter.includes(it.serverId) && !hidden.isHidden(it.jobFullName) && BuildTableLogic.passes(chips) { c -> c == BuildChip.QUEUED || c == BuildChip.MINE } }
            .filter { matchesSearch(it.jobFullName) }
            .map { BuildRow.Queued(it) }
        // One page holds whole groups: a build with its follow-ups is never split.
        val groups = BuildTableLogic.groups(queued.toList() + withChildren(builds))
        // Sorting moves whole groups by their first row, follow-ups stay below the build that started them.
        currentSort()?.let { (key, ascending) ->
            val comparator = sortComparator(key)
            groups.sortWith { a, b -> comparator.compare(a.first(), b.first()).let { if (ascending) it else -it } }
        }
        updateSortMarks()
        val size = pageSize()
        pageCount = BuildTableLogic.pageCount(groups.size, size)
        // New builds push older ones back a page: the table follows the selected build instead of losing it.
        selectedKey?.let { BuildTableLogic.pageOf(groups, it, size) }?.let { page = it }
        page = page.coerceIn(0, pageCount - 1)
        val shown = BuildTableLogic.page(groups, page, size)
        stripes = shown.flatMapIndexed { i, group -> List(group.size) { i % 2 == 1 } }.toBooleanArray()
        val builtCount = groups.sumOf { g -> g.count { it is BuildRow.Build } }
        pageLabel.text = JenkSpotterBundle.message("builds.page", page + 1, pageCount, builtCount)
        pager.isVisible = pageCount > 1
        updateEmptyText(shown.isEmpty() && snapshot.builds.any { filter.includes(it.serverId) })
        rendering = true
        try {
            model.items = shown.flatten()
            model.items.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 }?.let {
                table.selectionModel.setSelectionInterval(it, it)
            }
        } finally {
            rendering = false
        }
        // Same build still selected: only its data is updated, nothing is loaded again.
        details.show(selectedBuild())
    }

    /** Open build info: splitter below the table; closed: just its title row under the table. */
    private fun layoutDetails() {
        content.removeAll()
        if (details.collapsed) {
            detailsSplitter.firstComponent = null
            detailsSplitter.secondComponent = null
            content.add(tablePanel, BorderLayout.CENTER)
            content.add(details, BorderLayout.SOUTH)
        } else {
            detailsSplitter.firstComponent = tablePanel
            detailsSplitter.secondComponent = details
            content.add(detailsSplitter, BorderLayout.CENTER)
        }
        content.revalidate()
        content.repaint()
    }

    /** Built from the checked-out branch: its multibranch job, or a parameter whose value is the branch. */
    private fun isCurrentBranch(tracked: TrackedBuild): Boolean {
        val branchJobs = BranchJobs.getInstance(project)
        if (BuildSource.BRANCH in tracked.sources && branchJobs.isBranchJob(tracked.serverId, tracked.build.jobUrl)) return true
        val branches = branchJobs.branches
        return tracked.build.parameters.any { p ->
            val v = p.value?.removePrefix("refs/heads/")?.removePrefix("origin/") ?: return@any false
            v in branches
        }
    }

    /** Every word of the search field must appear somewhere (job name, number, parameters, description). */
    private fun matchesSearch(vararg texts: String): Boolean {
        val words = search.text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystack = texts.joinToString(" ") { FilterActions.decoded(it) }.lowercase()
        return words.all { it in haystack }
    }

    /** Orders builds newest first and puts follow-up builds indented below the build that started them. */
    private fun withChildren(builds: List<TrackedBuild>): List<BuildRow> {
        val keys = builds.map { it.key }.toSet()
        val children = builds.filter { it.parentKey in keys }.groupBy { it.parentKey }
        val result = mutableListOf<BuildRow>()
        val index = de.pdenis.jenkspotter.core.JobIndexService.getInstance()
        fun addExpected(serverId: String, parentKey: String, nodes: List<de.pdenis.jenkspotter.core.ChainNode>, depth: Int) {
            // A disabled job is not triggered, and nothing below it is either.
            for (node in nodes.filter { !it.disabled }) {
                result += BuildRow.Expected(serverId, parentKey, node.fullName, node.url, depth)
                addExpected(serverId, "$parentKey -> ${node.fullName}", node.children, depth + 1)
            }
        }
        fun add(build: TrackedBuild, depth: Int) {
            result += BuildRow.Build(build, depth)
            if (depth >= 8) return
            val own = children[build.key].orEmpty().sortedBy { it.build.timestamp }
            own.forEach { add(it, depth + 1) }
            // While a build runs, show the follow-ups its configuration will trigger but that did not start yet.
            if (build.build.building && BuildTableLogic.statusPasses(chipStates(), BuildChip.QUEUED)) {
                val job = index.jobByUrl(build.serverId, build.build.jobUrl) ?: return
                val started = own.map { it.build.jobFullName }.toSet()
                addExpected(build.serverId, build.key, index.chain(build.serverId, job).filter { it.fullName !in started }, depth + 1)
            }
        }
        builds.filter { it.parentKey !in keys }.forEach { add(it, 0) }
        return result
    }

    private fun selectedBuild(): TrackedBuild? = (table.selectedObject as? BuildRow.Build)?.tracked

    private fun selectedInput() = selectedBuild()?.let { BuildMonitorService.getInstance().snapshot.waitingInput[it.key] }

    private fun createToolbar() = ActionManager.getInstance()
        .createActionToolbar("JenkSpotter.Builds", DefaultActionGroup(actionGroup(), com.intellij.openapi.actionSystem.Separator.getInstance(), tableActions()), true)
        .also { it.targetComponent = table }
        .component

    private fun actionGroup() = DefaultActionGroup().apply {
        add(action("action.openLog", AllIcons.Debugger.Console, enabled = { selectedBuild() != null }) {
            selectedBuild()?.let { BuildActions.openLog(project, it) }
        })
        add(action("action.rebuild", AllIcons.Actions.Restart, enabled = { selectedBuild() != null }) {
            selectedBuild()?.let { BuildActions.rebuild(project, it) }
        })
        add(action("action.abort", AllIcons.Actions.Suspend, enabled = { selectedBuild()?.build?.building == true }) {
            selectedBuild()?.let { BuildActions.abort(project, it) }
        })
        add(action("action.openInBrowser", AllIcons.General.Web, enabled = { table.selectedObject != null }) {
            when (val row = table.selectedObject) {
                is BuildRow.Build -> BuildActions.openBuild(row.tracked.build)
                is BuildRow.Queued -> BuildActions.openInBrowser(row.trigger.jobUrl)
                is BuildRow.Expected -> row.jobUrl?.let(BuildActions::openInBrowser)
                null -> Unit
            }
        })
    }

    /** Actions on the table as a whole; only in the toolbar, not in the menu of a row. */
    private fun tableActions() = DefaultActionGroup().apply {
        // Stays pressed while finished builds are hidden, so it is visible why they are missing.
        add(object : com.intellij.openapi.project.DumbAwareToggleAction(JenkSpotterBundle.message("action.clearFinished"), null, AllIcons.Actions.GC) {
            override fun isSelected(e: AnActionEvent) = clearedBefore > 0
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                clearedBefore = if (state) System.currentTimeMillis() else 0
                render(BuildMonitorService.getInstance().snapshot)
            }
            override fun update(e: AnActionEvent) {
                super.update(e)
                e.presentation.description = JenkSpotterBundle.message("action.clearFinished.description")
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        // Refresh and settings are in the tool window title.
        add(columnsMenu())
    }

    /** What the empty table says: nothing known yet, or everything filtered away - then with a way out. */
    private fun updateEmptyText(filtered: Boolean) {
        val text = table.emptyText
        text.clear()
        if (!filtered) {
            text.appendText(JenkSpotterBundle.message("builds.empty"))
            text.appendSecondaryText(JenkSpotterBundle.message("action.configure"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, JenkSpotterConfigurable::class.java)
            }
            return
        }
        val reasons = listOfNotNull(
            chipStates().size.takeIf { it > 0 }?.let { JenkSpotterBundle.message("builds.empty.chips", it) },
            search.text.trim().takeIf { it.isNotEmpty() }?.let { JenkSpotterBundle.message("builds.empty.search", it) },
            JenkSpotterBundle.message("builds.empty.cleared").takeIf { clearedBefore > 0 },
            JenkSpotterBundle.message("builds.empty.jobFilters").takeIf { JenkSpotterSettings.getInstance().current.filters.any { it.enabled } },
        )
        text.appendText(JenkSpotterBundle.message("builds.empty.filtered", reasons.joinToString(", ")))
        if (hasOwnFilters()) text.appendSecondaryText(JenkSpotterBundle.message("builds.filters.reset"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { resetFilters() }
    }

    /** Chips, search text or hidden finished builds: what [resetFilters] undoes. */
    private fun hasOwnFilters() = chipStates().isNotEmpty() || search.text.isNotBlank() || clearedBefore > 0

    private fun resetFilters() {
        clearedBefore = 0
        page = 0
        if (search.text.isNotEmpty()) search.text = ""
        if (chipStates().isNotEmpty()) JenkSpotterSettings.getInstance().updateView { buildChips = mutableListOf() }
        else render(BuildMonitorService.getInstance().snapshot)
    }

    private fun chipStates(): Map<BuildChip, ChipState> =
        JenkSpotterSettings.getInstance().current.buildChips.mapNotNull { entry ->
            val (name, state) = entry.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            val chip = BuildChip.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
            ChipState.entries.firstOrNull { it.name == state }?.let { chip to it }
        }.toMap()

    private fun setChip(chip: BuildChip, state: ChipState) {
        page = 0
        val next = (chipStates() + (chip to state)).filterValues { it != ChipState.OFF }
        // Settings listeners rebuild the chips and render the table.
        JenkSpotterSettings.getInstance().updateView { buildChips = next.map { "${it.key.name}=${it.value.name}" }.toMutableList() }
    }

    /**
     * The status chips are always there. The scope chips (mine, pinned, ...) are in the "Filter"
     * menu and show up as a chip only while they are green or red.
     */
    private fun rebuildChips() {
        val states = chipStates()
        if (states == shownChips) return
        shownChips = states
        chipBar.removeAll()
        fun chip(chip: BuildChip) = FilterChip(
            JenkSpotterBundle.message(chip.key), states[chip] ?: ChipState.OFF,
            CHIP_STATES, { setChip(chip, it) }, null, null,
            "<html><b>" + JenkSpotterBundle.message(chip.tooltipKey) + "</b><br>" + JenkSpotterBundle.message("filter.chip.help") + "</html>",
        )
        BuildChip.entries.filter { it.status }.forEach { chipBar.add(chip(it)) }
        chipBar.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(4)))
        BuildChip.entries.filter { !it.status && it in states }.forEach { chipBar.add(chip(it)) }
        chipBar.add(com.intellij.ui.components.DropDownLink(JenkSpotterBundle.message("builds.filters.more")) { link -> scopeMenu(link) }.apply {
            toolTipText = JenkSpotterBundle.message("builds.filters.more.tooltip")
        })
        chipBar.revalidate()
        chipBar.repaint()
    }

    /** "Mine > Show only / Hide / Off" for every scope chip, and a way back to no filter at all. */
    private fun scopeMenu(owner: java.awt.Component): com.intellij.openapi.ui.popup.JBPopup {
        val states = chipStates()
        val group = DefaultActionGroup()
        for (chip in BuildChip.entries.filter { !it.status }) {
            group.add(DefaultActionGroup(JenkSpotterBundle.message(chip.key), true).apply {
                templatePresentation.description = JenkSpotterBundle.message(chip.tooltipKey)
                for (state in CHIP_STATES) {
                    add(object : com.intellij.openapi.project.DumbAwareToggleAction(JenkSpotterBundle.message(state.key)) {
                        override fun isSelected(e: AnActionEvent) = (states[chip] ?: ChipState.OFF) == state
                        override fun setSelected(e: AnActionEvent, selected: Boolean) = setChip(chip, state)
                        override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    })
                }
            })
        }
        group.addSeparator()
        group.add(action("builds.filters.reset", AllIcons.Actions.Cancel, enabled = { hasOwnFilters() }) { resetFilters() })
        return com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createActionGroupPopup(
            null, group, com.intellij.ide.DataManager.getInstance().getDataContext(owner),
            com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false,
        )
    }

    private fun matches(chip: BuildChip, tracked: TrackedBuild, snapshot: MonitorSnapshot): Boolean = when (chip) {
        BuildChip.MINE -> tracked.mine
        BuildChip.BRANCH -> isCurrentBranch(tracked)
        BuildChip.WAITING -> tracked.key in snapshot.waitingInput
        BuildChip.PINNED -> BuildSource.PINNED in tracked.sources
        BuildChip.WATCHED -> BuildSource.WATCHED in tracked.sources
        BuildChip.VIEWS -> BuildSource.VIEW in tracked.sources
        BuildChip.QUEUED -> false
        else -> statusOf(tracked.build) == chip
    }

    private fun pageSize(): Int = JenkSpotterSettings.getInstance().current.buildsPageSize.coerceAtLeast(10)

    private fun goToPage(target: Int) {
        // The table follows its selection, so leafing on lets go of it.
        table.clearSelection()
        page = target.coerceIn(0, pageCount - 1)
        render(BuildMonitorService.getInstance().snapshot)
        table.scrollRectToVisible(java.awt.Rectangle(0, 0, 1, 1))
    }

    private fun pageSizeMenu() = DefaultActionGroup({ JenkSpotterBundle.message("builds.pageSize") }, true).apply {
        templatePresentation.icon = AllIcons.Actions.GroupBy
        for (size in PAGE_SIZES) {
            add(object : com.intellij.openapi.project.DumbAwareToggleAction(JenkSpotterBundle.message("builds.pageSize.entry", size)) {
                override fun isSelected(e: AnActionEvent) = pageSize() == size
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    page = 0
                    JenkSpotterSettings.getInstance().updateView { buildsPageSize = size }
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
        }
    }

    /** Pin / watch the job of the selected build; the text names what a click will do. */
    private fun jobToggle(
        onKey: String, offKey: String, icon: javax.swing.Icon,
        isOn: JenkSpotterSettings.(String, String) -> Boolean,
        toggle: JenkSpotterSettings.(String, String, String) -> Unit,
    ) = object : DumbAwareAction({ JenkSpotterBundle.message(onKey) }, icon) {
        override fun actionPerformed(e: AnActionEvent) {
            val build = selectedBuild() ?: return
            JenkSpotterSettings.getInstance().toggle(build.serverId, build.build.jobFullName, build.build.jobUrl)
        }
        override fun update(e: AnActionEvent) {
            val build = selectedBuild()
            e.presentation.isEnabled = build != null
            val on = build != null && JenkSpotterSettings.getInstance().isOn(build.serverId, build.build.jobUrl)
            e.presentation.text = JenkSpotterBundle.message(if (on) offKey else onKey)
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

    override fun dispose() {
        disposed = true
        com.intellij.openapi.util.Disposer.dispose(details)
        ticker.stop()
    }

    /** Rebuilds the table columns if the chosen set or order changed. */
    /** Column key for sorting: the [BuildColumn] name, or JOB for the job column. */
    private fun sortKey(info: ColumnInfo<*, *>): String = (info as? FixedColumn)?.column?.name ?: SORT_JOB

    /** (key, ascending), null = default order (newest first). */
    private fun currentSort(): Pair<String, Boolean>? {
        val parts = JenkSpotterSettings.getInstance().current.buildSort.split(':').takeIf { it.size == 2 } ?: return null
        return parts[0] to (parts[1] == "asc")
    }

    /** Header click: ascending, descending, back to the default order. Times and durations start descending. */
    private fun toggleSort(key: String) {
        val current = currentSort()
        val firstDescending = key == BuildColumn.STARTED.name || key == BuildColumn.DURATION.name
        val next = when {
            current?.first != key -> if (firstDescending) "desc" else "asc"
            current.second == firstDescending -> if (firstDescending) "asc" else "desc"
            else -> null
        }
        page = 0
        // Settings listeners render the table again.
        JenkSpotterSettings.getInstance().updateView { buildSort = next?.let { "$key:$it" }.orEmpty() }
    }

    /** "▲" / "▼" behind the header of the sorted column. */
    private fun updateSortMarks() {
        val sort = currentSort()
        for (i in 0 until table.columnModel.columnCount) {
            val column = table.columnModel.getColumn(i)
            val info = model.columnInfos.getOrNull(column.modelIndex) ?: continue
            val mark = if (sort?.first == sortKey(info)) (if (sort.second) " \u25B2" else " \u25BC") else ""
            column.headerValue = info.name + mark
        }
        table.tableHeader.repaint()
    }

    private fun sortComparator(key: String): Comparator<BuildRow> {
        fun build(row: BuildRow) = (row as? BuildRow.Build)?.tracked
        @Suppress("UNCHECKED_CAST")
        val info = model.columnInfos.firstOrNull { sortKey(it) == key } as ColumnInfo<BuildRow, *>?
        val text = Comparator<BuildRow> { a, b -> String.CASE_INSENSITIVE_ORDER.compare(info?.valueOf(a)?.toString().orEmpty(), info?.valueOf(b)?.toString().orEmpty()) }
        return when (key) {
            SORT_JOB -> compareBy(String.CASE_INSENSITIVE_ORDER) { jobLabel(it.serverId, jobName(it)) }
            BuildColumn.STARTED.name -> compareBy { row ->
                when (row) {
                    is BuildRow.Build -> row.tracked.build.timestamp
                    is BuildRow.Queued -> row.trigger.since
                    is BuildRow.Expected -> Long.MAX_VALUE
                }
            }
            BuildColumn.DURATION.name -> {
                // One "now" for the whole sort: a clock moving between comparisons makes the order inconsistent.
                val now = System.currentTimeMillis()
                compareBy { row -> build(row)?.build?.let { if (it.building) now - it.timestamp else it.durationMillis } ?: -1L }
            }
            BuildColumn.TESTS.name -> compareBy<BuildRow> { build(it)?.build?.tests?.failed ?: -1 }.thenBy { build(it)?.build?.tests?.total ?: -1 }
            else -> text
        }
    }

    private fun applyColumns() {
        val wanted = BuildColumn.parse(JenkSpotterSettings.getInstance().current.buildColumns)
        if (wanted == shownColumns) return
        shownColumns = wanted
        model.columnInfos = (listOf<ColumnInfo<BuildRow, *>>(jobColumn) + wanted.map(::columnInfo)).toTypedArray()
        fixWidths()
    }

    /**
     * Optional columns start with a width that fits their typical content (or the width the user
     * dragged them to last time); all columns stay resizable, the job column takes the rest.
     */
    private fun fixWidths() {
        val saved = savedWidths()
        val metrics = table.getFontMetrics(table.font)
        val headerMetrics = table.tableHeader.getFontMetrics(table.tableHeader.font)
        for (i in 0 until table.columnModel.columnCount) {
            val column = table.columnModel.getColumn(i)
            column.resizable = true
            column.maxWidth = Int.MAX_VALUE
            val info = model.columnInfos.getOrNull(column.modelIndex)
            if (info !is FixedColumn) {
                column.minWidth = JBUI.scale(120)
                continue
            }
            column.minWidth = JBUI.scale(40)
            column.preferredWidth = saved[info.column]?.let(JBUI::scale) ?: info.fixedWidth(metrics, headerMetrics)
            column.width = column.preferredWidth
        }
    }

    private fun savedWidths(): Map<BuildColumn, Int> =
        JenkSpotterSettings.getInstance().current.buildColumnWidths.split(',').mapNotNull { part ->
            val (name, width) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            val column = BuildColumn.entries.firstOrNull { it.name == name.trim() } ?: return@mapNotNull null
            width.trim().toIntOrNull()?.let { column to it }
        }.toMap()

    /** Persists widths (unscaled) of the optional columns after the user resized one. */
    private fun saveColumnWidths() {
        val widths = savedWidths().toMutableMap()
        for (i in 0 until table.columnModel.columnCount) {
            val column = table.columnModel.getColumn(i)
            val info = model.columnInfos.getOrNull(column.modelIndex) as? FixedColumn ?: continue
            widths[info.column] = (column.width / com.intellij.ui.scale.JBUIScale.scale(1f)).toInt()
        }
        val text = widths.entries.joinToString(",") { "${it.key.name}=${it.value}" }
        if (text != JenkSpotterSettings.getInstance().current.buildColumnWidths) {
            JenkSpotterSettings.getInstance().updateView { buildColumnWidths = text }
        }
    }

    private abstract inner class FixedColumn(val column: BuildColumn) :
        ColumnInfo<BuildRow, String>(JenkSpotterBundle.message(column.key)) {
        fun fixedWidth(metrics: java.awt.FontMetrics, header: java.awt.FontMetrics) =
            maxOf(metrics.stringWidth(column.preferredText), header.stringWidth(name)) + JBUI.scale(18)

        override fun getWidth(table: JTable): Int =
            fixedWidth(table.getFontMetrics(table.font), table.tableHeader.getFontMetrics(table.tableHeader.font))
    }

    private fun columnInfo(column: BuildColumn): ColumnInfo<BuildRow, *> = when (column) {
        BuildColumn.STATUS -> object : FixedColumn(column) {
            override fun getRenderer(item: BuildRow?) = statusRenderer
            override fun valueOf(item: BuildRow): String = when (item) {
                is BuildRow.Queued -> JenkSpotterBundle.message("result.QUEUED")
                is BuildRow.Expected -> JenkSpotterBundle.message("result.EXPECTED")
                is BuildRow.Build -> if (item.tracked.key in BuildMonitorService.getInstance().snapshot.waitingInput) {
                    JenkSpotterBundle.message("result.WAITING")
                } else {
                    JenkSpotterBundle.message("result.${item.tracked.build.result.name}")
                }
            }
        }
        BuildColumn.STARTED -> object : FixedColumn(column) {
            override fun valueOf(item: BuildRow): String = when (item) {
                is BuildRow.Queued -> DateFormatUtil.formatPrettyDateTime(item.trigger.since)
                is BuildRow.Expected -> ""
                is BuildRow.Build -> DateFormatUtil.formatPrettyDateTime(item.tracked.build.timestamp)
            }
        }
        BuildColumn.DURATION -> object : FixedColumn(column) {
            override fun getRenderer(item: BuildRow?) = progressRenderer
            override fun valueOf(item: BuildRow): String = when (item) {
                is BuildRow.Queued -> item.trigger.why.orEmpty()
                is BuildRow.Expected -> ""
                is BuildRow.Build -> durationText(item.tracked)
            }
        }
        BuildColumn.SOURCE -> object : FixedColumn(column) {
            override fun valueOf(item: BuildRow): String = when (item) {
                is BuildRow.Queued -> JenkSpotterBundle.message("source.TRIGGERED")
                is BuildRow.Expected -> JenkSpotterBundle.message("source.DOWNSTREAM")
                is BuildRow.Build -> sourceText(item.tracked)
            }
        }
        BuildColumn.SERVER -> object : FixedColumn(column) {
            override fun valueOf(item: BuildRow): String = JenkSpotterSettings.getInstance().server(item.serverId)?.displayName.orEmpty()
        }
        BuildColumn.TESTS -> object : FixedColumn(column) {
            override fun valueOf(item: BuildRow): String {
                val tests = (item as? BuildRow.Build)?.tracked?.build?.tests ?: return ""
                return if (tests.failed > 0) JenkSpotterBundle.message("tests.failed", tests.failed, tests.total)
                else JenkSpotterBundle.message("tests.passed", tests.total)
            }
        }
        BuildColumn.DESCRIPTION -> object : FixedColumn(column) {
            override fun valueOf(item: BuildRow): String =
                (item as? BuildRow.Build)?.tracked?.build?.description?.let { com.intellij.openapi.util.text.StringUtil.stripHtml(it, " ") }.orEmpty()
        }
        BuildColumn.PARAMETERS -> object : FixedColumn(column) {
            override fun valueOf(item: BuildRow): String = when (item) {
                is BuildRow.Queued, is BuildRow.Expected -> ""
                is BuildRow.Build -> item.tracked.build.parameters.joinToString(", ") { "${it.name}=${it.value.orEmpty()}" }
            }
        }
    }

    /** Persists the order after the user dragged a column header. */
    private fun saveColumnOrder() {
        val order = (0 until table.columnModel.columnCount)
            .mapNotNull { (model.columnInfos.getOrNull(table.columnModel.getColumn(it).modelIndex) as? FixedColumn)?.column }
        if (order == shownColumns) return
        shownColumns = order
        JenkSpotterSettings.getInstance().updateView { buildColumns = BuildColumn.format(order) }
    }

    /**
     * Builds of other people: none (only those of interest), the running ones, or the running ones
     * plus the last N finished builds of all jobs.
     */
    private fun allBuildsMenu() = DefaultActionGroup({ JenkSpotterBundle.message("builds.show.all") }, true).apply {
        fun choice(text: String, running: Boolean, history: Int) =
            object : com.intellij.openapi.project.DumbAwareToggleAction(text) {
                override fun isSelected(e: AnActionEvent): Boolean {
                    val view = JenkSpotterSettings.getInstance().current
                    return view.showAllRunning == running && (!running || view.allBuildsHistory == history)
                }
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    JenkSpotterSettings.getInstance().updateView { showAllRunning = running; allBuildsHistory = history }
                    BuildMonitorService.getInstance().refresh()
                }
                override fun update(e: AnActionEvent) {
                    super.update(e)
                    e.presentation.description = JenkSpotterBundle.message("builds.show.allRunning.description")
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            }
        add(choice(JenkSpotterBundle.message("builds.show.all.off"), running = false, history = 0))
        add(choice(JenkSpotterBundle.message("builds.show.all.running"), running = true, history = 0))
        for (n in HISTORY_SIZES) add(choice(JenkSpotterBundle.message("builds.show.all.history", n), running = true, history = n))
    }

    private fun columnsMenu() = ViewOptions.menu("builds.show", listOf(
        allBuildsMenu(),
        ViewOptions.toggle("builds.show.folders", { showBuildJobFolders }) { showBuildJobFolders = it },
        com.intellij.openapi.actionSystem.Separator.create(JenkSpotterBundle.message("builds.columns")),
    ) + BuildColumn.entries.map { column ->
        ViewOptions.toggle(column.key, { column in BuildColumn.parse(buildColumns) }) { visible ->
            val current = BuildColumn.parse(buildColumns)
            buildColumns = BuildColumn.format(if (visible) current + column else current - column)
        }
    } + listOf(object : DumbAwareAction({ JenkSpotterBundle.message("builds.columns.reset") }) {
        override fun actionPerformed(e: AnActionEvent) =
            JenkSpotterSettings.getInstance().updateView { buildColumns = BuildColumn.format(BuildColumn.DEFAULT); buildColumnWidths = "" }
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
    }))

    private val jobRenderer: ColoredTableCellRenderer get() = jobRendererInstance

    /** Status text in the colour of the result. */
    private val statusRenderer = object : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val item = model.items.getOrNull(table.convertRowIndexToModel(row)) ?: return
            val result = (item as? BuildRow.Build)?.tracked?.build?.result
            val color = when (result) {
                BuildResult.FAILURE -> com.intellij.ui.JBColor.RED
                BuildResult.UNSTABLE -> com.intellij.ui.JBColor.ORANGE
                BuildResult.SUCCESS -> com.intellij.ui.JBColor(0x3E9B4F, 0x5AA469)
                else -> null
            }
            append(value?.toString().orEmpty(), if (color == null || selected) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color))
        }
    }

    /** Duration text; running builds get a progress bar based on Jenkins' estimate. */
    private val progressRenderer = object : javax.swing.table.DefaultTableCellRenderer() {
        private var fraction = -1.0

        override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): java.awt.Component {
            super.getTableCellRendererComponent(table, value, selected, false, row, column)
            val build = (model.items.getOrNull(table.convertRowIndexToModel(row)) as? BuildRow.Build)?.tracked?.build
            fraction = if (build != null && build.building && build.estimatedDurationMillis > 0) {
                ((System.currentTimeMillis() - build.timestamp).toDouble() / build.estimatedDurationMillis).coerceIn(0.0, 1.0)
            } else {
                -1.0
            }
            border = JBUI.Borders.empty(0, 4)
            return this
        }

        override fun paintComponent(g: java.awt.Graphics) {
            // After the cell itself: on striped and selected rows it fills its background.
            super.paintComponent(g)
            if (fraction >= 0) {
                val h = JBUI.scale(3)
                g.color = com.intellij.ui.JBColor.border()
                g.fillRect(JBUI.scale(4), height - h - JBUI.scale(1), width - JBUI.scale(8), h)
                g.color = com.intellij.ui.JBColor(0x3574F0, 0x548AF7)
                g.fillRect(JBUI.scale(4), height - h - JBUI.scale(1), ((width - JBUI.scale(8)) * fraction).toInt(), h)
            }
        }
    }

    private val jobRendererInstance = object : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val item = value as? BuildRow ?: return
            // Last known state of a Jenkins that cannot be polled right now: greyed, see [staleNote].
            val stale = staleSince(item.serverId)
            val plain = if (stale != null) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
            when (item) {
                is BuildRow.Expected -> {
                    icon = followUp(item.depth, JenkSpotterIcons.Expected)
                    ipad = JBUI.emptyInsets()
                    append(jobLabel(item.serverId, item.jobFullName), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
                is BuildRow.Queued -> {
                    icon = JenkSpotterIcons.Queued
                    ipad = JBUI.emptyInsets()
                    append(jobLabel(item.serverId, item.trigger.jobFullName), plain)
                }
                is BuildRow.Build -> {
                    val result = item.tracked.build.result
                    val base = when {
                        item.tracked.key in BuildMonitorService.getInstance().snapshot.waitingInput -> AllIcons.General.BalloonWarning
                        // A running build of an unreachable Jenkins may have finished long ago.
                        stale != null && item.tracked.build.building -> AllIcons.RunConfigurations.TestUnknown
                        else -> JenkSpotterIcons.forResult(result)
                    }
                    icon = followUp(item.depth, base)
                    ipad = JBUI.emptyInsets()
                    append(jobLabel(item.serverId, item.tracked.build.jobFullName), plain)
                    append("  #${item.tracked.build.number}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
            val view = JenkSpotterSettings.getInstance().current
            if (view.servers.size > 1 && filter.serverId == null && BuildColumn.SERVER !in shownColumns.orEmpty()) {
                val server = JenkSpotterSettings.getInstance().server(item.serverId)?.displayName.orEmpty()
                append("  $server", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
    }

    /** Follow-up builds: a small tree connector in front of the result icon, indented per level. */
    private fun followUp(depth: Int, icon: javax.swing.Icon): javax.swing.Icon =
        if (depth <= 0) icon else com.intellij.ui.RowIcon(TreeBranchIcon(depth), icon)

    /** Time of the last successful poll if the build's Jenkins is offline or failing, else null. */
    private fun staleSince(serverId: String): Long? {
        val status = BuildMonitorService.getInstance().snapshot.servers[serverId] ?: return null
        return if (status.online) null else status.lastUpdate
    }

    private fun buildTooltip(tracked: TrackedBuild): String {
        val e = StringUtil::escapeXmlEntities
        val b = tracked.build
        val out = StringBuilder("<html><b>").append(e(b.displayName)).append("</b> - ")
            .append(JenkSpotterBundle.message("result.${b.result.name}"))
        staleSince(tracked.serverId)?.let { since ->
            val server = JenkSpotterSettings.getInstance().server(tracked.serverId)?.displayName.orEmpty()
            val time = if (since > 0) DateFormatUtil.formatPrettyDateTime(since) else "-"
            out.append("<br><font color='#").append(com.intellij.ui.ColorUtil.toHex(com.intellij.ui.JBColor.RED)).append("'>")
                .append(e(JenkSpotterBundle.message("builds.stale", server, time))).append("</font>")
        }
        if (!JenkSpotterSettings.getInstance().current.showBuildJobFolders) out.append("<br>").append(e(JenkinsPaths.displayName(b.jobFullName)))
        out.append("<br>").append(JenkSpotterBundle.message("builds.tooltip.started", DateFormatUtil.formatDateTime(b.timestamp)))
        failureHint(tracked)?.let(out::append)
        b.causeDescriptions.distinct().forEach { out.append("<br>").append(e(it)) }
        b.tests?.let { t ->
            out.append("<br>").append(if (t.failed > 0) JenkSpotterBundle.message("tests.failed", t.failed, t.total) else JenkSpotterBundle.message("tests.passed", t.total))
        }
        b.description?.let { out.append("<br><i>").append(e(StringUtil.stripHtml(it, " "))).append("</i>") }
        if (b.parameters.isNotEmpty()) {
            out.append("<br>").append(JenkSpotterBundle.message("builds.tooltip.parameters"))
            b.parameters.take(15).forEach { out.append("<br>&nbsp;&nbsp;").append(e(it.name)).append(" = ").append(e(it.value.orEmpty())) }
        }
        return out.append("</html>").toString()
    }

    /**
     * The reason of a failed build arrived while its tooltip may be showing "looking...": a mouse
     * move at the same place makes the tooltip ask for its text again.
     */
    private fun refreshTooltip(at: java.awt.Point) {
        val mouse = table.mousePosition ?: return
        if (table.rowAtPoint(mouse) != table.rowAtPoint(at)) return
        java.awt.Toolkit.getDefaultToolkit().systemEventQueue.postEvent(
            MouseEvent(table, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, mouse.x, mouse.y, 0, false),
        )
    }

    /** Failed stage and error lines of a failed build, once loaded; see [de.pdenis.jenkspotter.core.FailureHints]. */
    private fun failureHint(build: TrackedBuild): String? =
        FailureHintHtml.render(de.pdenis.jenkspotter.core.FailureHints.getInstance().peek(build))

    companion object {
        private val PAGE_SIZES = listOf(25, 50, 100, 200)
        private val HISTORY_SIZES = listOf(25, 50, 100, 200)
        private const val SORT_JOB = "JOB"
        private val CHIP_STATES = listOf(ChipState.INCLUDE, ChipState.EXCLUDE, ChipState.OFF)
        private const val HOVER_DELAY_MILLIS = 400

        /** Which status chip a build belongs to. */
        fun statusOf(build: de.pdenis.jenkspotter.api.BuildInfo): BuildChip = when {
            build.building -> BuildChip.RUNNING
            build.result == BuildResult.SUCCESS -> BuildChip.SUCCESS
            build.result == BuildResult.FAILURE -> BuildChip.FAILURE
            build.result == BuildResult.UNSTABLE -> BuildChip.UNSTABLE
            else -> BuildChip.ABORTED
        }

        private fun jobName(row: BuildRow) = when (row) {
            is BuildRow.Queued -> row.trigger.jobFullName
            is BuildRow.Expected -> row.jobFullName
            is BuildRow.Build -> row.tracked.build.jobFullName
        }

        /** Job name as shown in the table: with or without its folder path (see the "Show" menu). */
        fun jobLabel(serverId: String, fullName: String): String =
            if (JenkSpotterSettings.getInstance().current.showBuildJobFolders) JenkinsPaths.displayName(fullName) else shortName(serverId, fullName)

        /**
         * Job name without folders. Branch jobs keep their multibranch pipeline in front, since a
         * branch name alone ("main") does not say which repository it belongs to.
         */
        fun shortName(serverId: String, fullName: String): String {
            val parts = fullName.split('/')
            if (parts.size < 2) return JenkinsPaths.displayName(fullName)
            val parent = de.pdenis.jenkspotter.core.JobIndexService.getInstance().jobByFullName(serverId, parts.dropLast(1).joinToString("/"))
            val keep = if (parent?.jobClass?.contains("MultiBranch") == true) 2 else 1
            return JenkinsPaths.displayName(parts.takeLast(keep).joinToString("/"))
        }

        /** Why a build is listed: mine, pinned, watched, view, follow-up, or else its first cause. */
        fun sourceText(tracked: TrackedBuild): String = buildList {
            if (tracked.mine) add(JenkSpotterBundle.message("source.MINE"))
            if (BuildSource.PINNED in tracked.sources) add(JenkSpotterBundle.message("source.PINNED"))
            if (BuildSource.WATCHED in tracked.sources) add(JenkSpotterBundle.message("source.WATCHED"))
            if (BuildSource.VIEW in tracked.sources) add(JenkSpotterBundle.message("source.VIEW"))
            if (BuildSource.DOWNSTREAM in tracked.sources) add(JenkSpotterBundle.message("source.DOWNSTREAM"))
            if (isEmpty()) addAll(tracked.build.causeDescriptions.take(1))
        }.joinToString(", ")

        fun durationText(tracked: TrackedBuild): String {
            val build = tracked.build
            if (!build.building) return formatDuration(build.durationMillis)
            val elapsed = (System.currentTimeMillis() - build.timestamp).coerceAtLeast(0)
            val estimate = build.estimatedDurationMillis.takeIf { it > 0 }?.let { " / ~" + formatDuration(it) }.orEmpty()
            return formatDuration(elapsed) + estimate
        }

        /** "1 h 5 m", "12 m 30 s", "45 s": whole seconds, no milliseconds. */
        fun formatDuration(millis: Long): String {
            val seconds = (millis + 500) / 1000
            val h = seconds / 3600
            val m = seconds % 3600 / 60
            val s = seconds % 60
            return when {
                h > 0 -> if (m > 0) "$h h $m m" else "$h h"
                m > 0 -> if (s > 0) "$m m $s s" else "$m m"
                else -> "$s s"
            }
        }
    }
}

/** Tree connector ("└─") for follow-up builds; one indent step per level, the line in the last one. */
private class TreeBranchIcon(private val depth: Int) : javax.swing.Icon {
    override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
        val g2 = g.create() as java.awt.Graphics2D
        try {
            g2.color = com.intellij.util.ui.UIUtil.getContextHelpForeground()
            val step = JBUI.scale(STEP)
            val cx = x + (depth - 1) * step + step / 2
            val cy = y + iconHeight / 2
            g2.drawLine(cx, y, cx, cy)
            g2.drawLine(cx, cy, x + depth * step - JBUI.scale(1), cy)
        } finally {
            g2.dispose()
        }
    }

    override fun getIconWidth() = JBUI.scale(STEP) * depth
    override fun getIconHeight() = JBUI.scale(16)

    companion object {
        private const val STEP = 12
    }
}
