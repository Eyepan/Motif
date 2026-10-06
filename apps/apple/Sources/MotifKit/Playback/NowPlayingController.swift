import Foundation
import MediaPlayer

/// Publishes the current track to the lock screen, Control Center, the macOS
/// Now Playing menu, AirPods and CarPlay, and routes their commands back.
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
            MainActor.assumeIsolated { engine.resume() }
            return .success
        }
        center.pauseCommand.addTarget { [unowned self] _ in
            MainActor.assumeIsolated { engine.pause() }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [unowned self] _ in
            MainActor.assumeIsolated { engine.togglePlayPause() }
            return .success
        }
        center.nextTrackCommand.addTarget { [unowned self] _ in
            MainActor.assumeIsolated { engine.next() }
            return .success
        }
        center.previousTrackCommand.addTarget { [unowned self] _ in
            MainActor.assumeIsolated { engine.previous() }
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [unowned self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let time = event.positionTime
            MainActor.assumeIsolated { engine.seek(to: time) }
            return .success
        }
    }

    func update() {
        let info = MPNowPlayingInfoCenter.default()
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
}
