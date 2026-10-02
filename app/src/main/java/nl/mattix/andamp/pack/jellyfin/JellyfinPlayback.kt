// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.AudioOut
import nl.mattix.andamp.core.playback.NetworkWatch
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.pack.common.stream.StreamPlayback
import nl.mattix.andamp.pack.common.stream.StreamRequest

/**
 * Builds the playback backend for a Jellyfin server.
 *
 * Playback is the SDK's [StreamPlayback]. This object supplies where a row's
 * audio is: the `universal` URL with the token in an `Authorization` header;
 * see [JellyfinStream].
 */
internal object JellyfinPlayback {
    /**
     * A backend over [server]. [server] is called each time a row is opened,
     * so a token replaced by a new sign-in is used from the next open.
     */
    fun backend(
        server: () -> JellyfinServer?,
        tracks: List<Track>,
        startIndex: Int,
        scope: CoroutineScope,
        out: AudioOut?,
        network: NetworkWatch,
    ): PlaybackBackend =
        StreamPlayback.backend(
            tracks = tracks,
            startIndex = startIndex,
            scope = scope,
            out = out,
            network = network,
            locate = { track, positionMs -> request(server(), track, Build.VERSION.SDK_INT, positionMs) },
        )

    /**
     * Where one row's audio is. Null when nobody is signed in or when the row's
     * address is not one of this source's.
     *
     * [sdk] is the phone's API level, passed in so a JVM test can choose it.
     */
    fun request(
        server: JellyfinServer?,
        track: Track,
        sdk: Int,
        positionMs: Long = 0,
    ): StreamRequest? {
        val signedIn = server ?: return null
        val address = track.uri ?: return null
        return JellyfinStream.request(signedIn, address, sdk, track.defaultName, positionMs)
    }
}
