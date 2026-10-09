import Foundation
import MotifDSP
import Testing
@testable import MotifKit

@Suite struct DJTests {
    let a = Track(title: "a", durationMs: 300_000, filePath: "a.flac", format: "flac", source: .local,
                  bpm: 120, musicalKey: "8A", firstDownbeat: 0.25)
    let b = Track(title: "b", durationMs: 300_000, filePath: "b.flac", format: "flac", source: .local,
                  bpm: 122, musicalKey: "9A", firstDownbeat: 0.5)

    func at(_ ms: Int) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }

    @Test func listensCountOnlyAudibleTime() {
        var tracker = DJListenTracker()
        _ = tracker.loaded(0, a)
        _ = tracker.loaded(1, b)
        // A plays alone for 10 s; B plays silently under it (fader on A).
        for t in 0...10 {
            _ = tracker.tick(at: at(1_000 * t), DeckSnapshot(track: a, playing: true, gain: 1, position: Double(t)),
                             DeckSnapshot(track: b, playing: true, gain: 0, position: 0))
        }
        let listens = tracker.endAll(reason: "stopped")
        #expect(listens.count == 1)
        #expect(listens.first?.track.id == a.id)
        #expect(listens.first?.listenedMs == 10_000)
        #expect(listens.first?.mixedIn == false)
    }

    @Test func crossfaderHandoverIsATransition() {
        var tracker = DJListenTracker()
        _ = tracker.loaded(0, a)
        _ = tracker.loaded(1, b)
        var now = 0
        var transitions: [DJTransition] = []
        func tick(_ ga: Float, _ gb: Float, auto: Bool = false) {
            if let t = tracker.tick(at: at(now), DeckSnapshot(track: a, playing: true, gain: ga, position: 0),
                                    DeckSnapshot(track: b, playing: true, gain: gb, position: 0, speed: 1.01, synced: true),
                                    auto: auto) {
                transitions.append(t)
            }
            now += 500
        }
        for _ in 0..<4 { tick(1, 0) }
        for _ in 0..<6 { tick(0.7, 0.7, auto: true) }
        for _ in 0..<4 { tick(0, 1) }
        #expect(transitions.count == 1)
        let t = transitions[0]
        #expect(t.from.id == a.id && t.to.id == b.id)
        #expect(t.lengthMs == 3_000)
        #expect(t.beatmatched)
        #expect(!t.manual)
        #expect(abs(t.tempoShiftPct - 1) < 1e-9)

        let replaced = tracker.loaded(0, b)
        #expect(replaced?.mixedOut == true)
        #expect(replaced?.endReason == "replaced")
        #expect(tracker.endAll(reason: "stopped").first { $0.track.id == b.id }?.mixedIn == true)
    }

    @Test func unheardTracksAreNotListens() {
        var tracker = DJListenTracker()
        _ = tracker.loaded(0, a)
        _ = tracker.tick(at: at(0), DeckSnapshot(track: a, playing: false, gain: 1, position: 0),
                         DeckSnapshot(track: nil, playing: false, gain: 0, position: 0))
        #expect(tracker.loaded(0, b) == nil)
        #expect(tracker.endAll(reason: "stopped").isEmpty)
    }

    @Test func eqBandsComeFromTheCore() {
        let flat = DeckFX().bands
        #expect(flat.count == 4)
        #expect(flat.allSatisfy { $0.bypass == 1 })
        let kill = DeckFX(low: -1, filter: -1).bands
        #expect(kill[0].kind == MOTIF_FX_LOW_SHELF.rawValue)
        #expect(kill[0].gain_db == -26)
        #expect(kill[0].bypass == 0)
        #expect(kill[3].kind == MOTIF_FX_LOW_PASS.rawValue)
        #expect(kill[3].freq < 200)
    }

    @Test func mixIntoNextIsBeatAlignedWithGrids() {
        let plan = PlaybackEngine.mixPlan(from: a, duration: a.duration, into: b)
        #expect(plan.lock > 0)
        #expect(abs(plan.length - 16 * 2) < 1e-9)  // 16 bars at 120 BPM
        #expect(plan.in_start == 0.5)
        var noGrid = b
        noGrid.firstDownbeat = nil
        #expect(PlaybackEngine.mixPlan(from: a, duration: a.duration, into: noGrid).lock == 0)
    }

    @Test func loopsSnapToTheBeat() {
        var timing = MotifTiming(bpm: 120, first_downbeat: 0.25, duration: 300)
        var start = 0.0, end = 0.0
        #expect(motif_loop_at(&timing, 1.0, 4, &start, &end) == 0)
        #expect(abs(start - 0.75) < 1e-9 && abs(end - 2.75) < 1e-9)
    }
}
