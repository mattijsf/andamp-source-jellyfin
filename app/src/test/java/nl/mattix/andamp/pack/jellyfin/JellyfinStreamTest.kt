// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin

import nl.mattix.andamp.core.model.Track
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The requests [JellyfinStream] and [JellyfinPlayback] build. */
class JellyfinStreamTest {
    @Test
    fun `a row's address becomes the universal request on the listener's server`() {
        val request = checkNotNull(JellyfinStream.request(SERVER, "jellyfin:track:90b750a2cec4eb338c0dab3b40b8bb13", MODERN))
        val url = request.url.toHttpUrl()

        assertEquals("/stable/Audio/90b750a2cec4eb338c0dab3b40b8bb13/universal", url.encodedPath)
        assertEquals(DEMO_USER, url.queryParameter("UserId"))
        assertEquals(DEVICE.id, url.queryParameter("DeviceId"))
        assertEquals("mp3", url.queryParameter("TranscodingContainer"))
        assertEquals("http", url.queryParameter("TranscodingProtocol"))
        assertEquals("mp3", url.queryParameter("AudioCodec"))
        // above any audio file's bitrate
        assertTrue(checkNotNull(url.queryParameter("MaxStreamingBitrate")).toLong() >= 100_000_000L)
    }

    @Test
    fun `the token travels in the header and never in the URL`() {
        val request = checkNotNull(JellyfinStream.request(SERVER, "jellyfin:track:abc", MODERN))

        assertFalse(request.url, request.url.contains(SERVER.token))
        assertNull(request.url.toHttpUrl().queryParameter("ApiKey"))
        assertNull(request.url.toHttpUrl().queryParameter("api_key"))
        assertEquals(mapOf("Authorization" to SERVER.authorization()), request.headers)
    }

    @Test
    fun `a stream request prints its URL and never its header's value`() {
        val printed = checkNotNull(JellyfinStream.request(SERVER, "jellyfin:track:abc", MODERN)).toString()

        assertFalse(printed, printed.contains(SERVER.token))
        assertTrue(printed, printed.contains("/Audio/abc/universal"))
    }

    @Test
    fun `a file off the list is a transcode, and a transcode starts at the position it is asked for`() {
        assertTrue(JellyfinStream.transcodes("01 - Heartbeats.flac", OREO))
        assertTrue(JellyfinStream.transcodes("track.wma", MODERN))
        assertFalse(JellyfinStream.transcodes("01 - Heartbeats.flac", MODERN))
        assertFalse(JellyfinStream.transcodes("Track.MP3", OREO))
        assertFalse("an m4a file is not transcoded", JellyfinStream.transcodes("a.m4a", OREO))
        assertFalse("a file with no name is not transcoded", JellyfinStream.transcodes(null, OREO))
        assertFalse(JellyfinStream.transcodes("no extension", OREO))

        val request = checkNotNull(JellyfinStream.request(SERVER, "jellyfin:track:abc", OREO, "a.flac", positionMs = 61_500))

        assertFalse("a transcoded request is not seekable", request.seekable)
        // 100-nanosecond ticks: 61.5 s is 615,000,000 of them
        assertEquals("615000000", request.url.toHttpUrl().queryParameter("StartTimeTicks"))
    }

    @Test
    fun `a file sent as it is carries no start time and can be sought in place`() {
        val request = checkNotNull(JellyfinStream.request(SERVER, "jellyfin:track:abc", MODERN, "a.flac", positionMs = 61_500))

        assertTrue(request.seekable)
        assertNull(request.url.toHttpUrl().queryParameter("StartTimeTicks"))
    }

    @Test
    fun `a transcode from the top carries no start time`() {
        val request = checkNotNull(JellyfinStream.request(SERVER, "jellyfin:track:abc", OREO, "a.flac"))

        assertFalse(request.seekable)
        assertNull(request.url.toHttpUrl().queryParameter("StartTimeTicks"))
    }

    @Test
    fun `a row is handed to the shared playback with its file name, position and header`() {
        val row = Track("abc", "Binaerpilot", "Deftone", 0, uri = "jellyfin:track:abc", defaultName = "Deftone.flac")

        val request = checkNotNull(JellyfinPlayback.request(SERVER, row, OREO, positionMs = 2_000))

        assertEquals("20000000", request.url.toHttpUrl().queryParameter("StartTimeTicks"))
        assertEquals(SERVER.authorization(), request.headers["Authorization"])
        assertNull(JellyfinPlayback.request(null, row, OREO))
        assertNull(JellyfinPlayback.request(SERVER, row.copy(uri = null), OREO))
        assertNull(JellyfinPlayback.request(SERVER, row.copy(uri = "subsonic:track:abc"), OREO))
    }

    @Test
    fun `FLAC is on the list this phone plays from API 27 and transcoded below it`() {
        assertFalse(JellyfinStream.containers(OREO).contains("flac"))
        assertTrue(JellyfinStream.containers(OREO_MR1).contains("flac"))
        assertTrue(JellyfinStream.containers(MODERN).contains("flac"))

        val below = checkNotNull(JellyfinStream.request(SERVER, "jellyfin:track:abc", OREO)).url.toHttpUrl()
        val above = checkNotNull(JellyfinStream.request(SERVER, "jellyfin:track:abc", MODERN)).url.toHttpUrl()
        assertFalse(checkNotNull(below.queryParameter("Container")).split(',').contains("flac"))
        assertTrue(checkNotNull(above.queryParameter("Container")).split(',').contains("flac"))
    }

    @Test
    fun `everything else a phone reads is on the list on every API level, narrowed to the codec it reads`() {
        for (sdk in listOf(OREO, MODERN)) {
            val containers = JellyfinStream.containers(sdk)
            assertTrue(containers.containsAll(listOf("mp3", "aac", "ogg", "opus", "wav")))
            // an m4a may hold ALAC, so only its AAC is listed
            assertTrue(containers.contains("m4a|aac"))
            assertFalse(containers.contains("m4a"))
        }
    }

    @Test
    fun `an address from another source never reaches this listener's server`() {
        assertNull(JellyfinStream.request(SERVER, "subsonic:track:abc", MODERN))
        assertNull(JellyfinStream.request(SERVER, "90b750a2cec4eb338c0dab3b40b8bb13", MODERN))
    }

    @Test
    fun `nobody signed in, or no address, has nothing to stream`() {
        assertNull(JellyfinStream.request(JellyfinServer(SERVER.address, DEVICE), "jellyfin:track:abc", MODERN))
        assertNull(JellyfinStream.request(JellyfinServer("", DEVICE, DEMO_USER, "tok"), "jellyfin:track:abc", MODERN))
    }

    private companion object {
        /** API 26, where FLAC is left off the container list, and 27, where it is on it. */
        const val OREO = 26
        const val OREO_MR1 = 27
        const val MODERN = 36
    }
}
