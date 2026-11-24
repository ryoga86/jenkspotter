package de.pdenis.jenkspotter.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.api.JenkinsPaths
import de.pdenis.jenkspotter.api.JobRef
import de.pdenis.jenkspotter.core.JobIndexService
import de.pdenis.jenkspotter.settings.ChainView
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.CubicCurve2D
import java.awt.geom.Path2D
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent

/**
 * Call graph of a job: every job that leads to it and everything it triggers, the job itself
 * coloured. Only paths through the job are shown; since several jobs may trigger the same one it is
 * a graph, not a chain. Shown as a graph (top-down or left-right), an indented tree or a list of
 * complete paths, see [ChainView]. A click on a job selects it in the list.
 */
object ChainsPopup {
    /** Whether [job] triggers or is triggered by another job, so there is something to show. */
    fun hasChains(serverId: String, job: JobRef): Boolean =
        job.downstream.isNotEmpty() || JobIndexService.getInstance().state(serverId).upstream[job.fullName].orEmpty().isNotEmpty()

    /**
     * Jobs starting a workflow [job] is part of: those that trigger it, directly or not, and are
     * triggered by none themselves. Empty if nothing triggers [job].
     */
    fun roots(serverId: String, job: JobRef): List<JobRef> =
        JobIndexService.getInstance().position(serverId, job)?.paths?.map { it.first() }?.distinctBy { it.fullName }.orEmpty()

    fun show(serverId: String, job: JobRef, where: RelativePoint, select: (JobRef) -> Unit) {
        if (!hasChains(serverId, job)) return
        lateinit var popup: JBPopup
        val pick: (JobRef) -> Unit = { target ->
            popup.cancel()
            select(target)
        }
        // All paths through the job first; with several workflows one more tab per workflow.
        val roots = roots(serverId, job).take(MAX_WORKFLOW_TABS)
        val state = JobIndexService.getInstance().state(serverId)
        val models = listOf(GraphModel(state, job, null)) +
            if (roots.size > 1) roots.map { GraphModel(state, job, it) } else emptyList()
        // One per tab; their content is replaced when the view changes.
        val hosts = models.map { JPanel(BorderLayout()) }
        fun fill(view: ChainView) = models.zip(hosts).forEach { (model, host) ->
            host.removeAll()
            host.add(JBScrollPane(component(model, view, pick)).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
            host.revalidate()
            host.repaint()
        }
        fill(currentView())

        val tabs: JComponent = if (models.size == 1) hosts.single() else com.intellij.ui.components.JBTabbedPane().apply {
            tabComponentInsets = JBUI.emptyInsets()
            addTab(JenkSpotterBundle.message("chains.popup.tab.all"), null, hosts.first(), JenkSpotterBundle.message("chains.popup.tab.all.tooltip"))
            models.zip(hosts).drop(1).forEach { (model, host) ->
                val root = model.root!!
                addTab(JenkinsPaths.displayName(root.name), AllIcons.Actions.Execute, host,
                    JenkSpotterBundle.message("chains.popup.tab.workflow.tooltip", JenkinsPaths.displayName(root.fullName), model.nodes.size))
            }
        }
        val selectedModel = { (tabs as? com.intellij.ui.components.JBTabbedPane)?.let { models[it.selectedIndex.coerceAtLeast(0)] } ?: models.first() }
        val toolbar = ActionManager.getInstance().createActionToolbar("JenkSpotter.ChainsPopup", DefaultActionGroup().apply {
            for (v in ChainView.entries) {
                add(object : DumbAwareToggleAction(JenkSpotterBundle.message(v.key), null, v.icon()) {
                    override fun isSelected(e: AnActionEvent) = currentView() == v
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        if (!state || currentView() == v) return
                        JenkSpotterSettings.getInstance().updateView { chainView = v }
                        fill(v)
                    }
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                })
            }
            addSeparator()
            add(object : DumbAwareAction({ JenkSpotterBundle.message("chains.popup.copyMermaid") }, AllIcons.Actions.Copy) {
                override fun actionPerformed(e: AnActionEvent) {
                    com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(selectedModel().mermaid()))
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
        }, true)
        val content = JPanel(BorderLayout()).apply {
            toolbar.targetComponent = this
            add(toolbar.component.apply { border = JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow.borderColor()) }, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }
        // Big enough for the graph in either direction, so switching the view does not cramp it.
        val sizes = models.flatMap { m -> listOf(true, false).map { GraphView(m, it, pick).preferredSize } }
        val tabsHeight = if (models.size > 1) JBUI.scale(32) else 0
        content.preferredSize = Dimension(
            (sizes.maxOf { it.width } + JBUI.scale(4)).coerceIn(JBUI.scale(420), JBUI.scale(1000)),
            (sizes.maxOf { it.height } + JBUI.scale(40) + tabsHeight).coerceIn(JBUI.scale(260), JBUI.scale(620)),
        )
        val title = if (models.size > 1) "chains.popup.title.workflows" else "chains.popup.title"
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, hosts.first())
            .setTitle(JenkSpotterBundle.message(title, JenkinsPaths.displayName(job.name), roots.size))
            .setAdText(JenkSpotterBundle.message(if (models.any { it.truncated }) "chains.popup.truncated" else "chains.popup.hint"))
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .createPopup()
        popup.show(where)
    }

    private fun currentView(): ChainView = JenkSpotterSettings.getInstance().current.chainView

    private fun ChainView.icon() = when (this) {
        ChainView.TOP_DOWN -> AllIcons.Actions.MoveDown
        ChainView.LEFT_RIGHT -> AllIcons.Actions.Forward
        ChainView.TREE -> AllIcons.Actions.ShowAsTree
        ChainView.PATHS -> AllIcons.Actions.ListFiles
    }

    private fun component(model: GraphModel, view: ChainView, select: (JobRef) -> Unit): JComponent = when (view) {
        ChainView.TOP_DOWN -> GraphView(model, vertical = true, select)
        ChainView.LEFT_RIGHT -> GraphView(model, vertical = false, select)
        ChainView.TREE -> html(model, treeHtml(model), select)
        ChainView.PATHS -> html(model, pathsHtml(model), select)
    }

    internal class Node(val fullName: String, val job: JobRef?, val layer: Int) {
        val disabled get() = job?.disabled == true
        /** Will run when the jobs before it run: no disabled job on every way to it. */
        var reached = true
    }

    /**
     * Jobs and trigger edges. [root] null: every job before and after [center]. Otherwise the whole
     * workflow started by [root]; jobs not on a path through [center] are muted, see [onPath].
     */
    internal class GraphModel(private val state: JobIndexService.IndexState, val center: JobRef, val root: JobRef?) {
        val nodes = LinkedHashMap<String, Node>()
        val edges = mutableListOf<Pair<Node, Node>>()
        /** Jobs on a path through [center]; null = all of them are. */
        var onPath: Set<String>? = null
            private set
        var truncated = false
            private set
        val incoming: Map<Node, List<Node>>
        val outgoing: Map<Node, List<Node>>

        init {
            if (root == null) throughCenter() else workflow(root)
            for (node in nodes.values) {
                for (next in node.job?.downstream.orEmpty()) {
                    val target = nodes[next] ?: continue
                    if (target.layer > node.layer) edges += node to target
                }
            }
            incoming = edges.groupBy({ it.second }, { it.first })
            outgoing = edges.groupBy({ it.first }, { it.second })
            // A disabled job does not run, so neither does anything only it triggers.
            for (node in nodes.values.sortedBy { it.layer }) {
                val before = incoming[node].orEmpty()
                node.reached = !node.disabled && (before.isEmpty() || before.any { it.reached })
            }
            if (root != null) {
                val path = HashSet<String>()
                fun walk(from: Node, next: (Node) -> List<Node>) {
                    if (path.add(from.fullName)) next(from).forEach { walk(it, next) }
                }
                centerNode?.let { c ->
                    walk(c) { incoming[it].orEmpty() }
                    path.remove(c.fullName)
                    walk(c) { outgoing[it].orEmpty() }
                }
                onPath = path
            }
        }

        val centerNode: Node? get() = nodes[center.fullName]

        fun isCenter(node: Node) = node.fullName == center.fullName

        fun isOnPath(node: Node) = isCenter(node) || onPath?.contains(node.fullName) != false

        /** Everything [root] triggers, directly or not; the longest way from it decides the step. */
        private fun workflow(root: JobRef) {
            val layers = linkedMapOf(root.fullName to 0)
            val queue = ArrayDeque(listOf(root.fullName to 0))
            while (queue.isNotEmpty()) {
                val (name, layer) = queue.removeFirst()
                if (layer >= MAX_DEPTH) continue
                val job = if (name == root.fullName) root else state.byFullName[name]
                for (next in job?.downstream.orEmpty()) {
                    if (next == root.fullName) continue
                    if (next in layers && layers.getValue(next) >= layer + 1) continue
                    if (next !in layers && layers.size >= MAX_NODES) { truncated = true; continue }
                    layers[next] = layer + 1
                    queue += next to layer + 1
                }
            }
            layers.entries.sortedBy { it.value }.forEach { (name, layer) ->
                nodes[name] = Node(name, if (name == center.fullName) center else state.byFullName[name], layer)
            }
        }

        /** Longest distance to the job decides the step, so every arrow points the same way. */
        private fun throughCenter() {
            val upstream = state.upstream
            val up = HashMap<String, Int>()
            val queue = ArrayDeque(listOf(center.fullName to 0))
            while (queue.isNotEmpty()) {
                val (name, layer) = queue.removeFirst()
                if (layer <= -MAX_DEPTH) continue
                for (job in upstream[name].orEmpty()) {
                    if (job.fullName == center.fullName) continue
                    if ((up[job.fullName] ?: 0) <= layer - 1 && job.fullName in up) continue
                    if (job.fullName !in up && up.size >= MAX_NODES / 2) { truncated = true; continue }
                    up[job.fullName] = layer - 1
                    queue += job.fullName to layer - 1
                }
            }
            val down = HashMap<String, Int>()
            queue += center.fullName to 0
            while (queue.isNotEmpty()) {
                val (name, layer) = queue.removeFirst()
                if (layer >= MAX_DEPTH) continue
                val job = if (name == center.fullName) center else state.byFullName[name]
                for (next in job?.downstream.orEmpty()) {
                    if (next == center.fullName || next in up) continue
                    if (next in down && down.getValue(next) >= layer + 1) continue
                    if (next !in down && down.size >= MAX_NODES / 2) { truncated = true; continue }
                    down[next] = layer + 1
                    queue += next to layer + 1
                }
            }
            (up.entries.sortedBy { it.value } + mapOf(center.fullName to 0).entries + down.entries.sortedBy { it.value }).forEach { (name, layer) ->
                nodes[name] = Node(name, if (name == center.fullName) center else state.byFullName[name], layer)
            }
        }

        /** Every complete path (first job to last), the ones through [center] first; at most [MAX_PATHS]. */
        fun paths(): List<List<Node>> {
            val result = mutableListOf<List<Node>>()
            fun walk(node: Node, before: List<Node>) {
                if (result.size >= MAX_PATHS) return
                val path = before + node
                val next = outgoing[node].orEmpty()
                if (next.isEmpty()) result += path else next.forEach { walk(it, path) }
            }
            nodes.values.filter { incoming[it].isNullOrEmpty() }.forEach { walk(it, emptyList()) }
            return result.sortedWith(compareByDescending<List<Node>> { p -> p.any(::isCenter) }.thenByDescending { it.size })
        }

        /** The graph as a Mermaid flowchart, e.g. for documentation or a ticket. */
        fun mermaid(): String {
            val ids = nodes.values.withIndex().associate { (i, n) -> n to "j$i" }
            val out = StringBuilder("flowchart TD\n")
            for (node in nodes.values) {
                val label = JenkinsPaths.displayName(node.fullName).replace("\"", "'")
                out.append("    ").append(ids[node]).append("[\"").append(label).append("\"]\n")
            }
            for ((from, to) in edges) out.append("    ").append(ids[from]).append(" --> ").append(ids[to]).append('\n')
            centerNode?.let { out.append("    style ").append(ids[it]).append(" fill:#FFE59A,stroke:#E0A800\n") }
            nodes.values.filter { it.disabled }.forEach { out.append("    style ").append(ids[it]).append(" stroke-dasharray: 4 3\n") }
            return out.toString()
        }
    }

    /** Boxes and arrows; [vertical]: one row per step from top to bottom, otherwise one column per step. */
    private class GraphView(private val model: GraphModel, private val vertical: Boolean, private val select: (JobRef) -> Unit) : JComponent() {
        private val bounds = HashMap<Node, Rectangle>()
        private val texts = HashMap<Node, String>()

        init {
            layoutNodes()
            toolTipText = ""
            val mouse = object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    cursor = if (nodeAt(e) != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                }

                override fun mouseClicked(e: MouseEvent) {
                    val node = nodeAt(e) ?: return
                    if (!model.isCenter(node)) node.job?.let(select)
                }
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
        }

        private fun nodeAt(e: MouseEvent) = model.nodes.values.firstOrNull { bounds[it]?.contains(e.point) == true }

        override fun getToolTipText(e: MouseEvent): String? {
            val node = nodeAt(e) ?: return null
            return (JenkinsPaths.displayName(node.fullName) + " " + stateText(node)).trim()
        }

        /** Within a step the jobs follow the order of their neighbours, to keep arrows short. */
        private fun layoutNodes() {
            val metrics = getFontMetrics(UIUtil.getLabelFont())
            val bold = getFontMetrics(UIUtil.getLabelFont().deriveFont(java.awt.Font.BOLD))
            val steps = model.nodes.values.groupBy { it.layer }.toSortedMap()
            val anchor = model.nodes[model.root?.fullName ?: model.center.fullName]
            val order = HashMap<Node, Double>()
            anchor?.let { order[it] = 0.0 }
            val start = anchor?.layer ?: 0
            fun arrange(layer: Int, neighbours: (Node) -> List<Node>) {
                val step = steps[layer] ?: return
                step.sortedBy { n -> neighbours(n).mapNotNull(order::get).average().takeIf { !it.isNaN() } ?: 0.0 }
                    .forEachIndexed { i, n -> order[n] = i.toDouble() - (step.size - 1) / 2.0 }
            }
            for (layer in start + 1..steps.lastKey()) arrange(layer) { model.incoming[it].orEmpty() }
            for (layer in start - 1 downTo steps.firstKey()) arrange(layer) { model.outgoing[it].orEmpty() }

            for (node in model.nodes.values) {
                val fm = if (model.isCenter(node)) bold else metrics
                texts[node] = ellipsize(JenkinsPaths.displayName(node.job?.name ?: node.fullName.substringAfterLast('/')), fm)
            }
            fun width(node: Node) = (if (model.isCenter(node)) bold else metrics).stringWidth(texts.getValue(node)) + JBUI.scale(20)
            val padding = JBUI.scale(10)
            val height = JBUI.scale(NODE_HEIGHT)
            val groups = steps.values.map { step -> step.sortedBy { order[it] ?: 0.0 } }
            if (vertical) {
                // One row per step, the jobs of a step side by side, each row centred.
                val gap = JBUI.scale(H_GAP_VERTICAL)
                fun rowWidth(row: List<Node>) = row.sumOf(::width) + (row.size - 1) * gap
                val widest = groups.maxOf(::rowWidth)
                var y = padding
                for (row in groups) {
                    var x = padding + (widest - rowWidth(row)) / 2
                    for (node in row) {
                        bounds[node] = Rectangle(x, y, width(node), height)
                        x += width(node) + gap
                    }
                    y += height + JBUI.scale(V_GAP_VERTICAL)
                }
                preferredSize = Dimension(widest + 2 * padding, y - JBUI.scale(V_GAP_VERTICAL) + padding)
            } else {
                // One column per step, as wide as its longest name, each column centred.
                val gap = JBUI.scale(V_GAP_HORIZONTAL)
                val tallest = groups.maxOf { it.size }
                val totalHeight = tallest * height + (tallest - 1) * gap
                var x = padding
                for (column in groups) {
                    val columnWidth = column.maxOf(::width)
                    val top = padding + (totalHeight - (column.size * height + (column.size - 1) * gap)) / 2
                    column.forEachIndexed { i, node -> bounds[node] = Rectangle(x, top + i * (height + gap), columnWidth, height) }
                    x += columnWidth + JBUI.scale(H_GAP_HORIZONTAL)
                }
                preferredSize = Dimension(x - JBUI.scale(H_GAP_HORIZONTAL) + padding, totalHeight + 2 * padding)
            }
        }

        private fun ellipsize(text: String, fm: java.awt.FontMetrics): String {
            val max = JBUI.scale(MAX_NODE_WIDTH)
            if (fm.stringWidth(text) <= max) return text
            var end = text.length
            while (end > 1 && fm.stringWidth(text.substring(0, end) + "…") > max) end--
            return text.substring(0, end) + "…"
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.color = UIUtil.getPanelBackground()
                g2.fillRect(0, 0, width, height)
                for ((from, to) in model.edges) paintEdge(g2, from, to)
                for (node in model.nodes.values) paintNode(g2, node)
            } finally {
                g2.dispose()
            }
        }

        private fun paintEdge(g2: Graphics2D, from: Node, to: Node) {
            val a = bounds[from] ?: return
            val b = bounds[to] ?: return
            val active = from.reached && to.reached
            g2.color = when {
                !active -> CUT_OFF
                !model.isOnPath(from) || !model.isOnPath(to) -> MUTED
                else -> EDGE_COLOR
            }
            g2.stroke = if (active) BasicStroke(JBUI.scale(1.3f)) else
                BasicStroke(JBUI.scale(1.3f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(JBUI.scale(4f), JBUI.scale(3f)), 0f)
            val size = JBUI.scale(6).toDouble()
            if (vertical) {
                val x1 = a.centerX; val y1 = a.maxY.toDouble(); val x2 = b.centerX; val y2 = b.minY - JBUI.scale(1)
                val bend = (y2 - y1) / 2
                g2.draw(CubicCurve2D.Double(x1, y1, x1, y1 + bend, x2, y2 - bend, x2, y2))
                g2.fill(Path2D.Double().apply { moveTo(x2, y2); lineTo(x2 - size / 2, y2 - size); lineTo(x2 + size / 2, y2 - size); closePath() })
            } else {
                val x1 = a.maxX.toDouble(); val y1 = a.centerY; val x2 = b.minX - JBUI.scale(1); val y2 = b.centerY
                val bend = (x2 - x1) / 2
                g2.draw(CubicCurve2D.Double(x1, y1, x1 + bend, y1, x2 - bend, y2, x2, y2))
                g2.fill(Path2D.Double().apply { moveTo(x2, y2); lineTo(x2 - size, y2 - size / 2); lineTo(x2 - size, y2 + size / 2); closePath() })
            }
        }

        private fun paintNode(g2: Graphics2D, node: Node) {
            val r = bounds[node] ?: return
            val text = texts.getValue(node)
            val arc = JBUI.scale(8)
            val current = model.isCenter(node)
            g2.color = if (current) HIGHLIGHT else UIUtil.getListBackground()
            g2.fillRoundRect(r.x, r.y, r.width, r.height, arc, arc)
            g2.color = when {
                current -> HIGHLIGHT_BORDER
                !node.reached -> CUT_OFF
                !model.isOnPath(node) -> MUTED
                else -> JBColor.border()
            }
            g2.stroke = BasicStroke(JBUI.scale(if (current) 1.6f else 1f))
            g2.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, arc, arc)
            g2.font = UIUtil.getLabelFont().let { if (current) it.deriveFont(java.awt.Font.BOLD) else it }
            val fm = g2.fontMetrics
            val tx = r.x + (r.width - fm.stringWidth(text)) / 2
            val ty = r.y + (r.height - fm.height) / 2 + fm.ascent
            g2.color = when {
                current -> UIUtil.getLabelForeground()
                node.job == null || !node.reached || !model.isOnPath(node) -> UIUtil.getInactiveTextColor()
                else -> JBUI.CurrentTheme.Link.Foreground.ENABLED
            }
            g2.drawString(text, tx, ty)
            if (node.disabled) {
                val y = ty - fm.ascent / 3
                g2.stroke = BasicStroke(JBUI.scale(1f))
                g2.drawLine(tx, y, tx + fm.stringWidth(text), y)
            }
        }
    }

    private fun stateText(node: Node): String = when {
        node.disabled -> JenkSpotterBundle.message("jobs.disabled")
        !node.reached -> JenkSpotterBundle.message("jobs.notReached")
        else -> ""
    }

    /** HTML view with job links; a click selects that job. */
    private fun html(model: GraphModel, body: String, select: (JobRef) -> Unit): JComponent = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder.simple()
        isEditable = false
        border = JBUI.Borders.empty(6, 10)
        text = "<html><body>$body</body></html>"
        caretPosition = 0
        addHyperlinkListener {
            if (it.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val name = JobInfoDialog.jobFromLink(it.description) ?: return@addHyperlinkListener
            model.nodes[name]?.job?.let(select)
        }
    }

    /** One job as a table cell: highlighted when it is the job itself, a link otherwise. */
    private fun cell(model: GraphModel, node: Node, prefix: String): String {
        val e = StringUtil::escapeXmlEntities
        val grey = ColorUtil.toHex(UIUtil.getInactiveTextColor())
        val name = e(JenkinsPaths.displayName(node.fullName)).let { if (node.disabled) "<s>$it</s>" else it }
        val state = stateText(node).takeIf { it.isNotEmpty() }?.let { " <i>${e(it)}</i>" }.orEmpty()
        val muted = !node.reached || !model.isOnPath(node) || node.job == null
        return when {
            model.isCenter(node) -> "<td bgcolor='#${ColorUtil.toHex(HIGHLIGHT)}'>$prefix<b>$name</b>$state</td>"
            muted -> "<td>$prefix<a href='${JobInfoDialog.jobLink(node.fullName)}'><font color='#$grey'>$name</font></a>$state</td>"
            else -> "<td>$prefix<a href='${JobInfoDialog.jobLink(node.fullName)}'>$name</a>$state</td>"
        }
    }

    /**
     * Indented tree. For all paths: what triggers the job (upwards, its triggers indented below it),
     * then what it triggers. For a workflow: everything from the job starting it.
     */
    private fun treeHtml(model: GraphModel): String {
        val e = StringUtil::escapeXmlEntities
        val out = StringBuilder()
        var lines = 0
        fun walk(node: Node, depth: Int, next: (Node) -> List<Node>, seen: Set<Node>) {
            if (lines++ > MAX_TREE_LINES) return
            val prefix = "&nbsp;&nbsp;&nbsp;&nbsp;".repeat(depth) + if (depth > 0) "└ " else ""
            out.append("<tr>").append(cell(model, node, prefix)).append("</tr>")
            for (child in next(node)) if (child !in seen) walk(child, depth + 1, next, seen + child)
        }
        fun section(key: String) = out.append("<tr><td><b>").append(e(JenkSpotterBundle.message(key))).append("</b></td></tr>")
        out.append("<table cellpadding='1' cellspacing='0'>")
        val center = model.centerNode
        val root = model.root
        if (root != null) {
            model.nodes[root.fullName]?.let { walk(it, 0, { n -> model.outgoing[n].orEmpty() }, setOf(it)) }
        } else if (center != null) {
            if (!model.incoming[center].isNullOrEmpty()) {
                section("chains.tree.triggeredBy")
                model.incoming[center].orEmpty().forEach { walk(it, 1, { n -> model.incoming[n].orEmpty() }, setOf(it)) }
            }
            if (!model.outgoing[center].isNullOrEmpty()) {
                section("chains.tree.triggers")
                walk(center, 0, { n -> model.outgoing[n].orEmpty() }, setOf(center))
            }
        }
        if (lines > MAX_TREE_LINES) out.append("<tr><td><i>…</i></td></tr>")
        return out.append("</table>").toString()
    }

    /** Every complete path in one row each: "1. A › B › C". */
    private fun pathsHtml(model: GraphModel): String {
        val e = StringUtil::escapeXmlEntities
        val grey = ColorUtil.toHex(UIUtil.getInactiveTextColor())
        val paths = model.paths()
        val out = StringBuilder("<table cellpadding='2' cellspacing='0'>")
        paths.forEachIndexed { i, path ->
            out.append("<tr><td valign='top' align='right'><font color='#$grey'>").append(i + 1).append(".</font></td><td><table cellpadding='1' cellspacing='0'><tr>")
            path.forEachIndexed { n, node ->
                if (n > 0) out.append("<td><font color='#$grey'>›</font></td>")
                out.append(cell(model, node, ""))
            }
            out.append("</tr></table></td></tr>")
        }
        out.append("</table>")
        if (paths.size >= MAX_PATHS) out.append("<i>").append(e(JenkSpotterBundle.message("chains.paths.limit", MAX_PATHS))).append("</i>")
        return out.toString()
    }

    private const val MAX_NODES = 120
    private const val MAX_DEPTH = 20
    private const val MAX_PATHS = 60
    private const val MAX_TREE_LINES = 400
    private const val NODE_HEIGHT = 26
    /** Top-down: between two steps (room for the arrows) and between two jobs of one step. */
    private const val V_GAP_VERTICAL = 34
    private const val H_GAP_VERTICAL = 14
    /** Left-right: between two jobs of one step and between two steps. */
    private const val V_GAP_HORIZONTAL = 10
    private const val H_GAP_HORIZONTAL = 44
    private const val MAX_NODE_WIDTH = 220
    private const val MAX_WORKFLOW_TABS = 12
    private val HIGHLIGHT = JBColor(0xFFE59A, 0x6B5414)
    private val HIGHLIGHT_BORDER = JBColor(0xE0A800, 0xC9A23A)
    private val EDGE_COLOR = JBColor(0x7F96B8, 0x6A7F9E)
    /** Parts of a workflow that do not pass the job. */
    private val MUTED = JBColor(0xD3D6DB, 0x45484D)
    private val CUT_OFF = JobHitRenderer.CUT_OFF_COLOR
}
