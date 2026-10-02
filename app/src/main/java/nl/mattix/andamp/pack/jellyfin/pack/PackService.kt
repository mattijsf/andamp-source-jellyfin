// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin.pack

import android.content.Context
import android.os.Build
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nl.mattix.andamp.core.network.SystemNetworkWatch
import nl.mattix.andamp.core.packapi.PackAccount
import nl.mattix.andamp.core.packapi.PackAnswer
import nl.mattix.andamp.core.packapi.PackDescriptor
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.playback.NetworkWatch
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.pack.common.PackServiceBase
import nl.mattix.andamp.pack.common.stream.StreamPlayback
import nl.mattix.andamp.pack.jellyfin.BuildConfig
import nl.mattix.andamp.pack.jellyfin.JellyfinLibrary
import nl.mattix.andamp.pack.jellyfin.JellyfinPlayback
import nl.mattix.andamp.pack.jellyfin.JellyfinServer
import nl.mattix.andamp.pack.jellyfin.OkHttpJellyfin

/**
 * The service the player binds. It supplies the server and sign-in kept on
 * this phone, the library of that server, and the backend that plays its rows;
 * the binder and the service lifetime are [PackServiceBase].
 *
 * Questions go to [JellyfinLibrary] directly; the server does the paging.
 *
 * A token can be revoked on the server, which shows as a 401 on the next
 * question. The service then forgets the token and tells every bound player;
 * see [revoked].
 */
class PackService : PackServiceBase() {
    /** The server and the sign-in on this phone. Its values are read from the preferences on every call, not held in a field. */
    private val store by lazy { PackStore(this) }

    /**
     * Whether the phone has a network, so a song whose connection dropped
     * waits for one. It can be made in a field initializer because it looks
     * up the connectivity service only when first used.
     */
    internal val network: NetworkWatch = SystemNetworkWatch(this)

    /** The library as it was last built, and for which address and token; see [library]. */
    private var shelves: JellyfinLibrary? = null
    private var shelvesFor: Pair<String, String>? = null

    override val launcherAlias: String = PackIdentity.LAUNCHER_ALIAS

    override fun makeBackend(): PlaybackBackend =
        JellyfinPlayback.backend(
            server = ::server,
            tracks = emptyList(),
            startIndex = 0,
            scope = scope,
            out = audio,
            network = network,
        )

    override fun descriptor(): PackDescriptor =
        PackIdentity.descriptor(
            version = BuildConfig.VERSION_NAME,
            playback = StreamPlayback.RENDERING,
            browse = JellyfinLibrary.SHELVES,
        )

    /** Signed in means a token is kept. The server is not asked; see [revoked] for a token it has revoked. */
    override fun whoIsHere(): PackAccount =
        if (store.signedIn) PackAccount(signedIn = true, name = store.userName) else PackAccount(signedIn = false)

    override fun answer(question: PackQuestion): PackAnswer? {
        val shelf = library() ?: return null
        return runBlocking { shelf.answer(question) }
    }

    /** Drops the library. Synchronized with [library], which a binder thread may be reading. */
    @Synchronized
    override fun forgetAccount() {
        shelves = null
        shelvesFor = null
    }

    /**
     * Signs out after the server answered 401 to a question asked with [token],
     * but only when [token] is still the kept one. A new sign-in revokes the
     * old token, so a question still out with the old token gets a 401 that
     * must not sign out the new sign-in.
     *
     * Called on a binder thread; the account is dropped on the main thread.
     */
    private fun revoked(token: String) {
        if (store.signOutIf(token)) scope.launch { dropAccount() }
    }

    /**
     * The server and sign-in as this phone holds them, or null when nobody is
     * signed in. Read from the store on every call, so a token replaced on the
     * settings screen is used by the next request.
     */
    private fun server(): JellyfinServer? =
        if (store.signedIn) store.server(deviceName = Build.MODEL.orEmpty(), version = BuildConfig.VERSION_NAME) else null

    /**
     * The library, kept while the address and the token stay the same and
     * built again when either changes. It holds no cache.
     */
    @Synchronized
    private fun library(): JellyfinLibrary? {
        val now = server() ?: return null
        val key = now.address to now.token
        shelves?.takeIf { shelvesFor == key }?.let { return it }
        shelvesFor = key
        val token = now.token
        return JellyfinLibrary(OkHttpJellyfin(now), now, signedOut = { revoked(token) }).also { shelves = it }
    }

    internal companion object {
        /**
         * Tells the service that the account on this phone changed. The
         * settings screen passes this to `SettingsActions`. Whether it was a
         * sign-in or a sign-out is read from the store.
         */
        fun announce(context: Context) {
            if (PackStore(context).signedIn) {
                signedIn(context, PackService::class.java)
            } else {
                signedOut(context, PackService::class.java)
            }
        }
    }
}
