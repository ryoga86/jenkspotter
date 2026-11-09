package de.pdenis.jenkspotter

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon
import de.pdenis.jenkspotter.api.BuildResult
import javax.swing.Icon

object JenkSpotterIcons {
    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/toolWindow.svg", JenkSpotterIcons::class.java)

    // Status bar: the lightning scar shows the overall state.
    @JvmField
    val StatusIdle: Icon = IconLoader.getIcon("/icons/statusIdle.svg", JenkSpotterIcons::class.java)

    @JvmField
    val StatusRunning: Icon = IconLoader.getIcon("/icons/statusRunning.svg", JenkSpotterIcons::class.java)

    @JvmField
    val StatusFailed: Icon = IconLoader.getIcon("/icons/statusFailed.svg", JenkSpotterIcons::class.java)

    @JvmField
    val StatusSuccess: Icon = IconLoader.getIcon("/icons/statusSuccess.svg", JenkSpotterIcons::class.java)

    @JvmField
    val StatusOffline: Icon = IconLoader.getIcon("/icons/statusOffline.svg", JenkSpotterIcons::class.java)

    fun forResult(result: BuildResult): Icon = when (result) {
        BuildResult.SUCCESS -> AllIcons.RunConfigurations.TestPassed
        BuildResult.FAILURE -> AllIcons.RunConfigurations.TestFailed
        BuildResult.UNSTABLE -> AllIcons.General.Warning
        BuildResult.ABORTED, BuildResult.NOT_BUILT -> AllIcons.RunConfigurations.TestIgnored
        BuildResult.RUNNING -> AnimatedIcon.Default.INSTANCE
        BuildResult.UNKNOWN -> AllIcons.RunConfigurations.TestUnknown
    }

    val Queued: Icon get() = AllIcons.Vcs.History
    val Expected: Icon get() = AllIcons.Actions.Forward
    val Folder: Icon get() = AllIcons.Nodes.Folder
    val Job: Icon get() = AllIcons.Actions.Execute

    @JvmField
    val JobWithParameters: Icon = IconLoader.getIcon("/icons/jobParams.svg", JenkSpotterIcons::class.java)
    val Pinned: Icon get() = AllIcons.Nodes.Favorite
}
