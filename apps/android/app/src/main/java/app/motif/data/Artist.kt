package app.motif.data

/** An artist in the library and every track that credits them. */
class Artist(val key: String, val name: String, val tracks: List<Track>)

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
): List<Artist> {
    val credited = tracks.filter { !it.artist.isNullOrBlank() }
    // Names that are unambiguous on their own, to decide whether "A & B" is a duo.
    val known = credited.flatMap { t -> split(t.artist!!, emptyList()).map { norm(it.first) } }.toSet()
    val names = LinkedHashMap<String, String>()
    val byKey = LinkedHashMap<String, MutableList<Track>>()
    for (track in credited) {
        for ((name, _) in split(track.artist!!, known)) {
            val key = norm(name)
            names.getOrPut(key) { name }
            val list = byKey.getOrPut(key) { mutableListOf() }
            if (list.lastOrNull() !== track) list += track
        }
    }
    val unknown = tracks.filter { it.artist.isNullOrBlank() }
    val artists = byKey.map { (key, list) -> Artist(key, names.getValue(key), list) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    return if (unknown.isEmpty()) artists else artists + Artist("\u0000unknown", "Unknown artist", unknown)
}
