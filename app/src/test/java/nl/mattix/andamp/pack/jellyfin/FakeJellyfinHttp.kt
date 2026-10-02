// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin

import org.json.JSONObject

/**
 * A [JellyfinHttp] that answers from a function or a map and records what it
 * was asked.
 *
 * The answers in `src/test/resources` were recorded from the public Jellyfin
 * demo at demo.jellyfin.org/stable (Jellyfin 12.0.0), one call each, with the
 * queries this source sends. The access token in them is replaced by
 * `0123456789abcdef0123456789abcdef` and the Quick Connect secret by
 * `SECRET-PLACEHOLDER`. `unauthorized.html` is the body the demo sent with a
 * 401, which is its reverse proxy's page.
 */
internal class FakeJellyfinHttp(
    private val answer: (Ask) -> JellyfinReply,
) : JellyfinHttp {
    /** Answers by path; any other path is unreachable. */
    constructor(replies: Map<String, JellyfinReply>) : this({ ask ->
        replies[ask.path] ?: JellyfinReply.Unreachable("nothing was recorded for ${ask.path}")
    })

    val asked = mutableListOf<Ask>()

    override suspend fun get(
        path: String,
        params: Map<String, String>,
    ): JellyfinReply = Ask("GET", path, params, null).also { asked += it }.let(answer)

    override suspend fun post(
        path: String,
        params: Map<String, String>,
        body: JSONObject?,
    ): JellyfinReply = Ask("POST", path, params, body?.toString()).also { asked += it }.let(answer)

    /** The one call this question made; it fails the test if there was more than one. */
    fun once(): Ask = asked.single()

    data class Ask(
        val method: String,
        val path: String,
        val params: Map<String, String>,
        val body: String?,
    )
}

/** A recorded body as a 200, read with [JellyfinReply.read]. */
internal fun recorded(name: String): JellyfinReply = JellyfinReply.read(200, fixture(name))

/** A recorded body, verbatim. */
internal fun fixture(name: String): String =
    checkNotNull(FakeJellyfinHttp::class.java.getResourceAsStream("/$name")) { "no recording called $name" }
        .use { stream -> stream.readBytes().decodeToString() }

/** The first object in a recording's `Items`. */
internal fun firstItem(name: String): JSONObject = JSONObject(fixture(name)).getJSONArray("Items").getJSONObject(0)

/** The placeholder token in the recorded sign-in replies. */
internal const val RECORDED_TOKEN = "0123456789abcdef0123456789abcdef"

/** The demo's user, whose id is in every recording. */
internal const val DEMO_USER = "4ed1b8b42a7c4ea682f0fe5d08d4e278"

internal val DEVICE = JellyfinDevice(name = "Pixel 9", id = "7d3f2a9e-andamp-test", version = "0.1.0")

/** The demo, signed in with a token that differs from the recorded placeholder, so tests can tell the two apart. */
internal val SERVER = JellyfinServer("https://demo.jellyfin.org/stable", DEVICE, DEMO_USER, "f00dfacecafe1234f00dfacecafe1234")
