// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin

import nl.mattix.andamp.pack.common.stream.StreamRequest

/**
 * Builds the request for a track's audio. It is built when a row is about to
 * play and is not kept on the row; see [JellyfinRow.track].
 *
 * The token goes in the `Authorization` header and not in the URL, so it does
 * not end up where URLs are written down, such as a proxy's access log. The
 * decoder sends the headers through `MediaExtractor.setDataSource(url, headers)`.
 */
object JellyfinStream {
    /**
     * The `universal` request for an address this source handed out. Null when
     * the address is not one of its own, the server address is not a URL, or
     * nobody is signed in.
     *
     * With `universal` the server decides: the request lists the containers
     * this phone plays as they are (see [containers]), and the server sends
     * the original file when it fits that list and otherwise transcodes to
     * [TRANSCODE_CODEC] over http. A transcode is sent without byte ranges and
     * cannot be sought in.
     *
     * `MaxStreamingBitrate` is set above any audio file's rate, so the server
     * does not transcode to fit it.
     *
     * [sdk] is the phone's API level, passed in so a JVM test can choose it.
     * [fileName] is the row's `defaultName` and [positionMs] is where the audio
     * should start. When [transcodes] predicts a transcode, the request carries
     * [positionMs] as `StartTimeTicks` and is marked not seekable; see
     * [StreamRequest.seekable].
     */
    fun request(
        server: JellyfinServer,
        address: String,
        sdk: Int,
        fileName: String? = null,
        positionMs: Long = 0,
    ): StreamRequest? {
        val id = JellyfinRow.trackId(address) ?: return null
        if (!server.signedIn) return null
        val transcoded = transcodes(fileName, sdk)
        val params =
            mapOf(
                "UserId" to server.userId,
                "DeviceId" to server.device.id,
                "MaxStreamingBitrate" to MAX_BITRATE.toString(),
                "Container" to containers(sdk).joinToString(","),
                "TranscodingContainer" to TRANSCODE_CODEC,
                "TranscodingProtocol" to "http",
                "AudioCodec" to TRANSCODE_CODEC,
            ) + if (transcoded) startingAt(positionMs) else emptyMap()
        val url = server.url(Jellyfin.universalAudio(id), params) ?: return null
        return StreamRequest(url.toString(), mapOf("Authorization" to server.authorization()), seekable = !transcoded)
    }

    /**
     * `StartTimeTicks` for a transcode that starts part way, in ticks of 100
     * nanoseconds; empty for a start at 0. A file sent as it is, is sought by
     * range and gets no start time.
     */
    private fun startingAt(positionMs: Long): Map<String, String> =
        if (positionMs > 0) mapOf("StartTimeTicks" to (positionMs * Jellyfin.TICKS_PER_MILLI).toString()) else emptyMap()

    /**
     * Whether the server is expected to transcode the file called [fileName]:
     * true when the file's extension is not on the [containers] list. A name
     * with no extension is taken as not transcoded.
     *
     * The prediction is wrong for an `m4a` that holds ALAC: its extension is on
     * the list and the server transcodes it for its codec. A seek in such a
     * track moves the readout and not the audio.
     */
    fun transcodes(
        fileName: String?,
        sdk: Int,
    ): Boolean {
        val extension = fileName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        return containers(sdk).none { it.substringBefore('|') == extension }
    }

    /**
     * The server's `Container` list: what this phone plays without a
     * transcode.
     *
     * Containers that can hold several codecs are narrowed to one codec, as in
     * `m4a|aac`, because an m4a can also hold ALAC.
     *
     * FLAC is on the list from API 27. Below that the platform's extractor
     * does not read FLAC over http reliably, so the server is left to
     * transcode it.
     */
    fun containers(sdk: Int): List<String> = if (sdk >= FLAC_OVER_HTTP_SDK) EVERYWHERE + FLAC else EVERYWHERE

    private val EVERYWHERE = listOf("mp3", "aac", "m4a|aac", "m4b|aac", "mp4|aac", "ogg", "oga", "opus", "webma", "wav")

    private const val FLAC = "flac"

    /** The first API level whose `MediaExtractor` reads FLAC over http reliably. */
    private const val FLAC_OVER_HTTP_SDK = 27

    /** The codec and container asked for when the server transcodes. */
    private const val TRANSCODE_CODEC = "mp3"

    /** 140 Mbps: higher than any audio file's rate, so it caps nothing. */
    private const val MAX_BITRATE = 140_000_000
}
