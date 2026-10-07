import AVFoundation
import Observation

/// Gapless-capable player built on AVAudioEngine. Plays FLAC, WAV, ALAC and
/// AIFF without transcoding; the engine is the place DSP (EQ, crossfade,
/// tempo) will plug in, which AVPlayer would not allow.
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

    public var current: Track? { currentIndex.map { queue[$0] } }

    private let store: LibraryStore
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private var file: AVAudioFile?
    private var segmentStartFrame: AVAudioFramePosition = 0
    /// Bumped whenever scheduled audio is discarded, so stale completion
    /// callbacks (from stop or seek) don't advance the queue.
    private var generation = 0
    private var ticker: Task<Void, Never>?
    @ObservationIgnored private var nowPlaying: NowPlayingController!
    @ObservationIgnored private var observers: [NSObjectProtocol] = []

    public init(store: LibraryStore) {
        self.store = store
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: nil)
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
        state == .playing ? pause() : resume()
    }

    public func resume() {
        guard file != nil else { return }
        do {
            try startEngineIfNeeded()
            player.play()
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
        player.pause()
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
        guard let file else { return }
        let rate = file.processingFormat.sampleRate
        let frame = AVAudioFramePosition(max(0, min(seconds, duration)) * rate)
        let wasPlaying = state == .playing
        schedule(file, from: frame)
        position = Double(frame) / rate
        if wasPlaying { player.play() }
        nowPlaying.update()
    }

    public func stop() {
        generation += 1
        player.stop()
        ticker?.cancel()
        state = .idle
        position = 0
        currentIndex = nil
        file = nil
        nowPlaying.update()
    }

    // MARK: - Loading and scheduling

    private func load(index: Int, autoplay: Bool) {
        let track = queue[index]
        Task {
            let url = await store.url(for: track)
            do {
                let f = try AVAudioFile(forReading: url)
                currentIndex = index
                file = f
                duration = Double(f.length) / f.processingFormat.sampleRate
                reconnect(for: f.processingFormat)
                schedule(f, from: 0)
                position = 0
                lastError = nil
                if autoplay { resume() } else { state = .paused; nowPlaying.update() }
            } catch {
                lastError = "Couldn't open \(track.title): \(error.localizedDescription)"
                stop()
            }
        }
    }

    private func reconnect(for format: AVAudioFormat) {
        #if os(iOS)
        // Ask the hardware for the file's native rate so hi-res audio isn't resampled when the route allows it.
        try? AVAudioSession.sharedInstance().setPreferredSampleRate(format.sampleRate)
        #endif
        let current = player.outputFormat(forBus: 0)
        guard current.sampleRate != format.sampleRate || current.channelCount != format.channelCount else { return }
        engine.disconnectNodeOutput(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
    }

    private func schedule(_ file: AVAudioFile, from frame: AVAudioFramePosition) {
        generation += 1
        let token = generation
        player.stop()
        segmentStartFrame = frame
        let remaining = AVAudioFrameCount(max(0, file.length - frame))
        guard remaining > 0 else { return }
        player.scheduleSegment(file, startingFrame: frame, frameCount: remaining, at: nil,
                               completionCallbackType: .dataPlayedBack) { [weak self] _ in
            let engine = self
            Task { @MainActor in engine?.segmentFinished(token) }
        }
    }

    private func segmentFinished(_ token: Int) {
        guard token == generation else { return }
        next()
    }

    private func currentPosition() -> TimeInterval {
        guard let file, let nodeTime = player.lastRenderTime, nodeTime.isSampleTimeValid,
              let playerTime = player.playerTime(forNodeTime: nodeTime) else { return position }
        let frames = segmentStartFrame + playerTime.sampleTime
        return min(duration, Double(frames) / file.processingFormat.sampleRate)
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
                try? await Task.sleep(for: .milliseconds(250))
            }
        }
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
        guard let file else { return }
        reconnect(for: file.processingFormat)
        schedule(file, from: AVAudioFramePosition(at * file.processingFormat.sampleRate))
        if wasPlaying { resume() }
    }
}
