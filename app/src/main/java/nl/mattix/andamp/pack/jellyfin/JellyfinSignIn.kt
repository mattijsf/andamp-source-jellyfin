// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin

import org.json.JSONObject

/**
 * Signing in with a password or with Quick Connect, and signing out.
 *
 * With Quick Connect the source shows a code, and the listener types it into
 * a Jellyfin app or web page where they are already signed in. Both ways end
 * in the same answer from the server and the same [SignIn].
 *
 * The password is sent once, in the body of one `POST`, and is not kept; the
 * token is. The [http] given here carries no token; the device is in its
 * header.
 */
class JellyfinSignIn(
    private val http: JellyfinHttp,
) {
    /**
     * Signs in with a name and a password. The password field is `Pw`. A 401
     * is [SignIn.WrongAccount].
     */
    suspend fun password(
        user: String,
        password: String,
    ): SignIn {
        val body = JSONObject().put("Username", user.trim()).put("Pw", password)
        return signedIn(http.post(Jellyfin.AUTHENTICATE, body = body))
    }

    /**
     * Whether this server offers Quick Connect, which an administrator can
     * turn off: true, false, or null when the server did not answer.
     */
    suspend fun quickConnectEnabled(): Boolean? =
        when (val reply = http.get(Jellyfin.QUICK_CONNECT_ENABLED)) {
            is JellyfinReply.Answered -> reply.body as? Boolean
            else -> null
        }

    /** A code to show the listener and the secret to ask about it with. Null on any failure. */
    suspend fun startQuickConnect(): QuickConnectCode? {
        val reply = http.post(Jellyfin.QUICK_CONNECT_INITIATE) as? JellyfinReply.Answered ?: return null
        val json = reply.body as? JSONObject ?: return null
        val code = json.optString("Code")
        val secret = json.optString("Secret")
        return if (code.isEmpty() || secret.isEmpty()) null else QuickConnectCode(code, secret)
    }

    /**
     * Whether the code has been approved. The caller polls this; the server
     * sends no notice. A 404 means the server does not know the secret, which
     * is [QuickConnectState.Expired].
     */
    suspend fun quickConnectState(secret: String): QuickConnectState =
        when (val reply = http.get(Jellyfin.QUICK_CONNECT_CONNECT, mapOf("Secret" to secret))) {
            is JellyfinReply.Answered -> {
                val json = reply.body as? JSONObject
                when {
                    json == null -> QuickConnectState.Failed
                    json.optBoolean("Authenticated") -> QuickConnectState.Approved
                    else -> QuickConnectState.Waiting
                }
            }

            is JellyfinReply.Rejected -> {
                if (reply.status == NOT_FOUND) QuickConnectState.Expired else QuickConnectState.Failed
            }

            else -> {
                QuickConnectState.Failed
            }
        }

    /** Signs in with an approved secret. Any status other than success or 401 is [SignIn.Failed]. */
    suspend fun redeemQuickConnect(secret: String): SignIn {
        val body = JSONObject().put("Secret", secret)
        return signedIn(http.post(Jellyfin.AUTHENTICATE_QUICK_CONNECT, body = body))
    }

    /**
     * Ends this phone's session on the server, which revokes its token. True
     * when the server confirmed it. A token that is only forgotten on the
     * phone stays valid on the server.
     */
    suspend fun signOut(): Boolean = http.post(Jellyfin.LOGOUT) is JellyfinReply.Answered

    private companion object {
        const val NOT_FOUND = 404

        /** Reads the answer both sign-ins get: `AccessToken`, and the user under `User`. */
        fun signedIn(reply: JellyfinReply): SignIn =
            when (reply) {
                is JellyfinReply.Answered -> {
                    val json = reply.body as? JSONObject
                    val token = json?.optString("AccessToken").orEmpty()
                    val user = json?.optJSONObject("User")
                    val userId = user?.optString("Id").orEmpty()
                    if (token.isEmpty() || userId.isEmpty()) {
                        SignIn.Failed("the server signed in nobody")
                    } else {
                        SignIn.SignedIn(userId = userId, userName = user?.optString("Name").orEmpty(), token = token)
                    }
                }

                JellyfinReply.Unauthorized -> {
                    SignIn.WrongAccount
                }

                is JellyfinReply.Rejected -> {
                    SignIn.Failed("the server answered ${reply.status}")
                }

                is JellyfinReply.Unreachable -> {
                    SignIn.Failed(reply.why)
                }
            }
    }
}

/** How a sign-in ended. */
sealed interface SignIn {
    /** Signed in, with the [userId] and [token] to keep. Not a data class, so [toString] leaves the token out. */
    class SignedIn(
        val userId: String,
        val userName: String,
        val token: String,
    ) : SignIn {
        override fun toString(): String = "SignedIn(userId=$userId, userName=$userName, token=…)"
    }

    /** A 401 at sign-in: the name or the password is wrong. */
    data object WrongAccount : SignIn

    /** Anything else: the server was not reached, or its answer was not a sign-in. */
    data class Failed(
        val why: String,
    ) : SignIn
}

/**
 * A Quick Connect code in progress. [code] is shown to the listener. [secret]
 * is what the state is asked with and the sign-in is redeemed with; it is not
 * shown, and [toString] leaves it out.
 */
class QuickConnectCode(
    val code: String,
    val secret: String,
) {
    override fun toString(): String = "QuickConnectCode(code=$code, secret=…)"
}

/** The state of a Quick Connect code. */
enum class QuickConnectState {
    /** Not yet approved. */
    Waiting,

    /** Approved: [JellyfinSignIn.redeemQuickConnect] can be called. */
    Approved,

    /** The server no longer knows the secret; a new code is needed. */
    Expired,

    /** The server gave no usable answer; asking again may work. */
    Failed,
}
