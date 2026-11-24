package de.pdenis.jenkspotter.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.LoadingDecorator
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.ui.tree.TreeUtil
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.JenkSpotterIcons
import de.pdenis.jenkspotter.api.BuildResult
import de.pdenis.jenkspotter.api.Stage
import de.pdenis.jenkspotter.api.StageStep
import de.pdenis.jenkspotter.core.JenkinsConnections
import de.pdenis.jenkspotter.core.TrackedBuild
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * Stages tab of the build info: the stages as a tree (nested stages and parallel branches, any
 * depth) and on the right the log of the selected stage in a console (search, soft wrap, scroll
 * to end, ANSI colours, links). Refreshes update the tree in place, so selection, expansion and
 * the mouse stay where they are; a running stage's log only gets its new lines appended.
 */
class StagesPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = StageRenderer()
        emptyText.text = JenkSpotterBundle.message("details.noStages")
    }
    private val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { setViewer(true) }.console
    private val loading: LoadingDecorator
    private val errors = LogErrorNavigator(console)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, parent)

    private var build: TrackedBuild? = null
    private var generation = 0

    /** What the console shows: "build key + stage id" and the text printed so far (headers included). */
    private var shownStage: String? = null
    private var shownText = ""

    init {
        Disposer.register(parent, console)
        val logPanel = SimpleToolWindowPanel(false, true)
        loading = LoadingDecorator(console.component, parent, 0)
        logPanel.setContent(loading.component)
        val actions = DefaultActionGroup().apply {
            addAll(errors.actions())
            addSeparator()
            addAll(*console.createConsoleActions())
            addSeparator()
            add(action("details.stage.fullLog", AllIcons.Debugger.Console, ::openFullLog))
        }
        logPanel.toolbar = ActionManager.getInstance().createActionToolbar("JenkSpotter.StageLog", actions, false)
            .also { it.targetComponent = console.component }.component

        add(OnePixelSplitter(false, "JenkSpotter.StageLog", 0.3f).apply {
            firstComponent = JBScrollPane(tree)
            secondComponent = logPanel
        }, BorderLayout.CENTER)

        // Selecting a stage (click, double-click, arrow keys) shows its log on the right.
        tree.addTreeSelectionListener { loadLog(refresh = false) }
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(action("details.stage.fullLog", AllIcons.Debugger.Console, ::openFullLog)), "JenkSpotter.StagesPopup")
        hint(JenkSpotterBundle.message("details.stageLog.hint"))
    }

    /** Number of stages, nested ones included. */
    val count: Int get() = java.util.Collections.list(root.depthFirstEnumeration()).size - 1

    /** Another build (or none) is shown: empty tree and console. */
    fun clear(newBuild: TrackedBuild?) {
        build = newBuild
        generation++
        alarm.cancelAllRequests()
        root.removeAllChildren()
        model.reload()
        loading.stopLoading()
        shownStage = null
        hint(JenkSpotterBundle.message("details.stageLog.hint"))
    }

    /**
     * New stages of the shown build. Nodes whose stage is unchanged are left alone; the log of the
     * selected stage is refreshed when that stage (or one nested in it) changed.
     */
    fun update(updated: TrackedBuild, stages: List<Stage>, pipeline: Boolean) {
        val first = root.childCount == 0
        build = updated
        tree.emptyText.text = JenkSpotterBundle.message(if (pipeline) "details.noStages" else "details.notPipeline")
        val before = selected()
        sync(root, stages)
        if (first) TreeUtil.expandAll(tree)
        val after = selected()
        if (after != null && after != before) loadLog(refresh = true)
    }

    /** Makes [parent]'s children match [stages], reusing nodes by stage id. */
    private fun sync(parent: DefaultMutableTreeNode, stages: List<Stage>) {
        stages.forEachIndexed { i, stage ->
            val existing = if (i < parent.childCount) parent.getChildAt(i) as DefaultMutableTreeNode else null
            if (existing != null && (existing.userObject as Stage).id == stage.id) {
                if (existing.userObject != stage) {
                    existing.userObject = stage
                    model.nodeChanged(existing)
                }
                sync(existing, stage.children)
            } else {
                // Different stage here: everything from this position on is new.
                while (parent.childCount > i) model.removeNodeFromParent(parent.getChildAt(i) as DefaultMutableTreeNode)
                val node = DefaultMutableTreeNode(stage)
                model.insertNodeInto(node, parent, i)
                sync(node, stage.children)
                if (root.childCount > 0) tree.expandPath(javax.swing.tree.TreePath(node.path))
            }
        }
        while (parent.childCount > stages.size) model.removeNodeFromParent(parent.getChildAt(stages.size) as DefaultMutableTreeNode)
    }

    private fun selected(): Stage? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Stage

    /**
     * Fetches the selected stage's log. A new stage shows the loading animation; a [refresh] of the
     * same stage appends what was added (or reprints when the text changed otherwise).
     */
    private fun loadLog(refresh: Boolean) {
        val build = build
        val stage = selected()
        val token = ++generation
        alarm.cancelAllRequests()
        if (build == null || stage == null) {
            loading.stopLoading()
            shownStage = null
            hint(JenkSpotterBundle.message("details.stageLog.hint"))
            return
        }
        val key = build.key + " " + stage.id
        if (!refresh || key != shownStage) {
            shownStage = null
            shownText = ""
            clearConsole()
            loading.startLoading(false)
        }
        val modality = ModalityState.stateForComponent(this)
        alarm.addRequest({
            val result = runCatching {
                val client = JenkinsConnections.getInstance().client(build.serverId)
                    ?: throw de.pdenis.jenkspotter.api.JenkinsException(JenkSpotterBundle.message("error.noToken"))
                client.stageLog(build.build.url, stage)
            }
            ApplicationManager.getApplication().invokeLater({
                if (token != generation) return@invokeLater
                loading.stopLoading()
                val steps = result.getOrElse {
                    show(key, JenkSpotterBundle.message("details.stageLog.error", it.message ?: it.javaClass.simpleName), ConsoleViewContentType.ERROR_OUTPUT)
                    return@invokeLater
                }
                when {
                    steps == null -> show(key, JenkSpotterBundle.message("details.stageLog.unavailable"), ConsoleViewContentType.SYSTEM_OUTPUT)
                    steps.isEmpty() -> show(key, JenkSpotterBundle.message("details.stageLog.empty", stage.name), ConsoleViewContentType.SYSTEM_OUTPUT)
                    else -> print(key, segments(stage, steps))
                }
            }, modality)
        }, 0)
    }

    /** Step headers (and the stage they belong to, when nested) as system lines, the logs with their colours. */
    private fun segments(stage: Stage, steps: List<StageStep>): List<Pair<String, ConsoleViewContentType>> {
        val names = stage.flatten().associate { it.id to it.name }
        val out = mutableListOf<Pair<String, ConsoleViewContentType>>()
        var lastStage: String? = null
        for (step in steps) {
            if (step.stageId.isNotEmpty() && step.stageId != lastStage && step.stageId != stage.id) {
                out += "\n■ ${names[step.stageId] ?: step.stageId}\n" to ConsoleViewContentType.SYSTEM_OUTPUT
            }
            lastStage = step.stageId
            // An echo has no step name and its header would only repeat its output.
            if (step.name.isBlank() && step.log.isNotBlank() && step.status == "SUCCESS") {
                out += LogConsoleManager.stripConsoleNotes(step.log).trimEnd('\n') + "\n" to ConsoleViewContentType.NORMAL_OUTPUT
                continue
            }
            val header = buildString {
                append("▸ ")
                append(listOf(step.name, step.description.lineSequence().firstOrNull().orEmpty().take(200)).filter { it.isNotBlank() }.joinToString(": "))
                if (step.status != "SUCCESS" && step.status.isNotEmpty()) append("  [").append(step.status).append(']')
                append('\n')
            }
            out += header to if (step.status == "FAILED") ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.SYSTEM_OUTPUT
            val log = LogConsoleManager.stripConsoleNotes(step.log).trimEnd('\n')
            if (log.isNotEmpty()) out += "$log\n" to ConsoleViewContentType.NORMAL_OUTPUT
            if (step.truncated) out += JenkSpotterBundle.message("details.stageLog.truncated") + "\n" to ConsoleViewContentType.SYSTEM_OUTPUT
        }
        return out
    }

    /** Prints only what is new compared with what the console already shows for [key]. */
    private fun print(key: String, segments: List<Pair<String, ConsoleViewContentType>>) {
        val text = segments.joinToString("") { it.first }
        val append = key == shownStage && text.startsWith(shownText)
        if (append && text.length == shownText.length) return
        if (!append) clearConsole()
        var skip = if (append) shownText.length else 0
        val decoder = AnsiEscapeDecoder()
        for ((part, type) in segments) {
            if (skip >= part.length) {
                skip -= part.length
                continue
            }
            val rest = part.substring(skip)
            skip = 0
            if (type == ConsoleViewContentType.NORMAL_OUTPUT) {
                decoder.escapeText(rest, ProcessOutputTypes.STDOUT) { chunk, outputType -> console.print(chunk, ConsoleViewContentType.getConsoleViewType(outputType)) }
            } else {
                console.print(rest, type)
            }
        }
        shownStage = key
        shownText = text
        errors.scan()
    }

    private fun clearConsole() {
        console.clear()
        errors.reset()
    }

    private fun show(key: String, message: String, type: ConsoleViewContentType) {
        if (key == shownStage && shownText == message) return
        clearConsole()
        console.print(message, type)
        shownStage = key
        shownText = message
    }

    private fun hint(message: String) {
        clearConsole()
        console.print(message, ConsoleViewContentType.SYSTEM_OUTPUT)
        shownText = ""
    }

    private fun openFullLog() {
        val stage = selected() ?: return
        build?.let { LogConsoleManager.getInstance(project).open(it, "[Pipeline] { (${stage.name})") }
    }

    private fun action(key: String, icon: javax.swing.Icon, perform: () -> Unit) =
        object : DumbAwareAction({ JenkSpotterBundle.message(key) }, icon) {
            override fun actionPerformed(e: AnActionEvent) = perform()
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = selected() != null
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    /** Result icon, name, duration; parallel branches marked, waiting stages in orange. */
    private class StageRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val stage = (value as? DefaultMutableTreeNode)?.userObject as? Stage ?: return
            icon = if (stage.waitingForInput) AllIcons.General.BalloonWarning else JenkSpotterIcons.forResult(stage.result)
            if (stage.parallel) append("∥ ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append(stage.name)
            if (stage.result != BuildResult.NOT_BUILT || stage.durationMillis > 0) {
                append("   " + BuildsPanel.formatDuration(stage.durationMillis), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            if (stage.waitingForInput) append("   " + JenkSpotterBundle.message("details.stage.waiting"), SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.ORANGE))
        }
    }
}
