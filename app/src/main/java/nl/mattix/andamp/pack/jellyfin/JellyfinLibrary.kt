// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin

import nl.mattix.andamp.core.model.AlbumKind
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.packapi.PackAlbum
import nl.mattix.andamp.core.packapi.PackAnswer
import nl.mattix.andamp.core.packapi.PackArtist
import nl.mattix.andamp.core.packapi.PackPlaylist
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.packapi.UNKNOWN_COUNT
import org.json.JSONArray
import org.json.JSONObject

/**
 * The server's library, answered one [PackQuestion] at a time. Each question is
 * one call to the server (two for an artist's albums, see [artistAlbums]);
 * nothing is cached.
 *
 * The server pages every query: `StartIndex` and `Limit` go out and
 * `TotalRecordCount` comes back, which [PackAnswer.more] is computed from.
 *
 * Every query carries the signed-in user's `userId`, so the server applies
 * that user's library access.
 *
 * A call that fails in any way is answered with `failed = true`, never as an
 * empty page. A 401 also calls [signedOut], because it means the token is not
 * valid and asking again will not help.
 *
 * A count the server does not give is [UNKNOWN_COUNT]. Artist counts are never
 * read and are always unknown.
 */
class JellyfinLibrary(
    private val http: JellyfinHttp,
    /** The user the queries are asked for, and the address the cover URLs are built on. */
    private val server: JellyfinServer,
    /** Called on every 401. */
    private val signedOut: () -> Unit = {},
) {
    /** One page of one question. An unknown kind is a failure. */
    suspend fun answer(question: PackQuestion): PackAnswer =
        when (question.kind) {
            PackQuestion.ARTISTS -> artists(question)
            PackQuestion.ALBUMS -> if (question.id.isEmpty()) everyAlbum(question) else artistAlbums(question)
            PackQuestion.TRACKS -> tracks(question)
            PackQuestion.PLAYLISTS -> playlists(question)
            PackQuestion.PLAYLIST_TRACKS -> playlistTracks(question)
            PackQuestion.SEARCH -> search(question)
            PackQuestion.FIND_ARTISTS -> findArtists(question)
            else -> FAILED
        }

    /**
     * The artists that have albums, sorted by the server's `SortName`. The
     * `Artists` endpoint would also list every guest credited on a single
     * track.
     */
    private suspend fun artists(question: PackQuestion): PackAnswer =
        asked(Jellyfin.ALBUM_ARTISTS, paged(question) + mapOf(SORT_BY to SORT_NAME, SORT_ORDER to ASCENDING)) { body ->
            PackAnswer(artists = items(body).map(::artistOf), more = more(body, question))
        }

    /** Every album, a page at a time, in name order. */
    private suspend fun everyAlbum(question: PackQuestion): PackAnswer =
        asked(Jellyfin.ITEMS, albums(question) + mapOf(SORT_BY to SORT_NAME)) { body ->
            PackAnswer(albums = items(body).map(::albumOf), more = more(body, question))
        }

    /**
     * An artist's albums, oldest first.
     *
     * The first query is by `AlbumArtistIds`. When it answers a total of 0, a
     * second query asks for the albums whose `ParentId` is the artist. That
     * finds the albums of an artist that is a folder on the server and whose
     * albums carry no album-artist tag. Later pages make the same choice,
     * because it depends only on the total.
     */
    private suspend fun artistAlbums(question: PackQuestion): PackAnswer {
        val chronological = albums(question) + mapOf(SORT_BY to "ProductionYear,SortName")
        val tagged = http.get(Jellyfin.ITEMS, chronological + mapOf("AlbumArtistIds" to question.id))
        val found = tagged as? JellyfinReply.Answered
        val none = (found?.body as? JSONObject)?.optInt(TOTAL, -1) == 0
        val reply = if (none) http.get(Jellyfin.ITEMS, chronological + mapOf(PARENT_ID to question.id)) else tagged
        return read(reply) { body -> PackAnswer(albums = items(body).map(::albumOf), more = more(body, question)) }
    }

    /**
     * An album's tracks, sorted by the server on disc (`ParentIndexNumber`),
     * track (`IndexNumber`) and name. The query is recursive, so an album
     * split into disc folders is one album.
     */
    private suspend fun tracks(question: PackQuestion): PackAnswer {
        val params =
            paged(question) + TRACK_FIELDS +
                mapOf(
                    PARENT_ID to question.id,
                    INCLUDE to AUDIO,
                    RECURSIVE to TRUE,
                    SORT_BY to "ParentIndexNumber,IndexNumber,SortName",
                    SORT_ORDER to ASCENDING,
                )
        return asked(Jellyfin.ITEMS, params) { body ->
            PackAnswer(tracks = JellyfinRow.tracks(body.optJSONArray(ITEMS), server), more = more(body, question))
        }
    }

    /**
     * The playlists this user can see, by name.
     *
     * Playlists of video are not filtered out here. Their entries are dropped
     * when the playlist is opened; see [JellyfinRow.track]. No owner is given.
     */
    private suspend fun playlists(question: PackQuestion): PackAnswer {
        val params =
            paged(question) +
                mapOf(
                    INCLUDE to "Playlist",
                    RECURSIVE to TRUE,
                    SORT_BY to SORT_NAME,
                    SORT_ORDER to ASCENDING,
                    FIELDS to CHILD_COUNT,
                )
        return asked(Jellyfin.ITEMS, params) { body ->
            PackAnswer(playlists = items(body).map(::playlistOf), more = more(body, question))
        }
    }

    /**
     * One playlist's entries from the playlist's own endpoint, in the
     * playlist's order, repeats included.
     *
     * Entries that are not tracks are dropped from the page but still count
     * toward where the next page starts.
     */
    private suspend fun playlistTracks(question: PackQuestion): PackAnswer =
        asked(Jellyfin.playlistItems(question.id), paged(question) + TRACK_FIELDS) { body ->
            PackAnswer(tracks = JellyfinRow.tracks(body.optJSONArray(ITEMS), server), more = more(body, question))
        }

    /** The server's search, for tracks. A blank query is answered empty with no call. */
    private suspend fun search(question: PackQuestion): PackAnswer {
        val terms = question.query.trim()
        if (terms.isEmpty()) return PackAnswer()
        val params = paged(question) + TRACK_FIELDS + mapOf(SEARCH_TERM to terms, INCLUDE to AUDIO, RECURSIVE to TRUE)
        return asked(Jellyfin.ITEMS, params) { body ->
            PackAnswer(tracks = JellyfinRow.tracks(body.optJSONArray(ITEMS), server), more = more(body, question))
        }
    }

    /**
     * Artists matching a search, from every artist and not only the album
     * artists, in the server's order. A blank query is answered empty with no
     * call.
     */
    private suspend fun findArtists(question: PackQuestion): PackAnswer {
        val terms = question.query.trim()
        if (terms.isEmpty()) return PackAnswer()
        return asked(Jellyfin.ARTISTS, paged(question) + mapOf(SEARCH_TERM to terms)) { body ->
            PackAnswer(artists = items(body).map(::artistOf), more = more(body, question))
        }
    }

    /** The parameters every album query has. */
    private fun albums(question: PackQuestion): Map<String, String> =
        paged(question) +
            mapOf(INCLUDE to "MusicAlbum", RECURSIVE to TRUE, SORT_ORDER to ASCENDING, FIELDS to CHILD_COUNT)

    /** The user, the start and the size of the page, and [LEAN]. */
    private fun paged(question: PackQuestion): Map<String, String> =
        mapOf(USER_ID to server.userId, START to question.from().toString(), LIMIT to question.size().toString()) + LEAN

    /** One `GET`, read with [read]. */
    private suspend fun asked(
        path: String,
        params: Map<String, String>,
        answer: (JSONObject) -> PackAnswer,
    ): PackAnswer = read(http.get(path, params), answer)

    /** A reply as an answer. Anything other than an answered JSON object is `failed`; a 401 also calls [signedOut]. */
    private fun read(
        reply: JellyfinReply,
        answer: (JSONObject) -> PackAnswer,
    ): PackAnswer =
        when (reply) {
            is JellyfinReply.Answered -> {
                (reply.body as? JSONObject)?.let(answer) ?: FAILED
            }

            JellyfinReply.Unauthorized -> {
                signedOut()
                FAILED
            }

            else -> {
                FAILED
            }
        }

    private fun artistOf(json: JSONObject): PackArtist = PackArtist(id = json.optString("Id"), name = json.optString("Name"))

    private fun albumOf(json: JSONObject): PackAlbum =
        PackAlbum(
            id = json.optString("Id"),
            title = json.optString("Name"),
            // the track credits when the album has no `AlbumArtist`
            artist = json.optString("AlbumArtist").ifEmpty { JellyfinRow.artist(json) },
            year = count(json, "ProductionYear"),
            trackCount = count(json, CHILD_COUNT),
            // no compilation flag is read from an album
            kind = AlbumKind.ALBUM.name,
        )

    private fun playlistOf(json: JSONObject): PackPlaylist =
        PackPlaylist(id = json.optString("Id"), name = json.optString("Name"), trackCount = count(json, CHILD_COUNT))

    companion object {
        /**
         * What this library can be asked, for `PackIdentity.descriptor`. A
         * constant, because the source describes itself before anybody is
         * signed in and a library exists.
         */
        val SHELVES =
            BrowseCapabilities(
                hasArtists = true,
                hasAlbums = true,
                canSearch = true,
                hasPlaylists = true,
                hasCatalogue = false,
            )

        private val FAILED = PackAnswer(failed = true)

        /** Leaves out what a browse query does not use: the user's play data, and every image tag except the cover's. */
        private val LEAN = mapOf("EnableUserData" to "false", "EnableImageTypes" to "Primary", "ImageTypeLimit" to "1")

        /** Asks for `MediaSources`, which a track row reads the file name, the bitrate and the sample rate from. */
        private val TRACK_FIELDS = mapOf(FIELDS to "MediaSources")

        private const val ITEMS = "Items"
        private const val TOTAL = "TotalRecordCount"
        private const val USER_ID = "userId"
        private const val START = "StartIndex"
        private const val LIMIT = "Limit"
        private const val SORT_BY = "SortBy"
        private const val SORT_ORDER = "SortOrder"
        private const val SORT_NAME = "SortName"
        private const val ASCENDING = "Ascending"
        private const val INCLUDE = "IncludeItemTypes"
        private const val RECURSIVE = "Recursive"
        private const val PARENT_ID = "ParentId"
        private const val SEARCH_TERM = "searchTerm"
        private const val FIELDS = "Fields"
        private const val CHILD_COUNT = "ChildCount"
        private const val AUDIO = "Audio"
        private const val TRUE = "true"
    }
}

/** The objects in an answer's `Items`, skipping anything that is not one. */
private fun items(body: JSONObject): List<JSONObject> {
    val array: JSONArray = body.optJSONArray("Items") ?: return emptyList()
    return (0 until array.length()).mapNotNull(array::optJSONObject)
}

/**
 * Whether there are rows after this page: the page's start plus the number of
 * items on it is below `TotalRecordCount`.
 *
 * The items are counted in the raw array, so an entry that was dropped when
 * read still counts. Without a total, a full page is taken to mean more.
 */
private fun more(
    body: JSONObject,
    question: PackQuestion,
): Boolean {
    val onPage = body.optJSONArray("Items")?.length() ?: 0
    val start = question.from()
    return if (body.has("TotalRecordCount")) {
        start + onPage < body.optInt("TotalRecordCount")
    } else {
        onPage >= question.size()
    }
}

/** A count the server gave, or [UNKNOWN_COUNT] when the key is absent. */
private fun count(
    json: JSONObject,
    name: String,
): Int = if (json.has(name)) json.optInt(name, UNKNOWN_COUNT) else UNKNOWN_COUNT

/** Where this question starts; a negative offset is read as 0. */
private fun PackQuestion.from(): Int = offset.coerceAtLeast(0)

/** The page size sent as `Limit`. A limit of 0 or less becomes [PackQuestion.PAGE]. */
private fun PackQuestion.size(): Int = if (limit <= 0) PackQuestion.PAGE else limit
