package app.motif.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The on-device library: SQLite created from the shared `schemas/library.sql`
 * (packaged as an asset) plus the media files it points at. [tracks] is the
 * whole library in memory, newest first; every write refreshes it.
 */
class LibraryStore(private val context: Context) {
    val mediaDir: File = File(context.filesDir, "media").apply { mkdirs() }

    private val helper = object : SQLiteOpenHelper(context, "library.sqlite", null, SCHEMA_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            statements("library.sql").forEach(db::execSQL)
        }

        /** Android shipped at v2, so every step it needs is in schemas/migrations. Runs in one transaction. */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            for (version in oldVersion + 1..newVersion) {
                statements("migrations/$version.sql").forEach(db::execSQL)
            }
        }

        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
            db.setForeignKeyConstraintsEnabled(true)
        }
    }

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    fun fileFor(track: Track): File = File(mediaDir, track.filePath)

    /** Site names appended to tags anywhere in the library (see [TagCleaner]); filled by [load]. */
    @Volatile var siteSuffixes: Set<String> = emptySet()
        private set

    suspend fun load() = withContext(Dispatchers.IO) {
        recleanIfNeeded()
        refresh()
    }

    /** Inserts a track and the raw tags its cleaned values came from. */
    suspend fun insert(track: Track, raw: RawTags? = null) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("tracks", null, track.toValues())
            raw?.let { insertRawTags(db, track.id, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        raw?.let(TagCleaner::siteSuffix)?.let { siteSuffixes = siteSuffixes + it }
        refresh()
    }

    private fun insertRawTags(db: SQLiteDatabase, trackId: String, raw: RawTags) {
        for ((key, value) in raw.entries()) {
            db.insert("track_tags", null, ContentValues().apply {
                put("track_id", trackId)
                put("key", key)
                put("value", value)
                put("origin", "tag")
            })
        }
    }

    /**
     * Collects site suffixes from every track's raw tags and, when the shared
     * cleanup rules changed since the last run (or never ran), re-cleans
     * title, artist, album and album artist from those raw tags. Tracks
     * imported before raw tags were kept use their current values as raw.
     */
    private fun recleanIfNeeded() {
        val version = TagCleaner.version
        if (version == 0) return
        val db = helper.writableDatabase
        val raw = HashMap<String, MutableMap<String, String>>()
        db.rawQuery("SELECT track_id, key, value FROM track_tags WHERE origin = 'tag'", null).use { c ->
            while (c.moveToNext()) raw.getOrPut(c.getString(0)) { HashMap() }[c.getString(1)] = c.getString(2)
        }
        val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
        val stale = prefs.getInt(PREF_CLEANER_VERSION, 0) < version
        val tracks = if (stale) {
            db.rawQuery("SELECT * FROM tracks", null).use { c -> buildList { while (c.moveToNext()) add(c.toTrack()) } }
        } else {
            emptyList()
        }
        val tags = tracks.associate { t ->
            t.id to (raw[t.id]?.let(RawTags::of) ?: RawTags(t.title, t.artist, t.album, t.albumArtist))
        }
        siteSuffixes = (raw.values.map(RawTags::of) + tags.values).mapNotNull(TagCleaner::siteSuffix).toSet()
        if (!stale) return

        db.beginTransaction()
        try {
            for (t in tracks) {
                val r = tags.getValue(t.id)
                if (t.id !in raw) insertRawTags(db, t.id, r)
                val values = ContentValues().apply {
                    put("title", TagCleaner.clean(r.title, siteSuffixes) ?: t.title)
                    put("artist", TagCleaner.clean(r.artist, siteSuffixes) ?: TagCleaner.clean(r.albumArtist, siteSuffixes))
                    put("album", TagCleaner.clean(r.album, siteSuffixes))
                    put("album_artist", TagCleaner.clean(r.albumArtist, siteSuffixes))
                }
                db.update("tracks", values, "id = ?", arrayOf(t.id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        prefs.edit().putInt(PREF_CLEANER_VERSION, version).apply()
    }

    suspend fun updateAnalysis(
        id: String,
        bpm: Double?,
        loudnessDb: Double?,
        key: String?,
        waveform: ByteArray?,
        firstDownbeat: Double?,
    ) =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put("bpm", bpm)
                put("loudness_db", loudnessDb)
                put("musical_key", key)
                put("waveform", waveform)
                put("beat_offset_ms", firstDownbeat?.let { Math.round(it * 1000) })
            }
            helper.writableDatabase.update("tracks", values, "id = ?", arrayOf(id))
            refresh()
        }

    suspend fun delete(track: Track) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("tracks", "id = ?", arrayOf(track.id))
        fileFor(track).delete()
        refresh()
    }

    // Synchronized so a refresh that read the table earlier can't publish after a later one.
    @Synchronized
    private fun refresh() {
        helper.readableDatabase.rawQuery("SELECT * FROM tracks ORDER BY added_at DESC, title", null).use { c ->
            val list = ArrayList<Track>(c.count)
            while (c.moveToNext()) list += c.toTrack()
            _tracks.value = list
        }
    }

    /** Statements from a schemas/ asset; the PRAGMA is handled by SQLiteOpenHelper's version. */
    private fun statements(asset: String): List<String> {
        val sql = context.assets.open(asset).bufferedReader().use { it.readText() }
        return sql.lines()
            .map { it.substringBefore("--").trimEnd() }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("PRAGMA", ignoreCase = true) }
    }

    companion object {
        /** Matches `PRAGMA user_version` in schemas/library.sql. */
        const val SCHEMA_VERSION = 3
        const val WAVEFORM_LENGTH = 128
        private const val PREF_CLEANER_VERSION = "tag_cleaner_version"
    }
}

private fun Track.toValues() = ContentValues().apply {
    put("id", id)
    put("title", title)
    put("artist", artist)
    put("album", album)
    put("duration_ms", durationMs)
    put("file_path", filePath)
    put("format", format)
    put("sample_rate", sampleRate)
    put("bit_depth", bitDepth)
    put("channels", channels)
    put("source", source)
    put("source_ref", sourceRef)
    put("license_url", licenseUrl)
    put("bpm", bpm)
    put("loudness_db", loudnessDb)
    put("musical_key", musicalKey)
    put("waveform", waveform)
    put("beat_offset_ms", firstDownbeat?.let { Math.round(it * 1000) })
    put("added_at", addedAt)
    put("album_artist", albumArtist)
}

private fun Cursor.str(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
private fun Cursor.int(name: String): Int? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getInt(it) }
private fun Cursor.double(name: String): Double? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getDouble(it) }

private fun Cursor.toTrack() = Track(
    id = str("id")!!,
    title = str("title")!!,
    artist = str("artist"),
    album = str("album"),
    durationMs = getLong(getColumnIndexOrThrow("duration_ms")),
    filePath = str("file_path")!!,
    format = str("format")!!,
    sampleRate = int("sample_rate"),
    bitDepth = int("bit_depth"),
    channels = int("channels"),
    source = str("source") ?: "local",
    sourceRef = str("source_ref"),
    licenseUrl = str("license_url"),
    bpm = double("bpm"),
    loudnessDb = double("loudness_db"),
    musicalKey = str("musical_key"),
    waveform = getColumnIndexOrThrow("waveform").let { if (isNull(it)) null else getBlob(it) },
    firstDownbeat = double("beat_offset_ms")?.let { it / 1000 },
    addedAt = getLong(getColumnIndexOrThrow("added_at")),
    albumArtist = str("album_artist"),
)
