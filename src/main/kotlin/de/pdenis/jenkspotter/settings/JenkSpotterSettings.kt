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
        val DEFAULT = listOf(STATUS, STARTED, DURATION, SOURCE)

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
    var showJobServer: Boolean = true

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

    fun copy(): JenkSpotterState = JenkSpotterState().also {
        it.servers = servers.map(ServerConfig::copy).toMutableList()
        it.pinnedJobs = pinnedJobs.map(PinnedJob::copy).toMutableList()
        it.filters = filters.map(RegexFilter::copy).toMutableList()
        it.recentJobs = recentJobs.toMutableList()
        it.rememberedParameters = rememberedParameters.toMutableMap(); it.rememberParameters = rememberParameters
        it.notifyInput = notifyInput; it.browserUi = browserUi
        it.buildColumns = buildColumns; it.buildColumnWidths = buildColumnWidths; it.showJobPath = showJobPath; it.showFolderEntries = showFolderEntries
        it.showDownstream = showDownstream; it.showJobServer = showJobServer
        it.notifySuccess = notifySuccess; it.notifyFailure = notifyFailure
        it.notifyUnstable = notifyUnstable; it.notifyAborted = notifyAborted
        it.notifyScope = notifyScope; it.systemNotificationWhenInactive = systemNotificationWhenInactive
        it.hideSuccessAfterSeconds = hideSuccessAfterSeconds; it.hideFailureAfterSeconds = hideFailureAfterSeconds
        it.groupNotifications = groupNotifications
        it.soundEnabled = soundEnabled; it.soundVolume = soundVolume
        it.customSuccessSound = customSuccessSound; it.customFailureSound = customFailureSound
        it.wizardingMode = wizardingMode; it.ownHistoryHours = ownHistoryHours
        it.buildsPerPinnedJob = buildsPerPinnedJob
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
            buildsPerPinnedJob == other.buildsPerPinnedJob
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
