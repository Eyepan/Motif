package app.motif.data

/**
 * Albums and artists built from the library, with the lookups album and
 * artist pages need: by key, a track's album and credited artists, and the
 * albums an artist appears on.
 */
class LibraryIndex(tracks: List<Track>) {
    /** Newest first. */
    val albums: List<Album> = albumsOf(tracks)
    /** By name, from split credits. */
    val artists: List<Artist>
    private val credits: Map<String, List<String>>
    private val albumsByKey = albums.associateBy { it.key }
    private val artistsByKey: Map<String, Artist>

    init {
        val (list, byTrack) = artistIndexOf(tracks)
        artists = list
        credits = byTrack
        artistsByKey = list.associateBy { it.key }
    }

    fun album(key: String): Album? = albumsByKey[key]

    fun artist(key: String): Artist? = artistsByKey[key]

    /** The album a track is on, if it's tagged with one. */
    fun albumOf(track: Track): Album? = track.album?.takeIf { it.isNotBlank() }?.let { albumsByKey[albumKey(it)] }

    /** The track's credited artists, primary first; the unknown artist when it credits none. */
    fun creditedArtists(track: Track): List<Artist> = credits[track.id].orEmpty().mapNotNull(artistsByKey::get)

    /** The artist an album is credited to, when it's one artist in the library (not "Various artists"). */
    fun artistOf(album: Album): Artist? = album.artist?.let { artistsByKey[TagCleaner.norm(it)] }

    /** Albums with at least one of the artist's songs, in library order. */
    fun albumsWith(artist: Artist): List<Album> {
        val ids = artist.tracks.mapTo(HashSet()) { it.id }
        return albums.filter { album -> album.tracks.any { it.id in ids } }
    }
}
