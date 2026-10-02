// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin.ui

import android.content.Context
import android.os.Build
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nl.mattix.andamp.pack.jellyfin.BuildConfig
import nl.mattix.andamp.pack.jellyfin.Jellyfin
import nl.mattix.andamp.pack.jellyfin.JellyfinDevice
import nl.mattix.andamp.pack.jellyfin.JellyfinHttp
import nl.mattix.andamp.pack.jellyfin.JellyfinReply
import nl.mattix.andamp.pack.jellyfin.JellyfinServer
import nl.mattix.andamp.pack.jellyfin.JellyfinSignIn
import nl.mattix.andamp.pack.jellyfin.OkHttpJellyfin
import nl.mattix.andamp.pack.jellyfin.QuickConnectCode
import nl.mattix.andamp.pack.jellyfin.QuickConnectState
import nl.mattix.andamp.pack.jellyfin.SignIn
import nl.mattix.andamp.pack.jellyfin.pack.PackStore

/**
 * What the settings page does to the store and the server. An interface, so
 * the page is tested with a fake in its place.
 */
internal interface JellyfinActions {
    /** What is kept on this phone now, for the page to open with. */
    fun kept(): Kept

    /** Whether the server at [address] offers Quick Connect. False for anything other than a `true` from the server. */
    suspend fun quickConnectOffered(address: String): Boolean

    /**
     * Tries what was typed without keeping a sign-in, and says what happened.
     *
     * It signs in under a separate device id and signs out again at once, so
     * the phone's own sign-in is not replaced; a sign-in under the same device
     * id would revoke it, see [nl.mattix.andamp.pack.jellyfin.JellyfinDevice].
     * When the password is empty and the address and user are the ones signed
     * in, the kept token is tested instead.
     */
    suspend fun test(
        address: String,
        user: String,
        password: String,
    ): Tried

    /** Signs in with a name and a password, keeps the token, and says what happened. The password is not kept. */
    suspend fun signIn(
        address: String,
        user: String,
        password: String,
    ): Tried

    /** A new Quick Connect code to show, or null when the server gave none. */
    suspend fun startQuickConnect(address: String): QuickConnectCode?

    /** The state of [code]; the page polls this. */
    suspend fun quickConnectState(
        address: String,
        code: QuickConnectCode,
    ): QuickConnectState

    /** Signs in with an approved [code], keeps the token, and says what happened. */
    suspend fun redeemQuickConnect(
        address: String,
        code: QuickConnectCode,
    ): Tried

    /** Signs out on the server first, then on the phone whether or not the server answered. */
    suspend fun signOut()
}

/** What the phone holds, without the token. */
internal data class Kept(
    val address: String,
    val user: String,
    val signedIn: Boolean,
)

/** The outcome of a test or a sign-in. */
internal sealed interface Tried {
    data object SignedIn : Tried

    /** A test: the server is Jellyfin and accepted the name and password. Nothing was kept. */
    data object Reached : Tried

    /** A test of the kept sign-in: the server accepts its token. */
    data object StillSignedIn : Tried

    /** A test of the kept sign-in: the server answered 401 for its token. */
    data object Revoked : Tried

    /** What was typed is not a URL; nothing was asked. */
    data object NoAddress : Tried

    /** Nothing answered at the address: no route, a timeout, a refused connection. */
    data class NoServer(
        val why: String,
    ) : Tried

    /** Something answered that is not Jellyfin, such as a router's page or a proxy asking for its own login. */
    data class NotJellyfin(
        val status: Int,
    ) : Tried

    /** Jellyfin said the name or the password is wrong. */
    data object Refused : Tried

    /** The Quick Connect code expired before it was approved. */
    data object Expired : Tried

    /** Jellyfin answered, and did not sign anybody in. */
    data class Failed(
        val why: String,
    ) : Tried
}

/** [JellyfinActions] over the store on disk and a real server. */
internal class SettingsActions(
    context: Context,
    private val store: PackStore,
    /** Tells every bound player that the account changed. A parameter, so a test starts no service. */
    private val announce: (Context) -> Unit = {},
    /** Makes the client for a server. A parameter, so a test can answer with recorded replies. */
    private val http: (JellyfinServer) -> JellyfinHttp = { OkHttpJellyfin(it) },
    /** The phone's name, which the server's Devices page lists. */
    private val deviceName: String = Build.MODEL.orEmpty(),
    private val version: String = BuildConfig.VERSION_NAME,
) : JellyfinActions {
    private val app = context.applicationContext

    override fun kept(): Kept = Kept(address = store.address, user = store.userName, signedIn = store.signedIn)

    override suspend fun quickConnectOffered(address: String): Boolean {
        val server = server(address)
        if (server.base == null) return false
        return JellyfinSignIn(http(server)).quickConnectEnabled() == true
    }

    /**
     * Asks first whether the server is Jellyfin, with the anonymous
     * `QuickConnect/Enabled` call; see [notJellyfin]. The sign-in's own answer
     * does not tell a wrong address from another server's login page.
     */
    override suspend fun signIn(
        address: String,
        user: String,
        password: String,
    ): Tried {
        val server = server(address)
        if (server.base == null) return Tried.NoAddress
        val client = http(server)
        notJellyfin(client)?.let { return it }
        return kept(address, JellyfinSignIn(client).password(user, password))
    }

    override suspend fun test(
        address: String,
        user: String,
        password: String,
    ): Tried {
        val server = server(address)
        if (server.base == null) return Tried.NoAddress
        notJellyfin(http(server))?.let { return it }
        return if (password.isEmpty() && isKept(address, user)) testKept() else testPassword(address, user, password)
    }

    /** Whether [address] and [user] are the sign-in this phone holds. */
    private fun isKept(
        address: String,
        user: String,
    ): Boolean = store.signedIn && address == store.address && user == store.userName

    /** Asks `System/Info` with the kept token, to learn whether the server accepts it. */
    private suspend fun testKept(): Tried =
        when (val reply = http(store.server(deviceName, version)).get(Jellyfin.SYSTEM_INFO)) {
            is JellyfinReply.Answered -> Tried.StillSignedIn
            JellyfinReply.Unauthorized -> Tried.Revoked
            is JellyfinReply.Unreachable -> Tried.NoServer(reply.why)
            is JellyfinReply.Rejected -> Tried.NotJellyfin(reply.status)
        }

    /** Signs in under a device id of its own and signs out again at once, so nothing is kept. */
    private suspend fun testPassword(
        address: String,
        user: String,
        password: String,
    ): Tried {
        val trial = JellyfinServer(address, JellyfinDevice(deviceName, store.deviceId + TRIAL, version))
        return when (val said = JellyfinSignIn(http(trial)).password(user, password)) {
            is SignIn.SignedIn -> {
                withContext(NonCancellable) {
                    withTimeoutOrNull(LOGOUT_MS) { JellyfinSignIn(http(trial.withSignIn(said.userId, said.token))).signOut() }
                }
                Tried.Reached
            }

            SignIn.WrongAccount -> {
                Tried.Refused
            }

            is SignIn.Failed -> {
                Tried.Failed(said.why)
            }
        }
    }

    override suspend fun startQuickConnect(address: String): QuickConnectCode? {
        val server = server(address)
        if (server.base == null) return null
        return JellyfinSignIn(http(server)).startQuickConnect()
    }

    override suspend fun quickConnectState(
        address: String,
        code: QuickConnectCode,
    ): QuickConnectState = JellyfinSignIn(http(server(address))).quickConnectState(code.secret)

    override suspend fun redeemQuickConnect(
        address: String,
        code: QuickConnectCode,
    ): Tried = kept(address, JellyfinSignIn(http(server(address))).redeemQuickConnect(code.secret))

    /**
     * Not cancellable, so the token is removed from the phone even when the
     * page closes before the server answers. The server gets [LOGOUT_MS].
     */
    override suspend fun signOut() =
        withContext(NonCancellable) {
            val server = store.server(deviceName, version)
            if (server.signedIn) withTimeoutOrNull(LOGOUT_MS) { JellyfinSignIn(http(server)).signOut() }
            store.signOut()
            announce(app)
        }

    /** A server at [address] as this install, with no sign-in yet. */
    private fun server(address: String): JellyfinServer =
        JellyfinServer(address, JellyfinDevice(deviceName, store.deviceId, version))

    /** Null when the server behind [client] answers as Jellyfin does; otherwise the outcome to show. */
    private suspend fun notJellyfin(client: JellyfinHttp): Tried? =
        when (val reply = client.get(Jellyfin.QUICK_CONNECT_ENABLED)) {
            is JellyfinReply.Answered -> if (reply.body is Boolean) null else Tried.NotJellyfin(OK)

            is JellyfinReply.Unreachable -> Tried.NoServer(reply.why)

            is JellyfinReply.Rejected -> Tried.NotJellyfin(reply.status)

            // a 401 for an anonymous call, such as from a proxy that wants its own login
            JellyfinReply.Unauthorized -> Tried.NotJellyfin(UNAUTHORIZED)
        }

    /**
     * Keeps a successful sign-in and announces it, or says why there is none.
     * The store is written before the announce, which reads it.
     */
    private fun kept(
        address: String,
        signIn: SignIn,
    ): Tried =
        when (signIn) {
            is SignIn.SignedIn -> {
                store.address = address
                store.signIn(userId = signIn.userId, userName = signIn.userName, token = signIn.token)
                announce(app)
                Tried.SignedIn
            }

            SignIn.WrongAccount -> {
                Tried.Refused
            }

            is SignIn.Failed -> {
                Tried.Failed(signIn.why)
            }
        }

    private companion object {
        /** How long a sign-out waits for the server before signing out on the phone. */
        const val LOGOUT_MS = 5_000L

        /** The suffix of a test's device id, so its sign-in does not revoke the kept one. */
        const val TRIAL = "-test"

        const val OK = 200
        const val UNAUTHORIZED = 401
    }
}

/**
 * Polls until [code] is approved and then redeems it, or says why it was not.
 *
 * The wait ends with [Tried.NoServer] after [MISSES] failed polls in a row and
 * with [Tried.Expired] when the server reports the code expired. While the
 * server answers "waiting", the poll continues until the caller cancels it.
 */
internal suspend fun JellyfinActions.awaitApproval(
    address: String,
    code: QuickConnectCode,
): Tried {
    var misses = 0
    while (true) {
        delay(POLL_MS)
        when (quickConnectState(address, code)) {
            QuickConnectState.Approved -> return redeemQuickConnect(address, code)
            QuickConnectState.Expired -> return Tried.Expired
            QuickConnectState.Waiting -> misses = 0
            QuickConnectState.Failed -> if (++misses >= MISSES) return Tried.NoServer("the server stopped answering")
        }
    }
}

/** The interval between polls for a shown code. */
internal const val POLL_MS = 1_500L

/** How many failed asks in a row end the wait for a code. */
private const val MISSES = 4
