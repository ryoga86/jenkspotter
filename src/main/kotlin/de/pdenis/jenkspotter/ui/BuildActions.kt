package de.pdenis.jenkspotter.ui

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.withBackgroundProgress
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.api.BuildForm
import de.pdenis.jenkspotter.api.BuildParameter
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import de.pdenis.jenkspotter.api.JenkinsException
import de.pdenis.jenkspotter.api.JenkinsPaths
import de.pdenis.jenkspotter.core.BuildMonitorService
import de.pdenis.jenkspotter.core.JenkinsConnections
import de.pdenis.jenkspotter.core.TrackedBuild
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Service(Service.Level.PROJECT)
class JenkSpotterProjectScope(val cs: CoroutineScope) {
    companion object {
        fun of(project: Project): CoroutineScope = project.service<JenkSpotterProjectScope>().cs
    }
}

/** User-triggered operations shared by the tool window, notifications and actions. */
object BuildActions {
    fun openInBrowser(url: String) = BrowserUtil.browse(url)

    fun openLog(project: Project, build: TrackedBuild) = LogConsoleManager.getInstance(project).open(build)

    fun rebuild(project: Project, build: TrackedBuild) = trigger(
        project, build.serverId, build.build.jobUrl, build.build.jobFullName, build.build.parameters,
    )

    fun abort(project: Project, build: TrackedBuild) {
        val name = "${JenkinsPaths.displayName(build.build.jobFullName)} #${build.build.number}"
        val confirmed = Messages.showYesNoDialog(
            project,
            JenkSpotterBundle.message("dialog.abort.message", name),
            JenkSpotterBundle.message("dialog.abort.title"),
            Messages.getQuestionIcon(),
        ) == Messages.YES
        if (!confirmed) return
        JenkSpotterProjectScope.of(project).launch {
            runReporting(project) {
                val client = clientFor(build.serverId)
                withContext(Dispatchers.IO) { client.stop(build.build.url) }
                BuildMonitorService.getInstance().refresh(build.serverId, includeSlow = false)
            }
        }
    }

    /**
     * Shows the parameter dialog (also for jobs without parameters, as a confirmation) and
     * queues the build. [previous] pre-fills the form for rebuilds.
     */
    fun trigger(project: Project, serverId: String, jobUrl: String, fullName: String, previous: List<BuildParameter>?) {
        JenkSpotterProjectScope.of(project).launch {
            runReporting(project) {
                val client = clientFor(serverId)
                val settings = JenkSpotterSettings.getInstance()
                val (definitions, form) = withBackgroundProgress(project, JenkSpotterBundle.message("progress.loadingParameters")) {
                    withContext(Dispatchers.IO) {
                        val defs = client.parameterDefinitions(jobUrl)
                        // The HTML form is only needed (and loaded) for types the JSON API cannot describe.
                        defs to if (defs.any { it.needsForm }) client.buildForm(jobUrl) else BuildForm.EMPTY
                    }
                }
                val initial = previous?.mapNotNull { p -> p.value?.let { p.name to it } }?.toMap()
                    ?: settings.rememberedParameters(serverId, jobUrl)
                val dialog = withContext(Dispatchers.EDT) {
                    TriggerBuildDialog(
                        project, JenkinsPaths.displayName(fullName), definitions, initial, form,
                        cascade = { name, referenced -> client.cascadeChoices(form, name, referenced) },
                        rememberByDefault = settings.current.rememberParameters,
                    ).takeIf { it.showAndGet() }
                } ?: return@runReporting
                val values = dialog.values()
                if (definitions.isNotEmpty()) {
                    settings.updateView { rememberParameters = dialog.rememberValues }
                    if (dialog.rememberValues) settings.rememberParameters(serverId, jobUrl, dialog.rememberableValues())
                }
                val queueUrl = withContext(Dispatchers.IO) {
                    client.trigger(jobUrl, if (definitions.isEmpty()) null else values)
                }
                settings.markRecent(serverId, jobUrl)
                BuildMonitorService.getInstance().trackQueued(serverId, queueUrl, fullName, jobUrl)
                notify(project, JenkSpotterBundle.message("notification.queued", JenkinsPaths.displayName(fullName)), NotificationType.INFORMATION)
            }
        }
    }

    /** Opens a build in the Jenkins UI chosen in the settings (classic, Blue Ocean, Pipeline Graph). */
    fun openBuild(build: de.pdenis.jenkspotter.api.BuildInfo) =
        openInBrowser(JenkinsUi.buildUrl(build, JenkSpotterSettings.getInstance().current.browserUi))

    fun proceedInput(project: Project, build: TrackedBuild, input: de.pdenis.jenkspotter.api.PendingInput, onDone: () -> Unit = {}) {
        val values = if (input.parameters.isEmpty()) {
            val ok = Messages.showYesNoDialog(project, input.message, JenkSpotterBundle.message("input.title", JenkinsPaths.displayName(build.build.jobFullName), build.build.number), input.proceedText, JenkSpotterBundle.message("input.abort"), Messages.getQuestionIcon())
            if (ok != Messages.YES) {
                if (ok == Messages.NO) abortInput(project, build, input, onDone)
                return
            }
            emptyMap()
        } else {
            val definitions = input.parameters.map { p ->
                de.pdenis.jenkspotter.api.ParameterDefinition(
                    p.name, if (p.choices.isNotEmpty()) de.pdenis.jenkspotter.api.ParameterType.CHOICE else if (p.type.contains("Boolean", true)) de.pdenis.jenkspotter.api.ParameterType.BOOLEAN else de.pdenis.jenkspotter.api.ParameterType.STRING,
                    p.type, p.description, p.defaultValue, p.choices,
                )
            }
            val dialog = TriggerBuildDialog(project, input.message, definitions, emptyMap(), rememberByDefault = false, titleText = input.message, okText = input.proceedText)
            if (!dialog.showAndGet()) return
            dialog.values()
        }
        JenkSpotterProjectScope.of(project).launch {
            runReporting(project) {
                val client = clientFor(build.serverId)
                withContext(Dispatchers.IO) { client.proceedInput(build.build.url, input.id, values) }
                BuildMonitorService.getInstance().inputHandled(build)
                withContext(Dispatchers.EDT) { onDone() }
            }
        }
    }

    fun abortInput(project: Project, build: TrackedBuild, input: de.pdenis.jenkspotter.api.PendingInput, onDone: () -> Unit = {}) {
        JenkSpotterProjectScope.of(project).launch {
            runReporting(project) {
                val client = clientFor(build.serverId)
                withContext(Dispatchers.IO) { client.abortInput(build.build.url, input.id) }
                BuildMonitorService.getInstance().inputHandled(build)
                withContext(Dispatchers.EDT) { onDone() }
            }
        }
    }

    internal suspend fun clientFor(serverId: String) = withContext(Dispatchers.IO) {
        JenkinsConnections.getInstance().client(serverId)
    } ?: throw JenkinsException(JenkSpotterBundle.message("error.noToken"))

    internal suspend fun runReporting(project: Project, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: JenkinsException) {
            notify(project, e.message ?: e.javaClass.simpleName, NotificationType.ERROR)
        }
    }

    fun notify(project: Project?, text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("JenkSpotter.Info")
            .createNotification(text, type)
            .notify(project)
    }
}
