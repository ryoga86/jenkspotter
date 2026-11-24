package de.pdenis.jenkspotter.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.JenkSpotterIcons
import de.pdenis.jenkspotter.api.Artifact
import de.pdenis.jenkspotter.api.BuildDetails
import de.pdenis.jenkspotter.api.Change
import de.pdenis.jenkspotter.api.FailedTest
import de.pdenis.jenkspotter.api.PendingInput
import de.pdenis.jenkspotter.api.Stage
import de.pdenis.jenkspotter.core.BuildMonitorService
import de.pdenis.jenkspotter.core.JenkinsConnections
import de.pdenis.jenkspotter.core.TrackedBuild
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel

/**
 * Details of the selected build below the builds table: input step banner, overview (what the
 * optional table columns show, plus all parameters), stages, failed tests, changes, artifacts and
 * the recent builds of the same job.
 * Loaded on selection, refreshed while the build runs.
 */
class BuildDetailsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val title = JBLabel().apply { border = JBUI.Borders.empty(3, 6) }
    private val toggle = JBLabel().apply { border = JBUI.Borders.empty(3, 6, 3, 0) }

    /** Called after the user opened or closed the build info (title row click). */
    var onCollapsedChanged: (Boolean) -> Unit = {}

    /** Only the title row is shown; nothing is loaded until it is opened again. */
    var collapsed: Boolean = de.pdenis.jenkspotter.settings.JenkSpotterSettings.getInstance().current.buildInfoCollapsed
        private set
    private val inputBanner = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
        background = JBUI.CurrentTheme.Banner.WARNING_BACKGROUND
        border = JBUI.Borders.customLine(JBUI.CurrentTheme.Banner.WARNING_BORDER_COLOR, 1, 0, 1, 0)
        isVisible = false
    }
    private val tabs = JBTabbedPane()

    /** Shown when the tabs could not be filled: says why (no token, Jenkins not reachable) instead of empty lists. */
    private val errorText = JBLabel(AllIcons.General.Error)
    private val errorBanner = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
        background = JBUI.CurrentTheme.Banner.ERROR_BACKGROUND
        border = JBUI.Borders.customLine(JBUI.CurrentTheme.Banner.ERROR_BORDER_COLOR, 1, 0, 1, 0)
        isVisible = false
        add(errorText)
        add(com.intellij.ui.components.ActionLink(JenkSpotterBundle.message("details.retry")) { current?.let(::load) })
    }
    private val overview = object : javax.swing.table.DefaultTableModel(arrayOf<Any>("", ""), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val overviewTable = com.intellij.ui.table.JBTable(overview).apply {
        tableHeader = null
        setShowGrid(false)
        columnModel.getColumn(0).apply { preferredWidth = JBUI.scale(120); maxWidth = JBUI.scale(260) }
        setDefaultRenderer(Any::class.java, OverviewRenderer())
    }
    private val tests = DefaultListModel<FailedTest>()
    private val changes = DefaultListModel<Change>()
    private val artifacts = DefaultListModel<Artifact>()
    private val recent = DefaultListModel<de.pdenis.jenkspotter.api.BuildInfo>()
    private val stagesPanel = StagesPanel(project, this)
    private val testList = JBList(tests)
    private val changeList = JBList(changes)
    private val artifactList = JBList(artifacts)
    private val recentList = JBList(recent)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    /** Why a failed or unstable build broke; a tab of its own, only for those builds. */
    private val reasonPane = javax.swing.JEditorPane().apply {
        editorKit = com.intellij.util.ui.HTMLEditorKitBuilder.simple()
        isEditable = false
        border = JBUI.Borders.empty(6, 10)
        addHyperlinkListener { e ->
            if (e.eventType != javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val build = current ?: return@addHyperlinkListener
            val hint = de.pdenis.jenkspotter.core.FailureHints.getInstance().peek(build) as? de.pdenis.jenkspotter.core.FailureHints.Hint.Found
            when {
                e.description == "tests:" -> tabs.selectedComponent = testsTab
                e.description == "log:" -> BuildActions.openLog(project, build)
                e.description.startsWith("line:") -> {
                    // The log is searched for the line; a shortened line only by its start.
                    val line = hint?.lines?.getOrNull(e.description.removePrefix("line:").toIntOrNull() ?: -1) ?: return@addHyperlinkListener
                    LogConsoleManager.getInstance(project).open(build, line.removeSuffix("…").take(80))
                }
            }
        }
    }
    private val reasonTab = JBScrollPane(reasonPane)

    /** The reason tab was open when a build without one got selected. */
    private var preferReason = false
    private var reasonBody = ""
    private val stagesTab: java.awt.Component get() = stagesPanel
    private val testsTab = JBScrollPane(testList)
    private val changesTab = JBScrollPane(changeList)
    private val artifactsTab = JBScrollPane(artifactList)
    private val recentTab = JBScrollPane(recentList)

    @Volatile
    private var current: TrackedBuild? = null
    private var generation = 0

    init {
        border = JBUI.Borders.customLineTop(JBUI.CurrentTheme.ToolWindow.borderColor())
        add(JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(toggle, BorderLayout.WEST)
                add(title, BorderLayout.CENTER)
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                toolTipText = JenkSpotterBundle.message("details.toggle")
                object : com.intellij.ui.ClickListener() {
                    override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                        setCollapsed(!collapsed)
                        onCollapsedChanged(collapsed)
                        return true
                    }
                }.installOn(this)
            }, BorderLayout.NORTH)
            add(errorBanner, BorderLayout.CENTER)
            add(inputBanner, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        tabs.addTab(JenkSpotterBundle.message("details.overview"), JBScrollPane(overviewTable))
        tabs.addTab(JenkSpotterBundle.message("details.stages"), stagesPanel)
        tabs.addTab(JenkSpotterBundle.message("details.tests"), testsTab)
        tabs.addTab(JenkSpotterBundle.message("details.changes"), changesTab)
        tabs.addTab(JenkSpotterBundle.message("details.artifacts"), artifactsTab)
        tabs.addTab(JenkSpotterBundle.message("details.recent"), recentTab)
        add(tabs, BorderLayout.CENTER)

        testList.cellRenderer = TestRenderer()
        changeList.cellRenderer = ChangeRenderer()
        artifactList.cellRenderer = ArtifactRenderer()
        recentList.cellRenderer = RecentBuildsPopup.Renderer()
        testList.emptyText.text = JenkSpotterBundle.message("details.noTests")
        changeList.emptyText.text = JenkSpotterBundle.message("details.noChanges")
        artifactList.emptyText.text = JenkSpotterBundle.message("details.noArtifacts")
        recentList.emptyText.text = JenkSpotterBundle.message("details.noRecent")

        onDoubleClick(testList) { navigateToTest(it, run = false) }
        onDoubleClick(changeList) { current?.let { BuildActions.openInBrowser(it.build.url + "changes") } }
        onDoubleClick(artifactList) { download(it) }
        onDoubleClick(recentList) { b -> current?.let { BuildActions.openLog(project, TrackedBuild(it.serverId, b, emptySet(), mine = false)) } }
        PopupHandler.installPopupMenu(testList, com.intellij.openapi.actionSystem.DefaultActionGroup().apply {
            add(simple("details.test.navigate", AllIcons.Actions.EditSource) { testList.selectedValue?.let { navigateToTest(it, run = false) } })
            add(simple("details.test.run", AllIcons.Actions.Execute) { testList.selectedValue?.let { navigateToTest(it, run = true) } })
            add(simple("details.test.copy", AllIcons.Actions.Copy) {
                testList.selectedValue?.let { t ->
                    com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection("${t.className}.${t.name}\n${t.errorDetails.orEmpty()}"))
                }
            })
        }, "JenkSpotter.TestsPopup")
        PopupHandler.installPopupMenu(changeList, com.intellij.openapi.actionSystem.DefaultActionGroup().apply {
            add(simple("details.change.copy", AllIcons.Actions.Copy) {
                changeList.selectedValue?.let { com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(it.commitId)) }
            })
        }, "JenkSpotter.ChangesPopup")
        setCollapsed(collapsed)
        show(null)
    }

    fun setCollapsed(value: Boolean) {
        val opened = collapsed && !value
        collapsed = value
        toggle.icon = if (value) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown
        tabs.isVisible = !value
        updateInputBanner()
        if (opened) current?.let(::load)
        revalidate()
        repaint()
    }

    /**
     * Shows [build]. Called with every snapshot too: for the build already shown only what changed
     * is updated, and everything is loaded once more when it has just finished.
     */
    fun show(build: TrackedBuild?) {
        val previous = current
        val same = build?.key == previous?.key
        val finishedNow = same && previous?.build?.building == true && build?.build?.building == false
        current = build
        updateTitle()
        if (build == null) {
            tabs.isVisible = false
            inputBanner.isVisible = false
            errorBanner.isVisible = false
            return
        }
        if (!same) errorBanner.isVisible = false
        tabs.isVisible = !collapsed
        updateInputBanner()
        updateOverview(build)
        updateReason(build)
        if (!same) {
            tests.clear(); changes.clear(); artifacts.clear(); recent.clear()
            stagesPanel.clear(build)
            // A moment later: stepping through the table with the arrow keys must not load every build passed.
            if (!collapsed) load(build, delayMillis = SELECTION_DELAY_MILLIS)
        } else if (finishedNow && !collapsed) {
            load(build)
        }
    }

    private fun updateTitle() {
        val build = current
        if (build == null) {
            title.text = JenkSpotterBundle.message("details.title")
            title.icon = null
            return
        }
        val name = BuildsPanel.jobLabel(build.serverId, build.build.jobFullName) + " #" + build.build.number
        title.text = "<html><b>${StringUtil.escapeXmlEntities(name)}</b> - ${JenkSpotterBundle.message("result.${build.build.result.name}")}</html>"
        title.icon = JenkSpotterIcons.forResult(build.build.result)
    }

    /** Input the banner shows, as "build key + input id"; rebuilt only when it changes, so its buttons keep the focus. */
    private var shownInput: String? = null

    private fun updateInputBanner() {
        val build = current ?: return
        val input = BuildMonitorService.getInstance().snapshot.waitingInput[build.key]
        val key = input?.takeIf { build.build.building }?.let { build.key + " " + it.id + " " + it.message }
        if (key == shownInput && inputBanner.isVisible == (key != null)) return
        shownInput = key
        inputBanner.removeAll()
        // Waiting builds show their banner even with the build info closed.
        inputBanner.isVisible = input != null && build.build.building
        if (input != null) {
            inputBanner.add(JBLabel(JenkSpotterBundle.message("details.input", input.message), AllIcons.General.BalloonWarning, JBLabel.LEFT))
            inputBanner.add(JButton(input.proceedText).apply { addActionListener { BuildActions.proceedInput(project, build, input) } })
            inputBanner.add(JButton(JenkSpotterBundle.message("input.abort")).apply { addActionListener { BuildActions.abortInput(project, build, input) } })
        }
        inputBanner.revalidate()
        inputBanner.repaint()
    }

    /**
     * Loads what the tabs show. [stagesOnly]: the periodic refresh of a running build - its stages
     * move, its changes, artifacts and earlier builds do not.
     */
    private fun load(build: TrackedBuild, delayMillis: Int = 0, stagesOnly: Boolean = false) {
        val token = ++generation
        alarm.cancelAllRequests()
        val modality = ModalityState.stateForComponent(this)
        alarm.addRequest({
            if (token != generation) return@addRequest
            fun failed(message: String?) = ApplicationManager.getApplication().invokeLater({
                if (token != generation || current?.key != build.key) return@invokeLater
                errorText.text = message?.let { JenkSpotterBundle.message("details.loadFailed", it) }.orEmpty()
                errorBanner.isVisible = message != null && !collapsed
            }, modality)
            val client = JenkinsConnections.getInstance().client(build.serverId)
                ?: return@addRequest failed(JenkSpotterBundle.message("error.noToken"))
            // The build itself answers this one; if it fails, nothing else will have worked either.
            val problem = if (stagesOnly) null else runCatching { client.build(build.build.url) }.exceptionOrNull()
            if (!stagesOnly) failed(problem?.let { it.message ?: it.javaClass.simpleName })
            val stageResult = if (build.build.isPipeline) runCatching { client.stages(build.build.url) }.getOrNull() else null
            val testResult = if (!stagesOnly && (build.build.tests != null || !build.build.building)) runCatching { client.failedTests(build.build.url) }.getOrNull() else null
            val details = if (stagesOnly) null else runCatching { client.details(build.build.url) }.getOrElse { BuildDetails(emptyList(), emptyList()) }
            val recentResult = if (stagesOnly) null else runCatching {
                client.recentBuilds(build.build.jobUrl, de.pdenis.jenkspotter.settings.JenkSpotterSettings.getInstance().current.recentBuildsCount)
            }.getOrNull()
            ApplicationManager.getApplication().invokeLater({
                if (token != generation || current?.key != build.key) return@invokeLater
                stagesPanel.update(build, stageResult.orEmpty(), build.build.isPipeline)
                title(stagesTab, JenkSpotterBundle.message("details.stages") + count(stagesPanel.count))
                if (details != null) {
                    replace(tests, testResult.orEmpty())
                    replace(changes, details.changes)
                    replace(artifacts, details.artifacts)
                    val selectedRecent = recentList.selectedValue?.number ?: build.build.number
                    replace(recent, recentResult.orEmpty())
                    (0 until recent.size()).firstOrNull { recent[it].number == selectedRecent }?.let(recentList::setSelectedIndex)
                    title(testsTab, JenkSpotterBundle.message("details.tests") + count(testResult?.size))
                    title(changesTab, JenkSpotterBundle.message("details.changes") + count(details.changes.size))
                    title(artifactsTab, JenkSpotterBundle.message("details.artifacts") + count(details.artifacts.size))
                    title(recentTab, JenkSpotterBundle.message("details.recent") + count(recentResult?.size))
                }
                // Running builds: their stages are asked again every few seconds.
                if (current?.build?.building == true && !collapsed) {
                    alarm.addRequest({ current?.let { load(it, stagesOnly = true) } }, RUNNING_REFRESH_MILLIS)
                }
            }, modality)
        }, delayMillis)
    }

    private fun title(tab: java.awt.Component, text: String) {
        tabs.indexOfComponent(tab).takeIf { it >= 0 }?.let { tabs.setTitleAt(it, text) }
    }

    /**
     * "Reason" tab right after the overview for failed and unstable builds, in their colour; gone
     * for others. Its content loads in the background the first time ([FailureHints]).
     */
    private fun updateReason(build: TrackedBuild) {
        val result = build.build.result
        val broken = !build.build.building && (result == de.pdenis.jenkspotter.api.BuildResult.FAILURE || result == de.pdenis.jenkspotter.api.BuildResult.UNSTABLE)
        val index = tabs.indexOfComponent(reasonTab)
        if (!broken) {
            if (index >= 0) {
                // Without this the tab after it would get selected; the next broken build shows its reason again.
                val wasSelected = tabs.selectedIndex == index
                tabs.removeTabAt(index)
                if (wasSelected) {
                    tabs.selectedIndex = 0
                    preferReason = true
                }
            }
            return
        }
        val unstable = result == de.pdenis.jenkspotter.api.BuildResult.UNSTABLE
        if (index < 0) {
            val onOverview = tabs.selectedIndex == 0
            tabs.insertTab(JenkSpotterBundle.message("details.reason"), null, reasonTab, null, 1)
            // For a broken build the reason is what one looks for first.
            if (preferReason || onOverview) tabs.selectedComponent = reasonTab
            preferReason = false
        }
        val at = tabs.indexOfComponent(reasonTab)
        tabs.setIconAt(at, JenkSpotterIcons.forResult(result))
        tabs.setForegroundAt(at, if (unstable) FailureHintHtml.UNSTABLE_COLOR else FailureHintHtml.FAILED_COLOR)
        val hints = de.pdenis.jenkspotter.core.FailureHints.getInstance()
        val hint = hints.hint(build) {
            ApplicationManager.getApplication().invokeLater({ current?.takeIf { it.key == build.key }?.let(::updateReason) }, ModalityState.any())
        }
        val e = StringUtil::escapeXmlEntities
        val heading = JenkSpotterBundle.message(if (unstable) "details.reason.unstable" else "details.reason.failed")
        val body = "<html><body><b><font color='#" + com.intellij.ui.ColorUtil.toHex(if (unstable) FailureHintHtml.UNSTABLE_COLOR else FailureHintHtml.FAILED_COLOR) + "'>" +
            e(heading) + "</font></b>" + FailureHintHtml.render(hint, lineLink = true).orEmpty() +
            "<br><br><a href='log:'>" + e(JenkSpotterBundle.message("details.reason.openLog")) + "</a></body></html>"
        // Rebuilt with every poll; only set when it changed, so the view does not jump.
        if (reasonBody != body) {
            reasonBody = body
            reasonPane.text = body
            reasonPane.caretPosition = 0
        }
    }

    /** Key/value rows; parameters follow below their own heading row. */
    private fun updateOverview(tracked: TrackedBuild) {
        val b = tracked.build
        val rows = mutableListOf<Pair<String, String>>()
        fun row(key: String, value: String?) {
            if (!value.isNullOrBlank()) rows += JenkSpotterBundle.message(key) to value
        }
        row("details.field.job", de.pdenis.jenkspotter.api.JenkinsPaths.displayName(b.jobFullName))
        row("column.status", if (tracked.key in BuildMonitorService.getInstance().snapshot.waitingInput) JenkSpotterBundle.message("result.WAITING")
            else JenkSpotterBundle.message("result.${b.result.name}"))
        row("column.started", com.intellij.util.text.DateFormatUtil.formatDateTime(b.timestamp))
        row("column.duration", BuildsPanel.durationText(tracked))
        row("column.source", BuildsPanel.sourceText(tracked))
        row("details.field.causes", b.causeDescriptions.distinct().joinToString("; "))
        row("column.server", de.pdenis.jenkspotter.settings.JenkSpotterSettings.getInstance().server(tracked.serverId)?.displayName)
        row("column.tests", b.tests?.let { t ->
            if (t.failed > 0) JenkSpotterBundle.message("tests.failed", t.failed, t.total) else JenkSpotterBundle.message("tests.passed", t.total)
        })
        row("column.description", b.description?.let { StringUtil.stripHtml(it, " ") })
        if (b.parameters.isNotEmpty()) {
            rows += JenkSpotterBundle.message("column.parameters") to ""
            b.parameters.forEach { rows += (PARAMETER_INDENT + it.name) to it.value.orEmpty() }
        }
        val current = (0 until overview.rowCount).map { overview.getValueAt(it, 0) to overview.getValueAt(it, 1) }
        if (current == rows) return
        val selectedRow = overviewTable.selectedRow
        overview.rowCount = 0
        rows.forEach { (k, v) -> overview.addRow(arrayOf<Any>(k, v)) }
        if (selectedRow in 0 until overview.rowCount) overviewTable.setRowSelectionInterval(selectedRow, selectedRow)
    }

    private fun count(n: Int?) = if (n == null || n == 0) "" else " ($n)"

    private fun <T> replace(model: DefaultListModel<T>, items: List<T>) {
        model.clear()
        items.forEach(model::addElement)
    }

    /**
     * Opens the test class (any language whose file is named like the class or module) at the test
     * method; with [run] the IDE's "Run" context action is invoked there, so the test runs locally
     * with the project's own test setup.
     */
    private fun navigateToTest(test: FailedTest, run: Boolean) {
        val scope = GlobalSearchScope.projectScope(project)
        val simple = test.simpleClassName.substringBefore('$')
        val candidates = listOf("java", "kt", "groovy", "scala", "cs", "ts", "js", "php", "rb").map { "$simple.$it" } +
            listOf(test.className.substringBeforeLast('.').substringAfterLast('.') + ".py", test.className.substringAfterLast('.') + ".py")
        val method = test.name.substringBefore('(').substringBefore('[').trim()
        // Looking the file up and reading it happens off the UI thread, and waits while the IDE is indexing.
        com.intellij.openapi.application.ReadAction.nonBlocking<Pair<VirtualFile, Int>?> {
            val file = candidates.asSequence().flatMap { FilenameIndex.getVirtualFilesByName(it, scope).asSequence() }.firstOrNull()
                ?: return@nonBlocking null
            val text = String(file.contentsToByteArray(), file.charset)
            file to (Regex("\\b${Regex.escape(method)}\\b").find(text)?.range?.first ?: 0)
        }.inSmartMode(project).expireWith(this).finishOnUiThread(ModalityState.defaultModalityState()) { found ->
            if (found == null) {
                BuildActions.notify(project, JenkSpotterBundle.message("details.test.notFound", test.className), NotificationType.WARNING)
                return@finishOnUiThread
            }
            val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                .openTextEditor(OpenFileDescriptor(project, found.first, found.second), true) ?: return@finishOnUiThread
            if (run) {
                val action = ActionManager.getInstance().getAction("RunClass") ?: return@finishOnUiThread
                ActionManager.getInstance().tryToExecute(action, null, editor.contentComponent, ActionPlaces.EDITOR_POPUP, true)
            }
        }.submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())
    }

    private fun download(artifact: Artifact) {
        val build = current ?: return
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(
            FileSaverDescriptor(JenkSpotterBundle.message("details.download.title"), artifact.relativePath), project,
        )
        val target = dialog.save(null as VirtualFile?, artifact.fileName)?.file?.toPath() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val client = JenkinsConnections.getInstance().client(build.serverId) ?: error(JenkSpotterBundle.message("error.noToken"))
                client.download(build.build.url + "artifact/" + artifact.relativePath, target)
            }
            ApplicationManager.getApplication().invokeLater {
                val group = NotificationGroupManager.getInstance().getNotificationGroup("JenkSpotter.Info")
                result.fold(
                    {
                        group.createNotification(JenkSpotterBundle.message("details.download.done", artifact.fileName), NotificationType.INFORMATION)
                            .addAction(NotificationAction.createSimpleExpiring(RevealFileAction.getActionName()) { RevealFileAction.openFile(target) })
                            .notify(project)
                    },
                    { group.createNotification(it.message ?: it.javaClass.simpleName, NotificationType.ERROR).notify(project) },
                )
            }
        }
    }

    private fun <T> onDoubleClick(list: JBList<T>, action: (T) -> Unit) {
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                list.selectedValue?.let(action) ?: return false
                return true
            }
        }.installOn(list)
    }

    private fun simple(key: String, icon: javax.swing.Icon, block: () -> Unit) =
        object : com.intellij.openapi.project.DumbAwareAction({ JenkSpotterBundle.message(key) }, icon) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) = block()
            override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
        }

    override fun dispose() = Unit

    /** Keys grey, parameter heading bold, full value in the tooltip. */
    private class OverviewRenderer : com.intellij.ui.ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: javax.swing.JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val text = value?.toString().orEmpty()
            val heading = column == 0 && !text.startsWith(PARAMETER_INDENT) && table.getValueAt(row, 1).toString().isEmpty()
            append(text, when {
                heading -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                column == 0 && !text.startsWith(PARAMETER_INDENT) -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
            })
            toolTipText = if (column == 1 && text.length > 40) "<html>" + StringUtil.escapeXmlEntities(text) + "</html>" else null
        }
    }

    private class TestRenderer : ColoredListCellRenderer<FailedTest>() {
        override fun customizeCellRenderer(list: JList<out FailedTest>, value: FailedTest, index: Int, selected: Boolean, hasFocus: Boolean) {
            icon = AllIcons.RunConfigurations.TestError
            append(value.simpleClassName + "." + value.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            value.errorDetails?.lineSequence()?.firstOrNull()?.let { append("   " + it.take(160), SimpleTextAttributes.ERROR_ATTRIBUTES) }
            if (value.status == "REGRESSION") append("   " + JenkSpotterBundle.message("details.test.regression"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            toolTipText = "<html><b>${StringUtil.escapeXmlEntities(value.className)}.${StringUtil.escapeXmlEntities(value.name)}</b><br><pre>" +
                StringUtil.escapeXmlEntities(value.errorDetails.orEmpty().take(1500)) + "</pre></html>"
        }
    }

    private class ChangeRenderer : ColoredListCellRenderer<Change>() {
        override fun customizeCellRenderer(list: JList<out Change>, value: Change, index: Int, selected: Boolean, hasFocus: Boolean) {
            icon = AllIcons.Vcs.CommitNode
            append(value.commitId.take(8) + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append(value.message.lineSequence().firstOrNull().orEmpty())
            value.author?.let { append("   $it", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
        }
    }

    private class ArtifactRenderer : ColoredListCellRenderer<Artifact>() {
        override fun customizeCellRenderer(list: JList<out Artifact>, value: Artifact, index: Int, selected: Boolean, hasFocus: Boolean) {
            icon = AllIcons.Actions.Download
            append(value.fileName)
            if (value.relativePath != value.fileName) append("   " + value.relativePath, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    companion object {
        private const val SELECTION_DELAY_MILLIS = 150
        private const val RUNNING_REFRESH_MILLIS = 5000
        private const val PARAMETER_INDENT = "    "
    }
}
