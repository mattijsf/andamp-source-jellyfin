// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

/**
 * The client name, header scheme and paths of Jellyfin's REST API that this
 * source uses. The paths are the ones in the server's OpenAPI document and in
 * the recorded answers under `src/test/resources`.
 *
 * Jellyfin's own API is used, and not the Subsonic plugin a server may run,
 * because its sign-in ends in a token that can be revoked on the server; see
 * `pack/PackStore`.
 */
object Jellyfin {
    /** The client name in the `Authorization` header; the server shows it in its device and session lists. */
    const val CLIENT = "AndAmp"

    /**
     * The scheme of the `Authorization` header. Current servers refuse the
     * older `X-Emby-Authorization` header unless legacy authorization is turned
     * on, so only `Authorization` is sent.
     */
    const val AUTH_SCHEME = "MediaBrowser"

    /** Sign-in with a name and a password, answered with a token. `POST`, the credentials in a JSON body. */
    const val AUTHENTICATE = "Users/AuthenticateByName"

    /** Whether this server offers Quick Connect; answers a bare `true` or `false`. */
    const val QUICK_CONNECT_ENABLED = "QuickConnect/Enabled"

    /** A new Quick Connect code and its secret. `POST`. */
    const val QUICK_CONNECT_INITIATE = "QuickConnect/Initiate"

    /** The state of a Quick Connect code, asked by secret. */
    const val QUICK_CONNECT_CONNECT = "QuickConnect/Connect"

    /** Sign-in with an approved Quick Connect secret; answers what a password sign-in does. `POST`. */
    const val AUTHENTICATE_QUICK_CONNECT = "Users/AuthenticateWithQuickConnect"

    /** The server's details, answered only for a valid token; a kept sign-in is tested with it. */
    const val SYSTEM_INFO = "System/Info"

    /** Ends this token's session on the server, which revokes the token. `POST`, answered 204. */
    const val LOGOUT = "Sessions/Logout"

    /** The artists that have an album. */
    const val ALBUM_ARTISTS = "Artists/AlbumArtists"

    /** Every artist credited anywhere; the artist search asks this. */
    const val ARTISTS = "Artists"

    /** The query endpoint for albums, tracks, playlists and the track search. */
    const val ITEMS = "Items"

    /** A playlist's entries, in the playlist's order. */
    fun playlistItems(id: String): String = "Playlists/$id/Items"

    /** An item's cover. */
    fun primaryImage(id: String): String = "Items/$id/Images/Primary"

    /** One track's audio, direct or transcoded, as the server decides from the containers the client lists. */
    fun universalAudio(id: String): String = "Audio/$id/universal"

    /** Jellyfin counts time in ticks of 100 nanoseconds; the player counts in milliseconds. */
    const val TICKS_PER_MILLI = 10_000L
}

/**
 * How this phone identifies itself to the server. Every request carries it,
 * signed in or not.
 *
 * The server lists [id] on its Devices page, and a sign-in under an id revokes
 * the token that id held before. The id therefore has to stay the same for one
 * install and differ between installs. `pack/PackStore` makes one and keeps it.
 *
 * @param name the name shown in the server's device list; the phone's model
 * @param id stable for this install
 * @param version this source's version, shown beside the client name
 */
data class JellyfinDevice(
    val name: String,
    val id: String,
    val version: String,
)

/**
 * A server address, the device, and the sign-in when there is one.
 *
 * A malformed address does not throw: [base] and [url] are null for it, and
 * `OkHttpJellyfin` answers `Unreachable`.
 *
 * Not a data class, so the token is not in the generated `toString`.
 *
 * @param address what somebody typed: a host, or a whole URL, with or without
 *   a scheme and with or without the path the server is mounted under
 * @param device who this phone is; see [JellyfinDevice]
 * @param userId the signed-in user, sent with the library queries; empty before
 *   a sign-in
 * @param token what the server granted at sign-in; empty before one
 */
class JellyfinServer(
    val address: String,
    val device: JellyfinDevice,
    val userId: String = "",
    val token: String = "",
) {
    /**
     * The address as a URL, or null when it is not one.
     *
     * A missing scheme is read as https, because the token travels in a header
     * on every call. A server on plain http, which is Jellyfin's default on
     * port 8096, needs the `http://` typed. A path is kept, because a server
     * behind a reverse proxy is often mounted under one.
     */
    val base: HttpUrl? by lazy(LazyThreadSafetyMode.PUBLICATION) { root(address) }

    /** Whether a user id and a token are held. The server may have revoked the token since. */
    val signedIn: Boolean get() = userId.isNotEmpty() && token.isNotEmpty()

    /** The same server and device with the given sign-in. */
    fun withSignIn(
        userId: String,
        token: String,
    ): JellyfinServer = JellyfinServer(address, device, userId, token)

    /**
     * The value of the `Authorization` header:
     * `MediaBrowser Client="AndAmp", Device="…", DeviceId="…", Version="…"`,
     * with `Token="…"` at the end when there is one. It is sent before sign-in
     * too, because the sign-in is where the server records the device.
     *
     * Every value is percent-encoded. The format is a comma-separated list of
     * quoted pairs with no escaping of its own, so a device name such as
     * `Sam's Pixel, 2` would otherwise end its pair early.
     */
    fun authorization(): String {
        val pairs =
            buildList {
                add("Client" to Jellyfin.CLIENT)
                add("Device" to device.name)
                add("DeviceId" to device.id)
                add("Version" to device.version)
                if (token.isNotEmpty()) add("Token" to token)
            }
        return Jellyfin.AUTH_SCHEME + " " + pairs.joinToString(", ") { (name, value) -> "$name=\"${encoded(value)}\"" }
    }

    /**
     * The URL of one call: [path] under the server's address, with [params] on
     * the query. Null when [base] is.
     *
     * The URL never carries the token, which goes in the header. Cover URLs are
     * made here too, and the player keeps those; see `JellyfinRow.artwork`.
     *
     * The path is added one segment at a time, so a slash inside an id is
     * escaped.
     */
    fun url(
        path: String,
        params: Map<String, String> = emptyMap(),
    ): HttpUrl? {
        val root = base ?: return null
        val url = root.newBuilder()
        for (segment in path.split('/')) url.addPathSegment(segment)
        for ((name, value) in params) url.addQueryParameter(name, value)
        return url.build()
    }

    override fun toString(): String =
        "JellyfinServer(address=$address, device=${device.id}, userId=$userId, token=${if (token.isEmpty()) "none" else "…"})"

    private companion object {
        /** The address trimmed of spaces and trailing slashes, with `https://` put in front when it has no scheme. */
        fun root(address: String): HttpUrl? {
            val typed = address.trim().trimEnd('/')
            if (typed.isEmpty()) return null
            val whole = if (typed.contains(SCHEME_MARK)) typed else "https://$typed"
            return whole.toHttpUrlOrNull()
        }

        /** Percent-encoding with a space as `%20`; `URLEncoder` writes `+`, which only a form decoder reads as a space. */
        fun encoded(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

        const val SCHEME_MARK = "://"
    }
}
