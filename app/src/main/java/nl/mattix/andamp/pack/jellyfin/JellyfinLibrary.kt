// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.mattix.andamp.core.model.AlbumKind
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.packapi.PackAlbum
import nl.mattix.andamp.core.packapi.PackAnswer
import nl.mattix.andamp.core.packapi.PackArtist
import nl.mattix.andamp.core.packapi.PackPlaylist
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.packapi.PackTrack
import nl.mattix.andamp.core.packapi.UNKNOWN_COUNT
import nl.mattix.andamp.core.packapi.toPack
import nl.mattix.andamp.pack.common.TrackFolders
import org.json.JSONArray
import org.json.JSONObject

/**
 * The server's library, answered one [PackQuestion] at a time. Each question is
 * one call to the server (two for an artist's albums, see [artistAlbums]),
 * except the questions about folders.
 *
 * The folders come from the path of each track's file, read in a walk over
 * every track in the library; see [walk]. The last whole walk is kept in
 * memory. The first page of the folders walks again, and the later pages and
 * the tracks in a folder are answered from the kept walk. Nothing else is
 * kept.
 *
 * The server pages every other query: `StartIndex` and `Limit` go out and
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
    /** Held while a walk runs or the kept walk is read, so one binder thread walks at a time; see [walked]. */
    private val walking = Mutex()

    /** The last whole walk, replaced only while [walking] is held. */
    @Volatile
    private var kept: Walk? = null

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
            PackQuestion.GENRES -> genres(question)
            PackQuestion.GENRE_TRACKS -> genreTracks(question)
            PackQuestion.FOLDERS -> folders(question)
            PackQuestion.FOLDER_TRACKS -> folderTracks(question)
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

    /**
     * The genres this user's tracks are tagged with, by name, from
     * [Jellyfin.MUSIC_GENRES]. `IncludeItemTypes` keeps to the genres of
     * tracks, and with it and `ItemCounts` the server gives each genre's
     * `SongCount`.
     */
    private suspend fun genres(question: PackQuestion): PackAnswer {
        val params =
            paged(question) +
                mapOf(INCLUDE to AUDIO, SORT_BY to SORT_NAME, SORT_ORDER to ASCENDING, FIELDS to "ItemCounts")
        return asked(Jellyfin.MUSIC_GENRES, params) { body ->
            PackAnswer(playlists = items(body).map(::genreOf), more = more(body, question))
        }
    }

    /** The tracks tagged with one genre, in the order of [EVERY_TRACK]. */
    private suspend fun genreTracks(question: PackQuestion): PackAnswer {
        val params = paged(question) + TRACK_FIELDS + EVERY_TRACK + mapOf("GenreIds" to question.id)
        return asked(Jellyfin.ITEMS, params) { body ->
            PackAnswer(tracks = JellyfinRow.tracks(body.optJSONArray(ITEMS), server), more = more(body, question))
        }
    }

    /**
     * The folders that hold tracks, by name, each with its number of tracks.
     * The first page walks the library again. A later page is cut from the
     * kept walk, so the pages fit together.
     */
    private suspend fun folders(question: PackQuestion): PackAnswer {
        val walk = walked(again = question.from() == 0) ?: return FAILED
        val groups = walk.folders.groups()
        return PackAnswer(playlists = question.cut(groups).map { it.toPack() }, more = question.endsBefore(groups.size))
    }

    /**
     * The tracks in one folder, in the order of the walk, from the kept walk.
     * The folder's id is its path. With no walk kept, the library is walked
     * first. A folder the walk does not know is answered empty.
     */
    private suspend fun folderTracks(question: PackQuestion): PackAnswer {
        val walk = walked(again = false) ?: return FAILED
        val ids = walk.folders.trackIds(question.id)
        return PackAnswer(tracks = question.cut(ids).mapNotNull(walk.tracks::get), more = question.endsBefore(ids.size))
    }

    /**
     * The kept walk, or a new one when [again] is true or none is kept. Null
     * when the new walk fails, and the walk kept before stays kept.
     *
     * A question that comes in while a walk runs waits for it, and takes the
     * walk that ended meanwhile as it is, so questions that arrive together
     * walk once.
     */
    private suspend fun walked(again: Boolean): Walk? {
        val before = kept
        return walking.withLock {
            val now = kept
            if (now != null && (!again || now !== before)) now else walk()?.also { kept = it }
        }
    }

    /**
     * Every track in the library with the path of its file, [WALK_PAGE] at a
     * time in the order of [EVERY_TRACK], until the server's total is reached,
     * grouped by folder. Null when a page fails.
     *
     * `Path` is the file's path on the server. A track without one is left
     * out.
     */
    private suspend fun walk(): Walk? {
        val files = mutableListOf<Pair<String, String>>()
        val rows = HashMap<String, PackTrack>()
        var from = 0
        do {
            val page = PackQuestion(PackQuestion.FOLDERS, offset = from, limit = WALK_PAGE)
            val body = body(http.get(Jellyfin.ITEMS, paged(page) + EVERY_TRACK + WALK_FIELDS)) ?: return null
            for (item in items(body)) {
                val row = JellyfinRow.track(item, server) ?: continue
                val file = if (item.isNull(PATH)) "" else item.optString(PATH)
                // a track on two pages is kept once
                if (file.isEmpty() || row.id in rows) continue
                files += row.id to file
                rows[row.id] = row
            }
            val onPage = body.optJSONArray(ITEMS)?.length() ?: 0
            from += onPage
        } while (onPage > 0 && more(body, page))
        return Walk(TrackFolders.of(files), rows)
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
    ): PackAnswer = body(reply)?.let(answer) ?: FAILED

    /** A reply's JSON object, or null for anything else. A 401 also calls [signedOut]. */
    private fun body(reply: JellyfinReply): JSONObject? =
        when (reply) {
            is JellyfinReply.Answered -> {
                reply.body as? JSONObject
            }

            JellyfinReply.Unauthorized -> {
                signedOut()
                null
            }

            else -> {
                null
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

    private fun genreOf(json: JSONObject): PackPlaylist =
        PackPlaylist(id = json.optString("Id"), name = json.optString("Name"), trackCount = count(json, "SongCount"))

    /** One walk over the library: its folders, and the rows of their tracks by [PackTrack.id]. */
    private class Walk(
        val folders: TrackFolders,
        val tracks: Map<String, PackTrack>,
    )

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

        /** Asks for what [TRACK_FIELDS] asks for, and for `Path`, which the folders are made from. */
        private val WALK_FIELDS = mapOf(FIELDS to "MediaSources,Path")

        /** Every track in the library, by album artist, album, disc, track and name. */
        private val EVERY_TRACK =
            mapOf(
                INCLUDE to AUDIO,
                RECURSIVE to TRUE,
                SORT_BY to "AlbumArtist,Album,ParentIndexNumber,IndexNumber,SortName",
                SORT_ORDER to ASCENDING,
            )

        /** The size of one page of the walk. */
        private const val WALK_PAGE = 500

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
        private const val PATH = "Path"
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

/** This question's page of [rows], for an answer cut from a list held here. An offset past the end gives an empty page. */
private fun <T> PackQuestion.cut(rows: List<T>): List<T> {
    val start = from().coerceAtMost(rows.size)
    return rows.subList(start, start + size().coerceAtMost(rows.size - start)).toList()
}

/** Whether rows follow this question's page in a list of [total] rows. */
private fun PackQuestion.endsBefore(total: Int): Boolean = size() < total - from()
