package app.motif.account

import app.motif.data.HistoryStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The sync loop from docs/server.md: upload this device's unsynced events in batches,
 * then pull other devices' events from the stored cursor until there are no more.
 * Pulled `history_deleted` events delete the same range here.
 */
class SyncService(private val api: MotifApi, private val history: HistoryStore) {
    data class Report(val uploaded: Int = 0, val rejected: Int = 0, val pulled: Int = 0)

    private val lock = Mutex()

    /** One full round; overlapping calls run one after the other. */
    suspend fun sync(): Report = lock.withLock { round() }

    suspend fun lastSyncMs(): Long? = history.syncValue("last_sync_ms")?.toLongOrNull()

    private suspend fun round(): Report {
        if (!api.isSignedIn) return Report()
        // A different account than last time: upload everything again and pull from the start.
        val user = api.userId
        if (history.syncValue("account_id") != user) {
            history.resetSync()
            history.setSyncValue("account_id", user)
        }

        var uploaded = 0
        var rejected = 0
        while (true) {
            val batch = history.unsynced(BATCH)
            if (batch.isEmpty()) break
            val result = api.upload(batch)
            val bad = result.rejected.toSet()
            val (refused, accepted) = batch.withIndex().partition { it.index in bad }
            history.markSynced(accepted.map { it.value.id })
            history.markSynced(refused.map { it.value.id }, rejected = true)
            uploaded += accepted.size
            rejected += refused.size
            if (batch.size < BATCH) break
        }

        var pulled = 0
        var cursor = history.syncValue("pull_cursor")
        while (true) {
            val page = api.pull(cursor, history.deviceId)
            history.insert(page.events)
            history.applyDeletions(page.events)
            pulled += page.events.size
            if (page.nextCursor.isNotEmpty()) {
                cursor = page.nextCursor
                history.setSyncValue("pull_cursor", cursor)
            }
            if (!page.hasMore) break
        }
        history.setSyncValue("last_sync_ms", System.currentTimeMillis().toString())
        return Report(uploaded, rejected, pulled)
    }

    private companion object {
        const val BATCH = 1000
    }
}
