// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin.ui

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CompletableDeferred
import nl.mattix.andamp.pack.jellyfin.QuickConnectState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The settings page over fake actions, asserted on what is on screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class JellyfinPageTest {
    @get:Rule
    val compose = createComposeRule()

    private fun page(actions: JellyfinActions): () -> Int {
        var done = 0
        compose.setContent { JellyfinPage(actions, FakeAppList(), onDone = { done++ }) }
        return { done }
    }

    /** The text in a field, without its label. */
    private fun holds(tag: String): String =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text

    private fun type(
        address: String = ADDRESS,
        user: String = USER,
        password: String = PASSWORD,
    ) {
        if (address.isNotEmpty()) compose.onNodeWithTag("jellyfin.address").performTextInput(address)
        if (user.isNotEmpty()) compose.onNodeWithTag("jellyfin.user").performTextInput(user)
        if (password.isNotEmpty()) compose.onNodeWithTag("jellyfin.password").performTextInput(password)
    }

    @Test
    fun `nobody signed in is said, with a form and no sign-out`() {
        page(FakeJellyfinActions())

        compose.onNodeWithText("Not signed in").assertIsDisplayed()
        compose.onNodeWithTag("jellyfin.signout").assertDoesNotExist()
        compose.onNodeWithTag("jellyfin.signin").assertIsNotEnabled()
        compose.onNodeWithTag("jellyfin.quickconnect").assertDoesNotExist()
    }

    /** A Jellyfin account can have an empty password. */
    @Test
    fun `an address and a user are enough to sign in, and a password may be empty`() {
        page(FakeJellyfinActions())

        type(user = "", password = "")
        compose.onNodeWithTag("jellyfin.signin").assertIsNotEnabled()

        compose.onNodeWithTag("jellyfin.user").performTextInput(USER)
        compose.onNodeWithTag("jellyfin.signin").assertIsEnabled()
    }

    @Test
    fun `a kept sign-in opens as who and where, with sign-out and the form filled in`() {
        page(FakeJellyfinActions(Kept(address = "http://192.168.1.10:8096/", user = USER, signedIn = true)))

        compose.onNodeWithText("192.168.1.10:8096").assertIsDisplayed()
        compose.onNodeWithText("Sign out").assertIsDisplayed()
        assertEquals("http://192.168.1.10:8096/", holds("jellyfin.address"))
        assertEquals(USER, holds("jellyfin.user"))
        assertEquals("", holds("jellyfin.password"))
        compose.onNodeWithText("Leave empty to stay signed in.").assertIsDisplayed()
    }

    @Test
    fun `signed in, Sign in waits for a change while Test is there at once`() {
        page(FakeJellyfinActions(Kept(address = ADDRESS, user = USER, signedIn = true)))

        compose.onNodeWithTag("jellyfin.signin").assertIsNotEnabled()
        compose.onNodeWithTag("jellyfin.test").assertIsEnabled()

        compose.onNodeWithTag("jellyfin.password").performTextInput(PASSWORD)
        compose.onNodeWithTag("jellyfin.signin").assertIsEnabled()
    }

    @Test
    fun `Test sends what was typed, spins while it is out, and says the server took it without closing`() {
        val actions = FakeJellyfinActions()
        val done = page(actions)
        type()

        compose.onNodeWithTag("jellyfin.test").performClick()

        compose.onNodeWithText("Testing…").assertIsDisplayed()
        compose.onNodeWithTag("jellyfin.signin").assertIsNotEnabled()
        assertEquals(listOf(listOf(ADDRESS, USER, PASSWORD)), actions.tests)

        actions.tested.complete(Tried.Reached)
        compose.waitForIdle()

        compose.onNodeWithText("Connected. Sign in to use this server in Andamp.").assertIsDisplayed()
        compose.onNodeWithTag("jellyfin.signin").assertIsEnabled()
        assertEquals("a test signs nobody in", emptyList<List<String>>(), actions.signedIn)
        assertEquals(0, done())
    }

    @Test
    fun `testing the kept sign-in says whether the server still takes it`() {
        val actions = FakeJellyfinActions(Kept(address = ADDRESS, user = USER, signedIn = true))
        page(actions)

        compose.onNodeWithTag("jellyfin.test").performClick()
        actions.tested.complete(Tried.Revoked)
        compose.waitForIdle()

        compose
            .onNodeWithText(
                "The server no longer accepts this sign-in. Enter the password and sign in again.",
            ).assertIsDisplayed()
    }

    @Test
    fun `signing out forgets the sign-in and offers the form again on the same address`() {
        val actions = FakeJellyfinActions(Kept(address = ADDRESS, user = USER, signedIn = true))
        val done = page(actions)

        compose.onNodeWithTag("jellyfin.signout").performClick()
        compose.waitForIdle()

        assertEquals(1, actions.signedOut)
        assertEquals(0, done())
        compose.onNodeWithText("Not signed in").assertIsDisplayed()
        assertEquals(ADDRESS, holds("jellyfin.address"))
        compose.onNodeWithText("Leave empty to stay signed in.").assertDoesNotExist()
    }

    @Test
    fun `a password sign-in sends exactly what was typed, spins while it is out, and closes the page when it works`() {
        val actions = FakeJellyfinActions()
        val done = page(actions)
        type()

        compose.onNodeWithTag("jellyfin.signin").performClick()

        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        compose.onNodeWithTag("jellyfin.signin").assertIsNotEnabled()
        assertEquals(listOf(listOf(ADDRESS, USER, PASSWORD)), actions.signedIn)

        actions.answer.complete(Tried.SignedIn)
        compose.waitForIdle()

        assertEquals(1, done())
    }

    @Test
    fun `a refused sign-in says so and stays`() {
        val done = answered(Tried.Refused)

        compose.onNodeWithText("Wrong user or password.").assertIsDisplayed()
        compose.onNodeWithTag("jellyfin.signin").assertIsEnabled()
        assertEquals(0, done())
    }

    @Test
    fun `each way a sign-in fails points at the thing to fix`() {
        val lines =
            mapOf(
                Tried.NoAddress to "That is not a server address.",
                Tried.NoServer("connection refused") to "Nothing answered at that address: connection refused",
                Tried.NotJellyfin(404) to "Something answered with HTTP 404, but it was not Jellyfin.",
                Tried.Failed("the server answered 500") to "Jellyfin did not sign you in: the server answered 500",
            )
        val actions = FakeJellyfinActions()
        page(actions)
        type()
        for ((said, words) in lines) {
            actions.answer = CompletableDeferred(said)
            compose.onNodeWithTag("jellyfin.signin").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("jellyfin.said").assertTextEquals(words)
        }
    }

    @Test
    fun `changing a field takes away what the last try said`() {
        answered(Tried.Refused)
        compose.onNodeWithTag("jellyfin.said").assertIsDisplayed()

        compose.onNodeWithTag("jellyfin.password").performTextInput("x")

        compose.onNodeWithTag("jellyfin.said").assertDoesNotExist()
    }

    @Test
    fun `the password starts hidden and can be shown while it is typed`() {
        page(FakeJellyfinActions())
        compose.onNodeWithTag("jellyfin.password").performTextInput(PASSWORD)

        compose.onNodeWithContentDescription("Show password").assertExists()
        compose.onNodeWithTag("jellyfin.password.show").performClick()
        compose.onNodeWithContentDescription("Hide password").assertExists()
        assertEquals("showing the password keeps the typed text", PASSWORD, holds("jellyfin.password"))

        compose.onNodeWithTag("jellyfin.password.show").performClick()
        compose.onNodeWithContentDescription("Show password").assertExists()
    }

    @Test
    fun `Quick Connect is not offered where the address does not have it`() {
        page(FakeJellyfinActions(offered = false))
        compose.onNodeWithTag("jellyfin.address").performTextInput(ADDRESS)
        asked()

        compose.onNodeWithTag("jellyfin.quickconnect").assertDoesNotExist()
    }

    @Test
    fun `Quick Connect is offered once the address says it has it`() {
        page(FakeJellyfinActions(offered = true))
        compose.onNodeWithTag("jellyfin.address").performTextInput(ADDRESS)
        asked()

        compose.onNodeWithTag("jellyfin.quickconnect").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a kept address is asked about Quick Connect as the page opens`() {
        page(FakeJellyfinActions(Kept(address = ADDRESS, user = "", signedIn = false), offered = true))
        asked()

        compose.onNodeWithTag("jellyfin.quickconnect").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `Quick Connect shows the code, the way to it and a spinner, in place of the password`() {
        quickConnect(FakeJellyfinActions(offered = true))

        compose.onNodeWithTag("jellyfin.code").assertTextEquals("153921")
        compose.onNodeWithText("Enter this code in Jellyfin: Settings > Quick Connect").assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy the code").assertIsDisplayed()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        compose.onNodeWithTag("jellyfin.password").assertDoesNotExist()
        compose.onNodeWithTag("jellyfin.address").assertIsNotEnabled()
    }

    @Test
    fun `a code is asked about on a timer until it is approved, then redeemed and the page closes`() {
        val actions = FakeJellyfinActions(offered = true)
        val done = quickConnect(actions)

        compose.mainClock.advanceTimeBy(POLL_MS * 2 + 100)
        compose.waitForIdle()
        assertEquals(listOf("153921", "153921"), actions.asked)
        assertEquals(0, done())

        actions.state = QuickConnectState.Approved
        compose.mainClock.advanceTimeBy(POLL_MS + 100)
        compose.waitForIdle()

        assertEquals(1, actions.redeemed)
        assertEquals(1, done())
    }

    @Test
    fun `canceling a code stops asking about it and brings the password back`() {
        val actions = FakeJellyfinActions(offered = true)
        quickConnect(actions)
        compose.mainClock.advanceTimeBy(POLL_MS + 100)
        compose.waitForIdle()

        compose.onNodeWithTag("jellyfin.cancel").performClick()
        compose.waitForIdle()
        val before = actions.asked.size
        compose.mainClock.advanceTimeBy(POLL_MS * 3)
        compose.waitForIdle()

        assertEquals(before, actions.asked.size)
        compose.onNodeWithTag("jellyfin.code").assertDoesNotExist()
        compose.onNodeWithTag("jellyfin.password").assertIsDisplayed()
        assertEquals(0, actions.redeemed)
    }

    @Test
    fun `a code that runs out says so and offers the way in again`() {
        val actions = FakeJellyfinActions(offered = true)
        quickConnect(actions)

        actions.state = QuickConnectState.Expired
        compose.mainClock.advanceTimeBy(POLL_MS + 100)
        compose.waitForIdle()

        compose.onNodeWithText("The code expired. Try again.").assertIsDisplayed()
        compose.onNodeWithTag("jellyfin.quickconnect").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a server that would not start a code says so`() {
        val actions = FakeJellyfinActions(offered = true, code = null)
        page(actions)
        compose.onNodeWithTag("jellyfin.address").performTextInput(ADDRESS)
        asked()

        compose.onNodeWithTag("jellyfin.quickconnect").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Jellyfin did not sign you in: the server would not start Quick Connect").assertIsDisplayed()
    }

    @Test
    fun `the page carries the app list switch`() {
        val list = FakeAppList(shown = true)
        compose.setContent { JellyfinPage(FakeJellyfinActions(), list, onDone = {}) }

        compose
            .onNodeWithTag("pack.applist")
            .performScrollTo()
            .assertIsOn()
            .performClick()

        assertEquals(listOf(false), list.told)
        compose.onNodeWithTag("pack.applist").assertIsOff()
    }

    /** Advances the clock past the delay after which the address is asked whether it offers Quick Connect. */
    private fun asked() {
        compose.mainClock.advanceTimeBy(PROBE_AFTER_MS + 100)
        compose.waitForIdle()
    }

    /** A page with everything typed, tried, and answered with [said]. */
    private fun answered(said: Tried): () -> Int {
        val actions = FakeJellyfinActions()
        actions.answer.complete(said)
        val done = page(actions)
        type()
        compose.onNodeWithTag("jellyfin.signin").performClick()
        compose.waitForIdle()
        return done
    }

    /** A page with an address typed and a code on screen. */
    private fun quickConnect(actions: FakeJellyfinActions): () -> Int {
        val done = page(actions)
        compose.onNodeWithTag("jellyfin.address").performTextInput(ADDRESS)
        asked()
        compose.onNodeWithTag("jellyfin.quickconnect").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("jellyfin.code").assertExists()
        return done
    }

    private companion object {
        const val ADDRESS = "https://jellyfin.example"
        const val USER = "listener"
        const val PASSWORD = "sesame"
    }
}
