import AVFoundation
import MotifDSP
import Observation

public enum DeckID: Int, CaseIterable, Identifiable, Sendable {
    case a = 0, b = 1

    public var id: Int { rawValue }
    public var other: DeckID { self == .a ? .b : .a }
    public var name: String { self == .a ? "A" : "B" }
    /// Crossfader position that plays only this deck.
    public var side: Double { self == .a ? 0 : 1 }
}

/// A deck's EQ and filter knobs, each -1...1 with 0 flat (core/dsp fx::Knobs).
public struct DeckFX: Equatable, Sendable {
    public var low = 0.0
    public var mid = 0.0
    public var high = 0.0
    public var filter = 0.0

    public init(low: Double = 0, mid: Double = 0, high: Double = 0, filter: Double = 0) {
        self.low = low; self.mid = mid; self.high = high; self.filter = filter
    }

    public var isFlat: Bool { self == DeckFX() }

    /// The four filters for these knobs from the DSP core: low shelf, mid peak, high shelf, filter.
    public var bands: [MotifFxBand] {
        var knobs = MotifFxKnobs(low: low, mid: mid, high: high, filter: filter)
        var out = [MotifFxBand](repeating: MotifFxBand(), count: 4)
        let n = out.withUnsafeMutableBufferPointer { motif_fx_bands(&knobs, $0.baseAddress, $0.count) }
        return Array(out.prefix(max(0, Int(n))))
    }
}

/// A deck's active loop on the beat grid, in seconds.
public struct DeckLoop: Equatable, Sendable {
    public var start: TimeInterval
    public var end: TimeInterval
    public var beats: Double
}

/// An automatic blend from one deck into the other: the incoming deck starts
/// on the outgoing one's next downbeat and the crossfader moves across.
/// Positions are seconds of the outgoing track.
public struct DJBlend: Equatable, Sendable {
    public var from: DeckID
    public var start: TimeInterval
    public var length: TimeInterval
    public var startFader: Double
    /// Bars when the blend is on the beat, nil for a timed crossfade.
    public var bars: Int?
    public var to: DeckID { from.other }
}

/// One deck: what's loaded and how it plays, plus its audio chain
/// (file → player → time-pitch → EQ → main mixer).
@MainActor
@Observable
public final class DJDeck {
    public let id: DeckID
    public internal(set) var track: Track?
    public internal(set) var isPlaying = false
    /// Seconds into the track. Updated a few times a second while playing.
    public internal(set) var position: TimeInterval = 0
    public internal(set) var duration: TimeInterval = 0
    /// Tempo fader, -`DJEngine.pitchRange`...+`DJEngine.pitchRange`.
    public internal(set) var pitch = 0.0
    /// Playback speed: 1 + pitch, or whatever SYNC set. Pitch is kept either way.
    public internal(set) var speed = 1.0
    /// Following the other deck's tempo and beat.
    public internal(set) var synced = false
    public internal(set) var cue: TimeInterval = 0
    public internal(set) var fx = DeckFX()
    public internal(set) var loop: DeckLoop?

    /// Tempo as it plays now.
    public var bpm: Double? { track?.bpm.map { $0 * speed } }
    public var progress: Double { duration > 0 ? min(1, max(0, position / duration)) : 0 }

    /// Beat of the bar (0...3) at the playhead, or nil without a beat grid.
    public var beatInBar: Int? {
        guard let bpm = track?.bpm, let downbeat = track?.firstDownbeat else { return nil }
        let beat = Int(((position - downbeat) * bpm / 60).rounded(.down))
        return ((beat % 4) + 4) % 4
    }

    var timing: MotifTiming {
        MotifTiming(bpm: track?.bpm ?? 0, first_downbeat: track?.firstDownbeat ?? -1, duration: duration)
    }

    @ObservationIgnored let player = AVAudioPlayerNode()
    @ObservationIgnored let timePitch = AVAudioUnitTimePitch()
    @ObservationIgnored let eq = AVAudioUnitEQ(numberOfBands: 4)
    @ObservationIgnored var file: AVAudioFile?
    /// Bumped whenever scheduled audio is discarded, so stale completion callbacks are ignored.
    @ObservationIgnored var generation = 0
    /// File frame where the current schedule starts.
    @ObservationIgnored var scheduledFrom: AVAudioFramePosition = 0
    /// Frames played before the loop takes over, and the loop's frames, for mapping player time to file position.
    @ObservationIgnored var leadIn: AVAudioFramePosition = 0
    @ObservationIgnored var loopFrames: (start: AVAudioFramePosition, length: AVAudioFramePosition)?

    init(id: DeckID) {
        self.id = id
    }

    /// Seconds into the file from the player's clock, or nil when it isn't rendering.
    var renderedPosition: TimeInterval? {
        guard let file, let nodeTime = player.lastRenderTime, nodeTime.isSampleTimeValid,
              let playerTime = player.playerTime(forNodeTime: nodeTime) else { return nil }
        let played = max(0, playerTime.sampleTime)
        let frame: AVAudioFramePosition
        if let loop = loopFrames, played >= leadIn, loop.length > 0 {
            frame = loop.start + (played - leadIn) % loop.length
        } else {
            frame = scheduledFrom + played
        }
        let rate = file.processingFormat.sampleRate
        return min(max(0, Double(frame) / rate), Double(file.length) / rate)
    }
}

/// Two-deck DJ mixing on AVAudioEngine: load, play, cue, tempo fader, SYNC,
/// EQ and filter, beat loops, BLEND and an equal-power crossfader. SYNC,
/// loops, blends and the EQ settings come from the shared DSP core, the same
/// maths as Android's DJ Mix and Mix into next. Listening is written to
/// history like any other play.
@MainActor
@Observable
public final class DJEngine {
    /// Tempo fader range either side of the track's own tempo.
    public nonisolated static let pitchRange = 0.08
    /// Loop lengths offered on a deck, in beats.
    public nonisolated static let loopBeats: [Double] = [1, 2, 4, 8, 16]
    /// BLEND length on the beat.
    public nonisolated static let blendBars = 16
    /// BLEND length without beat grids, seconds.
    public nonisolated static let freeBlendSeconds = 15.0
    /// A DJ session ends after this long with nothing playing.
    nonisolated static let sessionIdle: Duration = .seconds(300)

    public let decks: [DJDeck] = [DJDeck(id: .a), DJDeck(id: .b)]
    /// 0 = deck A only, 1 = deck B only.
    public private(set) var crossfader = 0.5
    public private(set) var blend: DJBlend?
    /// A short message for the last thing that couldn't be done, e.g. a failed SYNC.
    public var notice: String?

    /// Runs when a deck starts playing, so regular playback can pause and hand over the lock screen.
    @ObservationIgnored public var onStart: (@MainActor () -> Void)?
    /// Runs when what the lock screen should show changes.
    @ObservationIgnored public var onNowPlayingChange: (@MainActor () -> Void)?
    /// History stays unwritten while this says so (Pause history).
    @ObservationIgnored public var historyPaused: @MainActor () -> Bool = { false }

    public subscript(id: DeckID) -> DJDeck { decks[id.rawValue] }

    public var anyPlaying: Bool { decks.contains { $0.isPlaying } }

    /// The deck being heard: the playing one the crossfader favours, else the loaded one it favours.
    public var lead: DJDeck? {
        let favoured = crossfader <= 0.5 ? [self[.a], self[.b]] : [self[.b], self[.a]]
        return favoured.first { $0.isPlaying && $0.track != nil } ?? favoured.first { $0.track != nil }
    }

    private let store: LibraryStore
    private let writer: DJHistoryWriter?
    private let engine = AVAudioEngine()
    @ObservationIgnored private var ticker: Task<Void, Never>?
    @ObservationIgnored private var tracker = DJListenTracker()
    @ObservationIgnored private var session: DJSessionSummary?
    @ObservationIgnored private var sessionEnd: Task<Void, Never>?
    @ObservationIgnored private var lastFXMove: [DeckID: ContinuousClock.Instant] = [:]
    @ObservationIgnored private var pausedByAll: Set<DeckID> = []
    @ObservationIgnored private var observers: [NSObjectProtocol] = []

    public init(store: LibraryStore, history: HistoryStore?) {
        self.store = store
        writer = history.map { DJHistoryWriter(history: $0, library: store) }
        for deck in decks {
            engine.attach(deck.player)
            engine.attach(deck.timePitch)
            engine.attach(deck.eq)
            engine.connect(deck.player, to: deck.timePitch, format: nil)
            engine.connect(deck.timePitch, to: deck.eq, format: nil)
            engine.connect(deck.eq, to: engine.mainMixerNode, format: nil)
            apply(deck.fx, to: deck)
        }
        applyGains()
        observeSystemEvents()
    }

    // MARK: - Decks

    public func load(_ track: Track, on id: DeckID) async {
        let deck = self[id]
        let url = await store.url(for: track)
        let file: AVAudioFile
        do {
            file = try AVAudioFile(forReading: url)
        } catch {
            notice = "Couldn't open \(track.title)"
            return
        }
        if blend.map({ $0.from == id || $0.to == id }) == true { blend = nil }
        record(tracker.loaded(id.rawValue, track))
        session?.tracksLoaded += 1
        deck.generation += 1
        deck.player.stop()
        deck.file = file
        deck.track = track
        deck.duration = Double(file.length) / file.processingFormat.sampleRate
        deck.loop = nil
        deck.loopFrames = nil
        deck.isPlaying = false
        deck.pitch = 0
        deck.speed = 1
        deck.timePitch.rate = 1
        deck.synced = false
        deck.cue = track.firstDownbeat ?? 0
        reconnect(deck, for: file.processingFormat)
        schedule(deck, from: deck.cue)
        deck.position = deck.cue
        // Whatever followed this deck has lost its master.
        self[id.other].synced = false
        onNowPlayingChange?()
    }

    public func togglePlay(_ id: DeckID) {
        let deck = self[id]
        guard deck.track != nil else { return }
        if deck.isPlaying {
            pause(deck)
            if blend?.from == id { blend = nil }
        } else {
            play(deck)
        }
    }

    /// Playing: back to the cue point and stop. Stopped: set the cue here, on the nearest beat.
    public func cue(_ id: DeckID) {
        let deck = self[id]
        guard let track = deck.track else { return }
        session?.cuesUsed += 1
        deck.loop = nil
        if deck.isPlaying {
            pause(deck)
            if blend?.from == id { blend = nil }
            schedule(deck, from: deck.cue)
            deck.position = deck.cue
        } else {
            let at = nearestBeat(track, deck.position)
            deck.cue = at
            schedule(deck, from: at)
            deck.position = at
        }
    }

    public func seek(_ id: DeckID, to fraction: Double) {
        let deck = self[id]
        guard deck.file != nil else { return }
        deck.loop = nil
        let at = deck.duration * min(1, max(0, fraction))
        let playing = deck.isPlaying
        schedule(deck, from: at)
        deck.position = at
        tracker.seeked(id.rawValue)
        if playing {
            deck.player.play()
            if deck.synced { syncNow(id, snap: true) }
        }
    }

    /// Moving the tempo fader takes a deck out of SYNC.
    public func setPitch(_ id: DeckID, _ pitch: Double) {
        let deck = self[id]
        deck.pitch = min(Self.pitchRange, max(-Self.pitchRange, pitch))
        deck.synced = false
        setSpeed(deck, 1 + deck.pitch)
    }

    public func toggleSync(_ id: DeckID) {
        let deck = self[id]
        if deck.synced {
            deck.synced = false
            setSpeed(deck, 1 + deck.pitch)
            return
        }
        // One deck follows the other; turning SYNC on here releases the other one.
        if self[id.other].synced { toggleSync(id.other) }
        if syncNow(id, snap: true) { deck.synced = true }
    }

    /// EQ and filter for a deck. Each knob is -1...1 with 0 flat.
    public func setFX(_ id: DeckID, _ fx: DeckFX) {
        let deck = self[id]
        let clamp = { (x: Double) in min(1, max(-1, x)) }
        let knobs = DeckFX(low: clamp(fx.low), mid: clamp(fx.mid), high: clamp(fx.high), filter: clamp(fx.filter))
        // A gesture is a run of moves; count a new one after a second's rest.
        let now = ContinuousClock.now
        if lastFXMove[id].map({ now - $0 > .seconds(1) }) ?? true { session?.eqMoves += 1 }
        lastFXMove[id] = now
        deck.fx = knobs
        apply(knobs, to: deck)
    }

    /// Loops `beats` beats from the beat at or just before the playhead. The
    /// same length again exits the loop; another length resizes it from the same start.
    public func toggleLoop(_ id: DeckID, beats: Double) {
        let deck = self[id]
        guard let track = deck.track else { return }
        if deck.loop?.beats == beats {
            exitLoop(id)
            return
        }
        guard track.hasBeatGrid else {
            notice = "Loops need a beat grid"
            return
        }
        var timing = deck.timing
        var start = 0.0, end = 0.0
        let from = deck.loop?.start ?? currentPosition(deck)
        guard motif_loop_at(&timing, from, beats, &start, &end) == 0 else { return }
        guard end <= deck.duration else {
            notice = "Not enough track left for that loop"
            return
        }
        if deck.loop == nil { session?.loopsUsed += 1 }
        deck.loop = DeckLoop(start: start, end: end, beats: beats)
        let pos = currentPosition(deck)
        let playing = deck.isPlaying
        // Shrunk behind the playhead: wrap into the new loop now.
        let at = pos >= end ? start + (pos - start).truncatingRemainder(dividingBy: end - start) : pos
        schedule(deck, from: at)
        if playing { deck.player.play() }
    }

    public func exitLoop(_ id: DeckID) {
        let deck = self[id]
        guard deck.loop != nil else { return }
        let pos = currentPosition(deck)
        deck.loop = nil
        let playing = deck.isPlaying
        schedule(deck, from: pos)
        if playing { deck.player.play() }
    }

    /// Blends from the playing deck the crossfader favours into the other:
    /// SYNC on, the other deck starts on the next downbeat, and the crossfader
    /// moves across over `blendBars` bars. Pressed again, it stops where it is.
    public func toggleBlend() {
        if blend != nil {
            blend = nil
            return
        }
        let favoured: [DeckID] = crossfader <= 0.5 ? [.a, .b] : [.b, .a]
        guard let from = favoured.first(where: { self[$0].isPlaying }) else {
            notice = "Play a deck to blend from"
            return
        }
        let to = from.other
        guard let incoming = self[to].track, let outgoing = self[from].track else {
            notice = "Load a track on deck \(to.name) to blend into"
            return
        }
        let pos = currentPosition(self[from])
        if outgoing.hasBeatGrid && incoming.hasBeatGrid && !self[to].synced { toggleSync(to) }
        if outgoing.hasBeatGrid && self[to].synced, let bpm = outgoing.bpm {
            let bar = 240 / bpm
            var timing = self[from].timing
            var start = pos
            // A downbeat at least a beat away, so the incoming deck has time to start.
            _ = motif_next_downbeat(&timing, pos + bar / 4, &start)
            blend = DJBlend(from: from, start: start, length: Double(Self.blendBars) * bar, startFader: crossfader, bars: Self.blendBars)
        } else {
            blend = DJBlend(from: from, start: pos, length: Self.freeBlendSeconds * self[from].speed, startFader: crossfader, bars: nil)
        }
        startTicker()
    }

    public func setCrossfader(_ x: Double) {
        // Taking the fader stops BLEND.
        blend = nil
        moveCrossfader(x)
    }

    /// Plays again whatever `pauseAll` stopped (the lock screen's play button).
    public func resume() {
        let ids = pausedByAll.isEmpty ? Set([lead?.id].compactMap { $0 }) : pausedByAll
        pausedByAll = []
        for id in ids where self[id].track != nil { play(self[id]) }
    }

    public func pauseAll() {
        pausedByAll = Set(decks.filter(\.isPlaying).map(\.id))
        blend = nil
        for deck in decks where deck.isPlaying { pause(deck) }
    }

    /// Regular playback took over: pause, and close the DJ session's history.
    public func stopForPlayback() {
        pauseAll()
        endSession()
    }

    /// Unloads a deleted track.
    public func remove(_ track: Track) {
        for deck in decks where deck.track?.id == track.id {
            if blend.map({ $0.from == deck.id || $0.to == deck.id }) == true { blend = nil }
            record(tracker.end(deck.id.rawValue, reason: "stopped", reopen: false))
            deck.generation += 1
            deck.player.stop()
            deck.file = nil
            deck.track = nil
            deck.isPlaying = false
            deck.loop = nil
            deck.loopFrames = nil
            deck.position = 0
            deck.duration = 0
            deck.synced = false
        }
        onNowPlayingChange?()
    }

    // MARK: - Playback

    private func play(_ deck: DJDeck) {
        guard deck.file != nil else { return }
        do {
            try startEngineIfNeeded()
        } catch {
            notice = "Couldn't start audio: \(error.localizedDescription)"
            return
        }
        if deck.position >= deck.duration - 0.05 {
            schedule(deck, from: deck.cue)
            deck.position = deck.cue
        }
        // A synced deck starts on the beat.
        if deck.synced { syncNow(deck.id, snap: true) }
        onStart?()
        deck.player.play()
        deck.isPlaying = true
        beginSession()
        startTicker()
        onNowPlayingChange?()
    }

    private func pause(_ deck: DJDeck) {
        let at = currentPosition(deck)
        // Re-schedule from here, so play resumes on the same spot with the loop intact.
        schedule(deck, from: at)
        deck.position = at
        deck.isPlaying = false
        onNowPlayingChange?()
    }

    /// Replaces what the deck has scheduled with audio from `seconds`: to the
    /// end of the file, or into the loop and then the loop forever.
    private func schedule(_ deck: DJDeck, from seconds: TimeInterval) {
        guard let file = deck.file else { return }
        deck.generation += 1
        let token = deck.generation
        deck.player.stop()
        let rate = file.processingFormat.sampleRate
        var frame = AVAudioFramePosition(max(0, seconds) * rate)
        deck.loopFrames = nil
        deck.leadIn = 0

        if let loop = deck.loop {
            let start = AVAudioFramePosition(loop.start * rate)
            let end = min(file.length, AVAudioFramePosition(loop.end * rate))
            let length = end - start
            if length > 0, let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(length)) {
                file.framePosition = start
                if (try? file.read(into: buffer, frameCount: AVAudioFrameCount(length))) != nil {
                    if frame < start || frame >= end { frame = start + max(0, frame - start) % length }
                    deck.scheduledFrom = frame
                    deck.leadIn = end - frame
                    deck.loopFrames = (start, length)
                    if deck.leadIn > 0 {
                        deck.player.scheduleSegment(file, startingFrame: frame, frameCount: AVAudioFrameCount(deck.leadIn),
                                                    at: nil, completionHandler: nil)
                    }
                    deck.player.scheduleBuffer(buffer, at: nil, options: .loops, completionHandler: nil)
                    return
                }
            }
            deck.loop = nil
        }

        frame = min(frame, file.length)
        deck.scheduledFrom = frame
        let remaining = AVAudioFrameCount(max(0, file.length - frame))
        guard remaining > 0 else { return }
        deck.player.scheduleSegment(file, startingFrame: frame, frameCount: remaining, at: nil,
                                    completionCallbackType: .dataPlayedBack) { [weak self, weak deck] _ in
            let engine = self, played = deck
            Task { @MainActor in
                guard let engine, let played, played.generation == token else { return }
                engine.finished(played)
            }
        }
    }

    /// The deck played to the end of its track.
    private func finished(_ deck: DJDeck) {
        deck.isPlaying = false
        deck.position = deck.duration
        record(tracker.end(deck.id.rawValue, reason: "completed", position: deck.duration))
        if blend.map({ $0.from == deck.id || $0.to == deck.id }) == true { blend = nil }
        onNowPlayingChange?()
    }

    private func currentPosition(_ deck: DJDeck) -> TimeInterval {
        (deck.isPlaying ? deck.renderedPosition : nil) ?? deck.position
    }

    private func setSpeed(_ deck: DJDeck, _ speed: Double) {
        deck.speed = speed
        if abs(Double(deck.timePitch.rate) - speed) > 1e-4 { deck.timePitch.rate = Float(speed) }
    }

    /// Puts deck `id` on the other deck's tempo and beat. Returns false, with a notice, when it can't.
    @discardableResult
    private func syncNow(_ id: DeckID, snap: Bool) -> Bool {
        let slave = self[id], master = self[id.other]
        guard let slaveTrack = slave.track, let masterTrack = master.track else {
            notice = "Load a track on both decks to sync"
            return false
        }
        guard slaveTrack.hasBeatGrid, masterTrack.hasBeatGrid else {
            notice = "Sync needs a beat grid on both tracks"
            return false
        }
        var m = master.timing, s = slave.timing
        var speed = 1.0, pos = 0.0
        let slavePos = currentPosition(slave)
        guard motif_deck_sync(&m, currentPosition(master), master.speed, &s, slavePos, snap ? 1 : 0, &speed, &pos) == 0 else {
            notice = "Tempos are too far apart to sync"
            return false
        }
        // A looping deck keeps its loop; only its speed follows.
        if snap && slave.loop == nil && abs(pos - slavePos) > 0.002 {
            let playing = slave.isPlaying
            schedule(slave, from: pos)
            slave.position = pos
            if playing { slave.player.play() }
        }
        setSpeed(slave, speed)
        return true
    }

    private func nearestBeat(_ track: Track, _ position: TimeInterval) -> TimeInterval {
        guard let bpm = track.bpm, let downbeat = track.firstDownbeat else { return position }
        let beat = 60 / bpm
        return max(0, downbeat + ((position - downbeat) / beat).rounded() * beat)
    }

    private func moveCrossfader(_ x: Double) {
        crossfader = min(1, max(0, x))
        applyGains()
    }

    private func gains() -> (a: Float, b: Float) {
        let g = Crossfade.equalPowerGains(at: crossfader)
        return (g.outgoing, g.incoming)
    }

    private func applyGains() {
        let g = gains()
        self[.a].player.volume = g.a
        self[.b].player.volume = g.b
    }

    private func apply(_ fx: DeckFX, to deck: DJDeck) {
        for (band, setting) in zip(deck.eq.bands, fx.bands) {
            switch setting.kind {
            case MOTIF_FX_LOW_SHELF.rawValue: band.filterType = .lowShelf
            case MOTIF_FX_PEAK.rawValue: band.filterType = .parametric
            case MOTIF_FX_HIGH_SHELF.rawValue: band.filterType = .highShelf
            case MOTIF_FX_LOW_PASS.rawValue: band.filterType = .lowPass
            default: band.filterType = .highPass
            }
            band.frequency = Float(setting.freq)
            band.gain = Float(setting.gain_db)
            // Q as a bandwidth in octaves.
            band.bandwidth = Float(2 * asinh(1 / (2 * setting.q)) / log(2))
            band.bypass = setting.bypass != 0
        }
    }

    private func reconnect(_ deck: DJDeck, for format: AVAudioFormat) {
        let current = deck.player.outputFormat(forBus: 0)
        guard current.sampleRate != format.sampleRate || current.channelCount != format.channelCount else { return }
        engine.disconnectNodeOutput(deck.player)
        engine.disconnectNodeOutput(deck.timePitch)
        engine.disconnectNodeOutput(deck.eq)
        engine.connect(deck.player, to: deck.timePitch, format: format)
        engine.connect(deck.timePitch, to: deck.eq, format: format)
        engine.connect(deck.eq, to: engine.mainMixerNode, format: format)
    }

    private func startEngineIfNeeded() throws {
        guard !engine.isRunning else { return }
        #if os(iOS)
        try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, policy: .longFormAudio)
        try AVAudioSession.sharedInstance().setActive(true)
        #endif
        engine.prepare()
        try engine.start()
    }

    // MARK: - Ticker

    /// Publishes positions, keeps a synced deck locked, runs BLEND and counts listening while anything plays.
    private func startTicker() {
        guard ticker == nil else { return }
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, self.anyPlaying || self.blend != nil else { break }
                self.tick()
                try? await Task.sleep(for: .milliseconds(40))
            }
            guard let self else { return }
            self.ticker = nil
            self.trackListening()
            self.scheduleSessionEnd()
        }
    }

    private func tick() {
        for deck in decks where deck.isPlaying {
            if let p = deck.renderedPosition { deck.position = p }
        }
        stepBlend()
        if let id = DeckID.allCases.first(where: { self[$0].synced }),
           self[id].isPlaying, self[id.other].isPlaying, !syncNow(id, snap: false) {
            self[id].synced = false
        }
        trackListening()
    }

    /// Moves BLEND along: starts the incoming deck on the downbeat, then the crossfader, then stops the outgoing deck.
    private func stepBlend() {
        guard let b = blend else { return }
        let outgoing = self[b.from], incoming = self[b.to]
        guard outgoing.isPlaying, incoming.track != nil else {
            blend = nil
            return
        }
        let pos = currentPosition(outgoing)
        // Start a tick early: the player takes a moment, and SYNC snaps it onto the beat anyway.
        if !incoming.isPlaying && pos >= b.start - 0.04 { play(incoming) }
        let progress = min(1, max(0, (pos - b.start) / b.length))
        moveCrossfader(b.startFader + (b.to.side - b.startFader) * progress)
        if progress >= 1 {
            blend = nil
            pause(outgoing)
        }
    }

    private func trackListening() {
        let g = gains()
        func snap(_ d: DJDeck, _ gain: Float) -> DeckSnapshot {
            DeckSnapshot(track: d.track, playing: d.isPlaying, gain: gain, position: d.position, speed: d.speed, synced: d.synced)
        }
        if let t = tracker.tick(at: .now, snap(self[.a], g.a), snap(self[.b], g.b), auto: blend != nil) {
            session?.transitions += 1
            if !historyPaused() { writer?.transition(t) }
        }
        if anyPlaying { session?.endedAt = .now }
    }

    // MARK: - History

    private func record(_ listen: DJListen?) {
        guard let listen, !historyPaused() else { return }
        writer?.play(listen)
    }

    private func beginSession() {
        sessionEnd?.cancel()
        if session == nil { session = DJSessionSummary(startedAt: .now, endedAt: .now) }
    }

    private func scheduleSessionEnd() {
        sessionEnd?.cancel()
        sessionEnd = Task { [weak self] in
            try? await Task.sleep(for: Self.sessionIdle)
            guard !Task.isCancelled, let self, !self.anyPlaying else { return }
            self.endSession()
        }
    }

    private func endSession() {
        sessionEnd?.cancel()
        for listen in tracker.endAll(reason: "stopped") { record(listen) }
        if let s = session, !historyPaused() { writer?.session(s) }
        session = nil
    }

    // MARK: - System events

    private func observeSystemEvents() {
        let center = NotificationCenter.default
        // The engine stops itself when the output device or its format changes.
        observers.append(center.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.recoverFromConfigurationChange() }
        })
        #if os(iOS)
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init)
            guard type == .began else { return }
            MainActor.assumeIsolated { self?.pauseAll() }
        })
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap(AVAudioSession.RouteChangeReason.init)
            // Headphones unplugged: pause rather than blast through the speaker.
            guard reason == .oldDeviceUnavailable else { return }
            MainActor.assumeIsolated { self?.pauseAll() }
        })
        #endif
    }

    private func recoverFromConfigurationChange() {
        let playing = decks.filter(\.isPlaying)
        for deck in decks where deck.file != nil {
            let at = currentPosition(deck)
            if let file = deck.file { reconnect(deck, for: file.processingFormat) }
            schedule(deck, from: at)
            deck.position = at
        }
        guard !playing.isEmpty else { return }
        do {
            try startEngineIfNeeded()
            for deck in playing { deck.player.play() }
        } catch {
            for deck in playing { deck.isPlaying = false }
            notice = "Audio output changed; press play to continue"
        }
    }
}
