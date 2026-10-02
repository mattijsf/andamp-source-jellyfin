// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin

import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.AlbumKind
import nl.mattix.andamp.core.packapi.PackAnswer
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.packapi.UNKNOWN_COUNT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every question the browse contract asks, answered from recordings; see [FakeJellyfinHttp]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JellyfinLibraryTest {
    @Test
    fun `the artists are the album artists, asked for this user a page at a time`() =
        runTest {
            val http = FakeJellyfinHttp(mapOf(Jellyfin.ALBUM_ARTISTS to recorded("artists.json")))

            val answer = JellyfinLibrary(http, SERVER).answer(PackQuestion(PackQuestion.ARTISTS, offset = 0, limit = 50))

            assertEquals(
                mapOf(
                    "userId" to DEMO_USER,
                    "StartIndex" to "0",
                    "Limit" to "50",
                    "SortBy" to "SortName",
                    "SortOrder" to "Ascending",
                ) + LEAN,
                http.once().params,
            )
            assertEquals(listOf("Binaerpilot", "Joshua Boniface", "Leap Fidei"), answer.artists.map { it.name })
            assertEquals("9ebcc113abb3ee375dbf1d68c9576ed5", answer.artists.first().id)
            assertFalse(answer.more)
            assertFalse(answer.failed)
        }

    @Test
    fun `an artist's counts are unknown`() =
        runTest {
            val answer = library(Jellyfin.ALBUM_ARTISTS, "artists.json").answer(PackQuestion(PackQuestion.ARTISTS))

            assertEquals(UNKNOWN_COUNT, answer.artists.first().albumCount)
            assertEquals(UNKNOWN_COUNT, answer.artists.first().trackCount)
        }

    @Test
    fun `more comes from the server's total and not from a full page`() =
        runTest {
            // two asked for, two answered, three in total
            val library = library(Jellyfin.ALBUM_ARTISTS, "artists-page.json")

            val first = library.answer(PackQuestion(PackQuestion.ARTISTS, offset = 0, limit = 2))
            // the same two rows, asked for further along: 1 + 2 is not short of 3
            val later = library.answer(PackQuestion(PackQuestion.ARTISTS, offset = 1, limit = 2))

            assertEquals(2, first.artists.size)
            assertTrue(first.more)
            assertFalse(later.more)
        }

    @Test
    fun `a full page that reaches the total has no more`() =
        runTest {
            // four asked for, four answered, four in total
            val answer = library(Jellyfin.ITEMS, "albums.json").answer(PackQuestion(PackQuestion.ALBUMS, limit = 4))

            assertEquals(4, answer.albums.size)
            assertFalse(answer.more)
        }

    @Test
    fun `the catalog is every album in name order, with its year and its length`() =
        runTest {
            val http = FakeJellyfinHttp(mapOf(Jellyfin.ITEMS to recorded("albums.json")))

            val answer = JellyfinLibrary(http, SERVER).answer(PackQuestion(PackQuestion.ALBUMS, offset = 20, limit = 10))

            assertEquals(
                mapOf(
                    "userId" to DEMO_USER,
                    "StartIndex" to "20",
                    "Limit" to "10",
                    "IncludeItemTypes" to "MusicAlbum",
                    "Recursive" to "true",
                    "SortBy" to "SortName",
                    "SortOrder" to "Ascending",
                    "Fields" to "ChildCount",
                ) + LEAN,
                http.once().params,
            )
            val nemesis = answer.albums.first()
            assertEquals("Nemesis", nemesis.title)
            assertEquals("Leap Fidei", nemesis.artist)
            assertEquals(2024, nemesis.year)
            assertEquals(1, nemesis.trackCount)
            assertEquals(AlbumKind.ALBUM.name, nemesis.kind)
            // an album with no year in the answer
            val promo = answer.albums.first { it.title == "Promo" }
            assertEquals(UNKNOWN_COUNT, promo.year)
            assertEquals(2, promo.trackCount)
        }

    @Test
    fun `a named artist's records are asked for by album artist, oldest first`() =
        runTest {
            val http = FakeJellyfinHttp(mapOf(Jellyfin.ITEMS to recorded("albums-by-artist.json")))

            val question = PackQuestion(PackQuestion.ALBUMS, id = "6330eeddf969d120c0c4c054770f0ee0")
            val answer = JellyfinLibrary(http, SERVER).answer(question)

            val params = http.once().params
            assertEquals("6330eeddf969d120c0c4c054770f0ee0", params["AlbumArtistIds"])
            assertEquals("ProductionYear,SortName", params["SortBy"])
            assertEquals(listOf("Thraximundar"), answer.albums.map { it.title })
        }

    @Test
    fun `an artist whose albums carry no album-artist tag is found by the folder they are in`() =
        runTest {
            val http =
                FakeJellyfinHttp { ask ->
                    val byTag = ask.params.containsKey("AlbumArtistIds")
                    recorded(if (byTag) "albums-by-artist-none.json" else "albums-under-artist.json")
                }

            val question = PackQuestion(PackQuestion.ALBUMS, id = "9ebcc113abb3ee375dbf1d68c9576ed5")
            val answer = JellyfinLibrary(http, SERVER).answer(question)

            // Binaerpilot on the demo: no albums by `AlbumArtistIds`, two by `ParentId`
            assertEquals(2, http.asked.size)
            assertEquals("9ebcc113abb3ee375dbf1d68c9576ed5", http.asked.last().params["ParentId"])
            assertEquals(listOf("Promo", "You Can't Stop Da Funk"), answer.albums.map { it.title })
            // neither album has an album artist or credits
            assertEquals("", answer.albums.first().artist)
            assertFalse(answer.failed)
        }

    @Test
    fun `an artist with albums by tag is asked once`() =
        runTest {
            val http = FakeJellyfinHttp(mapOf(Jellyfin.ITEMS to recorded("albums-by-artist.json")))

            JellyfinLibrary(http, SERVER).answer(PackQuestion(PackQuestion.ALBUMS, id = "6330eeddf969d120c0c4c054770f0ee0"))

            assertEquals(1, http.asked.size)
        }

    @Test
    fun `an album's tracks are asked for in disc and track order, with their media`() =
        runTest {
            val http = FakeJellyfinHttp(mapOf(Jellyfin.ITEMS to recorded("tracks.json")))

            val question = PackQuestion(PackQuestion.TRACKS, id = "920673748d7c8a4aa03144614f1f947f")
            val answer = JellyfinLibrary(http, SERVER).answer(question)

            assertEquals(
                mapOf(
                    "userId" to DEMO_USER,
                    "StartIndex" to "0",
                    "Limit" to PackQuestion.PAGE.toString(),
                    "ParentId" to "920673748d7c8a4aa03144614f1f947f",
                    "IncludeItemTypes" to "Audio",
                    "Recursive" to "true",
                    "SortBy" to "ParentIndexNumber,IndexNumber,SortName",
                    "SortOrder" to "Ascending",
                    "Fields" to "MediaSources",
                ) + LEAN,
                http.once().params,
            )
            assertEquals(listOf("Thunderdays", "Cornered! (Promo Edit)"), answer.tracks.map { it.title })
            assertEquals("jellyfin:track:933e3a903b3ea327b4acfbeb46445b92", answer.tracks.first().id)
            assertFalse(answer.more)
        }

    @Test
    fun `the playlists say how long they are, and an empty one really is empty`() =
        runTest {
            val http = FakeJellyfinHttp(mapOf(Jellyfin.ITEMS to recorded("playlists.json")))

            val answer = JellyfinLibrary(http, SERVER).answer(PackQuestion(PackQuestion.PLAYLISTS))

            assertEquals("Playlist", http.once().params["IncludeItemTypes"])
            assertEquals("ChildCount", http.once().params["Fields"])
            assertEquals(listOf("Binaerpilot", "Horror", "kids", "Test"), answer.playlists.map { it.name })
            assertEquals("d970eee82134b487f1df37d753fab1f9", answer.playlists.first().id)
            assertEquals(3, answer.playlists.first().trackCount)
            assertEquals(0, answer.playlists.last().trackCount)
        }

    @Test
    fun `a playlist's tracks come from the playlist, in its order`() =
        runTest {
            val path = Jellyfin.playlistItems("d970eee82134b487f1df37d753fab1f9")
            val http = FakeJellyfinHttp(mapOf(path to recorded("playlist-items.json")))

            val question = PackQuestion(PackQuestion.PLAYLIST_TRACKS, id = "d970eee82134b487f1df37d753fab1f9", limit = 50)
            val answer = JellyfinLibrary(http, SERVER).answer(question)

            assertEquals("Playlists/d970eee82134b487f1df37d753fab1f9/Items", http.once().path)
            assertEquals("MediaSources", http.once().params["Fields"])
            assertEquals(listOf("Thunderdays", "Cornered! (Promo Edit)", "Bend"), answer.tracks.map { it.title })
        }

    @Test
    fun `a playlist of films opens to no rows, and paging past them is not thrown off`() =
        runTest {
            val path = Jellyfin.playlistItems("5309f2cdaa2c66dd4a70c09518bca8b4")
            val http = FakeJellyfinHttp(mapOf(path to recorded("playlist-items-video.json")))

            val question = PackQuestion(PackQuestion.PLAYLIST_TRACKS, id = "5309f2cdaa2c66dd4a70c09518bca8b4", limit = 1)
            val answer = JellyfinLibrary(http, SERVER).answer(question)

            // the demo's "Horror" playlist: two items of type Movie
            assertEquals(emptyList<Any>(), answer.tracks)
            assertFalse(answer.failed)
            // two entries on the page and a total of two
            assertFalse(answer.more)
        }

    @Test
    fun `a search asks for tracks and pages by the server's total`() =
        runTest {
            val http = FakeJellyfinHttp(mapOf(Jellyfin.ITEMS to recorded("search-tracks.json")))

            val question = PackQuestion(PackQuestion.SEARCH, query = " e ", offset = 0, limit = 2)
            val answer = JellyfinLibrary(http, SERVER).answer(question)

            val params = http.once().params
            assertEquals("e", params["searchTerm"])
            assertEquals("Audio", params["IncludeItemTypes"])
            assertEquals("true", params["Recursive"])
            assertEquals("2", params["Limit"])
            assertEquals(listOf("Thraximundar (He Who Paints The Earth Red)", "Bend"), answer.tracks.map { it.title })
            // two of five
            assertTrue(answer.more)
        }

    @Test
    fun `a search for artists asks every artist, not only the album artists`() =
        runTest {
            val http = FakeJellyfinHttp(mapOf(Jellyfin.ARTISTS to recorded("search-artists.json")))

            val question = PackQuestion(PackQuestion.FIND_ARTISTS, query = "i", offset = 0, limit = 2)
            val answer = JellyfinLibrary(http, SERVER).answer(question)

            assertEquals(Jellyfin.ARTISTS, http.once().path)
            assertEquals("i", http.once().params["searchTerm"])
            assertEquals(listOf("Joshua Boniface", "Leap Fidei"), answer.artists.map { it.name })
            assertTrue(answer.more)
        }

    @Test
    fun `an empty search asks the server nothing`() =
        runTest {
            val http = FakeJellyfinHttp(mapOf(Jellyfin.ITEMS to recorded("search-tracks.json")))

            val tracks = JellyfinLibrary(http, SERVER).answer(PackQuestion(PackQuestion.SEARCH, query = "   "))
            val artists = JellyfinLibrary(http, SERVER).answer(PackQuestion(PackQuestion.FIND_ARTISTS, query = ""))

            assertEquals(emptyList<Any>(), http.asked)
            assertEquals(PackAnswer(), tracks)
            assertEquals(PackAnswer(), artists)
        }

    @Test
    fun `a limit of nought is asked as the contract's page`() =
        runTest {
            val http = FakeJellyfinHttp(mapOf(Jellyfin.ALBUM_ARTISTS to recorded("artists.json")))

            JellyfinLibrary(http, SERVER).answer(PackQuestion(PackQuestion.ARTISTS, offset = -3, limit = 0))

            assertEquals(PackQuestion.PAGE.toString(), http.once().params["Limit"])
            assertEquals("0", http.once().params["StartIndex"])
        }

    @Test
    fun `a revoked token is a failure, and signs the listener out`() =
        runTest {
            var signedOut = 0
            val http = FakeJellyfinHttp(mapOf(Jellyfin.ALBUM_ARTISTS to JellyfinReply.read(401, fixture("unauthorized.html"))))

            val answer = JellyfinLibrary(http, SERVER) { signedOut++ }.answer(PackQuestion(PackQuestion.ARTISTS))

            assertTrue(answer.failed)
            assertEquals(emptyList<Any>(), answer.artists)
            assertEquals(1, signedOut)
        }

    @Test
    fun `a status that is not success is a failure and does not sign anybody out`() =
        runTest {
            var signedOut = 0
            val http = FakeJellyfinHttp(mapOf(Jellyfin.ITEMS to JellyfinReply.Rejected(500)))

            val answer = JellyfinLibrary(http, SERVER) { signedOut++ }.answer(PackQuestion(PackQuestion.TRACKS, id = "abc"))

            assertTrue(answer.failed)
            assertEquals(0, signedOut)
        }

    @Test
    fun `a server that cannot be reached is a failure and never an empty shelf`() =
        runTest {
            val http = FakeJellyfinHttp(mapOf(Jellyfin.ITEMS to JellyfinReply.Unreachable("no route to host")))

            val answer = JellyfinLibrary(http, SERVER).answer(PackQuestion(PackQuestion.PLAYLISTS))

            assertTrue(answer.failed)
            assertEquals(emptyList<Any>(), answer.playlists)
            assertFalse(answer.more)
        }

    @Test
    fun `an answer that is not an object is a failure`() =
        runTest {
            val http = FakeJellyfinHttp(mapOf(Jellyfin.ITEMS to JellyfinReply.Answered(true)))

            assertTrue(JellyfinLibrary(http, SERVER).answer(PackQuestion(PackQuestion.ALBUMS)).failed)
        }

    @Test
    fun `a failure on the first of an artist's two questions is a failure, not a second question`() =
        runTest {
            val http = FakeJellyfinHttp { JellyfinReply.Unreachable("timeout") }

            val answer = JellyfinLibrary(http, SERVER).answer(PackQuestion(PackQuestion.ALBUMS, id = "x"))

            assertTrue(answer.failed)
            assertEquals(1, http.asked.size)
        }

    @Test
    fun `a question this source has never heard of fails rather than answering nothing`() =
        runTest {
            assertTrue(library(Jellyfin.ITEMS, "albums.json").answer(PackQuestion("sonnets")).failed)
        }

    private fun library(
        path: String,
        recording: String,
    ): JellyfinLibrary = JellyfinLibrary(FakeJellyfinHttp(mapOf(path to recorded(recording))), SERVER)

    private companion object {
        val LEAN = mapOf("EnableUserData" to "false", "EnableImageTypes" to "Primary", "ImageTypeLimit" to "1")
    }
}
