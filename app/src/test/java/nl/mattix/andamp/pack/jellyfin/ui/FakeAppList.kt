// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin.ui

import nl.mattix.andamp.pack.common.AppListEntry

/** An [AppListEntry] with no launcher behind it; it records each call to [show]. */
internal class FakeAppList(
    override var shown: Boolean = true,
) : AppListEntry {
    val told = mutableListOf<Boolean>()

    override fun show(shown: Boolean) {
        told += shown
        this.shown = shown
    }
}
