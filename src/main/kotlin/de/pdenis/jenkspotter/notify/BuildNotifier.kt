package de.pdenis.jenkspotter.notify

import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.SystemNotifications
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.api.BuildResult
import de.pdenis.jenkspotter.api.JenkinsPaths
import de.pdenis.jenkspotter.core.BuildMonitorListener
import de.pdenis.jenkspotter.core.TrackedBuild
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import de.pdenis.jenkspotter.settings.JenkSpotterState
import de.pdenis.jenkspotter.settings.NotifyScope
import de.pdenis.jenkspotter.ui.BuildActions
import de.pdenis.jenkspotter.ui.LogConsoleManager
import javax.swing.Timer

/**
 * Registered declaratively in plugin.xml; turns finished builds into balloons, OS notifications
 * and sounds. Builds finishing close together are collected for a moment and shown as one summary;
 * while a summary balloon is still visible, further builds are merged into it. Balloons can hide
 * themselves after a configurable time - the entry stays in the Notifications tool window.
 */
class BuildNotifier : BuildMonitorListener {
    override fun buildFinished(build: TrackedBuild) {
        val settings = JenkSpotterSettings.getInstance().current
        if (!shouldNotify(settings, build)) return
        ApplicationManager.getApplication().invokeLater { Batcher.add(build) }
    }

    /** Approvals need a human: always a sticky balloon with Proceed / Abort, never grouped or auto-hidden. */
    override fun inputRequested(build: TrackedBuild, input: de.pdenis.jenkspotter.api.PendingInput) {
        val settings = JenkSpotterSettings.getInstance().current
        if (!settings.notifyInput) return
        ApplicationManager.getApplication().invokeLater {
            val job = JenkinsPaths.displayName(build.build.jobFullName)
            val title = JenkSpotterBundle.message("notification.input.title", job, build.build.number)
            val notification = NotificationGroupManager.getInstance().getNotificationGroup("JenkSpotter.Failure")
                .createNotification(title, StringUtil.escapeXmlEntities(input.message), NotificationType.WARNING)
            notification.addAction(NotificationAction.create(input.proceedText) { e, n ->
                val project = e.project ?: Batcher.project() ?: return@create
                BuildActions.proceedInput(project, build, input) { n.expire() }
            })
            notification.addAction(NotificationAction.create(JenkSpotterBundle.message("input.abort")) { e, n ->
                val project = e.project ?: Batcher.project() ?: return@create
                BuildActions.abortInput(project, build, input) { n.expire() }
            })
            notification.addAction(NotificationAction.createSimple(JenkSpotterBundle.message("action.openInBrowser")) {
                BrowserUtil.browse(build.build.url + "input/")
            })
            notification.notify(Batcher.project())
            SoundPlayer.play(BuildResult.UNSTABLE)
            if (settings.systemNotificationWhenInactive && !ApplicationManager.getApplication().isActive) {
                SystemNotifications.getInstance().notify("JenkSpotter", title, input.message)
            }
        }
    }

    private fun shouldNotify(settings: JenkSpotterState, build: TrackedBuild): Boolean {
        if (settings.notifyScope == NotifyScope.OWN_ONLY && !build.mine) return false
        return when (build.build.result) {
            BuildResult.SUCCESS -> settings.notifySuccess
            BuildResult.FAILURE -> settings.notifyFailure
            BuildResult.UNSTABLE -> settings.notifyUnstable
            BuildResult.ABORTED, BuildResult.NOT_BUILT -> settings.notifyAborted
            else -> false
        }
    }

    /** EDT only. */
    private object Batcher {
        private val pending = mutableListOf<TrackedBuild>()
        private var timer: Timer? = null

        /** The visible summary (or single) notification and the builds it shows, for merging. */
        private var shown: Notification? = null
        private var shownBuilds: List<TrackedBuild> = emptyList()

        fun add(build: TrackedBuild) {
            val settings = JenkSpotterSettings.getInstance().current
            pending += build
            if (!settings.groupNotifications) {
                flush()
                return
            }
            if (timer == null) {
                timer = Timer(GROUP_WINDOW_MILLIS) { flush() }.apply { isRepeats = false; start() }
            }
        }

        private fun flush() {
            timer?.stop()
            timer = null
            if (pending.isEmpty()) return
            val settings = JenkSpotterSettings.getInstance().current
            val fresh = pending.toList()
            pending.clear()

            val previous = shown
            val merge = settings.groupNotifications && previous != null && !previous.isExpired &&
                previous.balloon?.isDisposed == false
            val builds = if (merge) (shownBuilds + fresh).distinctBy { it.key } else fresh
            if (merge) previous?.expire()

            val notification = if (builds.size == 1) single(builds.single()) else summary(builds)
            notification.notify(targetProject())
            shown = notification
            shownBuilds = builds
            scheduleHide(notification, builds, settings)

            // Sound and OS notification only for what is new, once per batch.
            val worst = fresh.maxBy { severity(it.build.result) }.build.result
            SoundPlayer.play(worst)
            if (settings.systemNotificationWhenInactive && !ApplicationManager.getApplication().isActive) {
                SystemNotifications.getInstance().notify("JenkSpotter", notification.title, StringUtil.stripHtml(notification.content, " "))
            }
        }

        private fun scheduleHide(notification: Notification, builds: List<TrackedBuild>, settings: JenkSpotterState) {
            val failed = builds.any { severity(it.build.result) >= severity(BuildResult.UNSTABLE) }
            val seconds = if (failed) settings.hideFailureAfterSeconds else settings.hideSuccessAfterSeconds
            if (seconds <= 0) return
            Timer(seconds * 1000) { if (!notification.isExpired) notification.hideBalloon() }
                .apply { isRepeats = false; start() }
        }

        private fun single(build: TrackedBuild): Notification {
            val result = build.build.result
            val job = JenkinsPaths.displayName(build.build.jobFullName)
            val titleKey = when (result) {
                BuildResult.SUCCESS -> "notification.success.title"
                BuildResult.FAILURE -> "notification.failure.title"
                BuildResult.UNSTABLE -> "notification.unstable.title"
                else -> "notification.aborted.title"
            }
            val title = JenkSpotterBundle.message(titleKey, job, build.build.number)
            val content = JenkSpotterBundle.message("notification.content", StringUtil.formatDuration(build.build.durationMillis))
            return create(result, title, content)
                .addAction(action("action.openLog") { project, n -> BuildActions.openLog(project, build); n.expire() })
                .addAction(action("action.rebuild") { project, n -> BuildActions.rebuild(project, build); n.expire() })
                .addAction(NotificationAction.createSimple(JenkSpotterBundle.message("action.openInBrowser")) {
                    BrowserUtil.browse(build.build.url)
                })
        }

        private fun summary(builds: List<TrackedBuild>): Notification {
            val sorted = builds.sortedByDescending { severity(it.build.result) }
            val counts = sorted.groupingBy { it.build.result }.eachCount()
            val parts = listOf(BuildResult.FAILURE, BuildResult.UNSTABLE, BuildResult.ABORTED, BuildResult.NOT_BUILT, BuildResult.SUCCESS)
                .mapNotNull { r -> counts[r]?.let { "$it ${JenkSpotterBundle.message("result.$r").lowercase()}" } }
            val title = JenkSpotterBundle.message("notification.summary.title", builds.size, parts.joinToString(", "))
            val lines = sorted.take(MAX_LINES).joinToString("<br>") { b ->
                val mark = when (b.build.result) {
                    BuildResult.SUCCESS -> "&#10003;"
                    BuildResult.FAILURE -> "&#10007;"
                    BuildResult.UNSTABLE -> "!"
                    else -> "&#8211;"
                }
                "$mark ${StringUtil.escapeXmlEntities(JenkinsPaths.displayName(b.build.jobFullName))} #${b.build.number}"
            }
            val more = if (sorted.size > MAX_LINES) "<br>" + JenkSpotterBundle.message("notification.summary.more", sorted.size - MAX_LINES) else ""
            val worst = sorted.first()
            val notification = create(worst.build.result, title, lines + more)
                .addAction(action("notification.summary.show") { project, _ ->
                    ToolWindowManager.getInstance(project).getToolWindow(LogConsoleManager.TOOL_WINDOW_ID)?.activate(null)
                })
            if (severity(worst.build.result) > severity(BuildResult.SUCCESS)) {
                notification.addAction(action("notification.summary.openWorst") { project, n ->
                    BuildActions.openLog(project, worst); n.expire()
                })
            }
            return notification
        }

        private fun create(result: BuildResult, title: String, content: String): Notification {
            val groupId = if (result == BuildResult.SUCCESS) "JenkSpotter.Success" else "JenkSpotter.Failure"
            val type = when (result) {
                BuildResult.SUCCESS -> NotificationType.INFORMATION
                BuildResult.UNSTABLE, BuildResult.ABORTED, BuildResult.NOT_BUILT -> NotificationType.WARNING
                else -> NotificationType.ERROR
            }
            return NotificationGroupManager.getInstance().getNotificationGroup(groupId).createNotification(title, content, type)
        }

        private fun action(key: String, block: (Project, Notification) -> Unit) =
            NotificationAction.create(JenkSpotterBundle.message(key)) { e, n ->
                val project = e.project ?: targetProject() ?: return@create
                block(project, n)
            }

        fun project(): Project? = targetProject()

        private fun targetProject(): Project? =
            IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project
                ?: ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }

        private fun severity(result: BuildResult) = when (result) {
            BuildResult.FAILURE -> 4
            BuildResult.UNSTABLE -> 3
            BuildResult.ABORTED, BuildResult.NOT_BUILT -> 2
            BuildResult.SUCCESS -> 1
            else -> 0
        }

        private const val GROUP_WINDOW_MILLIS = 3000
        private const val MAX_LINES = 8
    }
}
