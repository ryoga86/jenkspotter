package de.pdenis.jenkspotter

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon
import de.pdenis.jenkspotter.api.BuildResult
import de.pdenis.jenkspotter.settings.ChainStepIcon
import de.pdenis.jenkspotter.settings.FollowUpIcon
import de.pdenis.jenkspotter.settings.JobIcon
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

    /** Three blue lines for the corner of a job icon: the job takes parameters. */
    @JvmField
    val ParamsBadge: Icon = IconLoader.getIcon("/icons/paramsBadge.svg", JenkSpotterIcons::class.java)

    /** Icon of a job in the Jenkins view, see [JobIcon]; null for [JobIcon.NONE]. */
    fun job(style: JobIcon, parameterized: Boolean): Icon? {
        val base = when (style) {
            JobIcon.PLAY -> return if (parameterized) JobWithParameters else Job
            JobIcon.RESUME -> AllIcons.Actions.Resume
            JobIcon.RUN_ALL -> AllIcons.Actions.RunAll
            JobIcon.RERUN -> AllIcons.Actions.Rerun
            JobIcon.APPLICATION -> AllIcons.RunConfigurations.Application
            JobIcon.COMPOUND -> AllIcons.RunConfigurations.Compound
            JobIcon.WAND -> AllIcons.Diff.MagicResolve
            JobIcon.LEARN -> AllIcons.Actions.Learn
            JobIcon.GIFT -> AllIcons.Ide.Gift
            JobIcon.BULB -> AllIcons.Actions.IntentionBulb
            JobIcon.STAR -> AllIcons.Ide.Rating
            JobIcon.LIGHTNING -> AllIcons.Actions.Lightning
            JobIcon.RECORD -> AllIcons.Profiler.Rec
            JobIcon.HAMMER -> AllIcons.Actions.Compile
            JobIcon.BUILD -> AllIcons.Toolwindows.ToolWindowBuild
            JobIcon.GEAR -> AllIcons.General.GearPlain
            JobIcon.TOOLBOX -> AllIcons.Nodes.Toolbox
            JobIcon.TOOLS -> AllIcons.General.ExternalTools
            JobIcon.STOPWATCH -> AllIcons.Actions.StopWatch
            JobIcon.TARGET -> AllIcons.Nodes.Target
            JobIcon.TASK -> AllIcons.Toolwindows.Task
            JobIcon.PACKAGE -> AllIcons.Nodes.Package
            JobIcon.ARCHIVE -> AllIcons.FileTypes.Archive
            JobIcon.DEPLOY -> AllIcons.Nodes.Deploy
            JobIcon.UPLOAD -> AllIcons.Actions.Upload
            JobIcon.PUSH -> AllIcons.Vcs.Push
            JobIcon.SERVER -> AllIcons.Toolwindows.ToolWindowServer
            JobIcon.GLOBE -> AllIcons.General.Web
            JobIcon.PLUGIN -> AllIcons.Nodes.Plugin
            JobIcon.BRANCH -> AllIcons.Vcs.Branch
            JobIcon.MERGE -> AllIcons.Vcs.Merge
            JobIcon.CONSOLE -> AllIcons.Nodes.Console
            JobIcon.CONFIG -> AllIcons.FileTypes.Config
            JobIcon.TAG -> AllIcons.Nodes.Tag
            JobIcon.NONE -> return if (parameterized) ParamsBadge else null
        }
        return if (parameterized) com.intellij.ui.LayeredIcon(base, ParamsBadge) else base
    }

    /** Icon in front of the follow-up count; null for [FollowUpIcon.ARROW], which is drawn as text. */
    fun followUp(style: FollowUpIcon): Icon? = when (style) {
        FollowUpIcon.ARROW -> null
        FollowUpIcon.FORWARD -> AllIcons.Actions.Forward
        FollowUpIcon.NEXT_STEP -> AllIcons.General.ChevronRight
        FollowUpIcon.FAST_FORWARD -> AllIcons.Actions.Play_forward
        FollowUpIcon.STEP_INTO -> AllIcons.Actions.TraceInto
        FollowUpIcon.LINK -> AllIcons.Ide.Link
        FollowUpIcon.HIERARCHY -> AllIcons.Toolwindows.ToolWindowHierarchy
        FollowUpIcon.TREE -> AllIcons.Actions.ShowAsTree
        FollowUpIcon.DEPENDENCIES -> AllIcons.Toolwindows.Dependencies
        FollowUpIcon.BRANCH -> AllIcons.Vcs.Branch
    }

    /** Icon after the step number of a triggered job; null for [ChainStepIcon.TEXT]. */
    fun chainStep(style: ChainStepIcon, followUpStyle: FollowUpIcon): Icon? = when (style) {
        ChainStepIcon.SAME -> followUp(followUpStyle) ?: AllIcons.Actions.Forward
        ChainStepIcon.TEXT -> null
        ChainStepIcon.LINK -> AllIcons.Ide.Link
        ChainStepIcon.BACK -> AllIcons.Actions.Back
        ChainStepIcon.STEP_OUT -> AllIcons.Actions.StepOut
        ChainStepIcon.CHEVRON -> AllIcons.General.ChevronLeft
        ChainStepIcon.HIERARCHY -> AllIcons.Toolwindows.ToolWindowHierarchy
        ChainStepIcon.TREE -> AllIcons.Actions.ShowAsTree
        ChainStepIcon.DEPENDENCIES -> AllIcons.Toolwindows.Dependencies
        ChainStepIcon.MERGE -> AllIcons.Vcs.Merge
        ChainStepIcon.TRIGGERED -> AllIcons.Actions.Lightning
    }

    @JvmField
    val JobWithParameters: Icon = IconLoader.getIcon("/icons/jobParams.svg", JenkSpotterIcons::class.java)
    val Pinned: Icon get() = AllIcons.General.Pin_tab
    val Watched: Icon get() = AllIcons.Actions.Preview
}
