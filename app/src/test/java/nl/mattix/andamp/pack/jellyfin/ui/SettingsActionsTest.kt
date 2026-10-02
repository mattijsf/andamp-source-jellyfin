// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.pack.jellyfin.DEMO_USER
import nl.mattix.andamp.pack.jellyfin.FakeJellyfinHttp
import nl.mattix.andamp.pack.jellyfin.Jellyfin
import nl.mattix.andamp.pack.jellyfin.JellyfinReply
import nl.mattix.andamp.pack.jellyfin.JellyfinServer
import nl.mattix.andamp.pack.jellyfin.QuickConnectCode
import nl.mattix.andamp.pack.jellyfin.QuickConnectState
import nl.mattix.andamp.pack.jellyfin.RECORDED_TOKEN
import nl.mattix.andamp.pack.jellyfin.fixture
import nl.mattix.andamp.pack.jellyfin.pack.PackStore
import nl.mattix.andamp.pack.jellyfin.recorded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SettingsActions] over the real store and recorded answers: what is written,
 * when the announce fires, and what is sent to the server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsActionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: PackStore
    private var announced = 0
    private val servers = mutableListOf<JellyfinServer>()
    private lateinit var http: FakeJellyfinHttp

    @Before
    fun clean() {
        store = PackStore(context)
        store.signOut()
        store.address = ""
    }

    /** Actions over the real store, counting announces, with every call answered from [replies]. */
    private fun actions(replies: Map<String, JellyfinReply> = SERVER_UP): SettingsActions {
        http = FakeJellyfinHttp(replies)
        return SettingsActions(
            context,
            store,
            announce = { announced++ },
            http = { server ->
                servers += server
                http
            },
            deviceName = "Pixel 9",
            version = "0.1.0",
        )
    }

    @Test
    fun `a password sign-in keeps the address, the user and the token, and never the password`() =
        runTest {
            val tried = actions().signIn(ADDRESS, "demo", PASSWORD)

            assertEquals(Tried.SignedIn, tried)
            assertEquals(ADDRESS, store.address)
            assertEquals(DEMO_USER, store.userId)
            assertEquals("demo", store.userName)
            assertEquals(RECORDED_TOKEN, store.token)
            val onDisk = context.getSharedPreferences("jellyfin", Context.MODE_PRIVATE).all.values
            assertFalse(onDisk.toString(), onDisk.any { it == PASSWORD })
            assertEquals(1, announced)
        }

    @Test
    fun `the password goes to the server once, in the sign-in's body`() =
        runTest {
            actions().signIn(ADDRESS, "demo", PASSWORD)

            val carrying = http.asked.filter { it.body.orEmpty().contains(PASSWORD) }
            assertEquals(listOf(Jellyfin.AUTHENTICATE), carrying.map { it.path })
        }

    /** The announce reads the store to tell a sign-in from a sign-out, so the store is written first. */
    @Test
    fun `what is kept is on disk by the time the announce reads it`() =
        runTest {
            val seen = mutableListOf<Boolean>()
            val http = FakeJellyfinHttp(SERVER_UP)
            SettingsActions(context, store, announce = { seen += PackStore(it).signedIn }, http = { http })
                .signIn(ADDRESS, "demo", PASSWORD)

            assertEquals(listOf(true), seen)
        }

    @Test
    fun `a wrong password is refused, and nothing is kept or announced`() =
        runTest {
            val tried = actions(SERVER_UP + (Jellyfin.AUTHENTICATE to JellyfinReply.Unauthorized)).signIn(ADDRESS, "demo", "nope")

            assertEquals(Tried.Refused, tried)
            assertFalse(store.signedIn)
            assertEquals(0, announced)
        }

    @Test
    fun `an address that could never be a URL is no address, without asking anybody`() =
        runTest {
            assertEquals(Tried.NoAddress, actions().signIn("gopher://music.example", "demo", PASSWORD))
            assertTrue("no client is built for the address", servers.isEmpty())
        }

    @Test
    fun `nothing answering is no server, and the reason travels with it`() =
        runTest {
            val tried =
                actions(
                    mapOf(Jellyfin.QUICK_CONNECT_ENABLED to JellyfinReply.Unreachable("connection refused")),
                ).signIn(ADDRESS, "demo", PASSWORD)

            assertEquals(Tried.NoServer("connection refused"), tried)
            assertTrue("no password is sent when nothing answers", http.asked.none { it.path == Jellyfin.AUTHENTICATE })
        }

    @Test
    fun `something that is not Jellyfin is said to be so, and is never sent the password`() =
        runTest {
            assertEquals(
                Tried.NotJellyfin(404),
                actions(mapOf(Jellyfin.QUICK_CONNECT_ENABLED to JellyfinReply.Rejected(404))).signIn(ADDRESS, "u", PASSWORD),
            )
            assertEquals(
                Tried.NotJellyfin(401),
                actions(mapOf(Jellyfin.QUICK_CONNECT_ENABLED to JellyfinReply.Unauthorized)).signIn(ADDRESS, "u", PASSWORD),
            )
            val json = JellyfinReply.Answered(org.json.JSONObject())
            assertEquals(
                Tried.NotJellyfin(200),
                actions(mapOf(Jellyfin.QUICK_CONNECT_ENABLED to json)).signIn(ADDRESS, "u", PASSWORD),
            )
            assertTrue(http.asked.none { it.path == Jellyfin.AUTHENTICATE })
        }

    @Test
    fun `Quick Connect is offered only where the server says yes`() =
        runTest {
            assertTrue(actions().quickConnectOffered(ADDRESS))
            assertFalse(
                actions(mapOf(Jellyfin.QUICK_CONNECT_ENABLED to JellyfinReply.Answered(false))).quickConnectOffered(ADDRESS),
            )
            assertFalse(actions(mapOf()).quickConnectOffered(ADDRESS))
            assertFalse(actions().quickConnectOffered(""))
        }

    @Test
    fun `a code is started, asked about and redeemed on the address that was typed`() =
        runTest {
            val actions = actions()

            val code = checkNotNull(actions.startQuickConnect(ADDRESS))
            val state = actions.quickConnectState(ADDRESS, code)
            val tried = actions.redeemQuickConnect(ADDRESS, code)

            assertEquals("153921", code.code)
            assertEquals(QuickConnectState.Approved, state)
            assertEquals(Tried.SignedIn, tried)
            assertEquals(ADDRESS, store.address)
            assertEquals(RECORDED_TOKEN, store.token)
            assertTrue(servers.all { it.address == ADDRESS && it.device.id == store.deviceId })
            assertEquals(1, announced)
        }

    @Test
    fun `a server that will not start a code starts none`() =
        runTest {
            assertNull(actions(mapOf(Jellyfin.QUICK_CONNECT_INITIATE to JellyfinReply.Unauthorized)).startQuickConnect(ADDRESS))
        }

    @Test
    fun `redeeming a code before it is approved keeps nothing`() =
        runTest {
            val tried =
                actions(
                    mapOf(Jellyfin.AUTHENTICATE_QUICK_CONNECT to JellyfinReply.Rejected(404)),
                ).redeemQuickConnect(ADDRESS, CODE)

            assertTrue(tried.toString(), tried is Tried.Failed)
            assertFalse(store.signedIn)
            assertEquals(0, announced)
        }

    /** The fake reads the store when the logout arrives, to check the token is still kept at that moment. */
    @Test
    fun `signing out ends the session on the server with the token, and then forgets it`() =
        runTest {
            keep()
            val kept = mutableListOf<String>()
            http =
                FakeJellyfinHttp { ask ->
                    if (ask.path == Jellyfin.LOGOUT) kept += store.token
                    JellyfinReply.read(204, "")
                }
            val actions =
                SettingsActions(context, store, announce = { announced++ }, http = { server ->
                    servers += server
                    http
                })

            actions.signOut()

            assertEquals(listOf(Jellyfin.LOGOUT), http.asked.map { it.path })
            assertEquals("the logout is sent while the token is still kept", listOf(RECORDED_TOKEN), kept)
            assertEquals(RECORDED_TOKEN, servers.single().token)
            assertFalse(store.signedIn)
            assertEquals("", store.token)
            assertEquals("signing out keeps the address", ADDRESS, store.address)
            assertEquals(1, announced)
        }

    @Test
    fun `a server that does not hear the sign-out does not keep the listener signed in`() =
        runTest {
            keep()

            actions(mapOf(Jellyfin.LOGOUT to JellyfinReply.Unreachable("timeout"))).signOut()

            assertFalse(store.signedIn)
            assertEquals(1, announced)
        }

    /** A sign-in under the phone's own device id would revoke the kept token, so a test uses another id. */
    @Test
    fun `a test signs in under its own device, signs out at once, and keeps nothing`() =
        runTest {
            keep()
            val tried = actions(SERVER_UP + (Jellyfin.LOGOUT to JellyfinReply.read(204, ""))).test(ADDRESS, "demo", PASSWORD)

            assertEquals(Tried.Reached, tried)
            assertEquals(
                listOf(Jellyfin.QUICK_CONNECT_ENABLED, Jellyfin.AUTHENTICATE, Jellyfin.LOGOUT),
                http.asked.map { it.path },
            )
            val signIn = servers.first { it.token.isEmpty() && it.device.id != store.deviceId }
            assertEquals(store.deviceId + "-test", signIn.device.id)
            assertEquals("the logout carries the test's own token", RECORDED_TOKEN, servers.last().token)
            assertEquals(store.deviceId + "-test", servers.last().device.id)
            assertEquals("the kept sign-in is untouched", RECORDED_TOKEN, store.token)
            assertEquals(0, announced)
        }

    @Test
    fun `a test with a wrong password is refused, and keeps nothing`() =
        runTest {
            val tried = actions(SERVER_UP + (Jellyfin.AUTHENTICATE to JellyfinReply.Unauthorized)).test(ADDRESS, "demo", "nope")

            assertEquals(Tried.Refused, tried)
            assertFalse(store.signedIn)
            assertEquals(0, announced)
        }

    @Test
    fun `testing the kept sign-in asks with its token, and sends no password`() =
        runTest {
            keep()
            val tried = actions(SERVER_UP + (Jellyfin.SYSTEM_INFO to JellyfinReply.read(200, "{}"))).test(ADDRESS, "demo", "")

            assertEquals(Tried.StillSignedIn, tried)
            assertEquals(RECORDED_TOKEN, servers.last().token)
            assertTrue(http.asked.none { it.path == Jellyfin.AUTHENTICATE })
        }

    @Test
    fun `a kept token the server no longer takes is said to be revoked, and is not forgotten by a test`() =
        runTest {
            keep()
            val tried = actions(SERVER_UP + (Jellyfin.SYSTEM_INFO to JellyfinReply.Unauthorized)).test(ADDRESS, "demo", "")

            assertEquals(Tried.Revoked, tried)
            assertTrue(store.signedIn)
            assertEquals(0, announced)
        }

    @Test
    fun `what is kept is the address and the user's name, and whether there is a token`() {
        keep()

        assertEquals(Kept(address = ADDRESS, user = "demo", signedIn = true), actions().kept())
    }

    private fun keep() {
        store.address = ADDRESS
        store.signIn(userId = DEMO_USER, userName = "demo", token = RECORDED_TOKEN)
    }

    private companion object {
        const val ADDRESS = "https://demo.jellyfin.org/stable"
        const val PASSWORD = "correct-horse-battery-staple"
        val CODE = QuickConnectCode("153921", "SECRET-PLACEHOLDER")

        /** The demo's recorded answer for each call the actions make. */
        val SERVER_UP: Map<String, JellyfinReply> by lazy {
            mapOf(
                Jellyfin.QUICK_CONNECT_ENABLED to recorded("quickconnect-enabled.json"),
                Jellyfin.AUTHENTICATE to recorded("authenticate.json"),
                Jellyfin.QUICK_CONNECT_INITIATE to recorded("quickconnect-initiate.json"),
                Jellyfin.QUICK_CONNECT_CONNECT to recorded("quickconnect-authorized.json"),
                Jellyfin.AUTHENTICATE_QUICK_CONNECT to JellyfinReply.read(200, fixture("quickconnect-authenticate.json")),
            )
        }
    }
}
