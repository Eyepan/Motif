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

    suspend fun load() = withContext(Dispatchers.IO) { refresh() }

    suspend fun insert(track: Track) = withContext(Dispatchers.IO) {
        helper.writableDatabase.insertOrThrow("tracks", null, track.toValues())
        refresh()
    }

    suspend fun updateAnalysis(id: String, bpm: Double?, loudnessDb: Double?, key: String?, waveform: ByteArray?) =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put("bpm", bpm)
                put("loudness_db", loudnessDb)
                put("musical_key", key)
                put("waveform", waveform)
            }
            helper.writableDatabase.update("tracks", values, "id = ?", arrayOf(id))
            refresh()
        }

    suspend fun delete(track: Track) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("tracks", "id = ?", arrayOf(track.id))
        fileFor(track).delete()
        refresh()
    }

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
    put("added_at", addedAt)
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
    addedAt = getLong(getColumnIndexOrThrow("added_at")),
)
