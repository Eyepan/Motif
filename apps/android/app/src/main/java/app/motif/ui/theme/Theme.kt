package app.motif.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

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

    /** Camelot keys get a colour per wheel position; minor keys are a touch softer. */
    fun keyColor(camelot: String?): Color {
        val n = camelot?.dropLast(1)?.toIntOrNull()?.takeIf { it in 1..12 } ?: return secondary
        val minor = camelot.endsWith("A")
        return Color.hsv((n - 1) * 30f, if (minor) 0.35f else 0.45f, 0.92f)
    }

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
        content = content,
    )
}
