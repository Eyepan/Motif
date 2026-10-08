import Foundation

/// The sync loop from docs/server.md: upload this device's unsynced events in batches,
/// then pull other devices' events from the stored cursor until there are no more.
/// Pulled `history_deleted` events delete the same range here.
public actor SyncService {
    public struct Report: Sendable, Equatable {
        public var uploaded = 0
        public var rejected = 0
        public var pulled = 0
    }

    private let api: MotifAPI
    private let history: HistoryStore
    private var running: Task<Report, Error>?

    public init(api: MotifAPI, history: HistoryStore) {
        self.api = api
        self.history = history
    }

    /// One full round. Calls while a round runs wait for it instead of starting another.
    @discardableResult
    public func sync() async throws -> Report {
        if let running { return try await running.value }
        let task = Task { try await self.round() }
        running = task
        defer { running = nil }
        return try await task.value
    }

    /// When the last round finished, from `sync_state`.
    public func lastSync() async -> Date? {
        await history.syncValue("last_sync_ms").flatMap(Int64.init).map { Date(timeIntervalSince1970: Double($0) / 1000) }
    }

    private func round() async throws -> Report {
        guard await api.isSignedIn else { return Report() }
        // A different account than last time: upload everything again and pull from the start.
        let user = await api.userID
        if await history.syncValue("account_id") != user {
            try await history.resetSync()
            try await history.setSyncValue(user, for: "account_id")
        }

        var report = Report()
        while true {
            let batch = try await history.unsynced(limit: 1000)
            if batch.isEmpty { break }
            let result = try await api.upload(batch)
            let rejected = Set(result.rejected)
            let ids = batch.enumerated().map { ($0.offset, $0.element.id) }
            try await history.markSynced(ids.filter { !rejected.contains($0.0) }.map(\.1))
            try await history.markSynced(ids.filter { rejected.contains($0.0) }.map(\.1), rejected: true)
            report.uploaded += batch.count - rejected.count
            report.rejected += rejected.count
            if batch.count < 1000 { break }
        }

        var cursor = await history.syncValue("pull_cursor")
        while true {
            let page = try await api.pull(after: cursor, excludeDevice: history.deviceID)
            try await history.insert(page.events)
            try await history.applyDeletions(in: page.events)
            report.pulled += page.events.count
            if !page.nextCursor.isEmpty {
                cursor = page.nextCursor
                try await history.setSyncValue(cursor, for: "pull_cursor")
            }
            if !page.hasMore { break }
        }
        let now = Int64(Date.now.timeIntervalSince1970 * 1000)
        try await history.setSyncValue(String(now), for: "last_sync_ms")
        return report
    }
}
