package de.pdenis.jenkspotter.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.core.BuildMonitorService
import de.pdenis.jenkspotter.core.HiddenJobs
import de.pdenis.jenkspotter.settings.JenkSpotterConfigurable
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import de.pdenis.jenkspotter.settings.FilterMode
import de.pdenis.jenkspotter.settings.RegexFilter
import de.pdenis.jenkspotter.settings.SettingsListener
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingConstants

/** Filter state shared by the Jobs and Builds panels of one tool window. EDT only. */
class SharedFilter {
    /** null = all servers. */
    var serverId: String? = null
        set(value) {
            field = value
            fire()
        }

    private val listeners = mutableListOf<() -> Unit>()

    fun hidden(): HiddenJobs = HiddenJobs.of(JenkSpotterSettings.getInstance().current)

    fun includes(serverId: String) = this.serverId == null || this.serverId == serverId

    fun serverIds(): List<String> =
        JenkSpotterSettings.getInstance().activeServers.map { it.id }.filter(::includes)

    fun onChange(listener: () -> Unit) {
        listeners += listener
    }

    fun fire() = listeners.forEach { it() }
}

/**
 * Tool window content: Jobs (left) and Builds (right). The global bar (Jenkins, filter chips,
 * folder filter) is not part of the content but shown in the tool window header, see [headerBar].
 */
class JenkSpotterMainPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    val filter = SharedFilter()
    val jobs = JobsPanel(project, filter)
    private val builds = BuildsPanel(project, filter)
    private val serverCombo = ComboBox<ServerItem>()
    private val filterBar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0)).apply { isOpaque = false }

    /** Placed into the tool window header right after "JenkSpotter". */
    val headerBar: JComponent get() = filterBar

    @Volatile
    private var disposed = false

    init {
        serverCombo.addActionListener {
            val id = (serverCombo.selectedItem as? ServerItem)?.id
            if (id != filter.serverId) filter.serverId = id
        }
        add(OnePixelSplitter(false, "JenkSpotter.JobsBuilds", 0.4f).apply {
            firstComponent = jobs
            secondComponent = builds
        }, BorderLayout.CENTER)

        ApplicationManager.getApplication().messageBus.connect(this).subscribe(SettingsListener.TOPIC, SettingsListener {
            ApplicationManager.getApplication().invokeLater({ rebuildBar(); filter.fire() }, { disposed || project.isDisposed })
        })
        rebuildBar()
    }

    private fun rebuildBar() {
        val selected = filter.serverId
        val items = ServerItem.all(includeAll = true)
        serverCombo.removeAllItems()
        items.forEach(serverCombo::addItem)
        val target = items.firstOrNull { it.id == selected } ?: items.firstOrNull()
        serverCombo.selectedItem = target
        if (target?.id != filter.serverId) filter.serverId = target?.id

        // Order: Jenkins selection | job filters | folder filter.
        filterBar.removeAll()
        fun separator() = JSeparator(SwingConstants.VERTICAL).apply { preferredSize = JBUI.size(1, 16) }
        filterBar.add(separator())
        if (items.size > 1) {
            filterBar.add(serverCombo)
            filterBar.add(separator())
        }
        val filters = JenkSpotterSettings.getInstance().current.filters
        for (f in filters.filter { !it.isFolderRule }) filterBar.add(filterChip(f))
        filterBar.add(ActionLink(JenkSpotterBundle.message("filter.add")) { editFilter(null) }.apply {
            toolTipText = JenkSpotterBundle.message("filter.add.tooltip")
        })
        filterBar.add(separator())
        filters.firstOrNull { it.isFolderRule }?.let { filterBar.add(filterChip(it)) }
        filterBar.add(ActionLink(JenkSpotterBundle.message("filter.folders")) { FolderFilterDialog.open(project) }.apply {
            toolTipText = JenkSpotterBundle.message("filter.folders.tooltip")
        })
        filterBar.revalidate()
        filterBar.repaint()
    }

    private fun filterChip(f: RegexFilter): JComponent {
        val settings = JenkSpotterSettings.getInstance()
        return FilterChip(
            f,
            onToggle = { settings.setFilterEnabled(f.id, it) },
            onEdit = { if (f.isFolderRule) FolderFilterDialog.open(project) else editFilter(f) },
            onSwitchMode = if (f.isFolderRule) null else {
                { settings.saveFilter(f.copy().apply { mode = if (mode == FilterMode.INCLUDE) FilterMode.EXCLUDE else FilterMode.INCLUDE }) }
            },
            onRemove = { settings.removeFilter(f.id) },
        )
    }

    private fun editFilter(existing: RegexFilter?) = FilterActions.edit(project, existing)

    override fun dispose() {
        disposed = true
        com.intellij.openapi.util.Disposer.dispose(jobs)
        com.intellij.openapi.util.Disposer.dispose(builds)
    }

    companion object {
        fun configureAction(project: Project?): AnAction =
            object : DumbAwareAction({ JenkSpotterBundle.message("action.configure") }, AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(e.project ?: project, JenkSpotterConfigurable::class.java)
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            }

        fun refreshAction(): AnAction =
            object : DumbAwareAction({ JenkSpotterBundle.message("action.refresh") }, AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = BuildMonitorService.getInstance().refresh()
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            }
    }
}

/**
 * Create/edit a regex filter. Aimed at people who do not write regular expressions every day:
 * a small builder (starts with / ends with / contains ... combined with OR / AND), clickable
 * examples and a live preview of which indexed jobs would be hidden.
 */
class RegexFilterDialog(
    project: Project,
    existing: RegexFilter?,
    /** Full names of all known buildable jobs. */
    private val sampleJobs: List<String>,
    /** The other active rules, so the preview shows what remains with everything combined. */
    private val otherFilters: List<RegexFilter> = emptyList(),
    private val isNew: Boolean = existing == null,
) : DialogWrapper(project) {
    private val base = existing?.copy() ?: RegexFilter()
    private val nameField = JBTextField(base.name, 20)
    private val patternField = JBTextField(base.pattern, 36)
    private val excludeRadio = com.intellij.ui.components.JBRadioButton(JenkSpotterBundle.message("filter.mode.exclude"), base.mode == FilterMode.EXCLUDE)
    private val includeRadio = com.intellij.ui.components.JBRadioButton(JenkSpotterBundle.message("filter.mode.include"), base.mode == FilterMode.INCLUDE)
    private val kindCombo = ComboBox(BuilderKind.entries.toTypedArray())
    private val textField = JBTextField(16)
    private val preview = com.intellij.ui.components.JBLabel().apply {
        foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
    }

    private enum class BuilderKind(val key: String) {
        STARTS("filter.kind.starts"), ENDS("filter.kind.ends"), CONTAINS("filter.kind.contains"),
        EXACT("filter.kind.exact"), FOLDER("filter.kind.folder");

        override fun toString() = JenkSpotterBundle.message(key)

        fun toRegex(text: String): String {
            val t = escape(text)
            return when (this) {
                STARTS -> "^$t"
                ENDS -> "$t$"
                CONTAINS -> t
                EXACT -> "^$t$"
                FOLDER -> "^${escape(text.trimEnd('/'))}(/|$)"
            }
        }
    }

    init {
        title = JenkSpotterBundle.message(if (isNew) "filter.dialog.titleNew" else "filter.dialog.titleEdit")
        javax.swing.ButtonGroup().apply { add(excludeRadio); add(includeRadio) }
        excludeRadio.addActionListener { updatePreview() }
        includeRadio.addActionListener { updatePreview() }
        patternField.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = updatePreview()
        })
        init()
        updatePreview()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(JenkSpotterBundle.message("filter.dialog.name")) { cell(nameField).align(AlignX.FILL) }
        row(JenkSpotterBundle.message("filter.dialog.mode")) {
            cell(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(excludeRadio); add(includeRadio) })
        }
            .rowComment(JenkSpotterBundle.message("filter.dialog.modeHint"))
        group(JenkSpotterBundle.message("filter.builder.title")) {
            row {
                cell(kindCombo)
                cell(textField).align(AlignX.FILL).resizableColumn()
                button(JenkSpotterBundle.message("filter.builder.set")) { combine(null) }
                button(JenkSpotterBundle.message("filter.builder.or")) { combine("or") }
                button(JenkSpotterBundle.message("filter.builder.and")) { combine("and") }
            }.rowComment(JenkSpotterBundle.message("filter.builder.hint"))
        }
        row(JenkSpotterBundle.message("filter.dialog.patternGeneric")) { cell(patternField).align(AlignX.FILL) }
            .rowComment(JenkSpotterBundle.message("filter.dialog.hint"))
        row { cell(preview) }
        collapsibleGroup(JenkSpotterBundle.message("filter.examples.title")) {
            for ((key, regex) in EXAMPLES) {
                row {
                    link(regex) { patternField.text = regex }.applyToComponent { font = com.intellij.util.ui.JBFont.create(java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, font.size)) }
                    comment(JenkSpotterBundle.message(key))
                }
            }
        }.apply {
            expanded = base.pattern.isEmpty()
            // Grow and shrink with the examples instead of leaving an empty area behind.
            addExpandedListener { javax.swing.SwingUtilities.invokeLater { if (isShowing) pack() } }
        }
    }

    private fun combine(mode: String?) {
        if (textField.text.isEmpty()) return
        val kind = kindCombo.item ?: BuilderKind.CONTAINS
        val next = kind.toRegex(textField.text)
        val current = patternField.text.trim()
        patternField.text = when {
            mode == null || current.isEmpty() -> next
            mode == "or" -> "$current|$next"
            else -> and(current, next)
        }
        textField.text = ""
        textField.requestFocusInWindow()
    }

    private fun updatePreview() {
        val regex = runCatching { Regex(patternField.text.trim(), RegexOption.IGNORE_CASE) }.getOrNull()
        preview.text = when {
            patternField.text.isBlank() -> ""
            regex == null -> JenkSpotterBundle.message("filter.invalid")
            sampleJobs.isEmpty() -> ""
            else -> {
                val hidden = HiddenJobs.of(otherFilters + result().apply { enabled = true })
                val remaining = sampleJobs.count { !hidden.isHidden(it) }
                JenkSpotterBundle.message("filter.remaining", remaining, sampleJobs.size)
            }
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = if (base.name.isEmpty()) nameField else patternField

    override fun doValidate(): ValidationInfo? {
        if (patternField.text.isBlank()) return ValidationInfo(JenkSpotterBundle.message("filter.dialog.empty"), patternField)
        return runCatching { Regex(patternField.text) }.exceptionOrNull()
            ?.let { ValidationInfo(it.message?.lineSequence()?.firstOrNull() ?: JenkSpotterBundle.message("filter.invalid"), patternField) }
    }

    fun result(): RegexFilter = base.copy().apply {
        name = nameField.text.trim().ifEmpty { patternField.text.trim() }
        pattern = patternField.text.trim()
        mode = if (includeRadio.isSelected) FilterMode.INCLUDE else FilterMode.EXCLUDE
    }

    companion object {
        val EXAMPLES = listOf(
            "filter.example.starts" to "^Playground/",
            "filter.example.ends" to "-prod$",
            "filter.example.contains" to "test",
            "filter.example.folder" to "^Playground(/|$)",
            "filter.example.or" to "-prod$|-staging$",
            "filter.example.and" to "^(?=.*aws)(?=.*deploy)",
            "filter.example.startsEnds" to "^team/.*-prod$",
            "filter.example.not" to "^(?!team/)",
        )

        private const val SPECIAL = "\\.[]{}()*+?^$|"

        /** Escapes regex metacharacters so typed text is matched literally. */
        fun escape(text: String): String = buildString {
            for (c in text) {
                if (c in SPECIAL) append('\\')
                append(c)
            }
        }

        /** Both patterns must match somewhere, in any order. */
        fun and(a: String, b: String): String {
            val parts = (lookaheads(a) ?: listOf(a)) + (lookaheads(b) ?: listOf(b))
            return "^" + parts.joinToString("") { "(?=.*(?:$it))" }
        }

        private val LOOKAHEAD = Regex("""\(\?=\.\*\(\?:(.*?)\)\)""")

        // Unpacks a pattern produced by [and] so repeated AND keeps a flat list.
        private fun lookaheads(pattern: String): List<String>? {
            if (!pattern.startsWith("^(?=.*(?:")) return null
            val parts = LOOKAHEAD.findAll(pattern.removePrefix("^")).map { it.groupValues[1] }.toList()
            return parts.takeIf { it.isNotEmpty() && "^" + parts.joinToString("") { "(?=.*(?:$it))" } == pattern }
        }
    }
}
