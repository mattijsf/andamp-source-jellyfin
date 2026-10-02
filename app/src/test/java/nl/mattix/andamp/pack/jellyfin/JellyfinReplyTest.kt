// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * [JellyfinReply.read], and the requests [OkHttpJellyfin] sends. The client
 * tests run [OkHttpJellyfin] over an OkHttp client whose interceptor answers in
 * place of the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JellyfinReplyTest {
    @Test
    fun `a 401 is its own answer, whatever page a proxy put in the body`() {
        // the demo's 401 body: an HTML page from its reverse proxy
        assertEquals(JellyfinReply.Unauthorized, JellyfinReply.read(401, fixture("unauthorized.html")))
    }

    @Test
    fun `any other failing status is rejected and its body is never read`() {
        assertEquals(JellyfinReply.Rejected(404), JellyfinReply.read(404, fixture("unauthorized.html")))
        assertEquals(JellyfinReply.Rejected(500), JellyfinReply.read(500, "{\"Items\":[]}"))
    }

    @Test
    fun `a success that is not JSON is not the server talking`() {
        // what a captive portal answers for every URL
        assertTrue(JellyfinReply.read(200, fixture("unauthorized.html")) is JellyfinReply.Unreachable)
    }

    @Test
    fun `a bare boolean is an answer`() {
        assertEquals(JellyfinReply.Answered(true), recorded("quickconnect-enabled.json"))
    }

    @Test
    fun `a 204 has nothing to say and still succeeded`() {
        val reply = JellyfinReply.read(204, "")

        assertTrue(reply is JellyfinReply.Answered)
        assertEquals(0, ((reply as JellyfinReply.Answered).body as JSONObject).length())
    }

    @Test
    fun `every request carries this phone's header, the token on it and nowhere else`() =
        runTest {
            val sent = mutableListOf<Request>()
            val http = OkHttpJellyfin(SERVER, client(sent) { respond(it, 200, fixture("artists.json")) })

            val reply = http.get(Jellyfin.ALBUM_ARTISTS, mapOf("userId" to DEMO_USER))

            assertTrue(reply is JellyfinReply.Answered)
            val request = sent.single()
            assertEquals("GET", request.method)
            assertEquals(SERVER.authorization(), request.header("Authorization"))
            assertEquals("/stable/Artists/AlbumArtists", request.url.encodedPath)
            assertTrue(request.url.toString(), !request.url.toString().contains(SERVER.token))
            // the legacy header name is not sent
            assertEquals(null, request.header("X-Emby-Authorization"))
        }

    @Test
    fun `a sign-in is a POST with its fields as JSON`() =
        runTest {
            val sent = mutableListOf<Request>()
            val server = JellyfinServer("https://demo.jellyfin.org/stable", DEVICE)
            val http = OkHttpJellyfin(server, client(sent) { respond(it, 200, fixture("authenticate.json")) })

            val signIn = JellyfinSignIn(http).password("demo", "")

            assertTrue(signIn is SignIn.SignedIn)
            val request = sent.single()
            assertEquals("POST", request.method)
            assertEquals("application/json", request.body?.contentType()?.let { "${it.type}/${it.subtype}" })
            val body = JSONObject(Buffer().also { checkNotNull(request.body).writeTo(it) }.readUtf8())
            assertEquals("demo", body.getString("Username"))
            assertEquals("", body.getString("Pw"))
            // nobody is signed in yet, so there is no token to send
            assertTrue(checkNotNull(request.header("Authorization")).endsWith("Version=\"0.1.0\""))
        }

    @Test
    fun `the real client reads a 401 off the status`() =
        runTest {
            val http = OkHttpJellyfin(SERVER, client(mutableListOf()) { respond(it, 401, fixture("unauthorized.html")) })

            assertEquals(JellyfinReply.Unauthorized, http.get(Jellyfin.ITEMS))
        }

    @Test
    fun `a network that is not there is an answer and not an exception`() =
        runTest {
            val http = OkHttpJellyfin(SERVER, client(mutableListOf()) { throw IOException("no route to host") })

            assertEquals(JellyfinReply.Unreachable("no route to host"), http.get(Jellyfin.ITEMS))
        }

    @Test
    fun `a server with no address is unreachable without a call`() =
        runTest {
            val sent = mutableListOf<Request>()
            val http = OkHttpJellyfin(JellyfinServer("", DEVICE), client(sent) { respond(it, 200, "{}") })

            assertTrue(http.get(Jellyfin.ITEMS) is JellyfinReply.Unreachable)
            assertEquals(emptyList<Request>(), sent)
        }

    /** An OkHttp client that answers with [answer] and adds every request to [sent]. */
    private fun client(
        sent: MutableList<Request>,
        answer: (Request) -> Response,
    ): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    sent += chain.request()
                    answer(chain.request())
                },
            ).build()

    private fun respond(
        request: Request,
        code: Int,
        body: String,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("recorded")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
}
