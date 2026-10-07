package app.motif.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.motif.MotifApp
import app.motif.ui.theme.Motif

enum class Tab(val label: String, val icon: ImageVector) {
    Library("Library", Icons.Outlined.LibraryMusic),
    Crates("Crates", Icons.Outlined.ViewAgenda),
    Mix("Mix", Icons.Outlined.Tune),
    Search("Search", Icons.Outlined.Search),
}

/** Tabs (Library, Crates, Mix, Search), the mini player above the bar, and the Now Playing / Add Music sheets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotifRoot(app: MotifApp) {
    var tab by rememberSaveable { mutableStateOf(Tab.Library) }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    val tracks by app.library.tracks.collectAsStateWithLifecycle()
    val player by app.playback.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Motif.ground,
        // Each screen handles the status bar itself; the navigation bar handles the bottom inset.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            Column {
                player.current?.let { MiniPlayer(player, app.playback, onOpen = { showNowPlaying = true }) }
                NavigationBar(containerColor = Motif.surface) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Motif.accent,
                                selectedTextColor = Motif.accent,
                                indicatorColor = Motif.raised,
                                unselectedIconColor = Motif.secondary,
                                unselectedTextColor = Motif.secondary,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when (tab) {
            Tab.Library -> LibraryScreen(
                tracks = tracks,
                currentId = player.current?.id,
                onPlay = { list, index -> app.playback.play(list, index) },
                onDelete = app::delete,
                onImport = { showImport = true },
                onOpenMix = { tab = Tab.Mix },
                modifier = modifier,
            )
            Tab.Crates -> ComingSoon("No crates yet", "Crates for building sets are coming soon.", modifier)
            Tab.Mix -> ComingSoon(
                "DJ Mix is on its way",
                "Two decks, sync and a crossfader are next. Until then, turn on Mix into next in Now Playing for automatic beatmatched blends.",
                modifier,
            )
            Tab.Search -> SearchScreen(
                tracks = tracks,
                currentId = player.current?.id,
                onPlay = { list, index -> app.playback.play(list, index) },
                modifier = modifier,
            )
        }
    }

    if (showNowPlaying && player.current != null) {
        NowPlayingSheet(player, app.playback, onDismiss = { showNowPlaying = false })
    }
    if (showImport) {
        ImportSheet(app, onDismiss = { showImport = false })
    }
}
