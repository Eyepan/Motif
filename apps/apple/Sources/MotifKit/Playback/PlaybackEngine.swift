import AVFoundation
import Observation

/// Player built on AVAudioEngine with two decks. Plays FLAC, WAV, ALAC and
/// AIFF without transcoding. With `mixIntoNext` on, the end of each track is
/// blended into the next one: the incoming deck is tempo-matched and the
/// decks crossfade on the DSP core's equal-power curve. Otherwise tracks
/// play back to back.
@MainActor
@Observable
public final class PlaybackEngine {
    public enum State: Equatable, Sendable { case idle, playing, paused }

    public private(set) var state: State = .idle
    public private(set) var queue: [Track] = []
    public private(set) var currentIndex: Int?
    /// Seconds into the current track. Updated a few times per second while playing.
    public private(set) var position: TimeInterval = 0
    public private(set) var duration: TimeInterval = 0
    public private(set) var lastError: String?
    /// True while a blend into the next track is running.
    public private(set) var isMixing = false
    public var mixIntoNext = false

    /// Output volume, 0...1.
    public var volume: Float = 1 {
        didSet { engine.mainMixerNode.outputVolume = max(0, min(1, volume)) }
    }

    public var current: Track? { currentIndex.map { queue[$0] } }
    public var upNext: Track? {
        guard let i = currentIndex, i + 1 < queue.count else { return nil }
        return queue[i + 1]
    }

    /// Blend length for a track: 16 bars at its tempo, clamped to 8...32 s
    /// and to a third of the track.
    public nonisolated static func mixLength(for track: Track) -> TimeInterval {
        let bars = track.bpm.map { 16 * 4 * 60 / $0 } ?? 16
        return min(max(bars, 8), 32, track.duration / 3)
    }

    /// Largest tempo change applied to the incoming track, as a fraction.
    public nonisolated static let maxTempoAdjust = 0.08

    private let store: LibraryStore
    private let engine = AVAudioEngine()
    @ObservationIgnored private var decks: [Deck] = []
    @ObservationIgnored private var active = 0
    @ObservationIgnored private var ticker: Task<Void, Never>?
    @ObservationIgnored private var mixTask: Task<Void, Never>?
    @ObservationIgnored private var nowPlaying: NowPlayingController!
    @ObservationIgnored private var observers: [NSObjectProtocol] = []

    private var deck: Deck { decks[active] }
    private var otherDeck: Deck { decks[1 - active] }

    public init(store: LibraryStore) {
        self.store = store
        decks = [Deck(), Deck()]
        for d in decks {
            engine.attach(d.player)
            engine.attach(d.timePitch)
            engine.connect(d.player, to: d.timePitch, format: nil)
            engine.connect(d.timePitch, to: engine.mainMixerNode, format: nil)
        }
        nowPlaying = NowPlayingController(engine: self)
        observeSystemEvents()
        nowPlaying.activate()
    }

    // MARK: - Transport

    public func play(_ tracks: [Track], startAt index: Int = 0) {
        guard tracks.indices.contains(index) else { return }
        queue = tracks
        load(index: index, autoplay: true)
    }

    public func togglePlayPause() {
        if state == .playing { pause() } else { resume() }
    }

    public func resume() {
        guard deck.file != nil else { return }
        do {
            try startEngineIfNeeded()
            deck.player.play()
            if isMixing { otherDeck.player.play() }
            state = .playing
            startTicker()
        } catch {
            lastError = error.localizedDescription
        }
        nowPlaying.update()
    }

    public func pause() {
        guard state == .playing else { return }
        position = currentPosition()
        deck.player.pause()
        if isMixing { otherDeck.player.pause() }
        state = .paused
        ticker?.cancel()
        nowPlaying.update()
    }

    public func next() {
        guard let i = currentIndex, i + 1 < queue.count else { stop(); return }
        load(index: i + 1, autoplay: state == .playing)
    }

    /// Restarts the current track if more than 3 s in, like every other player.
    public func previous() {
        guard let i = currentIndex else { return }
        if currentPosition() > 3 || i == 0 {
            seek(to: 0)
        } else {
            load(index: i - 1, autoplay: state == .playing)
        }
    }

    public func seek(to seconds: TimeInterval) {
        cancelMix()
        guard let file = deck.file else { return }
        let rate = file.processingFormat.sampleRate
        let frame = AVAudioFramePosition(max(0, min(seconds, duration)) * rate)
        let wasPlaying = state == .playing
        schedule(deck, from: frame)
        position = Double(frame) / rate
        if wasPlaying { deck.player.play() }
        nowPlaying.update()
    }

    public func stop() {
        cancelMix()
        deck.reset()
        ticker?.cancel()
        state = .idle
        position = 0
        currentIndex = nil
        nowPlaying.update()
    }

    // MARK: - Loading and scheduling

    private func load(index: Int, autoplay: Bool) {
        cancelMix()
        let track = queue[index]
        Task {
            let url = await store.url(for: track)
            do {
                let file = try AVAudioFile(forReading: url)
                deck.reset()
                deck.file = file
                currentIndex = index
                duration = Double(file.length) / file.processingFormat.sampleRate
                reconnect(deck, for: file.processingFormat)
                schedule(deck, from: 0)
                position = 0
                lastError = nil
                if autoplay { resume() } else { state = .paused; nowPlaying.update() }
            } catch {
                lastError = "Couldn't open \(track.title): \(error.localizedDescription)"
                stop()
            }
        }
    }

    private func reconnect(_ deck: Deck, for format: AVAudioFormat) {
        #if os(iOS)
        // Ask the hardware for the file's native rate so hi-res audio isn't resampled when the route allows it.
        try? AVAudioSession.sharedInstance().setPreferredSampleRate(format.sampleRate)
        #endif
        let current = deck.player.outputFormat(forBus: 0)
        guard current.sampleRate != format.sampleRate || current.channelCount != format.channelCount else { return }
        engine.disconnectNodeOutput(deck.player)
        engine.disconnectNodeOutput(deck.timePitch)
        engine.connect(deck.player, to: deck.timePitch, format: format)
        engine.connect(deck.timePitch, to: engine.mainMixerNode, format: format)
    }

    private func schedule(_ deck: Deck, from frame: AVAudioFramePosition) {
        guard let file = deck.file else { return }
        deck.generation += 1
        let token = deck.generation
        deck.player.stop()
        deck.segmentStartFrame = frame
        let remaining = AVAudioFrameCount(max(0, file.length - frame))
        guard remaining > 0 else { return }
        deck.player.scheduleSegment(file, startingFrame: frame, frameCount: remaining, at: nil,
                                    completionCallbackType: .dataPlayedBack) { [weak self, weak deck] _ in
            let engine = self, finished = deck
            Task { @MainActor in
                guard let engine, let finished else { return }
                engine.segmentFinished(finished, token: token)
            }
        }
    }

    private func segmentFinished(_ finished: Deck, token: Int) {
        // Ignore stale callbacks (stop, seek) and the outgoing deck of a blend, which hands off on its own.
        guard token == finished.generation, finished === deck, !isMixing else { return }
        next()
    }

    private func currentPosition() -> TimeInterval {
        deck.position ?? position
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

    private func startTicker() {
        ticker?.cancel()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                self.position = self.currentPosition()
                self.startMixIfDue()
                try? await Task.sleep(for: .milliseconds(250))
            }
        }
    }

    // MARK: - Mixing

    private func startMixIfDue() {
        guard mixIntoNext, !isMixing, state == .playing, let outgoing = current, let index = currentIndex,
              let incoming = upNext else { return }
        let length = Self.mixLength(for: outgoing)
        let remaining = duration - position
        guard length > 1, remaining <= length, remaining > 0.5 else { return }
        beginMix(from: outgoing, into: incoming, at: index + 1, length: remaining)
    }

    private func beginMix(from outgoingTrack: Track, into track: Track, at index: Int, length: TimeInterval) {
        isMixing = true
        mixTask = Task {
            let url = await store.url(for: track)
            guard !Task.isCancelled else { return }
            guard let file = try? AVAudioFile(forReading: url) else {
                isMixing = false
                return
            }
            let incoming = otherDeck, outgoing = deck
            incoming.reset()
            incoming.file = file
            reconnect(incoming, for: file.processingFormat)
            schedule(incoming, from: 0)
            incoming.timePitch.rate = Float(Self.tempoRatio(from: outgoingTrack, to: track))
            incoming.player.volume = 0
            incoming.player.play()

            let start = ContinuousClock.now
            while !Task.isCancelled {
                let t = min(1, (ContinuousClock.now - start) / .seconds(length))
                let gains = Crossfade.equalPowerGains(at: t)
                outgoing.player.volume = gains.outgoing
                incoming.player.volume = gains.incoming
                if t >= 1 { break }
                try? await Task.sleep(for: .milliseconds(30))
            }
            guard !Task.isCancelled else { return }

            // Hand off: the incoming deck becomes the current track.
            outgoing.reset()
            active = 1 - active
            currentIndex = index
            duration = Double(file.length) / file.processingFormat.sampleRate
            position = currentPosition()
            isMixing = false
            nowPlaying.update()

            // Ease back to the track's own tempo.
            let from = incoming.timePitch.rate
            for step in 1...40 {
                try? await Task.sleep(for: .milliseconds(100))
                if Task.isCancelled { return }
                incoming.timePitch.rate = from + (1 - from) * Float(step) / 40
            }
        }
    }

    private func cancelMix() {
        mixTask?.cancel()
        mixTask = nil
        if isMixing { otherDeck.reset() }
        isMixing = false
        deck.player.volume = 1
        deck.timePitch.rate = 1
    }

    /// Playback rate that brings `incoming` to `outgoing`'s tempo, within
    /// `maxTempoAdjust`. Half and double time count as matches.
    public nonisolated static func tempoRatio(from outgoing: Track?, to incoming: Track) -> Double {
        guard let a = outgoing?.bpm, let b = incoming.bpm, a > 0, b > 0 else { return 1 }
        let candidates = [a / b, a * 2 / b, a / (2 * b)]
        let best = candidates.min { abs($0 - 1) < abs($1 - 1) } ?? 1
        return abs(best - 1) <= maxTempoAdjust ? best : 1
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
            let options = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt).map(AVAudioSession.InterruptionOptions.init)
            MainActor.assumeIsolated {
                guard let self else { return }
                switch type {
                case .began: self.pause()
                case .ended where options?.contains(.shouldResume) == true: self.resume()
                default: break
                }
            }
        })
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap(AVAudioSession.RouteChangeReason.init)
            // Headphones unplugged: pause rather than blast through the speaker.
            guard reason == .oldDeviceUnavailable else { return }
            MainActor.assumeIsolated { self?.pause() }
        })
        #endif
    }

    private func recoverFromConfigurationChange() {
        let wasPlaying = state == .playing
        let at = currentPosition()
        cancelMix()
        guard let file = deck.file else { return }
        reconnect(deck, for: file.processingFormat)
        schedule(deck, from: AVAudioFramePosition(at * file.processingFormat.sampleRate))
        if wasPlaying { resume() }
    }
}

/// One player chain: file → player → time-pitch → main mixer.
@MainActor
private final class Deck {
    let player = AVAudioPlayerNode()
    let timePitch = AVAudioUnitTimePitch()
    var file: AVAudioFile?
    var segmentStartFrame: AVAudioFramePosition = 0
    /// Bumped whenever scheduled audio is discarded, so stale completion
    /// callbacks (from stop or seek) are ignored.
    var generation = 0

    /// Seconds into the file, or nil when the player isn't rendering.
    var position: TimeInterval? {
        guard let file, let nodeTime = player.lastRenderTime, nodeTime.isSampleTimeValid,
              let playerTime = player.playerTime(forNodeTime: nodeTime) else { return nil }
        let seconds = Double(segmentStartFrame + playerTime.sampleTime) / file.processingFormat.sampleRate
        return min(max(0, seconds), Double(file.length) / file.processingFormat.sampleRate)
    }

    func reset() {
        generation += 1
        player.stop()
        player.volume = 1
        timePitch.rate = 1
        file = nil
        segmentStartFrame = 0
    }
}
