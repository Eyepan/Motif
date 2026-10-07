import Foundation
import Testing
@testable import MotifKit

@Suite struct MixingTests {
    func track(bpm: Double?, seconds: Int = 300) -> Track {
        Track(title: "t", durationMs: seconds * 1000, filePath: "t.flac", format: "flac", source: .local, bpm: bpm)
    }

    @Test func tempoRatioMatchesCloseTempos() {
        #expect(abs(PlaybackEngine.tempoRatio(from: track(bpm: 124), to: track(bpm: 122)) - 124.0 / 122) < 1e-9)
        // Half time counts as a match.
        #expect(abs(PlaybackEngine.tempoRatio(from: track(bpm: 128), to: track(bpm: 64)) - 1) < 1e-9)
        // Too far apart, or unknown: play at native speed.
        #expect(PlaybackEngine.tempoRatio(from: track(bpm: 128), to: track(bpm: 100)) == 1)
        #expect(PlaybackEngine.tempoRatio(from: track(bpm: nil), to: track(bpm: 120)) == 1)
    }

    @Test func mixLengthIsSixteenBarsClamped() {
        #expect(abs(PlaybackEngine.mixLength(for: track(bpm: 120)) - 32) < 1e-9)
        #expect(PlaybackEngine.mixLength(for: track(bpm: 174)) < 32)
        #expect(PlaybackEngine.mixLength(for: track(bpm: 60)) == 32)
        #expect(PlaybackEngine.mixLength(for: track(bpm: 120, seconds: 30)) == 10)
    }

    @Test func equalPowerGainsComeFromTheDSPCore() {
        let mid = Crossfade.equalPowerGains(at: 0.5)
        #expect(abs(mid.outgoing * mid.outgoing + mid.incoming * mid.incoming - 1) < 1e-5)
        #expect(Crossfade.equalPowerGains(at: 0).outgoing == 1)
    }
}
