// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.mattix.andamp.pack.common.AppListEntry
import nl.mattix.andamp.pack.jellyfin.QuickConnectCode
import nl.mattix.andamp.pack.jellyfin.R

/**
 * The settings page: what this app is, who is signed in, the server form, and
 * the app list switch.
 *
 * There are two ways to sign in: a name and a password, or Quick Connect, a
 * code shown here and typed into a Jellyfin app or web page where the listener
 * is already signed in. Quick Connect is offered once the server at the typed
 * address says it has it; see [JellyfinActions.quickConnectOffered].
 *
 * The form is the same signed in or not. Test with the password left empty
 * checks the sign-in in use.
 */
@Composable
internal fun JellyfinPage(
    actions: JellyfinActions,
    appList: AppListEntry,
    onDone: () -> Unit,
    padding: PaddingValues = PaddingValues(),
) {
    var kept by remember { mutableStateOf(actions.kept()) }
    var signingOut by remember { mutableStateOf(false) }
    val work = rememberCoroutineScope()
    Column(
        Modifier
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Identity()
        Spacer(Modifier.height(20.dp))
        Standing(kept, signingOut, onSignOut = {
            signingOut = true
            work.launch {
                actions.signOut()
                kept = kept.copy(user = "", signedIn = false)
                signingOut = false
            }
        })
        Spacer(Modifier.height(12.dp))
        ServerForm(kept, actions, onSignedIn = onDone)
        Spacer(Modifier.height(12.dp))
        AppListRow(appList)
        Spacer(Modifier.height(32.dp))
    }
}

/** The icon, the name and one line on what this app is. */
@Composable
private fun Identity() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painterResource(R.drawable.ic_pack),
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Jellyfin for Andamp", style = MaterialTheme.typography.titleLarge)
            Text(
                "Play the music on your Jellyfin server in Andamp.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Who this phone is signed in as, and on which server. "Signed in" means a
 * token is kept; the server is not asked when the page opens.
 */
@Composable
private fun Standing(
    kept: Kept,
    signingOut: Boolean,
    onSignOut: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (kept.signedIn) Icons.Filled.AccountCircle else Icons.Outlined.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (kept.signedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(if (kept.signedIn) kept.user else "Not signed in", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (kept.signedIn) host(kept.address) else "Andamp skips Jellyfin tracks until you sign in",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (kept.signedIn) {
                TextButton(
                    onClick = onSignOut,
                    enabled = !signingOut,
                    modifier = Modifier.testTag("jellyfin.signout"),
                ) { Text(if (signingOut) "Signing out…" else "Sign out") }
            }
        }
    }
}

/**
 * The address, the account, and the two ways to sign in.
 *
 * It opens with the kept address and user name and an empty password. While
 * signed in, Sign in is disabled until a field changes.
 *
 * While a Quick Connect code is shown, it replaces the name and password
 * fields and the address field is disabled. The poll for approval is a
 * [LaunchedEffect] keyed on the code, so leaving the page or pressing Cancel
 * stops it.
 */
@Composable
private fun ServerForm(
    kept: Kept,
    actions: JellyfinActions,
    onSignedIn: () -> Unit,
) {
    var address by remember { mutableStateOf(kept.address) }
    var user by remember { mutableStateOf(kept.user) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf<Tried?>(null) }
    var offered by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<QuickConnectCode?>(null) }
    val work = rememberCoroutineScope()

    // asks the typed address, PROBE_AFTER_MS after the last change to it
    LaunchedEffect(address) {
        offered = false
        if (address.isBlank()) return@LaunchedEffect
        delay(PROBE_AFTER_MS)
        offered = actions.quickConnectOffered(address)
    }
    code?.let { shown ->
        LaunchedEffect(shown) {
            val ended = actions.awaitApproval(address, shown)
            code = null
            if (ended == Tried.SignedIn) onSignedIn() else tried = ended
        }
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Server", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = address,
                onValueChange = {
                    address = it
                    tried = null
                },
                enabled = code == null,
                label = { Text("Server address") },
                placeholder = { Text("http://192.168.1.10:8096") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("jellyfin.address"),
            )
            val showing = code
            if (showing != null) {
                Spacer(Modifier.height(16.dp))
                CodePanel(showing, onCancel = { code = null })
                return@Column
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = user,
                onValueChange = {
                    user = it
                    tried = null
                },
                label = { Text("User") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("jellyfin.user"),
            )
            Spacer(Modifier.height(8.dp))
            PasswordField(password, kept = kept.signedIn, onValueChange = {
                password = it
                tried = null
            })
            tried?.let {
                Spacer(Modifier.height(12.dp))
                Outcome(it)
            }
            // the password may be empty: a Jellyfin account can have none
            val filled = address.isNotBlank() && user.isNotBlank()
            // signed in, and nothing typed that differs from that sign-in
            val same = kept.signedIn && password.isEmpty() && address == kept.address && user == kept.user
            val idle = !busy && !testing
            Spacer(Modifier.height(16.dp))
            FormButtons(
                testing = testing,
                busy = busy,
                canTest = idle && filled,
                canSignIn = idle && filled && !same,
                onTest = {
                    testing = true
                    tried = null
                    work.launch {
                        tried = actions.test(address, user, password)
                        testing = false
                    }
                },
                onSignIn = {
                    busy = true
                    tried = null
                    work.launch {
                        val said = actions.signIn(address, user, password)
                        busy = false
                        if (said == Tried.SignedIn) onSignedIn() else tried = said
                    }
                },
            )
            if (offered) {
                QuickConnectOffer(enabled = idle, onStart = {
                    busy = true
                    tried = null
                    work.launch {
                        val started = actions.startQuickConnect(address)
                        busy = false
                        if (started == null) tried = Tried.Failed("the server would not start Quick Connect") else code = started
                    }
                })
            }
        }
    }
}

/** Test and Sign in, side by side, each with a spinner while its call is out. */
@Composable
private fun FormButtons(
    testing: Boolean,
    busy: Boolean,
    canTest: Boolean,
    canSignIn: Boolean,
    onTest: () -> Unit,
    onSignIn: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(enabled = canTest, onClick = onTest, modifier = Modifier.weight(1f).testTag("jellyfin.test")) {
            if (testing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (testing) "Testing…" else "Test")
        }
        Button(enabled = canSignIn, onClick = onSignIn, modifier = Modifier.weight(1f).testTag("jellyfin.signin")) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (busy) "Signing in…" else "Sign in")
        }
    }
}

/** The password field, hidden until the eye button is pressed. */
@Composable
private fun PasswordField(
    value: String,
    kept: Boolean,
    onValueChange: (String) -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Password") },
        supportingText = if (kept) ({ Text("Leave empty to stay signed in.") }) else null,
        singleLine = true,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { shown = !shown }, modifier = Modifier.testTag("jellyfin.password.show")) {
                Icon(
                    painterResource(if (shown) R.drawable.ic_eye_off else R.drawable.ic_eye),
                    contentDescription = if (shown) "Hide password" else "Show password",
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().testTag("jellyfin.password"),
    )
}

/** What the last test or sign-in said, in the error colors when it failed. */
@Composable
private fun Outcome(tried: Tried) {
    val good = tried == Tried.Reached || tried == Tried.StillSignedIn
    Surface(
        color = if (good) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (good) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (good) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                words(tried),
                style = MaterialTheme.typography.bodyMedium,
                color = if (good) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.testTag("jellyfin.said"),
            )
        }
    }
}

/** The address without its scheme or trailing slashes. */
private fun host(address: String): String = address.substringAfter("://").trimEnd('/')

/** The sentence the page shows for each outcome. */
private fun words(tried: Tried): String =
    when (tried) {
        Tried.SignedIn -> "Signed in."
        Tried.Reached -> "Connected. Sign in to use this server in Andamp."
        Tried.StillSignedIn -> "Connected. You are signed in."
        Tried.Revoked -> "The server no longer accepts this sign-in. Enter the password and sign in again."
        Tried.NoAddress -> "That is not a server address."
        is Tried.NoServer -> "Nothing answered at that address: ${tried.why}"
        is Tried.NotJellyfin -> "Something answered with HTTP ${tried.status}, but it was not Jellyfin."
        Tried.Refused -> "Wrong user or password."
        Tried.Expired -> "The code expired. Try again."
        is Tried.Failed -> "Jellyfin did not sign you in: ${tried.why}"
    }

/** How long after the last change to the address the server is asked whether it offers Quick Connect. */
internal const val PROBE_AFTER_MS = 600L
