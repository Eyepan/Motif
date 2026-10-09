package app.motif.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
) {
    /** The server's envelope (schemas/api/openapi.yaml, Event). */
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("v", v)
        put("at_ms", atMs)
        put("tz_min", tzMin)
        put("device_id", deviceId)
        trackKey?.let { put("track_key", it) }
        put("payload", runCatching { JSONObject(payload) }.getOrElse { JSONObject() })
    }

    companion object {
        /** Null when a required envelope field is missing. */
        fun fromJson(obj: JSONObject): HistoryEvent? = runCatching {
            HistoryEvent(
                id = obj.getString("id"), type = obj.getString("type"), v = obj.getInt("v"),
                atMs = obj.getLong("at_ms"), tzMin = obj.getInt("tz_min"), deviceId = obj.getString("device_id"),
                trackKey = obj.optString("track_key").takeIf { obj.has("track_key") && !obj.isNull("track_key") },
                payload = (obj.optJSONObject("payload") ?: JSONObject()).toString(),
            )
        }.getOrNull()
    }
}

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

    // Sync (docs/server.md). `synced` is 1 once the server has an event, 2 when it rejected it for good.

    /** The oldest events from this device the server hasn't acknowledged. */
    suspend fun unsynced(limit: Int): List<HistoryEvent> = withContext(Dispatchers.IO) {
        query("SELECT $COLUMNS FROM events WHERE synced = 0 AND device_id = ? ORDER BY id LIMIT $limit", arrayOf(deviceId))
    }

    suspend fun markSynced(ids: List<String>, rejected: Boolean = false) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply { put("synced", if (rejected) 2 else 1) }
            for (id in ids) db.update("events", values, "id = ?", arrayOf(id.lowercase()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Removes events of [types] with from <= at_ms < to (null bounds are open), synced or not. */
    suspend fun delete(types: List<String>, fromMs: Long?, toMs: Long?): Int = withContext(Dispatchers.IO) {
        if (types.isEmpty()) return@withContext 0
        val marks = types.joinToString(", ") { "?" }
        helper.writableDatabase.delete(
            "events",
            "at_ms >= ? AND at_ms < ? AND type IN ($marks)",
            arrayOf((fromMs ?: Long.MIN_VALUE).toString(), (toMs ?: Long.MAX_VALUE).toString()) + types,
        )
    }

    /** Applies pulled `history_deleted` events (schemas/events/history_deleted.v1.schema.json). */
    suspend fun applyDeletions(events: List<HistoryEvent>) {
        for (event in events) {
            if (event.type != "history_deleted") continue
            val deletion = parseDeletion(event.payload) ?: continue
            delete(deletion.types.filter { it != "history_deleted" }, deletion.fromMs, deletion.toMs)
        }
    }

    /** Newest first, optionally only one device's. */
    suspend fun recent(types: List<String>, limit: Int, deviceId: String? = null): List<HistoryEvent> = withContext(Dispatchers.IO) {
        if (types.isEmpty()) return@withContext emptyList()
        val marks = types.joinToString(", ") { "?" }
        val deviceFilter = if (deviceId == null) "" else "AND device_id = ?"
        query(
            "SELECT $COLUMNS FROM events WHERE type IN ($marks) $deviceFilter ORDER BY at_ms DESC, id DESC LIMIT $limit",
            (types + listOfNotNull(deviceId)).toTypedArray(),
        )
    }

    suspend fun count(types: List<String>): Int = withContext(Dispatchers.IO) {
        if (types.isEmpty()) return@withContext 0
        val marks = types.joinToString(", ") { "?" }
        helper.readableDatabase.rawQuery("SELECT count(*) FROM events WHERE type IN ($marks)", types.toTypedArray()).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** Every event as JSON Lines, the server's envelope, oldest first (Settings › Export). */
    suspend fun exportJsonLines(): String = withContext(Dispatchers.IO) {
        val rows = query("SELECT $COLUMNS FROM events ORDER BY at_ms, id", emptyArray())
        buildString { for (e in rows) append(e.toJson().toString()).append('\n') }
    }

    suspend fun syncValue(key: String): String? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT value FROM sync_state WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    suspend fun setSyncValue(key: String, value: String?) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        if (value == null) {
            db.delete("sync_state", "key = ?", arrayOf(key))
        } else {
            db.insertWithOnConflict("sync_state", null, ContentValues().apply {
                put("key", key)
                put("value", value)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** Signing in to a different account: this device's events upload again and the pull starts over. */
    suspend fun resetSync() {
        withContext(Dispatchers.IO) {
            helper.writableDatabase.update("events", ContentValues().apply { put("synced", 0) },
                "device_id = ? AND synced = 1", arrayOf(deviceId))
        }
        setSyncValue("pull_cursor", null)
        setSyncValue("last_sync_ms", null)
    }

    private fun query(sql: String, args: Array<String>): List<HistoryEvent> =
        helper.readableDatabase.rawQuery(sql, args).use { c ->
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

    companion object {
        /** Matches `PRAGMA user_version` in schemas/history.sql. */
        const val SCHEMA_VERSION = 1

        private val random = SecureRandom()

        private const val COLUMNS = "id, type, v, at_ms, tz_min, device_id, track_key, payload"

        /** Event types that make up listening history, the ones "Delete history" removes (docs/server.md). */
        val LISTENING_TYPES = listOf("play", "transition", "app_session", "search")

        data class Deletion(val fromMs: Long, val toMs: Long, val types: List<String>)

        fun parseDeletion(payload: String): Deletion? = runCatching {
            val obj = JSONObject(payload)
            val types = obj.optJSONArray("types")?.let { a -> List(a.length()) { a.getString(it) } } ?: LISTENING_TYPES
            Deletion(obj.getLong("from_ms"), obj.getLong("to_ms"), types)
        }.getOrNull()

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
