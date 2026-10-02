// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [JellyfinSignIn] against recorded answers. The Quick Connect recordings are
 * from one run on the demo: a code started, asked about before and after it
 * was approved, and redeemed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JellyfinSignInTest {
    @Test
    fun `a password is exchanged for a token and a user, and the password is not kept`() =
        runTest {
            val http = FakeJellyfinHttp(mapOf(Jellyfin.AUTHENTICATE to recorded("authenticate.json")))

            val signIn = JellyfinSignIn(http).password(" demo ", "")

            val ask = http.once()
            assertEquals("POST", ask.method)
            assertEquals(Jellyfin.AUTHENTICATE, ask.path)
            val body = JSONObject(checkNotNull(ask.body))
            assertEquals("demo", body.getString("Username"))
            // the password field is `Pw`
            assertEquals("", body.getString("Pw"))
            val signedIn = signIn as SignIn.SignedIn
            assertEquals(DEMO_USER, signedIn.userId)
            assertEquals("demo", signedIn.userName)
            assertEquals(RECORDED_TOKEN, signedIn.token)
        }

    @Test
    fun `a sign-in never prints the token it holds`() =
        runTest {
            val http = FakeJellyfinHttp(mapOf(Jellyfin.AUTHENTICATE to recorded("authenticate.json")))

            val printed = JellyfinSignIn(http).password("demo", "").toString()

            assertFalse(printed, printed.contains(RECORDED_TOKEN))
            assertTrue(printed, printed.contains(DEMO_USER))
        }

    @Test
    fun `a wrong password is a wrong account and not a server that is down`() =
        runTest {
            // a wrong password is a 401
            val http = FakeJellyfinHttp(mapOf(Jellyfin.AUTHENTICATE to JellyfinReply.read(401, fixture("unauthorized.html"))))

            assertEquals(SignIn.WrongAccount, JellyfinSignIn(http).password("demo", "wrong"))
        }

    @Test
    fun `a server that cannot be reached is a failure the listener did not cause`() =
        runTest {
            val http = FakeJellyfinHttp(mapOf(Jellyfin.AUTHENTICATE to JellyfinReply.Unreachable("no route to host")))

            assertEquals(SignIn.Failed("no route to host"), JellyfinSignIn(http).password("demo", ""))
        }

    @Test
    fun `a success with no token in it signed in nobody`() =
        runTest {
            val http = FakeJellyfinHttp(mapOf(Jellyfin.AUTHENTICATE to JellyfinReply.Answered(JSONObject("{\"User\":{}}"))))

            assertTrue(JellyfinSignIn(http).password("demo", "") is SignIn.Failed)
        }

    @Test
    fun `whether the server offers a code is asked, and a server that is down has not said no`() =
        runTest {
            val on = FakeJellyfinHttp(mapOf(Jellyfin.QUICK_CONNECT_ENABLED to recorded("quickconnect-enabled.json")))
            val down = FakeJellyfinHttp(mapOf(Jellyfin.QUICK_CONNECT_ENABLED to JellyfinReply.Unreachable("timeout")))

            assertEquals(true, JellyfinSignIn(on).quickConnectEnabled())
            assertEquals("GET", on.once().method)
            assertNull(JellyfinSignIn(down).quickConnectEnabled())
        }

    @Test
    fun `a code is started with a POST and comes with its secret`() =
        runTest {
            val http = FakeJellyfinHttp(mapOf(Jellyfin.QUICK_CONNECT_INITIATE to recorded("quickconnect-initiate.json")))

            val code = checkNotNull(JellyfinSignIn(http).startQuickConnect())

            assertEquals("POST", http.once().method)
            assertEquals("153921", code.code)
            assertEquals("SECRET-PLACEHOLDER", code.secret)
            // toString leaves the secret out
            assertFalse(code.toString().contains(code.secret))
        }

    @Test
    fun `a server with Quick Connect switched off starts no code`() =
        runTest {
            val http = FakeJellyfinHttp(mapOf(Jellyfin.QUICK_CONNECT_INITIATE to JellyfinReply.Unauthorized))

            assertNull(JellyfinSignIn(http).startQuickConnect())
        }

    @Test
    fun `a code is waiting until somebody types it in, then approved`() =
        runTest {
            val waiting = FakeJellyfinHttp(mapOf(Jellyfin.QUICK_CONNECT_CONNECT to recorded("quickconnect-waiting.json")))
            val approved = FakeJellyfinHttp(mapOf(Jellyfin.QUICK_CONNECT_CONNECT to recorded("quickconnect-authorized.json")))

            assertEquals(QuickConnectState.Waiting, JellyfinSignIn(waiting).quickConnectState("SECRET-PLACEHOLDER"))
            assertEquals(mapOf("Secret" to "SECRET-PLACEHOLDER"), waiting.once().params)
            assertEquals(QuickConnectState.Approved, JellyfinSignIn(approved).quickConnectState("SECRET-PLACEHOLDER"))
        }

    @Test
    fun `a secret the server no longer knows has expired, and a server that is down has not`() =
        runTest {
            // a 404 for a secret the server does not know
            val gone = FakeJellyfinHttp(mapOf(Jellyfin.QUICK_CONNECT_CONNECT to JellyfinReply.Rejected(404)))
            val down = FakeJellyfinHttp(mapOf(Jellyfin.QUICK_CONNECT_CONNECT to JellyfinReply.Unreachable("timeout")))

            assertEquals(QuickConnectState.Expired, JellyfinSignIn(gone).quickConnectState("0000"))
            assertEquals(QuickConnectState.Failed, JellyfinSignIn(down).quickConnectState("0000"))
        }

    @Test
    fun `an approved code is redeemed for the same answer a password gets`() =
        runTest {
            val http =
                FakeJellyfinHttp(mapOf(Jellyfin.AUTHENTICATE_QUICK_CONNECT to recorded("quickconnect-authenticate.json")))

            val signIn = JellyfinSignIn(http).redeemQuickConnect("SECRET-PLACEHOLDER")

            assertEquals("POST", http.once().method)
            assertEquals("SECRET-PLACEHOLDER", JSONObject(checkNotNull(http.once().body)).getString("Secret"))
            assertEquals(DEMO_USER, (signIn as SignIn.SignedIn).userId)
            assertEquals(RECORDED_TOKEN, signIn.token)
        }

    @Test
    fun `redeeming too early is a failure and not a wrong account`() =
        runTest {
            // the demo's answer before approval: a 404
            val http = FakeJellyfinHttp(mapOf(Jellyfin.AUTHENTICATE_QUICK_CONNECT to JellyfinReply.Rejected(404)))

            assertTrue(JellyfinSignIn(http).redeemQuickConnect("SECRET-PLACEHOLDER") is SignIn.Failed)
        }

    @Test
    fun `signing out ends the session on the server`() =
        runTest {
            // the server answers 204
            val http = FakeJellyfinHttp(mapOf(Jellyfin.LOGOUT to JellyfinReply.read(204, "")))
            val down = FakeJellyfinHttp(mapOf(Jellyfin.LOGOUT to JellyfinReply.Unreachable("timeout")))

            assertTrue(JellyfinSignIn(http).signOut())
            assertEquals("POST", http.once().method)
            assertFalse(JellyfinSignIn(down).signOut())
        }
}
