package app.motif.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.motif.data.Album
import app.motif.data.Artist
import app.motif.data.PlayContext
import app.motif.data.Track
import app.motif.data.formatTime
import app.motif.ui.theme.Motif

/** An album or artist page, opened over the tabs. */
sealed interface LibraryPage {
    data class AlbumPage(val key: String) : LibraryPage
    data class ArtistPage(val key: String) : LibraryPage

    companion object {
        /** Saves the open pages across configuration changes. */
        val StackSaver = Saver<List<LibraryPage>, ArrayList<String>>(
            save = { pages ->
                pages.mapTo(ArrayList()) {
                    when (it) {
                        is AlbumPage -> "album:${it.key}"
                        is ArtistPage -> "artist:${it.key}"
                    }
                }
            },
            restore = { saved ->
                saved.mapNotNull {
                    when {
                        it.startsWith("album:") -> AlbumPage(it.removePrefix("album:"))
                        it.startsWith("artist:") -> ArtistPage(it.removePrefix("artist:"))
                        else -> null
                    }
                }
            },
        )
    }
}

/** The top page of [pages]; system back closes it. */
@Composable
fun LibraryPages(
    pages: List<LibraryPage>,
    current: Track?,
    onPlay: (List<Track>, Int, PlayContext) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = LocalLibraryActions.current ?: return
    val page = pages.lastOrNull() ?: return
    BackHandler(onBack = onBack)
    Box(modifier.background(Motif.ground)) {
        when (page) {
            is LibraryPage.AlbumPage -> actions.index.album(page.key)
                ?.let { AlbumScreen(it, current, onPlay, onBack) }
                ?: Gone("This album isn't in your library any more.", onBack)
            is LibraryPage.ArtistPage -> actions.index.artist(page.key)
                ?.let { ArtistScreen(it, current, onPlay, onBack) }
                ?: Gone("This artist isn't in your library any more.", onBack)
        }
    }
}

/** An album: cover, credits and its songs in album order. Tap a song to play the album from it; long-press to queue it. */
@Composable
private fun AlbumScreen(album: Album, current: Track?, onPlay: (List<Track>, Int, PlayContext) -> Unit, onBack: () -> Unit) {
    val actions = LocalLibraryActions.current ?: return
    val artist = remember(album, actions.index) { actions.index.artistOf(album) }
    // Name each song's artist when the album has several.
    val showArtists = artist == null
    Column(Modifier.fillMaxSize()) {
        PageBar(album.title, onBack) { done -> TrackMenuItems(album.tracks, done, hideAlbum = true); AddToCrateItem(album.tracks, done) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ArtTile(
                        album.title, album.tracks[0].monogram, size = 240.dp, radius = 12.dp, artIds = album.tracks.map { it.id },
                        modifier = Modifier.shadow(20.dp, RoundedCornerShape(12.dp)),
                    )
                    Text(
                        album.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Motif.text,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp),
                    )
                    if (artist != null) {
                        Text(
                            artist.name, fontSize = 17.sp, color = Motif.accent,
                            modifier = Modifier
                                .padding(vertical = 2.dp)
                                .clickable(onClickLabel = "Open artist") { actions.open(LibraryPage.ArtistPage(artist.key)) },
                        )
                    } else {
                        Text(album.artist ?: "Unknown artist", fontSize = 17.sp, color = Motif.secondary)
                    }
                    Text("${album.summary} · ${album.qualitySummary}", style = Motif.mono(12.sp), color = Motif.secondary)
                    PlayShuffle(album.tracks, PlayContext.ALBUM, onPlay, Modifier.padding(top = 8.dp))
                }
            }
            itemsIndexed(album.tracks, key = { _, t -> t.id }) { index, track ->
                AlbumTrackRow(
                    number = track.trackNumber ?: (index + 1),
                    track = track,
                    isCurrent = track.id == current?.id,
                    showArtist = showArtists,
                    onClick = { onPlay(album.tracks, index, PlayContext.ALBUM) },
                )
            }
        }
    }
}

/** An artist: their albums, then every song that credits them. */
@Composable
private fun ArtistScreen(artist: Artist, current: Track?, onPlay: (List<Track>, Int, PlayContext) -> Unit, onBack: () -> Unit) {
    val actions = LocalLibraryActions.current ?: return
    val albums = remember(artist, actions.index) { actions.index.albumsWith(artist) }
    Column(Modifier.fillMaxSize()) {
        PageBar(artist.name, onBack) { done -> TrackMenuItems(artist.tracks, done); AddToCrateItem(artist.tracks, done) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ArtTile(artist.name, artist.monogram, size = 160.dp, radius = 80.dp, artIds = artist.tracks.map { it.id })
                    Text(
                        artist.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Motif.text,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(artist.summary(albums.size), fontSize = 13.sp, color = Motif.secondary)
                    PlayShuffle(artist.tracks, PlayContext.ARTIST, onPlay, Modifier.padding(top = 8.dp))
                }
            }
            if (albums.isNotEmpty()) {
                item { SectionHeader("Albums", "${albums.size}") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(albums, key = { it.key }) { album ->
                            AlbumTile(album.title, album.summary, album.tracks[0].monogram, album.tracks.map { it.id }) {
                                actions.open(LibraryPage.AlbumPage(album.key))
                            }
                        }
                    }
                }
            }
            item { SectionHeader("Songs", "${artist.tracks.size}") }
            itemsIndexed(artist.tracks, key = { _, t -> t.id }) { index, track ->
                TrackRow(
                    track, track.id == current?.id,
                    onClick = { onPlay(artist.tracks, index, PlayContext.ARTIST) },
                    mixWith = current,
                    onAddToCrate = { actions.addToCrate(listOf(track)) },
                    hideArtistKey = artist.key,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageBar(title: String, onBack: () -> Unit, menuItems: @Composable (done: () -> Unit) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        actions = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { menuItems { menu = false } }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Motif.ground, titleContentColor = Motif.text),
    )
}

@Composable
private fun AddToCrateItem(tracks: List<Track>, onDone: () -> Unit) {
    val actions = LocalLibraryActions.current ?: return
    DropdownMenuItem(text = { Text("Add to crate") }, onClick = { onDone(); actions.addToCrate(tracks) })
}

@Composable
private fun PlayShuffle(tracks: List<Track>, context: PlayContext, onPlay: (List<Track>, Int, PlayContext) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = { onPlay(tracks, 0, context) },
            enabled = tracks.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = Motif.accent, contentColor = Motif.onAccent),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
            Text(" Play", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
            onClick = { onPlay(tracks.shuffled(), 0, context) },
            enabled = tracks.isNotEmpty(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Motif.accent),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) {
            Icon(Icons.Filled.Shuffle, null, Modifier.size(20.dp))
            Text(" Shuffle", fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A song on an album page: its number instead of art, since the cover is above. Long-press to queue it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTrackRow(number: Int, track: Track, isCurrent: Boolean, showArtist: Boolean, onClick: () -> Unit) {
    val actions = LocalLibraryActions.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .heightIn(min = 56.dp)
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull("$number", track.title, track.artist.takeIf { showArtist }, formatTime(track.durationSeconds))
                        .joinToString(", ")
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.CenterEnd) {
                if (isCurrent) Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Motif.accent, modifier = Modifier.size(16.dp))
                else Text("$number", style = Motif.mono(13.sp), color = Motif.secondary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(track.title, fontSize = 16.sp, color = if (isCurrent) Motif.accent else Motif.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (showArtist && track.artist != null) {
                    Text(track.artist, fontSize = 13.sp, color = Motif.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                MixValue(track.bpmText, Motif.bpmColor(track.bpm), false)
                MixValue(track.musicalKey ?: "—", Motif.keyColor(track.musicalKey), false)
            }
            Text(formatTime(track.durationSeconds), style = Motif.mono(12.sp), color = Motif.secondary)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            TrackMenuItems(listOf(track), onDone = { menu = false }, hideAlbum = true)
            if (actions != null) {
                DropdownMenuItem(text = { Text("Add to crate") }, onClick = { menu = false; actions.addToCrate(listOf(track)) })
            }
        }
    }
}

@Composable
private fun Gone(message: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageBar("", onBack) {}
        ComingSoon("Not in library", message, Modifier.fillMaxSize())
    }
}
