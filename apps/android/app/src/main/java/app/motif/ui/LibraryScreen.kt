package app.motif.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.motif.data.LibraryFilter
import app.motif.data.Track
import app.motif.data.albumsOf
import app.motif.data.artistsOf
import app.motif.ui.theme.Motif

private enum class Segment(val label: String) { Songs("Songs"), Albums("Albums"), Artists("Artists"), Crates("Crates") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    tracks: List<Track>,
    current: Track?,
    onPlay: (List<Track>, Int) -> Unit,
    onDelete: (Track) -> Unit,
    onImport: () -> Unit,
    onOpenMix: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var segment by rememberSaveable { mutableStateOf(Segment.Songs) }
    var query by rememberSaveable { mutableStateOf("") }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val songs = remember(tracks, query) { LibraryFilter(query).let { f -> tracks.filter(f::matches) } }
    val albums = remember(tracks) { albumsOf(tracks) }
    val artists = remember(tracks) { artistsOf(tracks) }

    Column(modifier.nestedScroll(scroll.nestedScrollConnection)) {
        LargeTopAppBar(
            title = { Text("Library", fontWeight = FontWeight.Bold) },
            actions = {
                IconButton(onClick = onOpenMix) { Icon(Icons.Outlined.Tune, "Open DJ mix") }
                IconButton(onClick = onImport) { Icon(Icons.Filled.Add, "Add music") }
            },
            colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = Motif.ground, scrolledContainerColor = Motif.surface),
            scrollBehavior = scroll,
        )
        if (tracks.isEmpty()) EmptyLibrary(onImport) else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Songs, artists, BPM, key") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Motif.raised,
                        focusedContainerColor = Motif.raised,
                        unfocusedBorderColor = Motif.raised,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Segment.entries.forEachIndexed { i, s ->
                        SegmentedButton(
                            selected = segment == s,
                            onClick = { segment = s },
                            shape = SegmentedButtonDefaults.itemShape(i, Segment.entries.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = Motif.raised,
                                activeContentColor = Motif.text,
                                inactiveContainerColor = Motif.ground,
                                inactiveContentColor = Motif.secondary,
                            ),
                            icon = {},
                        ) { Text(s.label, fontSize = 13.sp) }
                    }
                }
            }
            when (segment) {
                Segment.Songs -> {
                    if (query.isEmpty() && albums.isNotEmpty()) {
                        item { SectionHeader("Recently added", "All on device") }
                        item {
                            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(albums.take(10), key = { it.key }) { album ->
                                    AlbumTile(album.title, album.artist, album.tracks[0].monogram, album.tracks.map { it.id }) { onPlay(album.tracks, 0) }
                                }
                            }
                        }
                    }
                    item {
                        SectionHeader("Songs", "${songs.size} · ${if (songs.all(Track::isLossless)) "lossless" else "on device"}")
                    }
                    itemsIndexed(songs, key = { _, t -> t.id }) { index, track ->
                        TrackRow(track, track.id == current?.id, onClick = { onPlay(songs, index) }, onDelete = { onDelete(track) }, mixWith = current)
                    }
                }
                Segment.Albums -> items(albums, key = { it.key }) { album ->
                    ListItem(
                        headlineContent = { Text(album.title) },
                        supportingContent = { Text("${album.artist ?: "Unknown artist"} · ${album.tracks.size} songs") },
                        leadingContent = { ArtTile(album.title, album.tracks[0].monogram, size = 56.dp, radius = 8.dp, artIds = album.tracks.map { it.id }) },
                        colors = ListItemDefaults.colors(containerColor = Motif.ground),
                        modifier = Modifier.padding(horizontal = 4.dp).clickable { onPlay(album.tracks, 0) },
                    )
                }
                Segment.Artists -> {
                    items(artists, key = { it.key }) { artist ->
                        ListItem(
                            headlineContent = { Text(artist.name) },
                            trailingContent = { Text("${artist.tracks.size}", color = Motif.secondary) },
                            colors = ListItemDefaults.colors(containerColor = Motif.ground),
                            modifier = Modifier.padding(horizontal = 4.dp).clickable { onPlay(artist.tracks, 0) },
                        )
                    }
                }
                Segment.Crates -> item {
                    Text(
                        "Crates for building sets are coming soon.",
                        color = Motif.secondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.MusicNote, null, tint = Motif.secondary, modifier = Modifier.size(48.dp))
            Text("No music yet", fontSize = 22.sp, color = Motif.text)
            Text("Add FLAC or WAV files from your phone, an SD card or a USB drive.", color = Motif.secondary, textAlign = TextAlign.Center)
            Button(onClick = onImport) { Text("Add music") }
        }
    }
}

@Composable
fun ComingSoon(title: String, detail: String, modifier: Modifier = Modifier) {
    Box(modifier.statusBarsPadding().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 22.sp, color = Motif.text, textAlign = TextAlign.Center)
            Text(detail, color = Motif.secondary, textAlign = TextAlign.Center)
        }
    }
}
