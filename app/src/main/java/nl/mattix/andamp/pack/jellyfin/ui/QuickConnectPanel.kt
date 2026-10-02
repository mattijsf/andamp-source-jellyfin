// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin.ui

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import nl.mattix.andamp.pack.jellyfin.QuickConnectCode

/** A divider and the button that asks for a Quick Connect code, under the password sign-in. */
@Composable
internal fun QuickConnectOffer(
    enabled: Boolean,
    onStart: () -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    OutlinedButton(
        enabled = enabled,
        onClick = onStart,
        modifier = Modifier.fillMaxWidth().testTag("jellyfin.quickconnect"),
    ) { Text("Use Quick Connect") }
}

/**
 * The Quick Connect code in large, spaced, fixed-width type, with a copy
 * button, a waiting line and Cancel.
 */
@Composable
internal fun CodePanel(
    code: QuickConnectCode,
    onCancel: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val copying = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Enter this code in Jellyfin: Settings > Quick Connect",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // as wide as the copy button on the right, so the code is centered
            Spacer(Modifier.width(48.dp))
            Text(
                code.code,
                style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp),
                modifier = Modifier.testTag("jellyfin.code"),
            )
            IconButton(
                onClick = {
                    copying.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Jellyfin code", code.code))) }
                },
                modifier = Modifier.testTag("jellyfin.copy"),
            ) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy the code") }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Waiting for approval…", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onCancel, modifier = Modifier.testTag("jellyfin.cancel")) { Text("Cancel") }
    }
}
