package app.motif.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Colours from the approved design (/mnt/project-files/design/apple-ui-decisions.md), same values as the Apple Theme. */
object Motif {
    val ground = Color(0xFF0B0C0F)
    val nowPlayingGround = Color(0xFF10161F)
    val surface = Color(0xFF16181D)
    val raised = Color(0xFF1F2229)
    val hairline = Color(0xFF2A2E37)
    val text = Color(0xFFF2F3F5)
    val secondary = Color(0xFF9AA0AB)
    val badge = Color(0xFFC9CDD4)
    val done = Color(0xFF9FD3C7)
    val mixCard = Color(0xFF18202B)
    val waveUnplayed = Color(0xFF2E3746)

    /** Neon green, Pan's pick. Text on accent fills uses [onAccent]. */
    val accent = Color(0xFFC6F432)
    val onAccent = Color(0xFF0B0C0F)
    val deckA = Color(0xFF5B9BFF)
    val deckB = accent

    /** BPM, key, format and times. */
    fun mono(size: TextUnit, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = size, fontWeight = weight)

    /**
     * Camelot key colours from `schemas/mix-colours.json`. Each wheel number
     * has its own hue and neighbours have neighbouring hues, so keys that mix
     * look alike; majors (B) are lighter than their relative minors (A).
     */
    val keyColors: Map<String, Color> = mapOf(
        "1A" to Color(0xFFEF958E), "1B" to Color(0xFFFDB5AF),
        "2A" to Color(0xFFE79E6B), "2B" to Color(0xFFFEB98B),
        "3A" to Color(0xFFD1AC5A), "3B" to Color(0xFFF1C45E),
        "4A" to Color(0xFFAFB965), "4B" to Color(0xFFC8D56C),
        "5A" to Color(0xFF84C485), "5B" to Color(0xFF93E195),
        "6A" to Color(0xFF58C8AD), "6B" to Color(0xFF5AE6C6),
        "7A" to Color(0xFF47C5D2), "7B" to Color(0xFF40E2F3),
        "8A" to Color(0xFF64BCED), "8B" to Color(0xFF8FD5FD),
        "9A" to Color(0xFF8FB0F8), "9B" to Color(0xFFB3CAFC),
        "10A" to Color(0xFFB6A3F0), "10B" to Color(0xFFCEC0FD),
        "11A" to Color(0xFFD599D8), "11B" to Color(0xFFF5ADF9),
        "12A" to Color(0xFFE893B5), "12B" to Color(0xFFFDB1CE),
    )

    /** BPM colours from `schemas/mix-colours.json`, one per 1/24 octave from 120 BPM. */
    val bpmColors: List<Color> = listOf(
        Color(0xFFF8A49D), Color(0xFFF6A88D), Color(0xFFF0AD7F), Color(0xFFE7B375), Color(0xFFDBB970), Color(0xFFCDC072),
        Color(0xFFBBC679), Color(0xFFA8CB86), Color(0xFF95CF96), Color(0xFF82D2A8), Color(0xFF71D3BA), Color(0xFF67D2CC),
        Color(0xFF65D0DC), Color(0xFF6CCDEA), Color(0xFF7AC8F5), Color(0xFF8CC3FC), Color(0xFF9EBDFF), Color(0xFFB1B7FD),
        Color(0xFFC2B1F8), Color(0xFFD2ACEE), Color(0xFFDFA8E1), Color(0xFFEAA4D2), Color(0xFFF2A3C1), Color(0xFFF6A3AF),
    )

    fun keyColor(camelot: String?): Color = camelot?.let(keyColors::get) ?: secondary

    /** Tempos a few BPM apart get neighbouring hues; half and double time share a colour. */
    fun bpmColor(bpm: Double?): Color = bpm?.takeIf { it > 0 }?.let { bpmColors[bpmBin(it)] } ?: secondary

    fun bpmBin(bpm: Double): Int = Math.floorMod((24 * ln(bpm / 120) / ln(2.0)).roundToInt(), 24)

    private val artPairs = listOf(
        0xFF2D4B73 to 0xFFBFD6F5, 0xFF6B3A2E to 0xFFF3C9B8, 0xFF3E5A3A to 0xFFCFE3C4,
        0xFF4A3D6B to 0xFFD9CCF5, 0xFF5E5A2E to 0xFFECE6B5, 0xFF2E5A5E to 0xFFB5E6EC,
    )

    /** Flat art placeholder colours (background, letter), stable per album; same djb2 hash as Apple. */
    fun artColors(seed: String): Pair<Color, Color> {
        var hash = 5381u
        seed.codePoints().forEach { hash = (hash shl 5) + hash + it.toUInt() }
        val (bg, fg) = artPairs[(hash % artPairs.size.toUInt()).toInt()]
        return Color(bg) to Color(fg)
    }
}

private val scheme = darkColorScheme(
    primary = Motif.accent,
    onPrimary = Motif.onAccent,
    primaryContainer = Motif.raised,
    onPrimaryContainer = Motif.accent,
    secondary = Motif.badge,
    onSecondary = Motif.ground,
    secondaryContainer = Motif.raised,
    onSecondaryContainer = Motif.text,
    tertiary = Motif.deckA,
    background = Motif.ground,
    onBackground = Motif.text,
    surface = Motif.ground,
    onSurface = Motif.text,
    surfaceVariant = Motif.raised,
    onSurfaceVariant = Motif.secondary,
    surfaceContainerLowest = Motif.ground,
    surfaceContainerLow = Motif.surface,
    surfaceContainer = Motif.surface,
    surfaceContainerHigh = Motif.raised,
    surfaceContainerHighest = Motif.raised,
    outline = Motif.hairline,
    outlineVariant = Motif.hairline,
)

@Composable
fun MotifTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    MaterialTheme(
        colorScheme = scheme,
        typography = base.copy(
            headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, fontSize = 34.sp),
        ),
    ) {
        // Text and icons outside a Surface (sheets on custom colours, popups) fall back to
        // LocalContentColor, which defaults to black; make the fallback the light text colour.
        CompositionLocalProvider(LocalContentColor provides Motif.text, content = content)
    }
}
