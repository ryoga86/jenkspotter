package de.pdenis.jenkspotter

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon
import de.pdenis.jenkspotter.api.BuildResult
import javax.swing.Icon

object JenkSpotterIcons {
    // Status bar: the colour of the butler's bow tie shows the overall state.
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
        BuildResult.FAILURE -> AllIcons.RunConfigurations.TestError
        BuildResult.UNSTABLE -> AllIcons.General.Warning
        BuildResult.ABORTED, BuildResult.NOT_BUILT -> AllIcons.RunConfigurations.TestIgnored
        BuildResult.RUNNING -> AnimatedIcon.Default.INSTANCE
        BuildResult.UNKNOWN -> AllIcons.RunConfigurations.TestUnknown
    }

    val Queued: Icon get() = AllIcons.Vcs.History
    val Expected: Icon get() = AllIcons.Actions.Forward
    val Folder: Icon get() = AllIcons.Nodes.Folder
    /** Folder with a branch: a multibranch pipeline, told apart from its branch jobs ([JobType.BRANCH]). */
    @JvmField
    val MultiBranch: Icon = IconLoader.getIcon("/icons/multiBranch.svg", JenkSpotterIcons::class.java)
    val Job: Icon get() = AllIcons.Actions.Execute

    /** Three blue lines for the corner of a job icon: the job takes parameters. */
    @JvmField
    val ParamsBadge: Icon = IconLoader.getIcon("/icons/paramsBadge.svg", JenkSpotterIcons::class.java)

    /** Kind of a Jenkins job, told apart by its icon in the Jenkins view. */
    enum class JobType(val key: String) { FREESTYLE("jobs.type.freestyle"), PIPELINE("jobs.type.pipeline"), BRANCH("jobs.type.branch"),
        MATRIX("jobs.type.matrix"), MULTIBRANCH("jobs.type.multibranch"), ORGANIZATION("jobs.type.organization"), FOLDER("jobs.type.folder") }

    /** [inMultiBranch]: the job is a branch of a multibranch pipeline. */
    fun jobType(job: de.pdenis.jenkspotter.api.JobRef, inMultiBranch: Boolean): JobType = when {
        job.jobClass.contains("OrganizationFolder") -> JobType.ORGANIZATION
        job.isMultiBranch -> JobType.MULTIBRANCH
        job.isFolder -> JobType.FOLDER
        inMultiBranch -> JobType.BRANCH
        job.jobClass.contains("Workflow") || job.jobClass.contains("Pipeline") -> JobType.PIPELINE
        job.jobClass.contains("Matrix") -> JobType.MATRIX
        else -> JobType.FREESTYLE
    }

    /** Icon of a job type; parameterized jobs get a small badge on top. */
    fun job(type: JobType, parameterized: Boolean): Icon {
        val base = when (type) {
            JobType.FREESTYLE -> return if (parameterized) JobWithParameters else Job
            JobType.PIPELINE -> AllIcons.Actions.RunAll
            JobType.BRANCH -> AllIcons.Vcs.BranchNode
            JobType.MATRIX -> AllIcons.Nodes.DataTables
            JobType.MULTIBRANCH -> MultiBranch
            JobType.ORGANIZATION -> AllIcons.Nodes.ModuleGroup
            JobType.FOLDER -> Folder
        }
        return if (parameterized) com.intellij.ui.LayeredIcon(base, ParamsBadge) else base
    }

    /** Next to a job that triggers follow-up jobs, before their number. */
    val FollowUps: Icon get() = AllIcons.Actions.Forward

    @JvmField
    val JobWithParameters: Icon = IconLoader.getIcon("/icons/jobParams.svg", JenkSpotterIcons::class.java)
    val Pinned: Icon get() = AllIcons.General.Pin_tab
    val Watched: Icon get() = AllIcons.Actions.Preview
}
