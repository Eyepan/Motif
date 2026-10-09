import Foundation
import MediaPlayer

/// Publishes the current track to the lock screen, Control Center, the macOS
/// Now Playing menu, AirPods and CarPlay, and routes their commands back.
/// While DJ Mix has taken over (`PlaybackEngine.djTakeover`), it shows the
/// deck being heard and play/pause controls the decks.
@MainActor
final class NowPlayingController {
    private unowned let engine: PlaybackEngine

    init(engine: PlaybackEngine) {
        self.engine = engine
    }

    func activate() {
        let center = MPRemoteCommandCenter.shared()
        // MediaPlayer calls these handlers on the main thread.
        center.playCommand.addTarget { [unowned self] _ in
            MainActor.assumeIsolated {
                if let dj = engine.djTakeover { dj.resume() } else { engine.resume() }
            }
            return .success
        }
        center.pauseCommand.addTarget { [unowned self] _ in
            MainActor.assumeIsolated {
                if let dj = engine.djTakeover { dj.pauseAll() } else { engine.pause() }
            }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [unowned self] _ in
            MainActor.assumeIsolated {
                if let dj = engine.djTakeover {
                    if dj.anyPlaying { dj.pauseAll() } else { dj.resume() }
                } else {
                    engine.togglePlayPause()
                }
            }
            return .success
        }
        center.nextTrackCommand.addTarget { [unowned self] _ in
            let handled = MainActor.assumeIsolated { () -> Bool in
                guard engine.djTakeover == nil else { return false }
                engine.next()
                return true
            }
            return handled ? .success : .noActionableNowPlayingItem
        }
        center.previousTrackCommand.addTarget { [unowned self] _ in
            let handled = MainActor.assumeIsolated { () -> Bool in
                guard engine.djTakeover == nil else { return false }
                engine.previous()
                return true
            }
            return handled ? .success : .noActionableNowPlayingItem
        }
        center.changePlaybackPositionCommand.addTarget { [unowned self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let time = event.positionTime
            let handled = MainActor.assumeIsolated { () -> Bool in
                guard engine.djTakeover == nil else { return false }
                engine.seek(to: time)
                return true
            }
            return handled ? .success : .commandFailed
        }
    }

    func update() {
        let info = MPNowPlayingInfoCenter.default()
        if let dj = engine.djTakeover {
            updateDJ(dj, info)
            return
        }
        guard let track = engine.current else {
            info.nowPlayingInfo = nil
            #if os(macOS)
            info.playbackState = .stopped
            #endif
            return
        }
        var values: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyPlaybackDuration: engine.duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: engine.position,
            MPNowPlayingInfoPropertyPlaybackRate: engine.state == .playing ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let artist = track.artist { values[MPMediaItemPropertyArtist] = artist }
        if let album = track.album { values[MPMediaItemPropertyAlbumTitle] = album }
        if let index = engine.currentIndex {
            values[MPNowPlayingInfoPropertyPlaybackQueueIndex] = index
            values[MPNowPlayingInfoPropertyPlaybackQueueCount] = engine.queue.count
        }
        info.nowPlayingInfo = values
        #if os(macOS)
        info.playbackState = engine.state == .playing ? .playing : .paused
        #endif
    }

    private func updateDJ(_ dj: DJEngine, _ info: MPNowPlayingInfoCenter) {
        guard let deck = dj.lead, let track = deck.track else {
            info.nowPlayingInfo = nil
            return
        }
        var values: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyAlbumTitle: "DJ Mix · deck \(deck.id.name)",
            MPMediaItemPropertyPlaybackDuration: deck.duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: deck.position,
            MPNowPlayingInfoPropertyPlaybackRate: deck.isPlaying ? deck.speed : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let artist = track.artist { values[MPMediaItemPropertyArtist] = artist }
        info.nowPlayingInfo = values
        #if os(macOS)
        info.playbackState = dj.anyPlaying ? .playing : .paused
        #endif
    }
}
