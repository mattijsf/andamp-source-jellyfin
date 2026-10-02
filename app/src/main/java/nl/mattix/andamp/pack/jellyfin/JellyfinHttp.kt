// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * One request to a server. An interface, so the library, the sign-in and the
 * settings actions are tested against recorded answers with no network.
 *
 * It has `GET` and `POST`, because the sign-ins and Quick Connect's start are
 * `POST`s.
 */
interface JellyfinHttp {
    /** `GET <server>/<path>?<params>`, with this phone's `Authorization` header. */
    suspend fun get(
        path: String,
        params: Map<String, String> = emptyMap(),
    ): JellyfinReply

    /** `POST <server>/<path>?<params>`, with [body] as JSON when there is one. */
    suspend fun post(
        path: String,
        params: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
    ): JellyfinReply
}

/**
 * What came back: an answer, or one of three kinds of failure.
 *
 * Jellyfin reports failure with the HTTP status, so the body of a failure is
 * not read. A reverse proxy in front of a server may replace it with an HTML
 * page.
 */
sealed interface JellyfinReply {
    /**
     * A success. [body] is a `JSONObject` for most endpoints, a `JSONArray`
     * for the few that answer one, a `Boolean` for `QuickConnect/Enabled`, and
     * an empty `JSONObject` for an empty body such as a 204.
     */
    data class Answered(
        val body: Any,
    ) : JellyfinReply

    /**
     * Nothing readable came back: no route, a timeout, or a body that is not
     * JSON, such as a captive portal's login page.
     */
    data class Unreachable(
        val why: String,
    ) : JellyfinReply

    /**
     * HTTP 401. On a sign-in it means the name or the password is wrong. On
     * any other call it means the token is not valid: revoked on the server's
     * Devices page, ended by a sign-out, or replaced by a later sign-in from
     * the same device id. `JellyfinLibrary` then signs the listener out.
     */
    data object Unauthorized : JellyfinReply

    /** Any other status that is not success. */
    data class Rejected(
        val status: Int,
    ) : JellyfinReply

    companion object {
        /** A status and a body as one of the four replies. */
        fun read(
            status: Int,
            body: String,
        ): JellyfinReply {
            if (status == UNAUTHORIZED) return Unauthorized
            if (status !in SUCCESS) return Rejected(status)
            if (body.isBlank()) return Answered(JSONObject())
            val value = runCatching { JSONTokener(body).nextValue() }.getOrNull()
            // JSONTokener reads an unquoted word as a string, so an HTML page
            // parses as "<html>"; only an object, an array or a boolean counts
            // as an answer
            return if (value is JSONObject || value is JSONArray || value is Boolean) {
                Answered(value)
            } else {
                Unreachable("the answer was not a Jellyfin response")
            }
        }

        private const val UNAUTHORIZED = 401
        private val SUCCESS = 200..299
    }
}

/**
 * [JellyfinHttp] over OkHttp, with the `Authorization` header on every call.
 *
 * The call is enqueued, so no thread is held while the server answers, and
 * canceling the coroutine cancels the call. An `IOException` becomes
 * [JellyfinReply.Unreachable]; nothing here throws.
 *
 * Requests are not logged, because every one carries the token in its header.
 */
class OkHttpJellyfin(
    private val server: JellyfinServer,
    private val client: OkHttpClient = JellyfinOkHttp.shared,
) : JellyfinHttp {
    override suspend fun get(
        path: String,
        params: Map<String, String>,
    ): JellyfinReply = sent(path, params) { get() }

    override suspend fun post(
        path: String,
        params: Map<String, String>,
        body: JSONObject?,
    ): JellyfinReply =
        sent(path, params) {
            post((body?.toString() ?: "").toRequestBody(JSON))
        }

    private suspend fun sent(
        path: String,
        params: Map<String, String>,
        method: Request.Builder.() -> Request.Builder,
    ): JellyfinReply {
        val url = server.url(path, params) ?: return JellyfinReply.Unreachable("no server address")
        val request =
            Request
                .Builder()
                .url(url)
                .header(AUTHORIZATION, server.authorization())
                .header("Accept", "application/json")
                .method()
                .build()
        return suspendCancellableCoroutine { waiting ->
            val call = client.newCall(request)
            waiting.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        waiting.resume(JellyfinReply.Unreachable(e.message ?: e.javaClass.simpleName))
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        waiting.resume(response.use(::reply))
                    }
                },
            )
        }
    }

    /** One response as a reply. A body that cannot be read to the end is [JellyfinReply.Unreachable]. */
    private fun reply(response: Response): JellyfinReply {
        if (!response.isSuccessful) return JellyfinReply.read(response.code, "")
        val body = runCatching { response.body.string() }.getOrNull()
        return body?.let { JellyfinReply.read(response.code, it) } ?: JellyfinReply.Unreachable("the answer stopped part way")
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
        val JSON = "application/json".toMediaType()
    }
}

/** The one HTTP client this source uses, with timeouts. */
object JellyfinOkHttp {
    val shared: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            // for a server name with both address families on a network that
            // routes only one: the other is tried without waiting for the
            // connect timeout
            .fastFallback(true)
            .build()
    }

    private const val TIMEOUT_S = 15L

    /** Long enough for a page of five hundred tracks with their media sources. */
    private const val CALL_TIMEOUT_S = 60L
}
