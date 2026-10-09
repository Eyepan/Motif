package app.motif.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
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
import app.motif.playback.DjState
import app.motif.playback.MixMatch
import app.motif.ui.theme.Motif
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

private fun DeckId.color(): Color = if (this == DeckId.A) Motif.deckA else Motif.deckB

/**
 * DJ Mix: stacked deck waveforms, two deck panels (CUE, play, SYNC, tempo,
 * EQ and filter knobs, beat loops), the crossfader with BLEND, and a library
 * browser that loads tracks onto either deck with harmonic-match suggestions.
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
            Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.notice ?: blendLabel(state),
                fontSize = 12.sp, color = Motif.badge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Blends from the deck being heard into the other, on the beat.
            DeckButton(
                if (state.blend != null) "STOP BLEND" else "BLEND",
                selected = state.blend != null,
                color = Motif.accent,
                enabled = state.anyPlaying,
                onClick = dj::toggleBlend,
            )
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
                BrowserRow(
                    track,
                    mixWith = ref?.track?.takeIf { it.id != track.id },
                    loadedOn = DeckId.entries.filter { state[it].track?.id == track.id },
                    onLoad = { dj.load(it, track) },
                )
            }
        }
    }
}

/** Mixes with the deck's track: compatible key and a tempo SYNC can match (the shared MixMatch rule). */
private fun harmonicMatch(deck: DeckState, track: Track): Boolean {
    val ref = deck.track ?: return false
    if (track.id == ref.id) return false
    val keyOk = ref.musicalKey == null || MixMatch.keys(ref, track)
    val bpmOk = ref.bpm == null || MixMatch.tempos(ref, track)
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // Read the knobs fresh on every move: a drag sends several before the next recomposition.
            val fx = { dj.state.value[id].fx }
            Knob("LOW", "Deck ${id.name} low", deck.fx.low, color) { dj.setFx(id, fx().copy(low = it)) }
            Knob("MID", "Deck ${id.name} mid", deck.fx.mid, color) { dj.setFx(id, fx().copy(mid = it)) }
            Knob("HI", "Deck ${id.name} high", deck.fx.high, color) { dj.setFx(id, fx().copy(high = it)) }
            Knob("FILTER", "Deck ${id.name} filter", deck.fx.filter, color) { dj.setFx(id, fx().copy(filter = it)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("LOOP", style = Motif.mono(9.sp, FontWeight.Bold), color = Motif.secondary)
            DjEngine.LOOP_BEATS.forEach { beats ->
                val on = deck.loop?.beats == beats
                Box(
                    Modifier.weight(1f).height(26.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (on) color else Color.Transparent)
                        .border(1.dp, if (on) color else Motif.hairline, RoundedCornerShape(6.dp))
                        .clickable(enabled = track?.hasBeatGrid == true) { dj.toggleLoop(id, beats) }
                        .semantics {
                            role = Role.Button
                            selected = on
                            contentDescription = "Deck ${id.name} loop ${beats.toInt()} ${if (beats == 1.0) "beat" else "beats"}"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${beats.toInt()}",
                        style = Motif.mono(10.sp, FontWeight.Bold),
                        color = when {
                            on -> Motif.onAccent
                            track?.hasBeatGrid == true -> Motif.text
                            else -> Motif.secondary
                        },
                    )
                }
            }
        }
    }
}

/** "Blending into B · 16 bars on the beat" while BLEND runs. */
private fun blendLabel(state: DjState): String {
    val blend = state.blend ?: return ""
    return "Blending into ${blend.to.name}" + (blend.bars?.let { " · $it bars on the beat" } ?: "")
}

/**
 * A mixer knob, -1..1 with 0 at the top: drag up or down to turn it,
 * double-tap to centre it. The arc runs from the centre to the value.
 */
@Composable
private fun Knob(label: String, description: String, value: Float, color: Color, onChange: (Float) -> Unit) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(30.dp)
                .pointerInput(Unit) {
                    // Full left to full right over this much drag.
                    val travel = 80.dp.toPx()
                    var dragged = 0f
                    detectVerticalDragGestures(onDragStart = { dragged = current }) { pointer, drag ->
                        pointer.consume()
                        dragged = (dragged - 2 * drag / travel).coerceIn(-1f, 1f)
                        change(dragged)
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { change(0f) }) }
                .semantics {
                    contentDescription = description
                    stateDescription = when {
                        abs(value) < 0.005f -> "centre"
                        else -> String.format(Locale.ROOT, "%+.0f%%", value * 100)
                    }
                    progressBarRangeInfo = ProgressBarRangeInfo(value, -1f..1f)
                    setProgress { target ->
                        change(target.coerceIn(-1f, 1f))
                        true
                    }
                },
        ) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2 + 1.dp.toPx()
            val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
            val topLeft = Offset(inset, inset)
            drawArc(Motif.hairline, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (abs(value) > 0.005f) {
                drawArc(color, 270f, value * 135f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            val angle = Math.toRadians(270.0 + value * 135.0)
            val r = arcSize.width / 2 - stroke
            drawLine(
                Motif.text,
                center,
                center + Offset((cos(angle) * r).toFloat(), (sin(angle) * r).toFloat()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        Text(label, style = Motif.mono(8.sp, FontWeight.Bold), color = if (abs(value) > 0.005f) color else Motif.secondary)
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
private fun BrowserRow(track: Track, mixWith: Track?, loadedOn: List<DeckId>, onLoad: (DeckId) -> Unit) {
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
            MixValue(track.bpm?.let { "${Math.round(it)}" } ?: "—", Motif.bpmColor(track.bpm), mixWith != null && MixMatch.tempos(mixWith, track))
            MixValue(track.musicalKey ?: "—", Motif.keyColor(track.musicalKey), mixWith != null && MixMatch.keys(mixWith, track))
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
