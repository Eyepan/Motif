package app.motif.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.motif.data.Track
import app.motif.data.formatTime
import app.motif.playback.DeckId
import app.motif.playback.DeckState
import app.motif.playback.DjEngine
import app.motif.ui.theme.Motif
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

private fun DeckId.color(): Color = if (this == DeckId.A) Motif.deckA else Motif.deckB

/**
 * DJ Mix: stacked deck waveforms, two deck panels (CUE, play, SYNC, tempo),
 * the crossfader, and a library browser that loads tracks onto either deck
 * with harmonic-match suggestions.
 */
@Composable
fun DjScreen(dj: DjEngine, tracks: List<Track>, modifier: Modifier = Modifier) {
    val state by dj.state.collectAsStateWithLifecycle()
    var matchesOnly by rememberSaveable { mutableStateOf(false) }

    state.notice?.let { notice ->
        LaunchedEffect(notice) {
            delay(2_500)
            dj.clearNotice()
        }
    }

    // Suggestions follow the deck that's playing (A if both or neither).
    val reference = DeckId.entries.firstOrNull { state[it].isPlaying && state[it].track != null }
        ?: DeckId.entries.firstOrNull { state[it].track != null }
    val ref = reference?.let { state[it] }
    val shown = if (!matchesOnly || ref == null) tracks else tracks.filter { harmonicMatch(ref, it) }

    Column(modifier.statusBarsPadding()) {
        Text("Mix", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DeckId.entries.forEach { id -> DeckWaveform(id, state[id], onSeek = { dj.seek(id, it) }) }
        }

        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DeckId.entries.forEach { id -> DeckPanel(id, state[id], dj, Modifier.weight(1f)) }
        }

        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("A", style = Motif.mono(13.sp, FontWeight.Bold), color = Motif.deckA)
            Slider(
                value = state.crossfader,
                onValueChange = dj::setCrossfader,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Crossfader" },
                colors = SliderDefaults.colors(
                    thumbColor = Motif.text,
                    activeTrackColor = Motif.hairline,
                    inactiveTrackColor = Motif.hairline,
                ),
            )
            Text("B", style = Motif.mono(13.sp, FontWeight.Bold), color = Motif.deckB)
        }

        Row(
            Modifier.fillMaxWidth().heightIn(min = 24.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(state.notice ?: "", fontSize = 12.sp, color = Motif.badge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Row(Modifier.padding(start = 20.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Library", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = matchesOnly,
                onClick = { matchesOnly = !matchesOnly },
                label = { Text("Harmonic match") },
                enabled = reference != null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Motif.raised,
                    selectedLabelColor = Motif.accent,
                ),
            )
        }

        if (tracks.isEmpty()) {
            Text(
                "Add music in the Library tab to start mixing.",
                color = Motif.secondary,
                modifier = Modifier.padding(20.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shown, key = { it.id }) { track ->
                BrowserRow(track, loadedOn = DeckId.entries.filter { state[it].track?.id == track.id }, onLoad = { dj.load(it, track) })
            }
        }
    }
}

/** Compatible Camelot key and within 4 BPM of the deck as it plays now. */
private fun harmonicMatch(deck: DeckState, track: Track): Boolean {
    val ref = deck.track ?: return false
    if (track.id == ref.id) return false
    val keyOk = ref.musicalKey == null || track.musicalKey?.let { it in ref.compatibleKeys } == true
    val bpm = deck.bpm
    val bpmOk = bpm == null || track.bpm?.let { abs(it - bpm) <= 4 } == true
    return keyOk && bpmOk
}

@Composable
private fun DeckWaveform(id: DeckId, deck: DeckState, onSeek: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DeckBadge(id)
        WaveformScrubber(
            values = deck.track?.waveform,
            progress = deck.progress,
            // The scrubber keeps the first lambda it gets, so this must not capture deck state.
            onSeek = onSeek,
            modifier = Modifier.weight(1f).height(36.dp),
            barCount = 96,
            playedColor = id.color(),
        )
        BeatDots(deck.beatInBar, id.color())
    }
}

@Composable
private fun DeckBadge(id: DeckId) {
    Box(Modifier.size(22.dp).background(id.color(), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
        Text(id.name, color = Motif.onAccent, style = Motif.mono(12.sp, FontWeight.Bold))
    }
}

/** Four dots, the current beat of the bar lit: lining the decks' dots up by eye is how you check a mix. */
@Composable
private fun BeatDots(beat: Int?, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.semantics { contentDescription = beat?.let { "Beat ${it + 1}" } ?: "No beat grid" }) {
        for (row in 0..1) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (col in 0..1) {
                    val i = row * 2 + col
                    Box(Modifier.size(6.dp).background(if (beat == i) color else Motif.hairline, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun DeckPanel(id: DeckId, deck: DeckState, dj: DjEngine, modifier: Modifier = Modifier) {
    val track = deck.track
    val color = id.color()
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Motif.raised).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(track?.title ?: "Deck ${id.name}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            track?.artist ?: if (track == null) "Load a track below" else "Unknown artist",
            fontSize = 12.sp, color = Motif.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                deck.bpm?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—",
                style = Motif.mono(20.sp, FontWeight.Bold), color = Motif.text,
            )
            Text(" BPM", style = Motif.mono(10.sp), color = Motif.secondary, modifier = Modifier.padding(bottom = 3.dp))
            Spacer(Modifier.weight(1f))
            Text(track?.musicalKey ?: "", style = Motif.mono(13.sp, FontWeight.Bold), color = Motif.keyColor(track?.musicalKey))
        }
        Row {
            Text("-" + formatTime(maxOf(0L, deck.durationMs - deck.positionMs) / 1000.0), style = Motif.mono(12.sp), color = Motif.secondary)
            Spacer(Modifier.weight(1f))
            // Tap the tempo readout to reset the fader.
            Text(
                String.format(Locale.ROOT, "%+.1f%%", (deck.speed - 1) * 100),
                style = Motif.mono(12.sp),
                color = if (abs(deck.speed - 1) < 0.0005) Motif.secondary else color,
                modifier = Modifier.clickable(enabled = track != null, onClickLabel = "Reset tempo") { dj.setPitch(id, 0.0) },
            )
        }
        Slider(
            value = (deck.speed - 1).toFloat().coerceIn(-DjEngine.PITCH_RANGE.toFloat(), DjEngine.PITCH_RANGE.toFloat()),
            onValueChange = { dj.setPitch(id, it.toDouble()) },
            valueRange = -DjEngine.PITCH_RANGE.toFloat()..DjEngine.PITCH_RANGE.toFloat(),
            enabled = track != null,
            modifier = Modifier.height(28.dp).semantics { contentDescription = "Deck ${id.name} tempo" },
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = Motif.hairline, inactiveTrackColor = Motif.hairline),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeckButton("CUE", selected = false, color = color, enabled = track != null) { dj.cue(id) }
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (track != null) color else Motif.hairline)
                    .clickable(enabled = track != null) { dj.togglePlay(id) }
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (deck.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (deck.isPlaying) "Pause deck ${id.name}" else "Play deck ${id.name}",
                    tint = Motif.onAccent,
                )
            }
            DeckButton("SYNC", selected = deck.synced, color = color, enabled = track != null) { dj.toggleSync(id) }
        }
    }
}

@Composable
private fun DeckButton(label: String, selected: Boolean, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) color else Color.Transparent)
            .border(1.dp, if (selected) color else Motif.hairline, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button; this.selected = selected }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Motif.mono(11.sp, FontWeight.Bold),
            color = when {
                selected -> Motif.onAccent
                enabled -> Motif.text
                else -> Motif.secondary
            },
        )
    }
}

@Composable
private fun BrowserRow(track: Track, loadedOn: List<DeckId>, onLoad: (DeckId) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ArtTile(track.artSeed, track.monogram, size = 36.dp)
        Column(Modifier.weight(1f)) {
            Text(track.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist ?: "Unknown artist", fontSize = 12.sp, color = Motif.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(track.bpm?.let { "${Math.round(it)}" } ?: "—", style = Motif.mono(12.sp), color = Motif.secondary)
            Text(track.musicalKey ?: "—", style = Motif.mono(12.sp), color = Motif.keyColor(track.musicalKey))
        }
        DeckId.entries.forEach { id ->
            val loaded = id in loadedOn
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (loaded) id.color() else Color.Transparent)
                    .border(1.dp, id.color(), RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "Load on deck ${id.name}") { onLoad(id) }
                    .semantics { role = Role.Button; contentDescription = "Load ${track.title} on deck ${id.name}" },
                contentAlignment = Alignment.Center,
            ) {
                Text(id.name, style = Motif.mono(12.sp, FontWeight.Bold), color = if (loaded) Motif.onAccent else id.color())
            }
        }
    }
}
