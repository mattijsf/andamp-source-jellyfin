// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin.pack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What [PackStore] keeps: a token and a user, a device id that outlives them, and no password. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a sign-in keeps the user and the token, and nothing that looks like a password`() {
        val store = PackStore(context)
        store.address = " https://demo.jellyfin.org/stable "

        store.signIn(userId = "4ed1b8b42a7c4ea682f0fe5d08d4e278", userName = "demo", token = "f00dface")

        assertTrue(store.signedIn)
        assertEquals("https://demo.jellyfin.org/stable", store.address)
        assertEquals("4ed1b8b42a7c4ea682f0fe5d08d4e278", store.userId)
        assertEquals("demo", store.userName)
        assertEquals("f00dface", store.token)
        val kept = context.getSharedPreferences("jellyfin", Context.MODE_PRIVATE).all.keys
        assertFalse(kept.toString(), kept.any { it.contains("pass", ignoreCase = true) || it == "pw" })
    }

    @Test
    fun `the device id is made once and survives a sign-out`() {
        val store = PackStore(context)
        val first = store.deviceId
        store.signIn("user", "demo", "token")

        store.signOut()

        assertEquals(first, PackStore(context).deviceId)
        assertFalse(store.signedIn)
        assertEquals("", store.token)
        assertEquals("", store.userId)
    }

    @Test
    fun `a sign-out keeps the address`() {
        val store = PackStore(context)
        store.address = "jellyfin.example"
        store.signIn("user", "demo", "token")

        store.signOut()

        assertEquals("jellyfin.example", store.address)
    }

    @Test
    fun `two installs are two devices`() {
        val made = PackStore(context).deviceId
        context
            .getSharedPreferences("jellyfin", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        assertNotEquals(made, PackStore(context).deviceId)
    }

    @Test
    fun `the server it describes is the one it keeps`() {
        val store = PackStore(context)
        store.address = "jellyfin.example"
        store.signIn("user", "demo", "token")

        val server = store.server(deviceName = "Pixel 9", version = "0.1.0")

        assertEquals("jellyfin.example", server.address)
        assertEquals("user", server.userId)
        assertEquals("token", server.token)
        assertEquals(store.deviceId, server.device.id)
        assertEquals("Pixel 9", server.device.name)
    }

    @Test
    fun `a revoked token signs out only the sign-in it belongs to`() {
        val store = PackStore(context)
        store.address = "jellyfin.example"
        store.signIn("user", "demo", "new")

        assertFalse(store.signOutIf("old"))
        assertTrue(store.signedIn)
        assertFalse("an empty token signs nobody out", store.signOutIf(""))

        assertTrue(store.signOutIf("new"))
        assertFalse(store.signedIn)
    }
}
