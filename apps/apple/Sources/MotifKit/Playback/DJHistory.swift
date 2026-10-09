import Foundation

/// One deck's play of a track in DJ Mix: a `play` event with context `mix` (docs/analytics.md).
public struct DJListen: Equatable, Sendable {
    public var track: Track
    public var startedAt: Date
    public var listenedMs: Int
    public var startPosMs: Int
    public var endPosMs: Int
    /// `completed`, `replaced` or `stopped`.
    public var endReason: String
    public var seeks = 0
    public var pauses = 0
    public var pausedMs = 0
    public var mixedIn = false
    public var mixedOut = false
}

/// A handover from one deck to the other: a `transition` event.
public struct DJTransition: Equatable, Sendable {
    public var from: Track
    public var to: Track
    public var lengthMs: Int
    public var beatmatched: Bool
    /// Tempo change on the incoming deck, percent.
    public var tempoShiftPct: Double
    /// The DJ moved the crossfader, rather than BLEND.
    public var manual: Bool
}

/// Counters for one stretch of DJing: a `dj_session` event.
public struct DJSessionSummary: Equatable, Sendable {
    public var startedAt: Date
    public var endedAt: Date
    public var tracksLoaded = 0
    public var transitions = 0
    public var cuesUsed = 0
    public var loopsUsed = 0
    public var eqMoves = 0
}

/// A deck as the tracker sees it at one tick.
public struct DeckSnapshot: Sendable {
    public var track: Track?
    public var playing: Bool
    /// Crossfader gain, 0...1.
    public var gain: Float
    public var position: TimeInterval
    public var speed: Double
    public var synced: Bool

    public init(track: Track?, playing: Bool, gain: Float, position: TimeInterval, speed: Double = 1, synced: Bool = false) {
        self.track = track; self.playing = playing; self.gain = gain
        self.position = position; self.speed = speed; self.synced = synced
    }
}

/// Turns DJ decks, sampled a few times a second, into listening history: one
/// listen per track per deck that counts only the time it was audible, and a
/// transition each time the crossfader hands over from one playing deck to
/// the other. The same rules as Android's DjListenTracker.
public struct DJListenTracker {
    /// Below this crossfader gain a deck counts as not heard (the fader's last few percent).
    public static let audibleGain: Float = 0.1
    /// Longest gap credited between ticks, so a suspended app doesn't add phantom listening.
    public static let maxTickMs = 1_000

    private struct Open {
        let track: Track
        var startedAt: Date?
        var startPosMs = 0
        var lastPosMs = 0
        var listenedMs = 0
        var seeks = 0
        var pauses = 0
        var pausedMs = 0
        var mixedIn = false
        var mixedOut = false
        var wasPlaying = false
        var wasAudible = false
    }

    private var open: [Open?] = [nil, nil]
    private var lastTick: Date?
    /// The deck heard on its own before both were, or nil.
    private var solo: Int?
    private var overlapStart: Date?
    private var overlapFrom = 0
    private var overlapAuto = false

    public init() {}

    /// A track went onto `deck` (0 = A): whatever played there before was replaced.
    public mutating func loaded(_ deck: Int, _ track: Track) -> DJListen? {
        let ended = end(deck, reason: "replaced", reopen: false)
        open[deck] = Open(track: track)
        return ended
    }

    public mutating func seeked(_ deck: Int) {
        if open[deck]?.startedAt != nil { open[deck]?.seeks += 1 }
    }

    /// Closes `deck`'s listen; it's returned only if the track was heard at all.
    /// With `reopen` the track stays on the deck and playing it again starts a new listen.
    public mutating func end(_ deck: Int, reason: String, position: TimeInterval? = nil, reopen: Bool = true) -> DJListen? {
        guard let o = open[deck] else { return nil }
        open[deck] = reopen ? Open(track: o.track) : nil
        guard let startedAt = o.startedAt else { return nil }
        return DJListen(
            track: o.track, startedAt: startedAt, listenedMs: o.listenedMs, startPosMs: o.startPosMs,
            endPosMs: position.map { Int(($0 * 1000).rounded()) } ?? o.lastPosMs, endReason: reason,
            seeks: o.seeks, pauses: o.pauses, pausedMs: o.pausedMs, mixedIn: o.mixedIn, mixedOut: o.mixedOut
        )
    }

    public mutating func endAll(reason: String) -> [DJListen] {
        solo = nil
        overlapStart = nil
        return [end(0, reason: reason), end(1, reason: reason)].compactMap { $0 }
    }

    /// `auto` is true while BLEND is moving the crossfader. Returns a transition when one just finished.
    public mutating func tick(at now: Date, _ a: DeckSnapshot, _ b: DeckSnapshot, auto: Bool = false) -> DJTransition? {
        let dt = lastTick.map { min(Self.maxTickMs, max(0, Int((now.timeIntervalSince($0) * 1000).rounded()))) } ?? 0
        lastTick = now
        let decks = [a, b]
        let audible = decks.map { $0.track != nil && $0.playing && $0.gain >= Self.audibleGain }

        for i in 0..<2 {
            guard var o = open[i], o.track.id == decks[i].track?.id else { continue }
            let d = decks[i]
            if audible[i] {
                if o.startedAt == nil {
                    o.startedAt = now
                    o.startPosMs = Int((d.position * 1000).rounded())
                    o.mixedIn = audible[1 - i]
                } else {
                    o.listenedMs += dt
                }
                o.mixedOut = false
            } else if o.wasAudible && d.playing && audible[1 - i] {
                // Faded out under the other deck.
                o.mixedOut = true
            }
            if o.startedAt != nil {
                if o.wasPlaying && !d.playing { o.pauses += 1 }
                if !d.playing { o.pausedMs += dt }
            }
            o.wasPlaying = d.playing
            o.wasAudible = audible[i]
            o.lastPosMs = Int((d.position * 1000).rounded())
            open[i] = o
        }

        var transition: DJTransition?
        if audible[0] && audible[1] {
            if overlapStart == nil, let solo {
                overlapStart = now
                overlapFrom = solo
                overlapAuto = false
            }
            overlapAuto = overlapAuto || auto
        } else if audible[0] != audible[1] {
            let heard = audible[0] ? 0 : 1
            if let start = overlapStart, overlapFrom == 1 - heard,
               let from = decks[overlapFrom].track, let to = decks[heard].track {
                transition = DJTransition(
                    from: from, to: to, lengthMs: Int((now.timeIntervalSince(start) * 1000).rounded()),
                    beatmatched: (decks[overlapFrom].synced || decks[heard].synced) && from.hasBeatGrid && to.hasBeatGrid,
                    tempoShiftPct: (decks[heard].speed - 1) * 100,
                    manual: !(overlapAuto || auto)
                )
            }
            overlapStart = nil
            solo = heard
        } else {
            overlapStart = nil
            solo = nil
        }
        return transition
    }
}

/// Writes DJ Mix history: `play` (context `mix`), `transition` and `dj_session` events.
public struct DJHistoryWriter: Sendable {
    let history: HistoryStore
    let library: LibraryStore

    public init(history: HistoryStore, library: LibraryStore) {
        self.history = history
        self.library = library
    }

    public func play(_ l: DJListen) {
        write("play", track: l.track, [
            "track_id": l.track.id.uuidString.lowercased(),
            "started_at_ms": Self.ms(l.startedAt),
            "listened_ms": l.listenedMs,
            "start_pos_ms": l.startPosMs,
            "end_pos_ms": l.endPosMs,
            "duration_ms": l.track.durationMs,
            "end_reason": l.endReason,
            "seeks": l.seeks,
            "pauses": l.pauses,
            "paused_ms": l.pausedMs,
            "context": "mix",
            "control": "app",
            "mixed_in": l.mixedIn,
            "mixed_out": l.mixedOut,
        ])
    }

    public func transition(_ t: DJTransition) {
        var payload: [String: Any] = [
            "from_track_id": t.from.id.uuidString.lowercased(),
            "to_track_id": t.to.id.uuidString.lowercased(),
            "kind": t.beatmatched ? "beatmatched" : "crossfade",
            "length_ms": t.lengthMs,
            "tempo_shift_pct": (t.tempoShiftPct * 100).rounded() / 100,
            "manual": t.manual,
        ]
        if let v = t.from.bpm { payload["from_bpm"] = v }
        if let v = t.to.bpm { payload["to_bpm"] = v }
        if let v = t.from.musicalKey { payload["from_key"] = v }
        if let v = t.to.musicalKey { payload["to_key"] = v }
        if t.from.musicalKey != nil, t.to.musicalKey != nil { payload["harmonic"] = MixMatch.keys(t.from, t.to) }
        write("transition", track: t.to, payload)
    }

    public func session(_ s: DJSessionSummary) {
        write("dj_session", track: nil, [
            "started_at_ms": Self.ms(s.startedAt),
            "ended_at_ms": Self.ms(s.endedAt),
            "tracks_loaded": s.tracksLoaded,
            "transitions": s.transitions,
            "cues_used": s.cuesUsed,
            "loops_used": s.loopsUsed,
            "eq_moves": s.eqMoves,
        ])
    }

    private static func ms(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded(.down)) }

    private func write(_ type: String, track: Track?, _ payload: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]),
              let json = String(data: data, encoding: .utf8) else { return }
        let history = history, library = library
        Task {
            var key: String?
            if let track { key = try? await library.contentHash(for: track) }
            _ = try? await history.append(type: type, v: 1, payload: json, trackKey: key)
        }
    }
}
