package de.pdenis.jenkspotter.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.core.HiddenJobs
import de.pdenis.jenkspotter.core.JobIndexService
import de.pdenis.jenkspotter.settings.FilterMode
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import de.pdenis.jenkspotter.settings.RegexFilter
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * All folders (incl. multibranch projects) of all servers in one tree. Unchecked = hidden with
 * everything inside (shown in red). The result is stored as one "Hidden folders" filter chip.
 */
class FolderFilterDialog(project: Project) : DialogWrapper(project) {
    private data class Folder(val path: String, val name: String, val jobs: Int, val hiddenByOther: Boolean)

    private val settings = JenkSpotterSettings.getInstance()
    private val existing = settings.current.filters.firstOrNull { it.isFolderRule }
    private val root = CheckedTreeNode("root")
    private val tree: CheckboxTree
    private val summary = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    init {
        title = JenkSpotterBundle.message("folders.title")
        buildNodes()
        tree = CheckboxTree(Renderer(), root, CheckboxTreeBase.CheckPolicy(true, true, true, false))
        tree.isRootVisible = false
        tree.showsRootHandles = true
        TreeSpeedSearch.installOn(tree, false) { path -> ((path.lastPathComponent as? CheckedTreeNode)?.userObject as? Folder)?.path.orEmpty() }
        tree.model.addTreeModelListener(object : javax.swing.event.TreeModelListener {
            override fun treeNodesChanged(e: javax.swing.event.TreeModelEvent) = updateSummary()
            override fun treeNodesInserted(e: javax.swing.event.TreeModelEvent) = Unit
            override fun treeNodesRemoved(e: javax.swing.event.TreeModelEvent) = Unit
            override fun treeStructureChanged(e: javax.swing.event.TreeModelEvent) = updateSummary()
        })
        // Open the first level and every path that leads to a hidden folder.
        TreeUtil.expand(tree, 1)
        TreeUtil.treeNodeTraverser(root).filter(CheckedTreeNode::class.java).filter { !it.isChecked }.forEach {
            tree.expandPath(TreePath((it.parent as? CheckedTreeNode)?.path ?: return@forEach))
        }
        init()
        updateSummary()
    }

    private fun buildNodes() {
        val index = JobIndexService.getInstance()
        val jobs = settings.activeServers.flatMap { index.state(it.id).jobs }
        val folders = jobs.filter { it.isFolder }.map { FilterActions.decoded(it.fullName) }.toSortedSet(String.CASE_INSENSITIVE_ORDER)
        val counts = HashMap<String, Int>()
        for (job in jobs.filter { it.isBuildable }.map { FilterActions.decoded(it.fullName) }.distinct()) {
            job.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" }.forEach { counts.merge(it, 1, Int::plus) }
        }
        val hidden = existing?.folders.orEmpty().toSet()
        val others = HiddenJobs.of(settings.current.filters.filter { it.enabled && !it.isFolderRule })
        val nodes = HashMap<String, CheckedTreeNode>()
        for (path in folders) {
            val parentPath = path.substringBeforeLast('/', "")
            val parent = nodes[parentPath] ?: root
            val folder = Folder(path, path.substringAfterLast('/'), counts[path] ?: 0, others.isFolderHidden(path))
            val node = CheckedTreeNode(folder)
            node.isChecked = path !in hidden && (parent === root || parent.isChecked)
            parent.add(node)
            nodes[path] = node
        }
    }

    /** Topmost unchecked folders; everything below them is hidden anyway. */
    private fun hiddenFolders(): List<String> {
        val result = mutableListOf<String>()
        fun walk(node: CheckedTreeNode) {
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i) as CheckedTreeNode
                if (!child.isChecked) result += (child.userObject as Folder).path else walk(child)
            }
        }
        walk(root)
        return result
    }

    private fun updateSummary() {
        val folders = hiddenFolders()
        val hidden = HiddenJobs.of(listOf(ruleFor(folders) ?: RegexFilter()) + settings.current.filters.filter { it.enabled && !it.isFolderRule })
        val jobs = FilterActions.knownJobs()
        summary.text = JenkSpotterBundle.message("folders.summary", folders.size, jobs.count { !hidden.isHidden(it) }, jobs.size)
    }

    override fun createCenterPanel(): JComponent {
        val actions = DefaultActionGroup(
            simple("folders.expandAll", AllIcons.Actions.Expandall) { TreeUtil.expandAll(tree) },
            simple("folders.collapseAll", AllIcons.Actions.Collapseall) { TreeUtil.collapseAll(tree, 0) },
            simple("folders.showAll", AllIcons.Actions.Show) { setAll(true) },
            simple("folders.hideAll", AllIcons.Actions.ToggleVisibility) { setAll(false) },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("JenkSpotter.Folders", actions, true)
        toolbar.targetComponent = tree
        return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            preferredSize = JBUI.size(520, 560)
            add(JPanel(BorderLayout()).apply {
                add(toolbar.component, BorderLayout.WEST)
                add(JBLabel(JenkSpotterBundle.message("folders.hint")).apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
            add(summary, BorderLayout.SOUTH)
        }
    }

    private fun setAll(checked: Boolean) {
        for (i in 0 until root.childCount) tree.setNodeState(root.getChildAt(i) as CheckedTreeNode, checked)
        (tree.model as DefaultTreeModel).nodeStructureChanged(root)
        TreeUtil.expand(tree, 1)
        updateSummary()
    }

    private fun simple(key: String, icon: javax.swing.Icon, block: () -> Unit) =
        object : DumbAwareAction({ JenkSpotterBundle.message(key) }, icon) {
            override fun actionPerformed(e: AnActionEvent) = block()
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    override fun getPreferredFocusedComponent(): JComponent = tree

    override fun doOKAction() {
        val folders = hiddenFolders()
        val rule = ruleFor(folders)
        if (rule == null) existing?.let { settings.removeFilter(it.id) } else settings.saveFilter(rule)
        super.doOKAction()
    }

    private fun ruleFor(folders: List<String>): RegexFilter? {
        if (folders.isEmpty()) return null
        return (existing?.copy() ?: RegexFilter()).apply {
            name = JenkSpotterBundle.message("folders.chip", folders.size)
            mode = FilterMode.EXCLUDE
            enabled = true
            this.folders = folders.toMutableList()
            pattern = folders.joinToString("|") { FilterActions.folderRegex(it) }
        }
    }

    private class Renderer : CheckboxTree.CheckboxTreeCellRenderer() {
        override fun customizeRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val node = value as? CheckedTreeNode ?: return
            val folder = node.userObject as? Folder ?: return
            textRenderer.icon = AllIcons.Nodes.Folder
            val attributes = if (node.isChecked) SimpleTextAttributes.REGULAR_ATTRIBUTES
            else SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, FilterChip.EXCLUDE_COLOR)
            textRenderer.append(folder.name, attributes)
            textRenderer.append("  " + JenkSpotterBundle.message("folders.jobs", folder.jobs), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            if (folder.hiddenByOther) textRenderer.append("  " + JenkSpotterBundle.message("folders.hiddenByOther"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        }
    }

    companion object {
        fun open(project: Project) {
            FolderFilterDialog(project).show()
        }
    }
}
