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
import com.intellij.openapi.ui.ComboBox
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
import de.pdenis.jenkspotter.settings.SettingsListener
import de.pdenis.jenkspotter.settings.BuildStatusFilter
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

private enum class Scope(val key: String) {
    ALL("filter.scope.all"), MINE("filter.scope.mine"), BRANCH("filter.scope.branch"), WAITING("filter.scope.waiting"),
    PINNED("filter.scope.pinned"), WATCHED("filter.scope.watched"), VIEWS("filter.scope.views"), RUNNING("filter.scope.running");
    override fun toString() = JenkSpotterBundle.message(key)
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
    private val table = TableView(model)
    private val scopeCombo = ComboBox(Scope.entries.toTypedArray())
    private val search = com.intellij.ui.SearchTextField(false).apply {
        textEditor.emptyText.text = JenkSpotterBundle.message("builds.searchHint")
        textEditor.columns = 12
    }

    /** Finished builds that ended before this time are hidden by "Clear Finished" (pins/views excepted). */
    private var clearedBefore = 0L
    private val details = BuildDetailsPanel(project)
    private val tableScroll = JBScrollPane(table)
    private val detailsSplitter = com.intellij.ui.OnePixelSplitter(true, "JenkSpotter.BuildDetails", 0.62f)
    private val content = JPanel(BorderLayout())
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
        })
        table.emptyText.text = JenkSpotterBundle.message("builds.empty")
        table.emptyText.appendSecondaryText(JenkSpotterBundle.message("action.configure"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, JenkSpotterConfigurable::class.java)
        }

        // One compact row: scope and search on the left, actions on the right.
        val filters = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply { add(scopeCombo); add(search) }
        val north = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(1, 2)
            add(filters, BorderLayout.CENTER)
            add(createToolbar(), BorderLayout.EAST)
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
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) details.show(selectedBuild()) }

        filter.onChange { render(BuildMonitorService.getInstance().snapshot) }
        scopeCombo.addActionListener { render(BuildMonitorService.getInstance().snapshot) }
        search.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = render(BuildMonitorService.getInstance().snapshot)
        })
        // Enter opens the log of the selected build, like a double-click.
        table.inputMap.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "jenkspotter.openLog")
        table.actionMap.put("jenkspotter.openLog", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                selectedBuild()?.let { BuildActions.openLog(project, it) }
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
                ApplicationManager.getApplication().invokeLater({
                    render(snapshot)
                    details.refresh(selectedBuild())
                }, { disposed || project.isDisposed })
            }
        })
        ticker.start()
        render(BuildMonitorService.getInstance().snapshot)
    }

    private fun render(snapshot: MonitorSnapshot) {
        applyColumns()
        val selectedKey = table.selectedObject?.key
        val hidden = filter.hidden()
        val scope = scopeCombo.selectedItem as? Scope ?: Scope.ALL
        val hiddenStatuses = statusFilterHidden()

        val builds = snapshot.builds.asSequence()
            .filter { filter.includes(it.serverId) && !hidden.isHidden(it.build.jobFullName) }
            .filter {
                when (scope) {
                    Scope.ALL -> true
                    Scope.MINE -> it.mine
                    Scope.BRANCH -> isCurrentBranch(it)
                    Scope.WAITING -> it.key in snapshot.waitingInput
                    Scope.PINNED -> BuildSource.PINNED in it.sources
                    Scope.WATCHED -> BuildSource.WATCHED in it.sources
                    Scope.VIEWS -> BuildSource.VIEW in it.sources
                    Scope.RUNNING -> it.build.building
                }
            }
            .filter { statusOf(it.build) !in hiddenStatuses }
            .filter { it.build.building || it.sources.any { s -> s == BuildSource.PINNED || s == BuildSource.WATCHED || s == BuildSource.VIEW } ||
                it.build.timestamp + it.build.durationMillis >= clearedBefore }
            .filter { matchesSearch(it.build.jobFullName, it.build.number.toString(), it.build.parameters.joinToString(" ") { p -> "${p.name}=${p.value}" }, it.build.description.orEmpty()) }
            .toList()
        val queued = snapshot.queued.asSequence()
            .filter { filter.includes(it.serverId) && !hidden.isHidden(it.jobFullName) && BuildStatusFilter.QUEUED !in hiddenStatuses && scope in setOf(Scope.ALL, Scope.MINE) }
            .filter { matchesSearch(it.jobFullName) }
            .map { BuildRow.Queued(it) }
        model.items = queued.toList() + withChildren(builds)
        model.items.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 }?.let {
            table.selectionModel.setSelectionInterval(it, it)
        }
    }

    /** Open build info: splitter below the table; closed: just its title row under the table. */
    private fun layoutDetails() {
        content.removeAll()
        if (details.collapsed) {
            detailsSplitter.firstComponent = null
            detailsSplitter.secondComponent = null
            content.add(tableScroll, BorderLayout.CENTER)
            content.add(details, BorderLayout.SOUTH)
        } else {
            detailsSplitter.firstComponent = tableScroll
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
            if (build.build.building && BuildStatusFilter.QUEUED !in statusFilterHidden()) {
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
        .createActionToolbar("JenkSpotter.Builds", actionGroup(), true)
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
        addSeparator()
        add(statusFilterMenu())
        add(action("action.clearFinished", AllIcons.Actions.GC, enabled = { model.items.any { it is BuildRow.Build && !it.tracked.build.building } }) {
            clearedBefore = System.currentTimeMillis()
            render(BuildMonitorService.getInstance().snapshot)
        })
        // Refresh and settings are in the tool window title.
        add(columnsMenu())
    }

    private fun statusFilterHidden(): Set<BuildStatusFilter> =
        JenkSpotterSettings.getInstance().current.hiddenStatuses.mapNotNull { n -> BuildStatusFilter.entries.firstOrNull { it.name == n } }.toSet()

    private fun setHiddenStatuses(hidden: Set<BuildStatusFilter>) =
        JenkSpotterSettings.getInstance().updateView { hiddenStatuses = hidden.map { it.name }.toMutableList() }

    /** Filter icon with one check box per build status; the icon gets a dot while something is hidden. */
    private fun statusFilterMenu() = object : DefaultActionGroup({ JenkSpotterBundle.message("builds.statusFilter") }, true) {
        init {
            templatePresentation.icon = AllIcons.General.Filter
            add(object : DumbAwareAction({ JenkSpotterBundle.message("builds.statusFilter.all") }) {
                override fun actionPerformed(e: AnActionEvent) = setHiddenStatuses(emptySet())
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = statusFilterHidden().isNotEmpty()
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            add(object : DumbAwareAction({ JenkSpotterBundle.message("builds.statusFilter.onlyFailed") }) {
                override fun actionPerformed(e: AnActionEvent) =
                    setHiddenStatuses(BuildStatusFilter.entries.toSet() - BuildStatusFilter.FAILURE - BuildStatusFilter.UNSTABLE)
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            addSeparator()
            for (status in BuildStatusFilter.entries) {
                add(object : com.intellij.openapi.project.DumbAwareToggleAction(JenkSpotterBundle.message(status.key), null, statusIcon(status)) {
                    override fun isSelected(e: AnActionEvent) = status !in statusFilterHidden()
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        setHiddenStatuses(if (state) statusFilterHidden() - status else statusFilterHidden() + status)
                    }
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                })
            }
        }

        override fun update(e: AnActionEvent) {
            val hidden = statusFilterHidden()
            e.presentation.icon = com.intellij.ui.BadgeIconSupplier(AllIcons.General.Filter).getLiveIndicatorIcon(hidden.isNotEmpty())
            e.presentation.text = JenkSpotterBundle.message("builds.statusFilter") +
                if (hidden.isEmpty()) "" else " (" + BuildStatusFilter.entries.filter { it !in hidden }.joinToString { JenkSpotterBundle.message(it.key) } + ")"
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private fun statusIcon(status: BuildStatusFilter): javax.swing.Icon = when (status) {
        BuildStatusFilter.RUNNING -> AllIcons.Actions.Execute
        BuildStatusFilter.SUCCESS -> JenkSpotterIcons.forResult(BuildResult.SUCCESS)
        BuildStatusFilter.FAILURE -> JenkSpotterIcons.forResult(BuildResult.FAILURE)
        BuildStatusFilter.UNSTABLE -> JenkSpotterIcons.forResult(BuildResult.UNSTABLE)
        BuildStatusFilter.ABORTED -> JenkSpotterIcons.forResult(BuildResult.ABORTED)
        BuildStatusFilter.QUEUED -> JenkSpotterIcons.Queued
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

    private fun columnsMenu() = ViewOptions.menu("jobs.show", listOf(
        object : com.intellij.openapi.project.DumbAwareToggleAction(JenkSpotterBundle.message("builds.show.allRunning")) {
            override fun isSelected(e: AnActionEvent) = JenkSpotterSettings.getInstance().current.showAllRunning
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                JenkSpotterSettings.getInstance().updateView { showAllRunning = state }
                BuildMonitorService.getInstance().refresh()
            }
            override fun update(e: AnActionEvent) {
                super.update(e)
                e.presentation.description = JenkSpotterBundle.message("builds.show.allRunning.description")
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        },
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
            if (fraction >= 0) {
                val h = JBUI.scale(3)
                g.color = com.intellij.ui.JBColor.border()
                g.fillRect(JBUI.scale(4), height - h - JBUI.scale(1), width - JBUI.scale(8), h)
                g.color = com.intellij.ui.JBColor(0x3574F0, 0x548AF7)
                g.fillRect(JBUI.scale(4), height - h - JBUI.scale(1), ((width - JBUI.scale(8)) * fraction).toInt(), h)
            }
            super.paintComponent(g)
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
                    toolTipText = buildTooltip(item.tracked)
                }
            }
            val view = JenkSpotterSettings.getInstance().current
            if (view.servers.size > 1 && filter.serverId == null && BuildColumn.SERVER !in BuildColumn.parse(view.buildColumns)) {
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

    companion object {
        /** Which entry of the status filter a build belongs to. */
        fun statusOf(build: de.pdenis.jenkspotter.api.BuildInfo): BuildStatusFilter = when {
            build.building -> BuildStatusFilter.RUNNING
            build.result == BuildResult.SUCCESS -> BuildStatusFilter.SUCCESS
            build.result == BuildResult.FAILURE -> BuildStatusFilter.FAILURE
            build.result == BuildResult.UNSTABLE -> BuildStatusFilter.UNSTABLE
            else -> BuildStatusFilter.ABORTED
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
