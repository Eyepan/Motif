import Foundation

/// Why a play ended (`end_reason` in schemas/events/play.v1.schema.json).
public enum PlayEndReason: String, Sendable {
    case completed, skipped, previous, stopped, replaced, error, interrupted
}

/// What started a play (`context`). `mix` is a DJ Mix deck.
public enum PlayContext: String, Sendable {
    case library, album, artist, crate, search, queue, autoplay, mix
}

/// A finished play: the `play` v1 payload.
public struct PlayPayload: Equatable, Sendable {
    public var trackID: String
    public var startedAtMs: Int64
    public var listenedMs: Int64
    public var startPosMs: Int64
    public var endPosMs: Int64
    public var durationMs: Int64
    public var endReason: PlayEndReason
    public var seeks: Int
    public var pauses: Int
    public var pausedMs: Int64
    public var context: PlayContext
    public var contextRef: String?
    public var mixedIn: Bool
    public var mixedOut: Bool

    public init(trackID: String, startedAtMs: Int64, listenedMs: Int64, startPosMs: Int64 = 0, endPosMs: Int64 = 0,
                durationMs: Int64 = 0, endReason: PlayEndReason, seeks: Int = 0, pauses: Int = 0, pausedMs: Int64 = 0,
                context: PlayContext, contextRef: String? = nil, mixedIn: Bool = false, mixedOut: Bool = false) {
        self.trackID = trackID; self.startedAtMs = startedAtMs; self.listenedMs = listenedMs
        self.startPosMs = startPosMs; self.endPosMs = endPosMs; self.durationMs = durationMs
        self.endReason = endReason; self.seeks = seeks; self.pauses = pauses; self.pausedMs = pausedMs
        self.context = context; self.contextRef = contextRef; self.mixedIn = mixedIn; self.mixedOut = mixedOut
    }

    public var jsonObject: [String: Any] {
        var obj: [String: Any] = [
            "track_id": trackID, "started_at_ms": startedAtMs, "listened_ms": listenedMs,
            "start_pos_ms": startPosMs, "end_pos_ms": endPosMs, "duration_ms": durationMs,
            "end_reason": endReason.rawValue, "seeks": seeks, "pauses": pauses, "paused_ms": pausedMs,
            "context": context.rawValue, "mixed_in": mixedIn, "mixed_out": mixedOut,
        ]
        if let contextRef { obj["context_ref"] = contextRef }
        return obj
    }

    public var json: String {
        let data = (try? JSONSerialization.data(withJSONObject: jsonObject, options: [.sortedKeys])) ?? Data("{}".utf8)
        return String(decoding: data, as: UTF8.self)
    }

    public init?(json: String) {
        guard let obj = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any],
              let trackID = obj["track_id"] as? String,
              let reason = (obj["end_reason"] as? String).flatMap(PlayEndReason.init(rawValue:)),
              let context = (obj["context"] as? String).flatMap(PlayContext.init(rawValue:)) else { return nil }
        func int(_ key: String) -> Int64 { (obj[key] as? NSNumber)?.int64Value ?? 0 }
        self.init(trackID: trackID, startedAtMs: int("started_at_ms"), listenedMs: int("listened_ms"),
                  startPosMs: int("start_pos_ms"), endPosMs: int("end_pos_ms"), durationMs: int("duration_ms"),
                  endReason: reason, seeks: Int(int("seeks")), pauses: Int(int("pauses")), pausedMs: int("paused_ms"),
                  context: context, contextRef: obj["context_ref"] as? String,
                  mixedIn: obj["mixed_in"] as? Bool ?? false, mixedOut: obj["mixed_out"] as? Bool ?? false)
    }
}

/// Accumulates one playback of one track into a `play` payload. Pure bookkeeping:
/// the player reports what happened with wall-clock and track positions in ms.
/// The rules are pinned by schemas/fixtures/plays.json, shared with Android.
public struct PlaySession: Sendable {
    public let trackID: String
    public let durationMs: Int64
    public let context: PlayContext
    public let contextRef: String?
    public let mixedIn: Bool

    public private(set) var started = false
    private var playing = false
    private var startedAtMs: Int64 = 0
    private var startPosMs: Int64 = 0
    private var lastPosMs: Int64 = 0
    private var lastAtMs: Int64 = 0
    private var listenedMs: Int64 = 0
    private var seeks = 0
    private var pauses = 0
    private var pausedMs: Int64 = 0
    private var pausedSinceMs: Int64 = 0

    public init(trackID: String, durationMs: Int64, context: PlayContext, contextRef: String? = nil, mixedIn: Bool = false) {
        self.trackID = trackID; self.durationMs = durationMs
        self.context = context; self.contextRef = contextRef; self.mixedIn = mixedIn
    }

    /// Audio is playing. The first call starts the play.
    public mutating func resume(at: Int64, pos: Int64) {
        if !started {
            started = true
            startedAtMs = at
            startPosMs = pos
        } else if playing {
            progress(at: at, pos: pos)
            return
        } else {
            pausedMs += at - pausedSinceMs
        }
        playing = true
        lastAtMs = at
        lastPosMs = pos
    }

    /// Forward movement while playing counts, capped so an unreported jump doesn't.
    public mutating func progress(at: Int64, pos: Int64) {
        if playing {
            let delta = pos - lastPosMs
            if delta > 0 { listenedMs += min(delta, max(0, at - lastAtMs) * 2 + 1000) }
            lastAtMs = at
        }
        lastPosMs = pos
    }

    public mutating func pause(at: Int64, pos: Int64) {
        progress(at: at, pos: pos)
        guard playing else { return }
        playing = false
        pauses += 1
        pausedSinceMs = at
    }

    public mutating func seek(at: Int64, from: Int64, to: Int64) {
        progress(at: at, pos: from)
        if started { seeks += 1 }
        lastPosMs = to
    }

    /// The finished play, or nil if audio never started.
    public mutating func end(at: Int64, pos: Int64, reason: PlayEndReason, mixedOut: Bool = false) -> PlayPayload? {
        guard started else { return nil }
        progress(at: at, pos: pos)
        return payload(at: at, reason: reason, mixedOut: mixedOut)
    }

    /// The play so far, as it would be recovered after a crash.
    public func snapshot(at: Int64) -> PlayPayload? {
        guard started else { return nil }
        return payload(at: at, reason: .interrupted, mixedOut: false)
    }

    private func payload(at: Int64, reason: PlayEndReason, mixedOut: Bool) -> PlayPayload {
        PlayPayload(trackID: trackID, startedAtMs: startedAtMs, listenedMs: listenedMs, startPosMs: startPosMs,
                    endPosMs: lastPosMs, durationMs: durationMs, endReason: reason, seeks: seeks, pauses: pauses,
                    pausedMs: pausedMs + (playing ? 0 : max(0, at - pausedSinceMs)),
                    context: context, contextRef: contextRef, mixedIn: mixedIn, mixedOut: mixedOut)
    }
}
