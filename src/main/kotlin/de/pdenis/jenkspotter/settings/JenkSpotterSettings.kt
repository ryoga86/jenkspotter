package de.pdenis.jenkspotter.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic
import java.util.UUID

/** One configured Jenkins instance. The API token is kept in the PasswordSafe, never here. */
class ServerConfig {
    var id: String = UUID.randomUUID().toString()
    /** Disabled servers keep their configuration but are neither polled nor shown. */
    var enabled: Boolean = true
    var name: String = ""
    var url: String = ""
    var username: String = ""
    var activePollSeconds: Int = 5
    /** How often to look for own builds (queue + executors) while nothing is running. */
    var discoveryPollSeconds: Int = 10
    var idlePollSeconds: Int = 30
    var trackOwnBuilds: Boolean = true
    var views: MutableList<String> = mutableListOf()

    val displayName: String get() = name.ifBlank { url.removePrefix("https://").removePrefix("http://").trimEnd('/') }

    fun copy(): ServerConfig = ServerConfig().also {
        it.id = id; it.enabled = enabled; it.name = name; it.url = url; it.username = username
        it.activePollSeconds = activePollSeconds; it.discoveryPollSeconds = discoveryPollSeconds
        it.idlePollSeconds = idlePollSeconds; it.trackOwnBuilds = trackOwnBuilds; it.views = views.toMutableList()
    }

    override fun equals(other: Any?): Boolean = other is ServerConfig &&
        id == other.id && enabled == other.enabled && name == other.name && url == other.url && username == other.username &&
        activePollSeconds == other.activePollSeconds && discoveryPollSeconds == other.discoveryPollSeconds &&
        idlePollSeconds == other.idlePollSeconds && trackOwnBuilds == other.trackOwnBuilds && views == other.views

    override fun hashCode(): Int = id.hashCode()
}

class PinnedJob {
    var serverId: String = ""
    var fullName: String = ""
    var url: String = ""

    fun copy(): PinnedJob = PinnedJob().also { it.serverId = serverId; it.fullName = fullName; it.url = url }
    override fun equals(other: Any?) = other is PinnedJob && serverId == other.serverId && url == other.url
    override fun hashCode() = 31 * serverId.hashCode() + url.hashCode()
}

enum class FilterMode {
    /** Hide jobs/builds whose full name matches. */
    EXCLUDE,

    /** Show only jobs/builds that match at least one enabled INCLUDE rule. */
    INCLUDE,
}

/** A user-defined filter chip working on the full job name (e.g. "team/app/main"). */
class RegexFilter {
    var id: String = UUID.randomUUID().toString()
    var name: String = ""
    var pattern: String = ""
    var enabled: Boolean = true
    var mode: FilterMode = FilterMode.EXCLUDE
    /** Set for the rule maintained by the folder overview: decoded folder paths hidden with their content. */
    var folders: MutableList<String> = mutableListOf()

    val isFolderRule: Boolean get() = folders.isNotEmpty()

    fun copy(): RegexFilter = RegexFilter().also {
        it.id = id; it.name = name; it.pattern = pattern; it.enabled = enabled; it.mode = mode
        it.folders = folders.toMutableList()
    }
    override fun equals(other: Any?) = other is RegexFilter && id == other.id && name == other.name &&
        pattern == other.pattern && enabled == other.enabled && mode == other.mode && folders == other.folders
    override fun hashCode() = id.hashCode()

    val label: String get() = name.ifBlank { pattern }

    fun regexOrNull(): Regex? = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
}

enum class NotifyScope { OWN_ONLY, ALL_VISIBLE }

/** Order of the jobs list. */
enum class JobSort(val key: String) {
    /** Best match while searching, otherwise recently built first. */
    RELEVANCE("jobs.sort.relevance"),
    NAME("jobs.sort.name"),
    /** By full path, so jobs of one folder stay together. */
    PATH("jobs.sort.path"),
    RECENT("jobs.sort.recent"),
}

/** How the Jenkins view (left) arranges jobs of several Jenkins. */
enum class JobLayout(val key: String) {
    /** One collapsible node per Jenkins, its jobs below. */
    TREE("jobs.layout.tree"),
    /** Jenkins nodes, below them collapsible folder nodes with their jobs. */
    TREE_FOLDERS("jobs.layout.treeFolders"),
    /** One list, Jenkins name after the job. */
    FLAT_SUFFIX("jobs.layout.flatSuffix"),
    /** One list, Jenkins name in front of the job. */
    FLAT_PREFIX("jobs.layout.flatPrefix"),
    /** One list without Jenkins names. */
    FLAT("jobs.layout.flat");

    val grouped: Boolean get() = this == TREE || this == TREE_FOLDERS
}

/** Icon in front of the jobs in the Jenkins view; parameterized jobs get a small badge on top. */
enum class JobIcon(val key: String, /** Entries of one group are listed together in the menu. */ val group: Int) {
    PLAY("jobs.icon.play", 1),
    RESUME("jobs.icon.resume", 1),
    RUN_ALL("jobs.icon.runAll", 1),
    RERUN("jobs.icon.rerun", 1),
    APPLICATION("jobs.icon.application", 1),
    COMPOUND("jobs.icon.compound", 1),
    WAND("jobs.icon.wand", 2),
    LEARN("jobs.icon.learn", 2),
    GIFT("jobs.icon.gift", 2),
    BULB("jobs.icon.bulb", 2),
    STAR("jobs.icon.star", 2),
    LIGHTNING("jobs.icon.lightning", 2),
    RECORD("jobs.icon.record", 2),
    HAMMER("jobs.icon.hammer", 3),
    BUILD("jobs.icon.build", 3),
    GEAR("jobs.icon.gear", 3),
    TOOLBOX("jobs.icon.toolbox", 3),
    TOOLS("jobs.icon.tools", 3),
    STOPWATCH("jobs.icon.stopwatch", 3),
    TARGET("jobs.icon.target", 3),
    TASK("jobs.icon.task", 3),
    PACKAGE("jobs.icon.package", 4),
    ARCHIVE("jobs.icon.archive", 4),
    DEPLOY("jobs.icon.deploy", 4),
    UPLOAD("jobs.icon.upload", 4),
    PUSH("jobs.icon.push", 4),
    SERVER("jobs.icon.server", 4),
    GLOBE("jobs.icon.globe", 4),
    PLUGIN("jobs.icon.plugin", 4),
    BRANCH("jobs.icon.branch", 5),
    MERGE("jobs.icon.merge", 5),
    CONSOLE("jobs.icon.console", 5),
    CONFIG("jobs.icon.config", 5),
    TAG("jobs.icon.tag", 5),
    NONE("jobs.icon.none", 6),
}

/** Mark of jobs with follow-up jobs in the Jenkins view, followed by their count. */
enum class FollowUpIcon(val key: String) {
    ARROW("jobs.followUpIcon.arrow"),
    FORWARD("jobs.followUpIcon.forward"),
    NEXT_STEP("jobs.followUpIcon.nextStep"),
    FAST_FORWARD("jobs.followUpIcon.fastForward"),
    STEP_INTO("jobs.followUpIcon.stepInto"),
    LINK("jobs.followUpIcon.link"),
    HIERARCHY("jobs.followUpIcon.hierarchy"),
    TREE("jobs.followUpIcon.tree"),
    DEPENDENCIES("jobs.followUpIcon.dependencies"),
    BRANCH("jobs.followUpIcon.branch"),
}

/** What hides or shows a Jenkins' jobs and builds, next to its light at the bottom of the Jenkins view. */
enum class VisibilityToggle(val key: String) {
    EYE("jenkins.toggle.eye"),
    EYE_CROSSED("jenkins.toggle.eyeCrossed"),
    SMALL_EYE("jenkins.toggle.smallEye"),
    SHOW("jenkins.toggle.show"),
    CHECKMARK("jenkins.toggle.checkmark"),
    BULB("jenkins.toggle.bulb"),
    TRAFFIC("jenkins.toggle.traffic"),
    VISIBILITY("jenkins.toggle.visibility"),
    LOCK("jenkins.toggle.lock"),
    LIKE("jenkins.toggle.like"),
    STAR("jenkins.toggle.star"),
    CHECKBOX("jenkins.toggle.checkbox"),
    NONE("jenkins.toggle.none"),
}

/** How the Jenkins view shows which jobs trigger which (freestyle "Build other projects"). */
enum class ChainDisplay(val key: String) {
    /** Step number and follow-up count with icons, see [ChainStepIcon] and [FollowUpIcon]. */
    BADGES("jobs.chainDisplay.badges"),
    /** No marks; the selected job's chain is tinted and shown as a clickable path below the list. */
    HIGHLIGHT("jobs.chainDisplay.highlight"),
    /** Jobs of a chain listed together and joined by a line at the left edge. */
    LANE("jobs.chainDisplay.lane"),
    /** Follow-up jobs nested below the job that triggers them, expandable. */
    TREE("jobs.chainDisplay.tree"),
    /** A small arrow in front of triggered jobs and after triggering ones. */
    ARROWS("jobs.chainDisplay.arrows"),
    /** Only in the tooltip. */
    NONE("jobs.chainDisplay.none"),
}

/** Mark of jobs triggered by other jobs: their step number, then this icon. */
enum class ChainStepIcon(val key: String) {
    SAME("jobs.chainStepIcon.same"),
    TEXT("jobs.chainStepIcon.text"),
    LINK("jobs.chainStepIcon.link"),
    BACK("jobs.chainStepIcon.back"),
    STEP_OUT("jobs.chainStepIcon.stepOut"),
    CHEVRON("jobs.chainStepIcon.chevron"),
    HIERARCHY("jobs.chainStepIcon.hierarchy"),
    TREE("jobs.chainStepIcon.tree"),
    DEPENDENCIES("jobs.chainStepIcon.dependencies"),
    MERGE("jobs.chainStepIcon.merge"),
    TRIGGERED("jobs.chainStepIcon.triggered"),
}

/** States the builds table can be filtered by; queued and expected rows count as [QUEUED]. */
enum class BuildStatusFilter(val key: String) {
    RUNNING("builds.status.running"),
    SUCCESS("builds.status.success"),
    FAILURE("builds.status.failure"),
    UNSTABLE("builds.status.unstable"),
    ABORTED("builds.status.aborted"),
    QUEUED("builds.status.queued"),
}

/** Which Jenkins page "Open in Browser" uses for builds. */
enum class BrowserUi { CLASSIC, BLUE_OCEAN, PIPELINE_GRAPH }

/** Optional columns of the builds table; the job column is always shown first. */
enum class BuildColumn(val key: String, val preferredText: String) {
    STATUS("column.status", "Unstable"),
    STARTED("column.started", "Yesterday 12:00"),
    DURATION("column.duration", "12 m 30 s / ~15 m"),
    SOURCE("column.source", "Mine, Pinned"),
    SERVER("column.server", "Jenkins Cloud"),
    PARAMETERS("column.parameters", "BRANCH=feature/x, DRY=true"),
    TESTS("column.tests", "3 failed / 1200"),
    DESCRIPTION("column.description", "Deployed to staging");

    companion object {
        // Status, source, parameters etc. are shown in the details below the table.
        val DEFAULT = listOf(STARTED, DURATION)

        /** "SOURCE,STATUS" -> [SOURCE, STATUS]; unknown or repeated names are ignored. */
        fun parse(text: String): List<BuildColumn> =
            text.split(',').mapNotNull { n -> entries.firstOrNull { it.name.equals(n.trim(), true) } }.distinct()

        fun format(columns: List<BuildColumn>) = columns.joinToString(",") { it.name }
    }
}

class JenkSpotterState {
    var servers: MutableList<ServerConfig> = mutableListOf()

    // Edited live from the tool window, not on the settings page.
    var pinnedJobs: MutableList<PinnedJob> = mutableListOf()
    /** Jobs whose builds are tracked and notified about without being favourites in the jobs list. */
    var watchedJobs: MutableList<PinnedJob> = mutableListOf()
    var filters: MutableList<RegexFilter> = mutableListOf()
    /** Most recently built jobs first, entries are "serverId url". */
    var recentJobs: MutableList<String> = mutableListOf()
    /** Last used build parameters per job ("serverId url" -> encoded values, passwords never stored). */
    var rememberedParameters: MutableMap<String, String> = mutableMapOf()
    var rememberParameters: Boolean = true
    var notifyInput: Boolean = true
    var browserUi: BrowserUi = BrowserUi.CLASSIC

    // View options of the tool window, switched from its "Show" menus.
    /** Visible optional columns of the builds table in display order, see [BuildColumn.parse]. */
    var buildColumns: String = BuildColumn.format(BuildColumn.DEFAULT)
    /** User-chosen column widths in px at 100% scale, "STATUS=90,SOURCE=120". */
    var buildColumnWidths: String = ""
    var showJobPath: Boolean = true
    var showFolderEntries: Boolean = true
    var showDownstream: Boolean = true
    /** "Step 2/4" next to jobs that are triggered by other jobs. */
    var showChainStep: Boolean = true
    var chainStepIcon: ChainStepIcon = ChainStepIcon.SAME
    var chainDisplay: ChainDisplay = ChainDisplay.BADGES
    /** Folder path in front of job names in the builds table. */
    var showBuildJobFolders: Boolean = true
    var jobSort: JobSort = JobSort.RELEVANCE
    var pinnedFirst: Boolean = true
    var jobLayout: JobLayout = JobLayout.TREE
    /** Ids of the servers whose group is collapsed in the jobs list. */
    var collapsedServers: MutableList<String> = mutableListOf()
    /** Collapsed folder nodes of [JobLayout.TREE_FOLDERS], entries are "serverId folderPath". */
    var collapsedFolders: MutableList<String> = mutableListOf()
    /** Build info below the builds table reduced to its title row. */
    var buildInfoCollapsed: Boolean = false
    var jobIcon: JobIcon = JobIcon.PLAY
    var followUpIcon: FollowUpIcon = FollowUpIcon.ARROW
    var visibilityToggle: VisibilityToggle = VisibilityToggle.EYE_CROSSED
    /** List every build currently running on the Jenkins, not only the ones of interest (no notifications). */
    var showAllRunning: Boolean = false
    /** Jenkins view lists only jobs building a Git repository of the open project. */
    var projectJobsOnly: Boolean = false
    /** Ids of the servers hidden with the eye in the Jenkins view: still polled and notified, just not listed. */
    var hiddenServers: MutableList<String> = mutableListOf()
    /** [BuildStatusFilter] names not shown in the builds table. */
    var hiddenStatuses: MutableList<String> = mutableListOf()

    var notifySuccess: Boolean = true
    var notifyFailure: Boolean = true
    var notifyUnstable: Boolean = true
    var notifyAborted: Boolean = true
    var notifyScope: NotifyScope = NotifyScope.OWN_ONLY
    var systemNotificationWhenInactive: Boolean = true
    /** Seconds until the balloon hides itself (entry stays in the Notifications tool window), 0 = never. */
    var hideSuccessAfterSeconds: Int = 8
    var hideFailureAfterSeconds: Int = 0
    /** Builds finishing within a few seconds of each other are shown as one summary notification. */
    var groupNotifications: Boolean = true

    var soundEnabled: Boolean = true
    var soundVolume: Int = 70
    var customSuccessSound: String = ""
    var customFailureSound: String = ""

    var wizardingMode: Boolean = false
    var ownHistoryHours: Int = 24
    var buildsPerPinnedJob: Int = 5
    /** Builds listed by "Show Recent Builds" in the jobs list. */
    var recentBuildsCount: Int = 10

    fun copy(): JenkSpotterState = JenkSpotterState().also {
        it.servers = servers.map(ServerConfig::copy).toMutableList()
        it.pinnedJobs = pinnedJobs.map(PinnedJob::copy).toMutableList()
        it.watchedJobs = watchedJobs.map(PinnedJob::copy).toMutableList()
        it.filters = filters.map(RegexFilter::copy).toMutableList()
        it.recentJobs = recentJobs.toMutableList()
        it.rememberedParameters = rememberedParameters.toMutableMap(); it.rememberParameters = rememberParameters
        it.notifyInput = notifyInput; it.browserUi = browserUi
        it.buildColumns = buildColumns; it.buildColumnWidths = buildColumnWidths; it.showJobPath = showJobPath; it.showFolderEntries = showFolderEntries
        it.showDownstream = showDownstream; it.showChainStep = showChainStep; it.chainStepIcon = chainStepIcon; it.chainDisplay = chainDisplay
        it.showBuildJobFolders = showBuildJobFolders; it.jobSort = jobSort; it.pinnedFirst = pinnedFirst
        it.jobLayout = jobLayout; it.collapsedServers = collapsedServers.toMutableList()
        it.collapsedFolders = collapsedFolders.toMutableList(); it.buildInfoCollapsed = buildInfoCollapsed
        it.jobIcon = jobIcon; it.followUpIcon = followUpIcon; it.visibilityToggle = visibilityToggle; it.showAllRunning = showAllRunning; it.projectJobsOnly = projectJobsOnly; it.hiddenServers = hiddenServers.toMutableList(); it.hiddenStatuses = hiddenStatuses.toMutableList()
        it.notifySuccess = notifySuccess; it.notifyFailure = notifyFailure
        it.notifyUnstable = notifyUnstable; it.notifyAborted = notifyAborted
        it.notifyScope = notifyScope; it.systemNotificationWhenInactive = systemNotificationWhenInactive
        it.hideSuccessAfterSeconds = hideSuccessAfterSeconds; it.hideFailureAfterSeconds = hideFailureAfterSeconds
        it.groupNotifications = groupNotifications
        it.soundEnabled = soundEnabled; it.soundVolume = soundVolume
        it.customSuccessSound = customSuccessSound; it.customFailureSound = customFailureSound
        it.wizardingMode = wizardingMode; it.ownHistoryHours = ownHistoryHours
        it.buildsPerPinnedJob = buildsPerPinnedJob; it.recentBuildsCount = recentBuildsCount
    }

    /** Compares everything that is edited on the settings page (not pins, filters, recents). */
    fun sameSettingsAs(other: JenkSpotterState): Boolean =
        servers == other.servers &&
            notifySuccess == other.notifySuccess && notifyFailure == other.notifyFailure &&
            notifyUnstable == other.notifyUnstable && notifyAborted == other.notifyAborted &&
            notifyScope == other.notifyScope && systemNotificationWhenInactive == other.systemNotificationWhenInactive &&
            hideSuccessAfterSeconds == other.hideSuccessAfterSeconds && hideFailureAfterSeconds == other.hideFailureAfterSeconds &&
            groupNotifications == other.groupNotifications && notifyInput == other.notifyInput &&
            browserUi == other.browserUi &&
            soundEnabled == other.soundEnabled && soundVolume == other.soundVolume &&
            customSuccessSound == other.customSuccessSound && customFailureSound == other.customFailureSound &&
            wizardingMode == other.wizardingMode && ownHistoryHours == other.ownHistoryHours &&
            buildsPerPinnedJob == other.buildsPerPinnedJob && recentBuildsCount == other.recentBuildsCount
}

fun interface SettingsListener {
    fun settingsChanged()

    companion object {
        @Topic.AppLevel
        val TOPIC = Topic.create("JenkSpotter settings", SettingsListener::class.java)
    }
}

@Service(Service.Level.APP)
@State(name = "JenkSpotterSettings", storages = [Storage("jenkspotter.xml")])
class JenkSpotterSettings : PersistentStateComponent<JenkSpotterState> {
    @Volatile
    private var stateRef = JenkSpotterState()

    /** Treat as read-only; change settings via [update] with a modified [JenkSpotterState.copy]. */
    val current: JenkSpotterState get() = stateRef

    override fun getState(): JenkSpotterState = stateRef

    override fun loadState(state: JenkSpotterState) {
        stateRef = state
    }

    fun server(id: String): ServerConfig? = current.servers.firstOrNull { it.id == id }

    /** Servers that are switched on; everything except the settings page works with these only. */
    val activeServers: List<ServerConfig> get() = current.servers.filter { it.enabled }

    fun setServerEnabled(id: String, enabled: Boolean) = edit { servers.firstOrNull { it.id == id }?.enabled = enabled }

    fun rememberedParameters(serverId: String, jobUrl: String): Map<String, String> =
        current.rememberedParameters["$serverId $jobUrl"]?.let(::decodeParams).orEmpty()

    fun rememberParameters(serverId: String, jobUrl: String, values: Map<String, String>) = edit {
        rememberedParameters["$serverId $jobUrl"] = encodeParams(values)
    }

    fun update(newState: JenkSpotterState) {
        stateRef = newState
        fireChanged()
    }

    private inline fun edit(block: JenkSpotterState.() -> Unit) = update(current.copy().apply(block))

    fun isPinned(serverId: String, url: String) = current.pinnedJobs.any { it.serverId == serverId && it.url == url }

    fun togglePin(serverId: String, fullName: String, url: String) = edit {
        val existing = pinnedJobs.firstOrNull { it.serverId == serverId && it.url == url }
        if (existing != null) {
            pinnedJobs.remove(existing)
        } else {
            pinnedJobs += PinnedJob().also { it.serverId = serverId; it.fullName = fullName; it.url = url }
        }
    }

    fun isWatched(serverId: String, url: String) = current.watchedJobs.any { it.serverId == serverId && it.url == url }

    fun toggleWatch(serverId: String, fullName: String, url: String) = edit {
        val existing = watchedJobs.firstOrNull { it.serverId == serverId && it.url == url }
        if (existing != null) {
            watchedJobs.remove(existing)
        } else {
            watchedJobs += PinnedJob().also { it.serverId = serverId; it.fullName = fullName; it.url = url }
        }
    }

    fun setServerCollapsed(serverId: String, collapsed: Boolean) = edit {
        collapsedServers.remove(serverId)
        if (collapsed) collapsedServers += serverId
    }

    fun setServerHidden(serverId: String, hidden: Boolean) = edit {
        hiddenServers.remove(serverId)
        if (hidden) hiddenServers += serverId
    }

    fun setFolderCollapsed(serverId: String, path: String, collapsed: Boolean) = edit {
        val key = "$serverId $path"
        collapsedFolders.remove(key)
        if (collapsed) collapsedFolders += key
    }

    fun markRecent(serverId: String, url: String) = edit {
        val key = "$serverId $url"
        recentJobs.remove(key)
        recentJobs.add(0, key)
        while (recentJobs.size > MAX_RECENT) recentJobs.removeAt(recentJobs.size - 1)
    }

    fun saveFilter(filter: RegexFilter) = edit {
        val index = filters.indexOfFirst { it.id == filter.id }
        if (index >= 0) filters[index] = filter.copy() else filters += filter.copy()
    }

    fun removeFilter(id: String) = edit { filters.removeIf { it.id == id } }

    fun setFilterEnabled(id: String, enabled: Boolean) = edit { filters.firstOrNull { it.id == id }?.enabled = enabled }

    fun updateView(block: JenkSpotterState.() -> Unit) = edit(block)

    private fun fireChanged() {
        ApplicationManager.getApplication().messageBus.syncPublisher(SettingsListener.TOPIC).settingsChanged()
    }

    companion object {
        const val MAX_RECENT = 30

        // name=value pairs, separated by control characters that never occur in Jenkins parameters.
        fun encodeParams(values: Map<String, String>) = values.entries.joinToString("\u001E") { "${it.key}\u001F${it.value}" }

        fun decodeParams(text: String): Map<String, String> = text.split('\u001E').mapNotNull { pair ->
            val i = pair.indexOf('\u001F')
            if (i <= 0) null else pair.substring(0, i) to pair.substring(i + 1)
        }.toMap()

        fun getInstance(): JenkSpotterSettings = service()
    }
}
