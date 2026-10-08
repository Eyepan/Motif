package app.motif.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A user's set of tracks for a gig or a mood. Crates live only in the history
 * log as `crate_changed` events, so they sync like every other event and each
 * device rebuilds the same crates from the merged log (docs/crates.md).
 */
data class Crate(
    val id: String,
    val name: String,
    /** Track keys ([Track.contentHash]) in the order they were added. A key can name a track that isn't on this device. */
    val trackKeys: List<String>,
)

/** The payload of one `crate_changed` v1 event (`schemas/events/crate_changed.v1.schema.json`). */
data class CrateChange(
    val crateId: String,
    val name: String? = null,
    val deleted: Boolean = false,
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
) {
    fun toJson(): String = JSONObject().apply {
        put("crate_id", crateId)
        name?.let { put("name", it) }
        if (deleted) put("deleted", true)
        if (added.isNotEmpty()) put("added", JSONArray(added))
        if (removed.isNotEmpty()) put("removed", JSONArray(removed))
    }.toString()

    companion object {
        /** Null when the payload isn't an object with a crate id. */
        fun parse(payload: String): CrateChange? {
            val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
            val id = json.opt("crate_id") as? String
            if (id.isNullOrEmpty()) return null
            fun keys(field: String): List<String> {
                val array = json.optJSONArray(field) ?: return emptyList()
                return (0 until array.length()).mapNotNull { array.opt(it) as? String }
            }
            return CrateChange(
                crateId = id,
                name = json.opt("name") as? String,
                deleted = json.opt("deleted") == true,
                added = keys("added"),
                removed = keys("removed"),
            )
        }
    }
}

object CrateLog {
    const val EVENT_TYPE = "crate_changed"
    const val VERSION = 1
    /** Keeps each payload under the server's 16 KiB limit (64-character keys). */
    const val MAX_KEYS_PER_EVENT = 200

    /** A crate id, minted lowercase like event ids. */
    fun newCrateId(): String = UUID.randomUUID().toString()

    /** Splits a change whose track lists exceed [MAX_KEYS_PER_EVENT] into several, in order. The name and deletion ride on the first one. */
    fun chunked(change: CrateChange): List<CrateChange> {
        if (change.added.size + change.removed.size <= MAX_KEYS_PER_EVENT) return listOf(change)
        val out = mutableListOf<CrateChange>()
        var pending = change.copy(added = emptyList(), removed = emptyList())
        var count = 0
        for ((key, isAdded) in change.added.map { it to true } + change.removed.map { it to false }) {
            if (count == MAX_KEYS_PER_EVENT) {
                out += pending
                pending = CrateChange(change.crateId)
                count = 0
            }
            pending = if (isAdded) pending.copy(added = pending.added + key) else pending.copy(removed = pending.removed + key)
            count++
        }
        out += pending
        return out
    }

    /**
     * Rebuilds crates by replaying `crate_changed` v1 events in id order, so every
     * device gets the same result whatever order the events arrived in. The latest
     * event wins: a name creates or renames, `deleted` empties and hides the crate,
     * and a later name brings it back empty. Membership changes are kept even for a
     * crate whose creation sorts later (another device's clock was behind).
     * Other types, other versions and payloads without a crate id are skipped.
     * Crates come back in the order they first appear in the log.
     */
    fun fold(events: List<HistoryEvent>): List<Crate> {
        class State {
            var name: String? = null
            var deleted = false
            val keys = LinkedHashSet<String>()
        }
        val seen = HashSet<String>()
        val state = LinkedHashMap<String, State>()
        for (event in events.sortedBy { it.id.lowercase() }) {
            if (event.type != EVENT_TYPE || event.v != VERSION || !seen.add(event.id.lowercase())) continue
            val change = CrateChange.parse(event.payload) ?: continue
            val crate = state.getOrPut(change.crateId.lowercase()) { State() }
            val name = change.name?.trim()
            if (!name.isNullOrEmpty()) {
                if (crate.deleted) crate.keys.clear()
                crate.name = name
                crate.deleted = false
            }
            if (change.deleted) {
                crate.deleted = true
                crate.keys.clear()
            }
            crate.keys.addAll(change.added)
            crate.keys.removeAll(change.removed.toSet())
        }
        return state.mapNotNull { (id, crate) ->
            val name = crate.name
            if (name == null || crate.deleted) null else Crate(id, name, crate.keys.toList())
        }
    }
}

/** Tracks by content hash, the first of any duplicates winning, for resolving crate members. */
fun tracksByKey(tracks: List<Track>): Map<String, Track> {
    val out = HashMap<String, Track>()
    for (t in tracks) t.contentHash?.let { out.putIfAbsent(it, t) }
    return out
}

/**
 * Crate edits. Each one appends `crate_changed` events to the history log and
 * re-folds the log into [crates].
 */
class CrateStore(private val history: HistoryStore, private val library: LibraryStore) {
    private val _crates = MutableStateFlow<List<Crate>>(emptyList())
    /** Crates in the order they were made. */
    val crates: StateFlow<List<Crate>> = _crates.asStateFlow()

    private val lock = Mutex()

    suspend fun load() = lock.withLock { refresh() }

    /** Creates a crate, optionally with tracks already in it, and returns its id. */
    suspend fun create(name: String, tracks: List<Track> = emptyList()): String {
        val id = CrateLog.newCrateId()
        val valid = validName(name)
        edit(CrateChange(id, name = valid, added = keys(tracks)), hashed = tracks.isNotEmpty())
        return id
    }

    suspend fun rename(id: String, name: String) = edit(CrateChange(id, name = validName(name)))

    suspend fun delete(id: String) = edit(CrateChange(id, deleted = true))

    suspend fun add(tracks: List<Track>, id: String) {
        val keys = keys(tracks)
        if (keys.isNotEmpty()) edit(CrateChange(id, added = keys), hashed = true)
    }

    suspend fun remove(keys: List<String>, id: String) {
        if (keys.isNotEmpty()) edit(CrateChange(id, removed = keys))
    }

    private fun validName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "A crate needs a name." }
        return trimmed.take(200)
    }

    private suspend fun keys(tracks: List<Track>): List<String> =
        tracks.map { library.contentHash(it) }.distinct()

    /**
     * Records a change and refreshes. [hashed] reloads the library first, since its
     * tracks just gained content hashes; otherwise the new crate would briefly show
     * its songs as on another device.
     */
    private suspend fun edit(change: CrateChange, hashed: Boolean = false) = lock.withLock {
        for (part in CrateLog.chunked(change)) history.append(CrateLog.EVENT_TYPE, CrateLog.VERSION, part.toJson())
        if (hashed) library.reload()
        refresh()
    }

    private suspend fun refresh() {
        val folded = CrateLog.fold(history.events(CrateLog.EVENT_TYPE))
        withContext(Dispatchers.Main.immediate) { _crates.value = folded }
    }
}
