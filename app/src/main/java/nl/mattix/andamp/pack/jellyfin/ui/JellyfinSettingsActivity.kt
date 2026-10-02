// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.jellyfin.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import nl.mattix.andamp.pack.common.PackLauncherEntry
import nl.mattix.andamp.pack.jellyfin.R
import nl.mattix.andamp.pack.jellyfin.pack.PackIdentity
import nl.mattix.andamp.pack.jellyfin.pack.PackService
import nl.mattix.andamp.pack.jellyfin.pack.PackStore

/**
 * The one screen this app has: which server, and who is signed in to it.
 *
 * The player starts it by resolving `nl.mattix.andamp.source.SETTINGS`. It is
 * also the app's launcher entry, through an alias in the manifest. It is
 * drawn in Material 3 and not in the player's skin.
 */
class JellyfinSettingsActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = PackStore(this)
        // puts the icon in the app list when the app is opened before Andamp
        // has bound the service; see PackLauncherEntry
        val appList = PackLauncherEntry(this, PackIdentity.LAUNCHER_ALIAS).also { it.restore() }
        setContent {
            PackTheme {
                Surface(Modifier.fillMaxSize()) {
                    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
                    Scaffold(
                        modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
                        topBar = {
                            LargeTopAppBar(
                                title = { Text(PackIdentity.LABEL) },
                                scrollBehavior = bar,
                                navigationIcon = {
                                    IconButton(
                                        onClick = { finish() },
                                        modifier =
                                            Modifier
                                                .semantics { contentDescription = "Back" }
                                                .testTag("pack.settings.back"),
                                    ) { Icon(painterResource(R.drawable.ic_back), contentDescription = null) }
                                },
                            )
                        },
                    ) { padding ->
                        // PackService::announce tells every bound player about
                        // a sign-in or sign-out made here
                        JellyfinPage(
                            SettingsActions(applicationContext, store, PackService::announce),
                            appList = appList,
                            onDone = ::finish,
                            padding = padding,
                        )
                    }
                }
            }
        }
    }
}

/** Dynamic colors from Android 12 on and Material's default scheme before that, light or dark as the system is. */
@Composable
private fun PackTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
            dark -> darkColorScheme()
            else -> lightColorScheme()
        }
    MaterialTheme(colorScheme = colors, content = content)
}
