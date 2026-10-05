// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin.pack

import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.packapi.PackDescriptor
import nl.mattix.andamp.core.playback.PcmProvider
import nl.mattix.andamp.pack.jellyfin.JellyfinRow

/**
 * What this source tells the player about itself: its scheme, its label, its
 * pages and what it can do. None of it needs Android, so it is tested on a JVM.
 */
internal object PackIdentity {
    /** What this source's rows start with: `jellyfin:track:…`. Read from [JellyfinRow], which makes the addresses. */
    const val SCHEME = JellyfinRow.SCHEME

    /**
     * The name of the manifest's launcher alias, which is the icon in the app
     * list. It has to equal the alias's `android:name` in the manifest.
     */
    const val LAUNCHER_ALIAS = "nl.mattix.andamp.pack.jellyfin.ui.LauncherEntry"

    /** What a listener reads: the row in Preferences, the entry in Media Library. */
    const val LABEL = "Jellyfin"

    /** Where this source's `update.json` is. */
    const val UPDATES = "https://andamp.nl/extensions/jellyfin/update.json"

    /** Where a listener gets this source. The player writes it into saved playlists beside this source's rows. */
    const val HOME = "https://andamp.nl/extensions/jellyfin"

    /** What the player reads when it binds. The capabilities are the backend's and the library's own, passed in. */
    fun descriptor(
        version: String,
        playback: Capabilities,
        browse: BrowseCapabilities,
    ): PackDescriptor =
        PackDescriptor(
            scheme = SCHEME,
            label = LABEL,
            version = version,
            canSeek = playback.canSeek,
            canEditQueue = playback.canEditQueue,
            canAttenuate = playback.canAttenuate,
            hasArtists = browse.hasArtists,
            hasAlbums = browse.hasAlbums,
            canSearch = browse.canSearch,
            hasPlaylists = browse.hasPlaylists,
            hasCatalogue = browse.hasCatalogue,
            skinnable = true,
            updates = UPDATES,
            home = HOME,
            // the decoded audio goes to the player, which applies its
            // equalizer, effects and visualizer
            handsOverAudio = true,
            // the format the SDK's decoder resamples to
            sampleRate = PcmProvider.SAMPLE_RATE_HZ,
            channels = PcmProvider.CHANNELS,
        )
}
