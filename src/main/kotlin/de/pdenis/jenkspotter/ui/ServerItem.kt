package de.pdenis.jenkspotter.ui

import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.settings.JenkSpotterSettings

/** Entry of a server selector; [id] null means "all servers". */
data class ServerItem(val id: String?, val label: String) {
    override fun toString() = label

    companion object {
        fun all(includeAll: Boolean): List<ServerItem> {
            val servers = JenkSpotterSettings.getInstance().activeServers.map { ServerItem(it.id, it.displayName) }
            return if (includeAll && servers.size > 1) listOf(ServerItem(null, JenkSpotterBundle.message("filter.allServers"))) + servers else servers
        }
    }
}
