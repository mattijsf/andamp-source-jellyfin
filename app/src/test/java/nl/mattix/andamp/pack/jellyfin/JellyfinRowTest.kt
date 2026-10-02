// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [JellyfinRow] on recorded items: tracks of three albums and a playlist of films. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JellyfinRowTest {
    @Test
    fun `a track's length is its ticks in milliseconds`() {
        val track = checkNotNull(JellyfinRow.track(firstItem("tracks.json"), SERVER))

        // 1416881630 ticks of 100 ns
        assertEquals(141_688L, track.durationMs)
    }

    @Test
    fun `a track is who and what the server says, with the rates of its audio stream`() {
        val track = checkNotNull(JellyfinRow.track(firstItem("tracks-flac.json"), SERVER))

        assertEquals("jellyfin:track:90b750a2cec4eb338c0dab3b40b8bb13", track.id)
        assertEquals(track.id, track.uri)
        assertEquals("Jellyfin", track.title)
        assertEquals("Leap Fidei", track.artist)
        // a FLAC at 764552 bps and 48 kHz
        assertEquals(764, track.bitrateKbps)
        assertEquals(48, track.sampleRateKhz)
        assertFalse(track.isStream)
        assertEquals("Jellyfin.flac", track.defaultName)
    }

    @Test
    fun `a track with no picture of its own shows its album's`() {
        val art = checkNotNull(JellyfinRow.track(firstItem("tracks.json"), SERVER)?.artworkUri).toHttpUrl()

        assertEquals("/stable/Items/920673748d7c8a4aa03144614f1f947f/Images/Primary", art.encodedPath)
        assertEquals("dcdaa02d4c5425aa20d7dbd3dc0df5fc", art.queryParameter("tag"))
    }

    @Test
    fun `a track with a picture of its own shows that one`() {
        val art = checkNotNull(JellyfinRow.track(firstItem("tracks-flac.json"), SERVER)?.artworkUri).toHttpUrl()

        assertEquals("/stable/Items/90b750a2cec4eb338c0dab3b40b8bb13/Images/Primary", art.encodedPath)
        assertEquals("e6ee40d5a27e25e143eebbe5b9bb489f", art.queryParameter("tag"))
    }

    @Test
    fun `no picture anywhere is no artwork, not a URL that will 404`() {
        // Thraximundar: no image on the track, none on the album
        assertNull(JellyfinRow.track(firstItem("tracks-no-art.json"), SERVER)?.artworkUri)
    }

    @Test
    fun `the cover URL carries no credential`() {
        val art = checkNotNull(JellyfinRow.track(firstItem("tracks.json"), SERVER)?.artworkUri)

        assertFalse(art, art.contains(SERVER.token))
        assertNull(art.toHttpUrl().queryParameter("api_key"))
        assertNull(art.toHttpUrl().queryParameter("ApiKey"))
    }

    @Test
    fun `an address goes out and comes back as the same id`() {
        val id = "933e3a903b3ea327b4acfbeb46445b92"

        assertEquals(id, JellyfinRow.trackId(JellyfinRow.address(id)))
        assertEquals("jellyfin:track:$id", JellyfinRow.address(id))
    }

    @Test
    fun `an address that is not this source's has no id in it`() {
        assertNull(JellyfinRow.trackId("subsonic:track:933e3a903b3ea327b4acfbeb46445b92"))
        assertNull(JellyfinRow.trackId("933e3a903b3ea327b4acfbeb46445b92"))
        assertNull(JellyfinRow.trackId("jellyfin:track:"))
    }

    @Test
    fun `a film on a playlist is not a track`() {
        val films = JSONObject(fixture("playlist-items-video.json")).getJSONArray("Items")

        assertEquals(emptyList<Any>(), JellyfinRow.tracks(films, SERVER))
    }

    @Test
    fun `an item with no id is dropped and the rest are kept`() {
        val items = JSONObject(fixture("tracks.json")).getJSONArray("Items")
        items.getJSONObject(0).remove("Id")

        assertEquals(listOf("Cornered! (Promo Edit)"), JellyfinRow.tracks(items, SERVER).map { it.title })
    }

    @Test
    fun `a track with no media asked for still reads, with its rates unsaid`() {
        val bare = firstItem("tracks.json")
        bare.remove("MediaSources")

        val track = checkNotNull(JellyfinRow.track(bare, SERVER))

        assertEquals(0, track.bitrateKbps)
        assertEquals(0, track.sampleRateKhz)
        // no path to take a name from, so the title and the container
        assertEquals("Thunderdays.mp3", track.defaultName)
    }

    @Test
    fun `several credited artists are all named, and none falls back to the album artist`() {
        val credited = JSONObject().put("Artists", JSONArray(listOf("Daft Punk", "Pharrell Williams")))
        val uncredited = JSONObject().put("AlbumArtist", "Various Artists")

        assertEquals("Daft Punk, Pharrell Williams", JellyfinRow.artist(credited))
        assertEquals("Various Artists", JellyfinRow.artist(uncredited))
    }
}
