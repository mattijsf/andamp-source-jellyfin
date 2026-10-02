// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden images of the settings screen in three states: nobody signed in,
 * signed in, and a Quick Connect code on screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class SettingsScreenGoldenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun draw(
        actions: JellyfinActions,
        dark: Boolean,
        shown: Boolean,
    ) {
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { JellyfinPage(actions, FakeAppList(shown), onDone = {}) }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a phone with nobody signed in`() {
        draw(FakeJellyfinActions(), dark = true, shown = true)

        compose.onRoot().captureRoboImage("$SNAPSHOTS/jellyfin_empty.png")
    }

    /** Signed in, a password typed and shown, and a test that worked. */
    @Test
    fun `signed in, tried, with the icon taken out of the app list`() {
        val actions = FakeJellyfinActions(Kept(address = "http://192.168.1.10:8096", user = "listener", signedIn = true))
        actions.tested.complete(Tried.Reached)
        draw(actions, dark = false, shown = false)
        compose.onNodeWithTag("jellyfin.password").performTextInput("hunter2")
        compose.onNodeWithTag("jellyfin.password.show").performClick()
        compose.onNodeWithTag("jellyfin.test").performClick()
        compose.waitForIdle()

        compose.onRoot().captureRoboImage("$SNAPSHOTS/jellyfin_signed_in.png")
    }

    @Test
    fun `a Quick Connect code waiting to be entered`() {
        draw(FakeJellyfinActions(Kept(address = "", user = "", signedIn = false), offered = true), dark = true, shown = true)
        compose.onNodeWithTag("jellyfin.address").performTextInput("http://192.168.1.10:8096")
        compose.mainClock.advanceTimeBy(PROBE_AFTER_MS + 100)
        compose.waitForIdle()
        compose.onNodeWithTag("jellyfin.quickconnect").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onRoot().captureRoboImage("$SNAPSHOTS/jellyfin_quick_connect.png")
    }

    private companion object {
        const val SNAPSHOTS = "src/test/snapshots"
    }
}
