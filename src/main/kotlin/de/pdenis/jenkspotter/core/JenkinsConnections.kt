package de.pdenis.jenkspotter.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.net.JdkProxyProvider
import com.intellij.util.net.ssl.CertificateManager
import de.pdenis.jenkspotter.api.JenkinsClient
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import de.pdenis.jenkspotter.settings.ServerConfig
import de.pdenis.jenkspotter.settings.SettingsListener
import de.pdenis.jenkspotter.settings.TokenStore
import java.net.http.HttpClient
import java.util.concurrent.ConcurrentHashMap

/** Hands out one [JenkinsClient] per configured server; rebuilt whenever settings change. */
@Service(Service.Level.APP)
class JenkinsConnections {
    private data class Entry(val fingerprint: String, val client: JenkinsClient)

    private val clients = ConcurrentHashMap<String, Entry>()
    private val tokens = ConcurrentHashMap<String, String>()

    init {
        ApplicationManager.getApplication().messageBus.connect()
            .subscribe(SettingsListener.TOPIC, SettingsListener {
                // Clients are cached by URL/user/token fingerprint; drop the ones of removed servers.
                val ids = JenkSpotterSettings.getInstance().current.servers.map { it.id }.toSet()
                clients.keys.retainAll(ids)
                tokens.keys.retainAll(ids)
            })
    }

    /** Blocking (PasswordSafe access); returns null if no token is configured. */
    fun client(server: ServerConfig): JenkinsClient? {
        val token = tokens[server.id] ?: TokenStore.get(server.id)?.also { tokens[server.id] = it } ?: return null
        val fingerprint = "${server.url}|${server.username}|${token.hashCode()}"
        clients[server.id]?.takeIf { it.fingerprint == fingerprint }?.let { return it.client }
        val client = JenkinsClient(server.url, server.username, token, createHttpClient())
        clients[server.id] = Entry(fingerprint, client)
        return client
    }

    fun client(serverId: String): JenkinsClient? = JenkSpotterSettings.getInstance().server(serverId)?.let(::client)

    fun invalidate() {
        clients.clear()
        tokens.clear()
    }

    companion object {
        fun getInstance(): JenkinsConnections = service()

        /** Uses the IDE proxy and certificate settings so corporate proxies and internal CAs work. */
        fun createHttpClient(): HttpClient = JenkinsClient.defaultHttpClient {
            val proxy = JdkProxyProvider.getInstance()
            proxy(proxy.proxySelector)
            authenticator(proxy.authenticator)
            sslContext(CertificateManager.getInstance().sslContext)
        }
    }
}
