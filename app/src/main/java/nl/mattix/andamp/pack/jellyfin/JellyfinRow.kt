// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin

import nl.mattix.andamp.core.packapi.PackTrack
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a Jellyfin item into a [PackTrack].
 *
 * An album's tracks, a playlist's entries and a search all answer the same
 * item object in an `Items` array. Every field except the id is read as
 * optional; `MediaSources` is only present when the query asked for it.
 */
object JellyfinRow {
    /**
     * This source's scheme, the first word of every address it hands out. The
     * player routes a row by it and saved playlists record it, so it cannot
     * change after a release.
     */
    const val SCHEME = "jellyfin"

    /** A server's item id as an address, `jellyfin:track:<id>`. */
    fun address(id: String): String = TRACK + id

    /** The item id inside an address, or null when the address is not one of this source's. */
    fun trackId(address: String): String? = address.removePrefix(TRACK).takeIf { it != address && it.isNotEmpty() }

    /**
     * One item as a row, or null when it has no id or its `Type` is not
     * `Audio`.
     *
     * The type is checked here because a Jellyfin playlist can hold video, and
     * the playlist endpoints do not filter it out. An item with no `Type` is
     * kept.
     *
     * [PackTrack.uri] is the address and not a stream URL, because the player
     * writes it into saved playlists. [JellyfinStream] builds the request when
     * the row is played.
     */
    fun track(
        item: JSONObject,
        server: JellyfinServer,
    ): PackTrack? {
        val id = item.optString("Id").takeIf { it.isNotEmpty() } ?: return null
        val type = item.optString("Type")
        if (type.isNotEmpty() && type != AUDIO) return null
        val address = address(id)
        val audio = audioStream(item)
        return PackTrack(
            id = address,
            artist = artist(item),
            title = item.optString("Name"),
            durationMs = item.optLong("RunTimeTicks") / Jellyfin.TICKS_PER_MILLI,
            uri = address,
            // 0 when the server gives no rate
            bitrateKbps = bitsPerSecond(item, audio) / BPS_PER_KBPS,
            sampleRateKhz = (audio?.optInt("SampleRate") ?: 0) / HZ_PER_KHZ,
            isStream = false,
            artworkUri = artwork(item, server),
            defaultName = fileName(item, id),
        )
    }

    /** Every item in an array that reads as a track; other entries are dropped. */
    fun tracks(
        items: JSONArray?,
        server: JellyfinServer,
    ): List<PackTrack> {
        if (items == null) return emptyList()
        return (0 until items.length()).mapNotNull { at -> items.optJSONObject(at)?.let { track(it, server) } }
    }

    /**
     * The names in `Artists`, joined with a comma, or `AlbumArtist` when there
     * are none. `AlbumArtist` comes second because on a compilation it is
     * "Various Artists".
     */
    fun artist(item: JSONObject): String {
        val credited = item.optJSONArray("Artists")
        val names = if (credited == null) emptyList() else (0 until credited.length()).map(credited::optString)
        return names.filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { item.optString("AlbumArtist") }
    }

    /**
     * The URL of this track's cover: the track's own primary image when it has
     * one, otherwise the album's. Null when there is neither.
     *
     * The URL carries no credential. The player fetches it in its own process
     * and writes it into saved playlists, and Jellyfin's image endpoints need
     * no token. Covers do not load from a server whose reverse proxy demands a
     * login for every path.
     *
     * The `tag` parameter changes when the picture does, so a cached cover is
     * replaced when the server has a new one.
     */
    fun artwork(
        item: JSONObject,
        server: JellyfinServer,
    ): String? {
        val own = item.optJSONObject("ImageTags")?.optString(PRIMARY).orEmpty()
        val (owner, tag) =
            when {
                own.isNotEmpty() -> item.optString("Id") to own
                else -> item.optString("AlbumId") to item.optString("AlbumPrimaryImageTag")
            }
        if (owner.isEmpty() || tag.isEmpty()) return null
        return server.url(Jellyfin.primaryImage(owner), mapOf("tag" to tag))?.toString()
    }

    /**
     * The name of the file behind this track: the last segment of the media
     * source's `Path`, or the title (or the id) with the `Container` as its
     * extension. Null when the server gives neither.
     *
     * It goes into the row's `defaultName`, which the player keeps with the row
     * and hands back when the row is played. [JellyfinStream] reads the file's
     * extension from it.
     */
    private fun fileName(
        item: JSONObject,
        id: String,
    ): String? {
        val file = source(item)?.optString("Path").orEmpty().substringAfterLast('/')
        val container = item.optString("Container").substringBefore(',')
        return when {
            file.isNotEmpty() -> file
            container.isEmpty() -> null
            else -> "${item.optString("Name").ifEmpty { id }}.$container"
        }
    }

    /** The audio stream's `BitRate`, or the media source's `Bitrate`, or 0. */
    private fun bitsPerSecond(
        item: JSONObject,
        audio: JSONObject?,
    ): Int = audio?.optInt("BitRate")?.takeIf { it > 0 } ?: source(item)?.optInt("Bitrate") ?: 0

    /** The first media source. */
    private fun source(item: JSONObject): JSONObject? = item.optJSONArray("MediaSources")?.optJSONObject(0)

    /** The first audio stream of the first media source, which has the bitrate and the sample rate. */
    private fun audioStream(item: JSONObject): JSONObject? {
        val streams = source(item)?.optJSONArray("MediaStreams") ?: return null
        return (0 until streams.length()).mapNotNull(streams::optJSONObject).firstOrNull { it.optString("Type") == AUDIO }
    }

    private const val TRACK = "$SCHEME:track:"
    private const val AUDIO = "Audio"
    private const val PRIMARY = "Primary"
    private const val BPS_PER_KBPS = 1_000

    /** [PackTrack.sampleRateKhz] is in whole kHz. */
    private const val HZ_PER_KHZ = 1_000
}
