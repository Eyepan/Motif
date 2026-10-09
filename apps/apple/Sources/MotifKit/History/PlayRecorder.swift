import Foundation

/// Writes `play` events to the history log (docs/analytics.md). `PlaybackEngine` reports
/// each finished `PlaySession` and checkpoints the one in progress, so a crash or the OS
/// ending the app still records the listen as `interrupted` on the next launch.
/// DJ decks report their listens through `recordListen`.
@MainActor
public final class PlayRecorder {
    /// Pause history: while it returns true nothing is written or checkpointed.
    public var isPaused: @MainActor () -> Bool = { false }
    /// After a play is written, e.g. to refresh counts and schedule an upload.
    public var onRecorded: @MainActor () -> Void = {}

    private let history: HistoryStore
    private let library: LibraryStore
    /// Writes run one after another, so a checkpoint can't land after the play that cleared it.
    private var tail: Task<Void, Never>?
    private var lastCheckpointMs: Int64 = 0

    static let checkpointKey = "open_play"
    static let checkpointIntervalMs: Int64 = 15_000

    public init(history: HistoryStore, library: LibraryStore) {
        self.history = history
        self.library = library
        enqueue { await self.recover() }
    }

    nonisolated static func trackID(_ track: Track) -> String { track.id.uuidString.lowercased() }

    nonisolated static var nowMs: Int64 { Int64((Date.now.timeIntervalSince1970 * 1000).rounded(.down)) }

    /// A play from the player ended: write it and drop its checkpoint.
    public func finish(_ payload: PlayPayload, track: Track) {
        lastCheckpointMs = 0
        enqueue { [history] in try? await history.setSyncValue(nil, for: Self.checkpointKey) }
        record(payload, track: track, atMs: Self.nowMs)
    }

    /// One listen on a DJ Mix deck.
    public func recordListen(track: Track, startedAtMs: Int64, listenedMs: Int64, endReason: PlayEndReason,
                             mixedIn: Bool, mixedOut: Bool) {
        record(PlayPayload(trackID: Self.trackID(track), startedAtMs: startedAtMs, listenedMs: listenedMs,
                           durationMs: Int64(track.durationMs), endReason: endReason, context: .mix,
                           mixedIn: mixedIn, mixedOut: mixedOut),
               track: track, atMs: Self.nowMs)
    }

    /// Saves the play in progress, at most every 15 s.
    public func checkpoint(_ session: PlaySession) {
        let now = Self.nowMs
        guard now - lastCheckpointMs >= Self.checkpointIntervalMs else { return }
        lastCheckpointMs = now
        let value = isPaused() ? nil : session.snapshot(at: now).flatMap { Self.checkpointJSON($0, atMs: now) }
        enqueue { [history] in try? await history.setSyncValue(value, for: Self.checkpointKey) }
    }

    /// A play the app didn't live to finish becomes an `interrupted` play. Runs on launch, before any checkpoint.
    public func recoverInterrupted() async {
        enqueue { await self.recover() }
        await tail?.value
    }

    private func recover() async {
        let recovered: (PlayPayload, Int64)? = await history.syncValue(Self.checkpointKey).flatMap(Self.parseCheckpoint)
        guard let (payload, atMs) = recovered else { return }
        try? await history.setSyncValue(nil, for: Self.checkpointKey)
        guard payload.listenedMs > 0 else { return }
        var key: String?
        if let track = (try? await library.allTracks())?.first(where: { Self.trackID($0) == payload.trackID }) {
            key = try? await library.contentHash(for: track)
        }
        _ = try? await history.append(type: "play", v: 1, payload: payload.json, trackKey: key,
                                      at: Date(timeIntervalSince1970: Double(atMs) / 1000))
        onRecorded()
    }

    private func record(_ payload: PlayPayload, track: Track, atMs: Int64) {
        guard !isPaused(), payload.listenedMs > 0 else { return }
        enqueue { [history, library] in
            var key = track.contentHash
            if key == nil { key = try? await library.contentHash(for: track) }
            _ = try? await history.append(type: "play", v: 1, payload: payload.json, trackKey: key,
                                          at: Date(timeIntervalSince1970: Double(atMs) / 1000))
            await MainActor.run { self.onRecorded() }
        }
    }

    private func enqueue(_ work: @escaping @Sendable () async -> Void) {
        let previous = tail
        tail = Task {
            await previous?.value
            await work()
        }
    }

    nonisolated static func checkpointJSON(_ payload: PlayPayload, atMs: Int64) -> String? {
        let obj: [String: Any] = ["at_ms": atMs, "play": payload.jsonObject]
        return (try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])).map { String(decoding: $0, as: UTF8.self) }
    }

    nonisolated static func parseCheckpoint(_ json: String) -> (PlayPayload, Int64)? {
        guard let obj = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any],
              let atMs = (obj["at_ms"] as? NSNumber)?.int64Value,
              let play = obj["play"] as? [String: Any],
              let data = try? JSONSerialization.data(withJSONObject: play),
              let payload = PlayPayload(json: String(decoding: data, as: UTF8.self)) else { return nil }
        return (payload, atMs)
    }
}
