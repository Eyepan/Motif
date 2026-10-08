package app.motif.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.TimeZone
import java.util.UUID

/** One row of the history log. [payload] is the JSON object as text. */
data class HistoryEvent(
    val id: String,
    val type: String,
    val v: Int,
    val atMs: Long,
    val tzMin: Int,
    val deviceId: String,
    val trackKey: String? = null,
    val payload: String,
)

/**
 * The append-only event log in `history.sqlite`, created from the shared
 * `schemas/history.sql`. Events written here are uploaded to the user's
 * account and merged with other devices' events (docs/server.md), so they
 * never carry file paths.
 */
class HistoryStore(context: Context) {
    private val helper = object : SQLiteOpenHelper(context, "history.sqlite", null, SCHEMA_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            schemaStatements(context, "history.sql").forEach(db::execSQL)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }
    }

    /** Random per install, never a hardware id. */
    val deviceId: String by lazy {
        val db = helper.writableDatabase
        db.rawQuery("SELECT value FROM sync_state WHERE key = 'device_id'", null).use { c ->
            if (c.moveToFirst()) return@lazy c.getString(0)
        }
        val minted = UUID.randomUUID().toString()
        db.insert("sync_state", null, ContentValues().apply {
            put("key", "device_id")
            put("value", minted)
        })
        minted
    }

    /** Last timestamp put in an id, so ids from this device keep their order even if the clock steps back. */
    private var lastIdMs = 0L

    /** Writes a new event from this device and returns it. */
    suspend fun append(
        type: String,
        v: Int,
        payload: String,
        trackKey: String? = null,
        atMs: Long = System.currentTimeMillis(),
    ): HistoryEvent = withContext(Dispatchers.IO) {
        val id = synchronized(this@HistoryStore) {
            val ms = maxOf(atMs, lastIdMs + 1)
            lastIdMs = ms
            uuidV7(ms)
        }
        val event = HistoryEvent(
            id = id, type = type, v = v, atMs = atMs,
            tzMin = TimeZone.getDefault().getOffset(atMs) / 60_000,
            deviceId = deviceId, trackKey = trackKey, payload = payload,
        )
        insertBlocking(listOf(event))
        event
    }

    /** Adds events, skipping ids already present: pulled events from other devices, or a retried batch. */
    suspend fun insert(events: List<HistoryEvent>) = withContext(Dispatchers.IO) { insertBlocking(events) }

    private fun insertBlocking(events: List<HistoryEvent>) {
        val db = helper.writableDatabase
        val own = deviceId
        db.beginTransaction()
        try {
            for (e in events) {
                db.insertWithOnConflict("events", null, ContentValues().apply {
                    put("id", e.id.lowercase())
                    put("type", e.type)
                    put("v", e.v)
                    put("at_ms", e.atMs)
                    put("tz_min", e.tzMin)
                    put("device_id", e.deviceId)
                    put("track_key", e.trackKey)
                    // Events from other devices arrive already on the server.
                    put("synced", if (e.deviceId == own) 0 else 1)
                    put("payload", e.payload)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Every event of one type, in id order (time order across devices). */
    suspend fun events(type: String): List<HistoryEvent> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT id, type, v, at_ms, tz_min, device_id, track_key, payload FROM events WHERE type = ? ORDER BY id",
            arrayOf(type),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(HistoryEvent(
                        id = c.getString(0), type = c.getString(1), v = c.getInt(2), atMs = c.getLong(3),
                        tzMin = c.getInt(4), deviceId = c.getString(5),
                        trackKey = if (c.isNull(6)) null else c.getString(6), payload = c.getString(7),
                    ))
                }
            }
        }
    }

    /** Events not yet acknowledged by the server. */
    suspend fun unsyncedCount(): Int = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT count(*) FROM events WHERE synced = 0", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    companion object {
        /** Matches `PRAGMA user_version` in schemas/history.sql. */
        const val SCHEMA_VERSION = 1

        private val random = SecureRandom()

        /** A lowercase UUIDv7: 48 bits of unix ms, then version 7, variant 10 and random bits. */
        fun uuidV7(ms: Long): String {
            val bytes = ByteArray(16).also(random::nextBytes)
            for (i in 0 until 6) bytes[i] = (ms ushr (8 * (5 - i))).toByte()
            bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
            val hex = bytes.joinToString("") { "%02x".format(it) }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
        }
    }
}
