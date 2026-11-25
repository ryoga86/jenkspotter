package de.pdenis.jenkspotter.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.api.JenkinsException
import de.pdenis.jenkspotter.api.JenkinsPaths
import de.pdenis.jenkspotter.core.BuildMonitorService
import de.pdenis.jenkspotter.core.JenkinsConnections
import de.pdenis.jenkspotter.core.TrackedBuild
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens build logs as closable tabs in the JenkSpotter tool window and tails them while running. */
@Service(Service.Level.PROJECT)
class LogConsoleManager(private val project: Project, private val cs: CoroutineScope) {

    /** Opens the log; with [scrollTo] the view jumps to the first line containing that text (e.g. a stage). */
    fun open(build: TrackedBuild, scrollTo: String? = null) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        val contentManager = toolWindow.contentManager
        val tabName = "#${build.build.number} ${build.build.jobFullName.substringAfterLast('/').let(JenkinsPaths::displayName)}"
        contentManager.contents.firstOrNull { it.getUserData(BUILD_URL_KEY) == build.build.url }?.let {
            contentManager.setSelectedContent(it)
            toolWindow.activate(null)
            if (scrollTo != null) it.getUserData(SCROLLER_KEY)?.invoke(scrollTo)
            return
        }

        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val panel = SimpleToolWindowPanel(false, true)
        panel.setContent(console.component)
        val errors = LogErrorNavigator(console)
        val actions = DefaultActionGroup().apply {
            addAll(errors.actions())
            addSeparator()
            addAll(*console.createConsoleActions())
            addSeparator()
            add(object : DumbAwareAction(JenkSpotterBundle.message("action.openInBrowser"), null, AllIcons.General.Web) {
                override fun actionPerformed(e: AnActionEvent) = BuildActions.openInBrowser(build.build.url + "console")
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(object : DumbAwareAction(JenkSpotterBundle.message("action.abort"), null, AllIcons.Actions.Suspend) {
                override fun actionPerformed(e: AnActionEvent) = BuildActions.abort(project, current(build))
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = current(build).build.building
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("JenkSpotter.Log", actions, false)
        toolbar.targetComponent = console.component
        panel.toolbar = toolbar.component

        val content = contentManager.factory.createContent(panel, tabName, false).apply {
            isCloseable = true
            putUserData(BUILD_URL_KEY, build.build.url)
            setDisposer(console)
        }
        contentManager.addContent(content)
        contentManager.setSelectedContent(content)
        toolWindow.activate(null)

        val text = StringBuilder()
        var pendingScroll = scrollTo
        val scroll: (String) -> Unit = { target ->
            val offset = text.indexOf(target)
            if (offset >= 0) {
                console.scrollTo(offset)
                pendingScroll = null
            } else {
                pendingScroll = target
            }
        }
        content.putUserData(SCROLLER_KEY, scroll)
        val job = cs.launch {
            tail(build, console, errors) { part ->
                text.append(part)
                pendingScroll?.let(scroll)
            }
        }
        Disposer.register(content) { job.cancel() }
    }

    private fun current(build: TrackedBuild): TrackedBuild =
        BuildMonitorService.getInstance().snapshot.builds.firstOrNull { it.key == build.key } ?: build

    private suspend fun tail(build: TrackedBuild, console: ConsoleView, errors: LogErrorNavigator, printed: (String) -> Unit) {
        val decoder = AnsiEscapeDecoder()
        var offset = 0L
        try {
            val client = withContext(Dispatchers.IO) { JenkinsConnections.getInstance().client(build.serverId) }
                ?: throw JenkinsException(JenkSpotterBundle.message("error.noToken"))
            while (true) {
                val chunk = withContext(Dispatchers.IO) { client.progressiveLog(build.build.url, offset) }
                offset = chunk.nextOffset
                val text = stripConsoleNotes(chunk.text)
                if (text.isNotEmpty()) {
                    withContext(Dispatchers.EDT) {
                        decoder.escapeText(text, ProcessOutputTypes.STDOUT) { part, key ->
                            console.print(part, ConsoleViewContentType.getConsoleViewType(key))
                            printed(part)
                        }
                        errors.scan()
                    }
                }
                if (!chunk.hasMore) break
                delay(POLL_MILLIS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.EDT) {
                console.print("\n[JenkSpotter] ${e.message ?: e.javaClass.simpleName}\n", ConsoleViewContentType.ERROR_OUTPUT)
            }
        }
    }

    companion object {
        const val TOOL_WINDOW_ID = "JenkSpotter"
        private const val POLL_MILLIS = 2000L
        private val BUILD_URL_KEY = com.intellij.openapi.util.Key.create<String>("JenkSpotter.buildUrl")
        private val SCROLLER_KEY = com.intellij.openapi.util.Key.create<(String) -> Unit>("JenkSpotter.scroller")

        fun stripConsoleNotes(text: String): String = de.pdenis.jenkspotter.core.LogErrors.stripConsoleNotes(text)

        fun getInstance(project: Project): LogConsoleManager = project.service()
    }
}
