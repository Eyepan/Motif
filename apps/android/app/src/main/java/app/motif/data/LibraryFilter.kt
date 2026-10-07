package app.motif.data

/**
 * Library search with DJ filters: free text plus `bpm:120`, `bpm:120-126` and
 * `key:8A` (Camelot). Same grammar as the Apple app's `LibraryFilter`.
 */
class LibraryFilter(query: String) {
    val text: String
    var bpm: ClosedFloatingPointRange<Double>? = null
        private set
    var key: String? = null
        private set

    init {
        val words = mutableListOf<String>()
        for (token in query.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val lower = token.lowercase()
            when {
                lower.startsWith("bpm:") -> {
                    val parts = lower.removePrefix("bpm:").split("-").mapNotNull { it.toDoubleOrNull() }
                    when (parts.size) {
                        1 -> bpm = (parts[0] - 0.5)..(parts[0] + 0.5)
                        2 -> bpm = minOf(parts[0], parts[1])..maxOf(parts[0], parts[1])
                        else -> words += token
                    }
                }
                lower.startsWith("key:") && lower.length > 4 -> key = lower.removePrefix("key:").uppercase()
                else -> words += token
            }
        }
        text = words.joinToString(" ")
    }

    val isEmpty: Boolean get() = text.isEmpty() && bpm == null && key == null

    fun matches(track: Track): Boolean {
        bpm?.let { range -> if (track.bpm?.let { it in range } != true) return false }
        key?.let { if (track.musicalKey?.uppercase() != it) return false }
        if (text.isEmpty()) return true
        return listOfNotNull(track.title, track.artist, track.album).any { it.contains(text, ignoreCase = true) }
    }
}
