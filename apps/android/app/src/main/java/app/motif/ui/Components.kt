package app.motif.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.motif.data.Track
import app.motif.ui.theme.Motif

/** Flat colour tile with the title's first letter, standing in for album art. */
@Composable
fun ArtTile(seed: String, letter: String, size: Dp = 44.dp, radius: Dp = 6.dp, modifier: Modifier = Modifier) {
    val (bg, fg) = remember(seed) { Motif.artColors(seed) }
    Box(
        modifier.size(size).background(bg, RoundedCornerShape(radius)).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = fg, fontWeight = FontWeight.Bold, fontSize = (size.value / 2.6f).sp)
    }
}

@Composable
fun FormatBadge(text: String) {
    Text(
        text,
        color = Motif.badge,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.4.sp,
        modifier = Modifier
            .border(1.dp, Color(0xFF3A3F4A), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
fun SectionHeader(title: String, detail: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Motif.text)
        Spacer(Modifier.weight(1f))
        if (detail != null) Text(detail, fontSize = 13.sp, color = Motif.secondary)
    }
}

@Composable
fun AlbumTile(title: String, artist: String?, letter: String, onClick: () -> Unit) {
    Column(
        Modifier.width(132.dp).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ArtTile(title, letter, size = 132.dp, radius = 10.dp)
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Motif.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (artist != null) Text(artist, fontSize = 12.sp, color = Motif.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Song row: art, title, format badge + artist, and BPM / Camelot key on the right. Long-press for actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(track: Track, isCurrent: Boolean, onClick: () -> Unit, onDelete: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = onDelete?.let { { menu = true } })
                .heightIn(min = 60.dp)
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull(track.title, track.artist, track.bpmText, track.musicalKey, track.format).joinToString(", ")
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ArtTile(track.artSeed, track.monogram)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    track.title, fontSize = 16.sp, color = if (isCurrent) Motif.accent else Motif.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FormatBadge(track.format.uppercase())
                    Text(track.artist ?: "Unknown artist", fontSize = 13.sp, color = Motif.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(track.bpmText, style = Motif.mono(12.sp), color = Motif.secondary)
                Text(track.musicalKey ?: "—", style = Motif.mono(12.sp), color = Motif.keyColor(track.musicalKey))
            }
        }
        if (onDelete != null) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Delete from library") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

/**
 * Bar waveform from the analysed overview; the played part is drawn in the
 * accent. Drag or tap to seek. Flat bars before a track is analysed.
 */
@Composable
fun WaveformScrubber(
    values: ByteArray?,
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    barCount: Int = 60,
    playedColor: Color = Motif.accent,
    unplayedColor: Color = Motif.waveUnplayed,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val levels = remember(values, barCount) {
        FloatArray(barCount) { i ->
            if (values == null || values.isEmpty()) 0.35f else {
                val from = i * values.size / barCount
                val to = maxOf(from + 1, (i + 1) * values.size / barCount)
                var peak = 0
                for (j in from until minOf(to, values.size)) peak = maxOf(peak, values[j].toInt() and 0xFF)
                0.12f + 0.88f * peak / 255f
            }
        }
    }
    Canvas(
        modifier
            .semantics { contentDescription = "Playback position" }
            .pointerInput(Unit) {
                detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let(onSeek); drag = null },
                    onDragCancel = { drag = null },
                ) { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) }
            },
    ) {
        val shown = drag ?: progress
        val gap = 2.dp.toPx()
        val w = maxOf(1f, (size.width - gap * (barCount - 1)) / barCount)
        for (i in 0 until barCount) {
            val h = maxOf(3.dp.toPx(), size.height * levels[i])
            drawRoundRect(
                color = if (i.toFloat() / barCount < shown) playedColor else unplayedColor,
                topLeft = Offset(i * (w + gap), (size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}
