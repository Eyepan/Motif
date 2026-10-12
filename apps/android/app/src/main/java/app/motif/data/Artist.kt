package app.motif.data

/** An artist in the library and every track that credits them. */
class Artist(val key: String, val name: String, val tracks: List<Track>) {
    val monogram: String get() = name.firstOrNull()?.uppercase() ?: "♪"

    /** "3 albums · 27 songs" */
    fun summary(albums: Int): String {
        val songs = "${tracks.size} ${if (tracks.size == 1) "song" else "songs"}"
        return if (albums == 0) songs else "$albums ${if (albums == 1) "album" else "albums"} · $songs"
    }
}

const val UNKNOWN_ARTIST_KEY = "\u0000unknown"

/**
 * Artists from the tracks' artist credits, sorted by name. A track counts for
 * each credited artist, primary or featured, so "A, B feat. C" lists the song
 * under A, B and C. "A & B" splits only when both names also appear alone in
 * the library (see `split_artists` in core/dsp/src/meta.rs).
 */
fun artistsOf(
    tracks: List<Track>,
    split: (String, Collection<String>) -> List<Pair<String, String>> = TagCleaner::splitArtists,
    norm: (String) -> String = TagCleaner::norm,
): List<Artist> = artistIndexOf(tracks, split, norm).first

/** [artistsOf], and each track's credited artist keys in credit order. */
fun artistIndexOf(
    tracks: List<Track>,
    split: (String, Collection<String>) -> List<Pair<String, String>> = TagCleaner::splitArtists,
    norm: (String) -> String = TagCleaner::norm,
): Pair<List<Artist>, Map<String, List<String>>> {
    val credited = tracks.filter { !it.artist.isNullOrBlank() }
    // Names that are unambiguous on their own, to decide whether "A & B" is a duo.
    val known = credited.flatMap { t -> split(t.artist!!, emptyList()).map { norm(it.first) } }.toSet()
    val names = LinkedHashMap<String, String>()
    val byKey = LinkedHashMap<String, MutableList<Track>>()
    val credits = HashMap<String, MutableList<String>>()
    for (track in credited) {
        for ((name, _) in split(track.artist!!, known)) {
            val key = norm(name)
            names.getOrPut(key) { name }
            val list = byKey.getOrPut(key) { mutableListOf() }
            if (list.lastOrNull() !== track) {
                list += track
                credits.getOrPut(track.id) { mutableListOf() } += key
            }
        }
    }
    val unknown = tracks.filter { it.artist.isNullOrBlank() }
    val artists = byKey.map { (key, list) -> Artist(key, names.getValue(key), list) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    if (unknown.isEmpty()) return artists to credits
    for (t in unknown) credits[t.id] = mutableListOf(UNKNOWN_ARTIST_KEY)
    return artists + Artist(UNKNOWN_ARTIST_KEY, "Unknown artist", unknown) to credits
}
