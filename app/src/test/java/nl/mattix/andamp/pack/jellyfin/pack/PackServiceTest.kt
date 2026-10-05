// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin.pack

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.network.SystemNetworkWatch
import nl.mattix.andamp.core.packapi.IMusicSourcePack
import nl.mattix.andamp.core.packapi.IPackListener
import nl.mattix.andamp.core.packapi.PackAccount
import nl.mattix.andamp.core.packapi.PackApi
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.packapi.PackState
import nl.mattix.andamp.pack.common.stream.StreamPlayback
import nl.mattix.andamp.pack.jellyfin.BuildConfig
import nl.mattix.andamp.pack.jellyfin.JellyfinLibrary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * [PackService] through its binder, as the player calls it. The generated stub
 * is a local interface here, so there is no second process.
 *
 * The two 401 tests use the real HTTP client against a loopback server that
 * answers 401 to everything; see [refusing]. Nothing else uses a network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackServiceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = PackStore(app)
    private lateinit var service: ServiceController<PackService>
    private lateinit var wire: IMusicSourcePack

    /** A player's listener, as a plain record of everything it was told. */
    private class Heard : IPackListener.Stub() {
        val accounts = mutableListOf<PackAccount>()
        val states = mutableListOf<PackState>()

        override fun onState(state: PackState) {
            states += state
        }

        override fun onAccount(account: PackAccount) {
            accounts += account
        }
    }

    @Before
    fun bind() {
        store.signOut()
        store.address = ""
        service = Robolectric.buildService(PackService::class.java).create()
        wire = IMusicSourcePack.Stub.asInterface(service.get().onBind(Intent(BIND)))
        idle()
    }

    @After
    fun unbind() {
        service.destroy()
    }

    @Test
    fun `the source speaks the contract this build was compiled against`() {
        assertEquals(PackApi.PACK_API, wire.apiVersion())
    }

    @Test
    fun `the descriptor says who the source is, which build, and that it hands its audio over`() {
        val said = wire.describe()

        assertEquals("jellyfin", said.scheme)
        assertEquals("Jellyfin", said.label)
        assertEquals(BuildConfig.VERSION_NAME, said.version)
        assertEquals("https://andamp.nl/extensions/jellyfin/update.json", said.updates)
        assertEquals("https://andamp.nl/extensions/jellyfin", said.home)
        assertTrue("the source hands its audio over to the player", said.handsOverAudio)
    }

    @Test
    fun `what the descriptor says it can do is the backend's and the library's own answer`() {
        val said = wire.describe()

        assertEquals(StreamPlayback.RENDERING.canSeek, said.canSeek)
        assertEquals(StreamPlayback.RENDERING.canEditQueue, said.canEditQueue)
        assertEquals(StreamPlayback.RENDERING.canAttenuate, said.canAttenuate)
        assertEquals(JellyfinLibrary.SHELVES, said.browse)
    }

    @Test
    fun `with nothing kept the account is signed out`() {
        assertEquals(PackAccount(signedIn = false), wire.account())
    }

    @Test
    fun `with a token kept the account is signed in, under the user's name`() {
        keep()

        assertEquals(PackAccount(signedIn = true, name = USER), wire.account())
    }

    @Test
    fun `an address alone is still signed out`() {
        store.address = "https://music.example"

        assertFalse(wire.account().signedIn)
    }

    @Test
    fun `a listener is told where things stand the moment it listens`() {
        val heard = Heard()

        wire.listen(heard)

        assertEquals(1, heard.states.size)
    }

    @Test
    fun `a sign-in on the settings screen reaches every listening player`() {
        val heard = Heard()
        wire.listen(heard)
        keep()

        announced()

        assertEquals(listOf(PackAccount(signedIn = true, name = USER)), heard.accounts)
    }

    @Test
    fun `a sign-out on the settings screen reaches every listening player`() {
        keep()
        val heard = Heard()
        wire.listen(heard)
        store.signOut()

        announced()

        assertEquals(listOf(PackAccount(signedIn = false)), heard.accounts)
    }

    @Test
    fun `a player that stopped listening is not told`() {
        val heard = Heard()
        wire.listen(heard)
        wire.stopListening(heard)
        keep()

        announced()

        assertTrue(heard.accounts.toString(), heard.accounts.isEmpty())
    }

    @Test
    fun `a missing question fails rather than throws`() {
        keep()

        assertTrue(wire.ask(null).failed)
    }

    @Test
    fun `a question with nobody signed in fails rather than answers empty`() {
        assertTrue(wire.ask(PackQuestion(kind = PackQuestion.ARTISTS)).failed)
    }

    @Test
    fun `a 401 from the server signs the listener out and tells every player`() {
        refusing { address ->
            keep(address)
            val heard = Heard()
            wire.listen(heard)

            val answer = wire.ask(PackQuestion(kind = PackQuestion.ARTISTS))
            idle()

            assertTrue(answer.failed)
            assertFalse("a 401 signs the listener out", store.signedIn)
            assertEquals("", store.token)
            assertEquals("signing out keeps the address", address, store.address)
            assertEquals(listOf(PackAccount(signedIn = false)), heard.accounts)
        }
    }

    /** A new sign-in from this device revokes the old token, so a question still out with the old token gets a 401. */
    @Test
    fun `a 401 for a token that has since been replaced signs nobody out`() {
        refusing(meanwhile = { store.signIn(userId = USER_ID, userName = USER, token = "a-newer-token") }) { address ->
            keep(address)

            assertTrue(wire.ask(PackQuestion(kind = PackQuestion.ARTISTS)).failed)
            idle()

            assertTrue(store.signedIn)
            assertEquals("a-newer-token", store.token)
        }
    }

    @Test
    fun `the audio is a pipe the player can read, and it ends when the source goes`() {
        val pipe = wire.openAudio()

        assertNotNull("the source opens an audio pipe", pipe)
        assertTrue(checkNotNull(pipe).fileDescriptor.valid())

        service.destroy()

        ParcelFileDescriptor.AutoCloseInputStream(pipe).use { read ->
            assertEquals("the pipe reads as ended once the service is destroyed", -1, read.read())
        }
    }

    /**
     * The service's network watch is a [SystemNetworkWatch], and the merged
     * manifest has ACCESS_NETWORK_STATE, which the SDK's manifest brings.
     */
    @Test
    fun `the service watches the phone's network and has the permission for it`() {
        val network = service.get().network
        assertTrue("the network watch is a SystemNetworkWatch: $network", network is SystemNetworkWatch)
        val asked = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
        assertTrue(
            "the manifest requests ACCESS_NETWORK_STATE",
            asked.orEmpty().contains(Manifest.permission.ACCESS_NETWORK_STATE),
        )

        val connectivity = shadowOf(app.getSystemService(ConnectivityManager::class.java))
        network.watch {}
        assertEquals("watching registers one network callback", 1, connectivity.networkCallbacks.size)
        network.unwatch()
        assertTrue("unwatching removes the network callback", connectivity.networkCallbacks.isEmpty())
    }

    /** Calls the announce the settings screen calls, and delivers the start it makes to this service. */
    private fun announced() {
        PackService.announce(app)
        val started = checkNotNull(shadowOf(app).nextStartedService) { "the announce starts the service" }
        service.withIntent(started).startCommand(0, 1)
        idle()
    }

    private fun keep(address: String = "https://music.example") {
        store.address = address
        store.signIn(userId = USER_ID, userName = USER, token = TOKEN)
    }

    /**
     * Runs [test] against a loopback server that answers every request with a
     * 401, after running [meanwhile], where a test can change the store while a
     * question is out.
     */
    private fun refusing(
        meanwhile: () -> Unit = {},
        test: (address: String) -> Unit,
    ) {
        ServerSocket(0).use { socket ->
            val answering =
                thread(isDaemon = true) {
                    runCatching {
                        while (true) {
                            socket.accept().use { call ->
                                val reader = call.getInputStream().bufferedReader()
                                while (reader.readLine().orEmpty().isNotEmpty()) Unit
                                meanwhile()
                                call.getOutputStream().write(REFUSAL.toByteArray())
                                call.getOutputStream().flush()
                            }
                        }
                    }
                }
            test("http://127.0.0.1:${socket.localPort}")
            answering.interrupt()
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private companion object {
        const val BIND = "nl.mattix.andamp.source.BIND"
        const val USER = "listener"
        const val USER_ID = "4ed1b8b42a7c4ea682f0fe5d08d4e278"
        const val TOKEN = "f00dfacecafe1234f00dfacecafe1234"
        const val REFUSAL = "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    }
}
