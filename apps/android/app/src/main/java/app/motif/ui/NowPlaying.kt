package app.motif.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.motif.data.Track
import app.motif.data.formatTime
import app.motif.playback.MixMatch
import app.motif.playback.Mixing
import app.motif.playback.PlaybackEngine
import app.motif.playback.PlayerState
import app.motif.ui.theme.Motif
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun PlayerState.progress(): Float = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

/** Floating mini player above the navigation bar. Tap to open Now Playing. */
@Composable
fun MiniPlayer(state: PlayerState, engine: PlaybackEngine, onOpen: () -> Unit) {
    val track = state.current ?: return
    Column(
        Modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Motif.raised)
            .clickable(onClickLabel = "Open Now Playing", onClick = onOpen),
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ArtTile(track.artSeed, track.monogram, size = 40.dp, artIds = listOf(track.id))
            Column(Modifier.weight(1f)) {
                Text(track.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Motif.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (state.isMixing) "Blending into ${state.upNext?.title ?: "next"}" else (track.artist ?: track.shortQualityLabel),
                    fontSize = 12.sp, color = if (state.isMixing) Motif.accent else Motif.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = engine::togglePlayPause) {
                Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (state.isPlaying) "Pause" else "Play", tint = Motif.text)
            }
            IconButton(
                onClick = engine::next,
                enabled = state.upNext != null,
                colors = IconButtonDefaults.iconButtonColors(contentColor = Motif.text, disabledContentColor = Motif.hairline),
            ) { Icon(Icons.Filled.SkipNext, "Next") }
        }
        LinearProgressIndicator(
            progress = { state.progress() },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = Motif.accent,
            trackColor = Motif.hairline,
            drawStopIndicator = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(state: PlayerState, engine: PlaybackEngine, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showQueue by remember { mutableStateOf(false) }
    val track = state.current ?: return
    val backdrop by animateColorAsState(rememberBackdropTint(track), tween(600), label = "backdrop")
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Motif.nowPlayingGround,
        contentColor = Motif.text,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // The art's colour washes down from the top and settles into the usual ground.
                .background(Brush.verticalGradient(0f to backdrop, 0.62f to Motif.nowPlayingGround))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.KeyboardArrowDown, "Close", tint = Motif.badge) }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.Check, null, tint = Motif.done, modifier = Modifier.size(14.dp))
                Text(" On this phone", fontSize = 12.sp, color = Motif.badge)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue", tint = Motif.badge) }
            }

            val artScale by animateFloatAsState(if (state.isPlaying) 1f else 0.86f, tween(350), label = "art")
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, 360.dp)
                ArtTile(
                    track.artSeed, track.monogram, size = side, radius = 14.dp, artIds = listOf(track.id),
                    modifier = Modifier.scale(artScale).shadow(24.dp, RoundedCornerShape(14.dp)),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(track.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Motif.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.subtitle, fontSize = 17.sp, color = Color(0xFFB5BAC3), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TrackChips(track)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WaveformScrubber(track.waveform, state.progress(), onSeek = engine::seekTo, modifier = Modifier.fillMaxWidth().height(40.dp))
                Row {
                    Text(formatTime(state.positionMs / 1000.0), style = Motif.mono(12.sp), color = Motif.secondary)
                    Spacer(Modifier.weight(1f))
                    Text(track.qualityLabel, style = Motif.mono(12.sp), color = Motif.badge)
                    Spacer(Modifier.weight(1f))
                    Text("-" + formatTime(maxOf(0L, state.durationMs - state.positionMs) / 1000.0), style = Motif.mono(12.sp), color = Motif.secondary)
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = engine::previous, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipPrevious, "Previous", tint = Motif.text, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(Motif.accent)
                        .clickable(onClick = engine::togglePlayPause)
                        .semantics { role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (state.isPlaying) "Pause" else "Play",
                        tint = Motif.onAccent,
                        modifier = Modifier.size(44.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = engine::next,
                    enabled = state.upNext != null,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Motif.text, disabledContentColor = Motif.hairline),
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(Icons.Filled.SkipNext, "Next", modifier = Modifier.size(36.dp))
                }
            }

            MixToggleRow(state, engine)
            UpNext(state, engine, onOpenQueue = { showQueue = true })
            Spacer(Modifier.height(8.dp))
        }
    }
    if (showQueue) QueueSheet(state, engine, onDismiss = { showQueue = false })
}

/** The backdrop colour: a darkened average of the art, else of the letter tile's colour. */
@Composable
private fun rememberBackdropTint(track: Track): Color {
    val fallback = remember(track.artSeed) { Motif.artColors(track.artSeed).first.copy(alpha = 0.7f) }
    val store = LocalArtwork.current ?: return fallback
    val available by store.available.collectAsStateWithLifecycle()
    val hasArt = track.id in available
    val tint by produceState<Color?>(null, track.id, hasArt) {
        value = if (hasArt) withContext(Dispatchers.IO) { store.tint(track.id) }?.let { Color(it) } else null
    }
    return tint ?: fallback
}

/** BPM and Camelot key chips in their colours, and a lossless chip, under the title; "Not analysed" until analysis has run. */
@Composable
private fun TrackChips(track: Track) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (track.bpm == null && track.musicalKey == null) {
            Chip("Not analysed", Motif.secondary)
        } else {
            track.bpm?.let { Chip(track.bpmText, Motif.bpmColor(it), mono = true) }
            track.musicalKey?.let { Chip(it, Motif.keyColor(it), mono = true) }
        }
        if (track.isLossless) Chip("Lossless", Motif.done)
    }
}

@Composable
private fun Chip(text: String, color: Color, mono: Boolean = false) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text,
        color = color,
        style = if (mono) Motif.mono(13.sp, FontWeight.SemiBold) else TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), shape)
            .border(1.dp, color.copy(alpha = 0.35f), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** The next few tracks in the queue; tap one to jump to it. */
@Composable
private fun UpNext(state: PlayerState, engine: PlaybackEngine, onOpenQueue: () -> Unit) {
    val next = state.queue.drop(state.currentIndex + 1).take(UP_NEXT_COUNT)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Up next", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Motif.text, modifier = Modifier.weight(1f))
            if (state.queue.size > 1) {
                TextButton(onClick = onOpenQueue) { Text("Queue · ${state.queue.size}", color = Motif.accent, fontSize = 14.sp) }
            }
        }
        if (next.isEmpty()) {
            Text("Nothing after this one. Play an album or a list to fill the queue.", fontSize = 13.sp, color = Motif.secondary)
        }
        next.forEachIndexed { i, track ->
            val index = state.currentIndex + 1 + i
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClickLabel = "Play ${track.title}") { engine.skipTo(index) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ArtTile(track.artSeed, track.monogram, size = 44.dp, artIds = listOf(track.id))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(track.title, fontSize = 15.sp, color = Motif.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist ?: "Unknown artist", fontSize = 13.sp, color = Motif.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Boxed when it mixes with what's playing, as in the library.
                val current = state.current
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    MixValue(track.bpmText, Motif.bpmColor(track.bpm), current != null && MixMatch.tempos(current, track))
                    MixValue(track.musicalKey ?: "—", Motif.keyColor(track.musicalKey), current != null && MixMatch.keys(current, track))
                }
            }
        }
    }
}

private const val UP_NEXT_COUNT = 3

/** "Mix into next": beatmatched blend into the next track, else gapless. */
@Composable
fun MixToggleRow(state: PlayerState, engine: PlaybackEngine) {
    val on = state.mixIntoNext
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Motif.mixCard)
            .clickable { engine.setMixIntoNext(!on) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.Tune, null, tint = if (on) Motif.accent else Color(0xFF6B7280))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                state.upNext?.let { "Mix into next: ${it.title}" } ?: "Mix into next",
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Motif.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (state.isMixing) "Blending now" else Mixing.detail(state.current, state.upNext, on),
                fontSize = 12.sp, color = Motif.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = on,
            onCheckedChange = engine::setMixIntoNext,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Motif.accent,
                checkedThumbColor = Motif.onAccent,
                uncheckedTrackColor = Motif.raised,
                uncheckedThumbColor = Motif.secondary,
                uncheckedBorderColor = Motif.hairline,
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(state: PlayerState, engine: PlaybackEngine, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Motif.surface) {
        Text("Up Next", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyColumn(Modifier.fillMaxHeight(0.7f)) {
            itemsIndexed(state.queue, key = { i, t -> "$i-${t.id}" }) { index, track ->
                TrackRow(track, index == state.currentIndex, onClick = { engine.skipTo(index) }, mixWith = state.current)
            }
        }
    }
}
