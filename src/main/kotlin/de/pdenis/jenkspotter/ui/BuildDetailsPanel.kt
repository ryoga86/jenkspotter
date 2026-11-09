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
 * Details of the selected build below the builds table: input step banner, stages, failed tests,
 * changes and artifacts. Loaded on selection, refreshed while the build runs.
 */
class BuildDetailsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val title = JBLabel().apply { border = JBUI.Borders.empty(4, 6) }
    private val inputBanner = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
        background = JBUI.CurrentTheme.Banner.WARNING_BACKGROUND
        border = JBUI.Borders.customLine(JBUI.CurrentTheme.Banner.WARNING_BORDER_COLOR, 1, 0, 1, 0)
        isVisible = false
    }
    private val tabs = JBTabbedPane()
    private val stages = DefaultListModel<Stage>()
    private val tests = DefaultListModel<FailedTest>()
    private val changes = DefaultListModel<Change>()
    private val artifacts = DefaultListModel<Artifact>()
    private val stageList = JBList(stages)
    private val testList = JBList(tests)
    private val changeList = JBList(changes)
    private val artifactList = JBList(artifacts)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    @Volatile
    private var current: TrackedBuild? = null
    private var generation = 0

    init {
        border = JBUI.Borders.customLineTop(JBUI.CurrentTheme.ToolWindow.borderColor())
        add(JPanel(BorderLayout()).apply {
            add(title, BorderLayout.NORTH)
            add(inputBanner, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        tabs.addTab(JenkSpotterBundle.message("details.stages"), JBScrollPane(stageList))
        tabs.addTab(JenkSpotterBundle.message("details.tests"), JBScrollPane(testList))
        tabs.addTab(JenkSpotterBundle.message("details.changes"), JBScrollPane(changeList))
        tabs.addTab(JenkSpotterBundle.message("details.artifacts"), JBScrollPane(artifactList))
        add(tabs, BorderLayout.CENTER)

        stageList.cellRenderer = StageRenderer()
        testList.cellRenderer = TestRenderer()
        changeList.cellRenderer = ChangeRenderer()
        artifactList.cellRenderer = ArtifactRenderer()
        stageList.emptyText.text = JenkSpotterBundle.message("details.noStages")
        testList.emptyText.text = JenkSpotterBundle.message("details.noTests")
        changeList.emptyText.text = JenkSpotterBundle.message("details.noChanges")
        artifactList.emptyText.text = JenkSpotterBundle.message("details.noArtifacts")

        onDoubleClick(stageList) { stage -> current?.let { LogConsoleManager.getInstance(project).open(it, "[Pipeline] { (${stage.name})") } }
        onDoubleClick(testList) { navigateToTest(it, run = false) }
        onDoubleClick(changeList) { current?.let { BuildActions.openInBrowser(it.build.url + "changes") } }
        onDoubleClick(artifactList) { download(it) }
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
        show(null)
    }

    fun show(build: TrackedBuild?) {
        val same = build?.key == current?.key
        current = build
        isVisible = build != null
        if (build == null) return
        title.text = "<html><b>${StringUtil.escapeXmlEntities(build.build.displayName)}</b> - ${JenkSpotterBundle.message("result.${build.build.result.name}")}</html>"
        title.icon = JenkSpotterIcons.forResult(build.build.result)
        updateInputBanner()
        if (!same) {
            stages.clear(); tests.clear(); changes.clear(); artifacts.clear()
            load(build)
        }
    }

    /** Called with every monitor snapshot: keeps the banner and running builds up to date. */
    fun refresh(updated: TrackedBuild?) {
        val previous = current ?: return
        if (updated == null || updated.key != previous.key) return
        val finishedNow = previous.build.building && !updated.build.building
        current = updated
        title.text = "<html><b>${StringUtil.escapeXmlEntities(updated.build.displayName)}</b> - ${JenkSpotterBundle.message("result.${updated.build.result.name}")}</html>"
        title.icon = JenkSpotterIcons.forResult(updated.build.result)
        updateInputBanner()
        if (finishedNow) load(updated)
    }

    private fun updateInputBanner() {
        val build = current ?: return
        val input = BuildMonitorService.getInstance().snapshot.waitingInput[build.key]
        inputBanner.removeAll()
        inputBanner.isVisible = input != null && build.build.building
        if (input != null) {
            inputBanner.add(JBLabel(JenkSpotterBundle.message("details.input", input.message), AllIcons.General.BalloonWarning, JBLabel.LEFT))
            inputBanner.add(JButton(input.proceedText).apply { addActionListener { BuildActions.proceedInput(project, build, input) } })
            inputBanner.add(JButton(JenkSpotterBundle.message("input.abort")).apply { addActionListener { BuildActions.abortInput(project, build, input) } })
        }
        inputBanner.revalidate()
        inputBanner.repaint()
    }

    private fun load(build: TrackedBuild) {
        val token = ++generation
        alarm.cancelAllRequests()
        val modality = ModalityState.stateForComponent(this)
        alarm.addRequest({
            val client = JenkinsConnections.getInstance().client(build.serverId) ?: return@addRequest
            val stageResult = if (build.build.isPipeline) runCatching { client.stages(build.build.url) }.getOrNull() else null
            val testResult = if (build.build.tests != null || !build.build.building) runCatching { client.failedTests(build.build.url) }.getOrNull() else null
            val details = runCatching { client.details(build.build.url) }.getOrElse { BuildDetails(emptyList(), emptyList()) }
            ApplicationManager.getApplication().invokeLater({
                if (token != generation || current?.key != build.key) return@invokeLater
                replace(stages, stageResult.orEmpty())
                replace(tests, testResult.orEmpty())
                replace(changes, details.changes)
                replace(artifacts, details.artifacts)
                tabs.setTitleAt(0, JenkSpotterBundle.message("details.stages") + count(stageResult?.size))
                tabs.setTitleAt(1, JenkSpotterBundle.message("details.tests") + count(testResult?.size))
                tabs.setTitleAt(2, JenkSpotterBundle.message("details.changes") + count(details.changes.size))
                tabs.setTitleAt(3, JenkSpotterBundle.message("details.artifacts") + count(details.artifacts.size))
                stageList.emptyText.text = JenkSpotterBundle.message(if (build.build.isPipeline) "details.noStages" else "details.notPipeline")
                // Running builds: refresh stages (and the rest) every few seconds.
                if (current?.build?.building == true) alarm.addRequest({ current?.let(::load) }, RUNNING_REFRESH_MILLIS)
            }, modality)
        }, 0)
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
        val file: VirtualFile? = candidates.asSequence().flatMap { FilenameIndex.getVirtualFilesByName(it, scope).asSequence() }.firstOrNull()
        if (file == null) {
            BuildActions.notify(project, JenkSpotterBundle.message("details.test.notFound", test.className), NotificationType.WARNING)
            return
        }
        val text = String(file.contentsToByteArray(), file.charset)
        val method = test.name.substringBefore('(').substringBefore('[').trim()
        val offset = Regex("\\b${Regex.escape(method)}\\b").find(text)?.range?.first ?: 0
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            .openTextEditor(OpenFileDescriptor(project, file, offset), true) ?: return
        if (run) {
            val action = ActionManager.getInstance().getAction("RunClass") ?: return
            ActionManager.getInstance().tryToExecute(action, null, editor.contentComponent, ActionPlaces.EDITOR_POPUP, true)
        }
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

    private class StageRenderer : ColoredListCellRenderer<Stage>() {
        override fun customizeCellRenderer(list: JList<out Stage>, value: Stage, index: Int, selected: Boolean, hasFocus: Boolean) {
            icon = if (value.waitingForInput) AllIcons.General.BalloonWarning else JenkSpotterIcons.forResult(value.result)
            append(value.name)
            val duration = StringUtil.formatDuration(value.durationMillis)
            append("   $duration", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (value.waitingForInput) append("   " + JenkSpotterBundle.message("details.stage.waiting"), SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.ORANGE))
        }
    }

    private class TestRenderer : ColoredListCellRenderer<FailedTest>() {
        override fun customizeCellRenderer(list: JList<out FailedTest>, value: FailedTest, index: Int, selected: Boolean, hasFocus: Boolean) {
            icon = AllIcons.RunConfigurations.TestFailed
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
        private const val RUNNING_REFRESH_MILLIS = 5000
    }
}
