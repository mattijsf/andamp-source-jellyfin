// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin

import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/** The `Authorization` header and the URLs [JellyfinServer] builds. */
class JellyfinServerTest {
    @Test
    fun `before sign-in the header names the client and the device and nothing else`() {
        val server = JellyfinServer("https://demo.jellyfin.org/stable", DEVICE)

        assertEquals(
            "MediaBrowser Client=\"AndAmp\", Device=\"Pixel%209\", DeviceId=\"7d3f2a9e-andamp-test\", Version=\"0.1.0\"",
            server.authorization(),
        )
        assertFalse(server.signedIn)
    }

    @Test
    fun `after sign-in the token goes on the end of the same header`() {
        assertEquals(
            "MediaBrowser Client=\"AndAmp\", Device=\"Pixel%209\", DeviceId=\"7d3f2a9e-andamp-test\", Version=\"0.1.0\", " +
                "Token=\"f00dfacecafe1234f00dfacecafe1234\"",
            SERVER.authorization(),
        )
        assertTrue(SERVER.signedIn)
    }

    @Test
    fun `a device name somebody typed cannot break the header open`() {
        val named = JellyfinServer("demo.jellyfin.org", DEVICE.copy(name = "Sam's Pixel, \"Pro\""))

        val header = named.authorization()
        val device = header.substringAfter("Device=\"").substringBefore('"')

        // no comma, quote or space in the encoded name, and it decodes back
        assertFalse(device, device.contains(',') || device.contains('"') || device.contains(' '))
        assertEquals("Sam's Pixel, \"Pro\"", URLDecoder.decode(device, "UTF-8"))
        assertTrue(header, header.contains("DeviceId=\"7d3f2a9e-andamp-test\""))
    }

    @Test
    fun `no URL this source builds carries the token`() {
        val urls =
            listOf(
                SERVER.url(Jellyfin.ITEMS, mapOf("userId" to DEMO_USER)),
                SERVER.url(Jellyfin.primaryImage("abc"), mapOf("tag" to "t")),
                SERVER.url(Jellyfin.universalAudio("abc")),
            ).map { checkNotNull(it) }

        for (url in urls) {
            assertFalse(url.toString(), url.toString().contains(SERVER.token))
            assertNull(url.queryParameter("api_key"))
            assertNull(url.queryParameter("ApiKey"))
        }
    }

    @Test
    fun `the server never prints its token`() {
        val printed = SERVER.toString()

        assertFalse(printed, printed.contains(SERVER.token))
        assertTrue(printed, printed.contains(DEMO_USER))
        assertTrue(JellyfinServer("x", DEVICE).toString().contains("token=none"))
    }

    @Test
    fun `a server under a path keeps it`() {
        assertEquals("https://demo.jellyfin.org:443/stable/Users/AuthenticateByName", where(SERVER.url(Jellyfin.AUTHENTICATE)))
    }

    @Test
    fun `what somebody types becomes an address that works`() {
        val typed = JellyfinServer("  jellyfin.example/ ", DEVICE)

        // a missing scheme is read as https
        assertEquals("https://jellyfin.example:443/Artists", where(typed.url(Jellyfin.ARTISTS)))
        val local = JellyfinServer("http://192.168.1.20:8096", DEVICE)
        assertEquals("http://192.168.1.20:8096/Items", where(local.url(Jellyfin.ITEMS)))
    }

    @Test
    fun `an address that is not one has no URL rather than an exception`() {
        assertNull(JellyfinServer("", DEVICE).url(Jellyfin.ITEMS))
        assertNull(JellyfinServer("   ", DEVICE).url(Jellyfin.ITEMS))
        assertNull(JellyfinServer("gopher://nope", DEVICE).url(Jellyfin.ITEMS))
    }

    @Test
    fun `somebody else's text on a query is escaped, and so is a segment of a path`() {
        val url = checkNotNull(SERVER.url(Jellyfin.playlistItems("a b"), mapOf("searchTerm" to "AC/DC & 100%")))

        assertEquals("AC/DC & 100%", url.queryParameter("searchTerm"))
        assertEquals("/stable/Playlists/a%20b/Items", url.encodedPath)
    }

    @Test
    fun `a sign-in makes the same server with a user and a token`() {
        val signedIn = JellyfinServer("demo.jellyfin.org", DEVICE).withSignIn(DEMO_USER, "tok")

        assertEquals("demo.jellyfin.org", signedIn.address)
        assertEquals(DEVICE, signedIn.device)
        assertEquals(DEMO_USER, signedIn.userId)
        assertTrue(signedIn.signedIn)
    }

    private fun where(url: HttpUrl?): String = checkNotNull(url).let { "${it.scheme}://${it.host}:${it.port}${it.encodedPath}" }
}
