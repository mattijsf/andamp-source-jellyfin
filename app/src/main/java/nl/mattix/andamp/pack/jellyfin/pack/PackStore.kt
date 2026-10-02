// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin.pack

import android.content.Context
import nl.mattix.andamp.pack.jellyfin.JellyfinDevice
import nl.mattix.andamp.pack.jellyfin.JellyfinServer
import java.util.UUID

/**
 * Keeps the server address and the sign-in, in this app's private preferences.
 *
 * What is kept is a token and a user id, not the password. The token is
 * protected by the app's private storage, which no other app can read, the
 * player included, and `allowBackup` is off. It is valid only on this server
 * and can be revoked on the server's Devices page.
 */
internal class PackStore(
    context: Context,
) {
    private val kept = context.applicationContext.getSharedPreferences("jellyfin", Context.MODE_PRIVATE)

    /** The server's address as the listener typed it, trimmed; empty when none is kept. */
    var address: String
        get() = kept.getString(ADDRESS, "").orEmpty()
        set(value) {
            kept.edit().putString(ADDRESS, value.trim()).apply()
        }

    /** The signed-in user's id, sent with every library query; empty when signed out. */
    val userId: String get() = kept.getString(USER_ID, "").orEmpty()

    /** The signed-in user's name, shown on the settings screen; empty when signed out. */
    val userName: String get() = kept.getString(USER_NAME, "").orEmpty()

    /** What the server granted at sign-in; empty when signed out. */
    val token: String get() = kept.getString(TOKEN, "").orEmpty()

    /**
     * This install's device id, made on first use and kept through a sign-out.
     * Jellyfin revokes the token a device id held when that id signs in again,
     * so with one id a new sign-in replaces this phone's old session; see
     * [JellyfinDevice].
     */
    val deviceId: String
        get() =
            kept.getString(DEVICE_ID, null) ?: UUID.randomUUID().toString().also { made ->
                kept.edit().putString(DEVICE_ID, made).apply()
            }

    /** Whether an address, a user id and a token are kept. The server may have revoked the token since. */
    val signedIn: Boolean get() = address.isNotBlank() && userId.isNotEmpty() && token.isNotEmpty()

    /** Keeps a sign-in: the user and the token, in one write. */
    fun signIn(
        userId: String,
        userName: String,
        token: String,
    ) = synchronized(SIGN_IN) {
        kept
            .edit()
            .putString(USER_ID, userId)
            .putString(USER_NAME, userName)
            .putString(TOKEN, token)
            .apply()
    }

    /**
     * Signs out if [revoked] is still the kept token, and says whether it did.
     *
     * It holds the same lock as [signIn], across every store instance. The
     * service may find a token revoked on a binder thread while the settings
     * screen keeps the sign-in that replaced it, and that new sign-in must not
     * be removed.
     */
    fun signOutIf(revoked: String): Boolean =
        synchronized(SIGN_IN) {
            val still = revoked.isNotEmpty() && token == revoked
            if (still) signOut()
            still
        }

    /**
     * Signs out: removes the user and the token. The address stays, so the
     * listener can sign in to the same server again, and so does the device
     * id; see [deviceId].
     */
    fun signOut() {
        kept
            .edit()
            .remove(USER_ID)
            .remove(USER_NAME)
            .remove(TOKEN)
            .apply()
    }

    /**
     * The server as this store holds it, for the phone called [deviceName]
     * running version [version] of this source. Both are parameters so a test
     * can set them.
     */
    fun server(
        deviceName: String,
        version: String,
    ): JellyfinServer = JellyfinServer(address, JellyfinDevice(deviceName, deviceId, version), userId, token)

    private companion object {
        /** The lock for [signIn] and [signOutIf]. */
        val SIGN_IN = Any()

        const val ADDRESS = "address"
        const val USER_ID = "userId"
        const val USER_NAME = "userName"
        const val TOKEN = "token"
        const val DEVICE_ID = "deviceId"
    }
}
