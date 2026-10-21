// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.packapi.UNKNOWN_COUNT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The folders and the genres, answered from the files in `src/test/resources`;
 * see [FakeJellyfinHttp].
 *
 * The walk over the library is `walk-1.json` from 0 and `walk-2.json` from 3:
 * five tracks, four of them in three folders, and one whose file the server
 * does not give.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JellyfinGroupsTest {
    @Test
    fun `the genres are the genres of tracks, by name, a page at a time`() =
        runTest {
            val http = FakeJellyfinHttp(mapOf(Jellyfin.MUSIC_GENRES to recorded("genres.json")))

            val answer = JellyfinLibrary(http, SERVER).answer(PackQuestion(PackQuestion.GENRES, offset = 0, limit = 3))

            assertEquals(Jellyfin.MUSIC_GENRES, http.once().path)
            assertEquals(
                mapOf(
                    "userId" to DEMO_USER,
                    "StartIndex" to "0",
                    "Limit" to "3",
                    "IncludeItemTypes" to "Audio",
                    "SortBy" to "SortName",
                    "SortOrder" to "Ascending",
                    "Fields" to "ItemCounts",
                ) + LEAN,
                http.once().params,
            )
            assertEquals(listOf("Chiptune", "Electronic", "Industrial"), answer.playlists.map { it.name })
            assertEquals("5b0e6c1f8a7d4e2b9c3f1a6d8e2b4c7a", answer.playlists.first().id)
            assertEquals(null, answer.playlists.first().owner)
            // three of four
            assertTrue(answer.more)
            assertFalse(answer.failed)
        }

    @Test
    fun `a genre's length is its number of tracks and not of albums`() =
        runTest {
            val answer = library(Jellyfin.MUSIC_GENRES, "genres.json").answer(PackQuestion(PackQuestion.GENRES))

            assertEquals(listOf(2, 3, 1), answer.playlists.map { it.trackCount })
        }

    @Test
    fun `a genre the server does not count has an unknown length`() =
        runTest {
            val answer = library(Jellyfin.MUSIC_GENRES, "genres-uncounted.json").answer(PackQuestion(PackQuestion.GENRES))

            assertEquals(listOf(UNKNOWN_COUNT, UNKNOWN_COUNT), answer.playlists.map { it.trackCount })
            assertFalse(answer.more)
        }

    @Test
    fun `a genre's tracks are asked for by its id, by album and track, a page at a time`() =
        runTest {
            val http = FakeJellyfinHttp(mapOf(Jellyfin.ITEMS to recorded("search-tracks.json")))
            val library = JellyfinLibrary(http, SERVER)

            val first = library.answer(PackQuestion(PackQuestion.GENRE_TRACKS, id = ELECTRONIC, offset = 0, limit = 2))
            // the same two rows, asked for from 3: 3 + 2 is not short of 5
            val last = library.answer(PackQuestion(PackQuestion.GENRE_TRACKS, id = ELECTRONIC, offset = 3, limit = 2))

            assertEquals(
                mapOf(
                    "userId" to DEMO_USER,
                    "StartIndex" to "0",
                    "Limit" to "2",
                    "Fields" to "MediaSources",
                    "GenreIds" to ELECTRONIC,
                ) + EVERY_TRACK + LEAN,
                http.asked.first().params,
            )
            assertEquals("3", http.asked.last().params["StartIndex"])
            assertEquals(listOf("Thraximundar (He Who Paints The Earth Red)", "Bend"), first.tracks.map { it.title })
            assertTrue(first.more)
            assertFalse(last.more)
        }

    @Test
    fun `the folders are walked from every track, a page of five hundred at a time`() =
        runTest {
            val http = walking()

            JellyfinLibrary(http, SERVER).answer(PackQuestion(PackQuestion.FOLDERS))

            assertEquals(2, http.asked.size)
            assertEquals(
                mapOf(
                    "userId" to DEMO_USER,
                    "StartIndex" to "0",
                    "Limit" to "500",
                    "Fields" to "MediaSources,Path",
                ) + EVERY_TRACK + LEAN,
                http.asked.first().params,
            )
            // the second page starts after the three tracks on the first
            assertEquals("3", http.asked.last().params["StartIndex"])
            assertEquals(listOf(Jellyfin.ITEMS, Jellyfin.ITEMS), http.asked.map { it.path })
        }

    @Test
    fun `a folder is named from the folder above the one they all share, and counts its tracks`() =
        runTest {
            val answer = JellyfinLibrary(walking(), SERVER).answer(PackQuestion(PackQuestion.FOLDERS))

            assertEquals(
                listOf(
                    "Music/Binaerpilot/Promo",
                    "Music/Binaerpilot/You Can't Stop Da Funk",
                    "Music/Joshua Boniface/Into Aether",
                ),
                answer.playlists.map { it.name },
            )
            assertEquals(listOf(PROMO, FUNK, INTO_AETHER), answer.playlists.map { it.id })
            assertEquals(listOf(2, 1, 1), answer.playlists.map { it.trackCount })
            assertEquals(null, answer.playlists.first().owner)
            assertFalse(answer.more)
            assertFalse(answer.failed)
        }

    @Test
    fun `a track whose file the server does not give is in no folder`() =
        runTest {
            val answer = JellyfinLibrary(walking(), SERVER).answer(PackQuestion(PackQuestion.FOLDERS))

            // Leap Fidei's track has no `Path`
            assertTrue(answer.playlists.none { "Leap Fidei" in it.name })
            assertEquals(4, answer.playlists.sumOf { it.trackCount })
        }

    @Test
    fun `a later page of the folders is cut from the walk and asks the server nothing`() =
        runTest {
            val http = walking()
            val library = JellyfinLibrary(http, SERVER)

            val first = library.answer(PackQuestion(PackQuestion.FOLDERS, offset = 0, limit = 2))
            val second = library.answer(PackQuestion(PackQuestion.FOLDERS, offset = 2, limit = 2))

            assertEquals(listOf(PROMO, FUNK), first.playlists.map { it.id })
            assertTrue(first.more)
            assertEquals(listOf(INTO_AETHER), second.playlists.map { it.id })
            assertFalse(second.more)
            assertEquals(2, http.asked.size)
        }

    @Test
    fun `the first page of the folders walks the library again`() =
        runTest {
            val http = walking()
            val library = JellyfinLibrary(http, SERVER)

            library.answer(PackQuestion(PackQuestion.FOLDERS))
            library.answer(PackQuestion(PackQuestion.FOLDERS))

            assertEquals(4, http.asked.size)
        }

    @Test
    fun `a folder's tracks come from the kept walk, in its order, without a new call`() =
        runTest {
            val http = walking()
            val library = JellyfinLibrary(http, SERVER)
            library.answer(PackQuestion(PackQuestion.FOLDERS))

            val first = library.answer(PackQuestion(PackQuestion.FOLDER_TRACKS, id = PROMO, offset = 0, limit = 1))
            val second = library.answer(PackQuestion(PackQuestion.FOLDER_TRACKS, id = PROMO, offset = 1, limit = 1))

            assertEquals(2, http.asked.size)
            assertEquals(listOf("Thunderdays"), first.tracks.map { it.title })
            assertEquals("jellyfin:track:933e3a903b3ea327b4acfbeb46445b92", first.tracks.single().id)
            // read from the media source, as every other track row
            assertEquals(192, first.tracks.single().bitrateKbps)
            assertTrue(first.more)
            assertEquals(listOf("Cornered! (Promo Edit)"), second.tracks.map { it.title })
            assertFalse(second.more)
        }

    @Test
    fun `a folder's tracks walk the library first when no walk is kept`() =
        runTest {
            val http = walking()
            val library = JellyfinLibrary(http, SERVER)

            val answer = library.answer(PackQuestion(PackQuestion.FOLDER_TRACKS, id = INTO_AETHER))
            library.answer(PackQuestion(PackQuestion.FOLDER_TRACKS, id = FUNK))

            assertEquals(listOf("Thraximundar (He Who Paints The Earth Red)"), answer.tracks.map { it.title })
            assertFalse(answer.failed)
            // one walk of two pages, kept for the second folder
            assertEquals(2, http.asked.size)
        }

    @Test
    fun `a folder the walk does not know is empty`() =
        runTest {
            val answer = JellyfinLibrary(walking(), SERVER).answer(PackQuestion(PackQuestion.FOLDER_TRACKS, id = "/media/Films"))

            assertEquals(emptyList<Any>(), answer.tracks)
            assertFalse(answer.failed)
            assertFalse(answer.more)
        }

    @Test
    fun `questions that come in together share one walk`() =
        runTest {
            val fake = walking()
            val slow =
                object : JellyfinHttp by fake {
                    override suspend fun get(
                        path: String,
                        params: Map<String, String>,
                    ): JellyfinReply {
                        delay(SLOW_MS)
                        return fake.get(path, params)
                    }
                }
            val library = JellyfinLibrary(slow, SERVER)

            val promo = async { library.answer(PackQuestion(PackQuestion.FOLDER_TRACKS, id = PROMO)) }
            val funk = async { library.answer(PackQuestion(PackQuestion.FOLDER_TRACKS, id = FUNK)) }

            assertEquals(2, promo.await().tracks.size)
            assertEquals(1, funk.await().tracks.size)
            assertEquals(2, fake.asked.size)
        }

    @Test
    fun `a failed page of the walk fails the folders and keeps nothing`() =
        runTest {
            val http =
                FakeJellyfinHttp { ask ->
                    if (ask.params["StartIndex"] == "0") page(ask) else JellyfinReply.Rejected(500)
                }
            val library = JellyfinLibrary(http, SERVER)

            val folders = library.answer(PackQuestion(PackQuestion.FOLDERS))
            val tracks = library.answer(PackQuestion(PackQuestion.FOLDER_TRACKS, id = PROMO))

            assertTrue(folders.failed)
            assertEquals(emptyList<Any>(), folders.playlists)
            // nothing was kept, so the folder's tracks walked again, and failed again
            assertTrue(tracks.failed)
            assertEquals(4, http.asked.size)
        }

    @Test
    fun `a failed walk leaves the walk kept before it`() =
        runTest {
            var up = true
            val http = FakeJellyfinHttp { ask -> if (up) page(ask) else JellyfinReply.Unreachable("timeout") }
            val library = JellyfinLibrary(http, SERVER)
            library.answer(PackQuestion(PackQuestion.FOLDERS))
            up = false

            val again = library.answer(PackQuestion(PackQuestion.FOLDERS))
            val tracks = library.answer(PackQuestion(PackQuestion.FOLDER_TRACKS, id = PROMO))

            assertTrue(again.failed)
            assertEquals(2, tracks.tracks.size)
            assertFalse(tracks.failed)
        }

    @Test
    fun `a 401 on a page of the walk fails the folders and signs the listener out`() =
        runTest {
            var signedOut = 0
            val http =
                FakeJellyfinHttp { ask ->
                    if (ask.params["StartIndex"] == "0") page(ask) else JellyfinReply.Unauthorized
                }

            val answer = JellyfinLibrary(http, SERVER) { signedOut++ }.answer(PackQuestion(PackQuestion.FOLDERS))

            assertTrue(answer.failed)
            assertEquals(1, signedOut)
        }

    @Test
    fun `an empty library has no folders, which is an answer and not a failure`() =
        runTest {
            val none = JellyfinReply.read(200, """{"Items":[],"TotalRecordCount":0,"StartIndex":0}""")
            val http = FakeJellyfinHttp(mapOf(Jellyfin.ITEMS to none))

            val answer = JellyfinLibrary(http, SERVER).answer(PackQuestion(PackQuestion.FOLDERS))

            assertEquals(1, http.asked.size)
            assertEquals(emptyList<Any>(), answer.playlists)
            assertFalse(answer.failed)
            assertFalse(answer.more)
        }

    /** A server that answers the walk's two pages. */
    private fun walking(): FakeJellyfinHttp = FakeJellyfinHttp(::page)

    /** The walk's page that starts at the call's `StartIndex`; any other start is unreachable. */
    private fun page(ask: FakeJellyfinHttp.Ask): JellyfinReply =
        when (ask.params["StartIndex"]) {
            "0" -> recorded("walk-1.json")
            "3" -> recorded("walk-2.json")
            else -> JellyfinReply.Unreachable("no page from ${ask.params["StartIndex"]}")
        }

    private fun library(
        path: String,
        file: String,
    ): JellyfinLibrary = JellyfinLibrary(FakeJellyfinHttp(mapOf(path to recorded(file))), SERVER)

    private companion object {
        val LEAN = mapOf("EnableUserData" to "false", "EnableImageTypes" to "Primary", "ImageTypeLimit" to "1")

        /** What every query over all tracks sends: tracks only, the whole library, by album artist, album, disc and track. */
        val EVERY_TRACK =
            mapOf(
                "IncludeItemTypes" to "Audio",
                "Recursive" to "true",
                "SortBy" to "AlbumArtist,Album,ParentIndexNumber,IndexNumber,SortName",
                "SortOrder" to "Ascending",
            )

        const val ELECTRONIC = "e41f9a2c7b6d4f80a3e5c1b9d7f2a6e4"
        const val PROMO = "/media/Music/Binaerpilot/Promo"
        const val FUNK = "/media/Music/Binaerpilot/You Can't Stop Da Funk"
        const val INTO_AETHER = "/media/Music/Joshua Boniface/Into Aether"
        const val SLOW_MS = 10L
    }
}
