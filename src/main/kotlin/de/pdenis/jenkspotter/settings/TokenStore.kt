package de.pdenis.jenkspotter.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * API tokens live in the IDE PasswordSafe (macOS Keychain, Windows Credential Manager / KeePass,
 * Linux Secret Service / KeePass), so they never end up in jenkspotter.xml or Settings Sync.
 * Reads can block on the OS keychain; call [get] from background threads only.
 */
object TokenStore {
    private fun attributes(serverId: String) = CredentialAttributes(generateServiceName("JenkSpotter", serverId))

    fun get(serverId: String): String? = PasswordSafe.instance.getPassword(attributes(serverId))

    fun set(serverId: String, username: String, token: String?) {
        val credentials = token?.takeIf { it.isNotEmpty() }?.let { Credentials(username, it) }
        PasswordSafe.instance.set(attributes(serverId), credentials)
    }
}
