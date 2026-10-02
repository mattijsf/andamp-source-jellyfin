// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin.ui

import kotlinx.coroutines.CompletableDeferred
import nl.mattix.andamp.pack.jellyfin.QuickConnectCode
import nl.mattix.andamp.pack.jellyfin.QuickConnectState

/**
 * Actions that record their calls and answer what the test sets. The answers
 * to a sign-in and a test are deferreds, so a test can hold the page between
 * the press and the answer. The Quick Connect state is a field the test
 * changes.
 */
internal class FakeJellyfinActions(
    private val holds: Kept = Kept(address = "", user = "", signedIn = false),
    /** What `QuickConnect/Enabled` says, for any address. */
    var offered: Boolean = false,
    /** The code a start answers; null for a server that starts none. */
    var code: QuickConnectCode? = QuickConnectCode("153921", "SECRET"),
) : JellyfinActions {
    var answer = CompletableDeferred<Tried>()
    var tested = CompletableDeferred<Tried>()
    val tests = mutableListOf<List<String>>()
    var state = QuickConnectState.Waiting
    val signedIn = mutableListOf<List<String>>()
    val asked = mutableListOf<String>()
    var redeemed = 0
    var signedOut = 0

    override fun kept(): Kept = holds

    override suspend fun quickConnectOffered(address: String): Boolean = offered

    override suspend fun signIn(
        address: String,
        user: String,
        password: String,
    ): Tried {
        signedIn += listOf(address, user, password)
        return answer.await()
    }

    override suspend fun test(
        address: String,
        user: String,
        password: String,
    ): Tried {
        tests += listOf(address, user, password)
        return tested.await()
    }

    override suspend fun startQuickConnect(address: String): QuickConnectCode? = code

    override suspend fun quickConnectState(
        address: String,
        code: QuickConnectCode,
    ): QuickConnectState {
        asked += code.code
        return state
    }

    override suspend fun redeemQuickConnect(
        address: String,
        code: QuickConnectCode,
    ): Tried {
        redeemed++
        return Tried.SignedIn
    }

    override suspend fun signOut() {
        signedOut++
    }
}
