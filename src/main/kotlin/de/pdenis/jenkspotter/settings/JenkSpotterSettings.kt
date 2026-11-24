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

/**
 * Filter chips of the builds table: build states and why a build is listed. Within each kind a
 * green chip lets through only its builds; a red one hides them. Queued and expected rows count as [QUEUED].
 */
enum class BuildChip(val status: Boolean) {
    RUNNING(true), SUCCESS(true), FAILURE(true), UNSTABLE(true), ABORTED(true), QUEUED(true),
    MINE(false), BRANCH(false), WAITING(false), PINNED(false), WATCHED(false), VIEWS(false);

    val key: String get() = "builds.chip.$name"
    val tooltipKey: String get() = "builds.chip.$name.tooltip"
}

/** Views of the call graph popup. */
enum class ChainView(val key: String) {
    TOP_DOWN("chains.view.topDown"),
    LEFT_RIGHT("chains.view.leftRight"),
    TREE("chains.view.tree"),
    PATHS("chains.view.paths"),
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
    /** "2 -> 3" behind jobs: triggered by 2 jobs, triggers 3. */
    var showTriggers: Boolean = true
    /** Thin strip at the left of jobs in a workflow, one colour per workflow. */
    var showWorkflowColors: Boolean = true
    /** Tint the jobs triggering / triggered by the job under the mouse. */
    var highlightChainOnHover: Boolean = true
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
    /** List every build currently running on the Jenkins, not only the ones of interest (no notifications). */
    var showAllRunning: Boolean = false
    /** With [showAllRunning]: also the last this many finished builds of all jobs, 0 = none. */
    var allBuildsHistory: Int = 0
    /** Jenkins view lists only jobs building a Git repository of the open project. */
    var projectJobsOnly: Boolean = false
    /** Ids of the servers hidden with the eye in the Jenkins view: still polled and notified, just not listed. */
    var hiddenServers: MutableList<String> = mutableListOf()
    /** Filter chips of the builds table that are not off, entries are "[BuildChip] name=[ChipState] name". */
    var buildChips: MutableList<String> = mutableListOf()
    /** Status filter of versions before the chips; read once and turned into [buildChips], see [JenkSpotterSettings.migrate]. */
    var hiddenStatuses: MutableList<String> = mutableListOf()
    /** Layout of this state, raised by [JenkSpotterSettings.migrate]. */
    var stateVersion: Int = 0
    /** Builds (each with its follow-ups) per page of the builds table. */
    var buildsPageSize: Int = 50
    /** Sort order of the builds table, "STARTED:desc" ([BuildColumn] name or JOB); empty = newest first. */
    var buildSort: String = ""
    /** How the call graph popup shows a job's triggers. */
    var chainView: ChainView = ChainView.TOP_DOWN

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
        it.showTriggers = showTriggers; it.showWorkflowColors = showWorkflowColors; it.highlightChainOnHover = highlightChainOnHover
        it.showBuildJobFolders = showBuildJobFolders; it.jobSort = jobSort; it.pinnedFirst = pinnedFirst
        it.jobLayout = jobLayout; it.collapsedServers = collapsedServers.toMutableList()
        it.collapsedFolders = collapsedFolders.toMutableList(); it.buildInfoCollapsed = buildInfoCollapsed
        it.showAllRunning = showAllRunning; it.allBuildsHistory = allBuildsHistory; it.projectJobsOnly = projectJobsOnly; it.hiddenServers = hiddenServers.toMutableList(); it.buildChips = buildChips.toMutableList(); it.stateVersion = stateVersion; it.buildsPageSize = buildsPageSize; it.buildSort = buildSort; it.chainView = chainView
        it.notifySuccess = notifySuccess; it.notifyFailure = notifyFailure
        it.notifyUnstable = notifyUnstable; it.notifyAborted = notifyAborted
        it.notifyScope = notifyScope; it.systemNotificationWhenInactive = systemNotificationWhenInactive
        it.hideSuccessAfterSeconds = hideSuccessAfterSeconds; it.hideFailureAfterSeconds = hideFailureAfterSeconds
        it.groupNotifications = groupNotifications
        it.soundEnabled = soundEnabled; it.soundVolume = soundVolume
        it.customSuccessSound = customSuccessSound; it.customFailureSound = customFailureSound
        it.ownHistoryHours = ownHistoryHours
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
            ownHistoryHours == other.ownHistoryHours &&
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
        stateRef = migrate(state)
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

    /** Switches a rule on or off; [mode] null keeps its mode. */
    fun setFilterState(id: String, enabled: Boolean, mode: FilterMode? = null) = edit {
        filters.firstOrNull { it.id == id }?.let { f ->
            f.enabled = enabled
            if (mode != null) f.mode = mode
        }
    }

    fun updateView(block: JenkSpotterState.() -> Unit) = edit(block)

    private fun fireChanged() {
        ApplicationManager.getApplication().messageBus.syncPublisher(SettingsListener.TOPIC).settingsChanged()
    }

    companion object {
        const val STATE_VERSION = 1

        /** Brings a state written by an older version up to date; settings that changed shape keep their meaning. */
        fun migrate(state: JenkSpotterState): JenkSpotterState {
            if (state.stateVersion < 1) {
                // The status check boxes became filter chips: what was unchecked is a red chip now.
                if (state.buildChips.isEmpty()) {
                    state.buildChips = state.hiddenStatuses.filter { name -> BuildChip.entries.any { it.status && it.name == name } }
                        .map { "$it=EXCLUDE" }.toMutableList()
                }
                state.hiddenStatuses = mutableListOf()
            }
            state.stateVersion = STATE_VERSION
            return state
        }
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
