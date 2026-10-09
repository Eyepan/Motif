import Foundation
import Testing
@testable import MotifKit

@Suite struct PlayTests {
    /// Every platform must turn the same playback into the same payload (schemas/fixtures/plays.json).
    @Test func sessionMatchesSharedFixture() throws {
        let data = try Data(contentsOf: CrateTests.schemas.appending(path: "fixtures/plays.json"))
        let fixture = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        for c in try #require(fixture["cases"] as? [[String: Any]]) {
            let name = c["name"] as! String
            let play = c["play"] as! [String: Any]
            var session = PlaySession(trackID: play["track_id"] as! String, durationMs: Int64(play["duration_ms"] as! Int),
                                      context: PlayContext(rawValue: play["context"] as! String)!,
                                      contextRef: play["context_ref"] as? String, mixedIn: play["mixed_in"] as? Bool ?? false)
            var result: PlayPayload?
            for step in c["steps"] as! [[String: Any]] {
                func ms(_ key: String) -> Int64 { Int64(step[key] as! Int) }
                switch step["op"] as! String {
                case "resume": session.resume(at: ms("at"), pos: ms("pos"))
                case "progress": session.progress(at: ms("at"), pos: ms("pos"))
                case "pause": session.pause(at: ms("at"), pos: ms("pos"))
                case "seek": session.seek(at: ms("at"), from: ms("from"), to: ms("to"))
                case "end":
                    result = session.end(at: ms("at"), pos: ms("pos"), reason: PlayEndReason(rawValue: step["reason"] as! String)!,
                                         mixedOut: step["mixed_out"] as? Bool ?? false)
                case "snapshot": result = session.snapshot(at: ms("at"))
                default: Issue.record("unknown op in \(name)")
                }
            }
            if let expected = c["expected"] as? [String: Any] {
                let got = try #require(result, "\(name)")
                #expect(NSDictionary(dictionary: got.jsonObject) == NSDictionary(dictionary: expected), "\(name)")
                #expect(PlayPayload(json: got.json) == got, "\(name)")
            } else {
                #expect(result == nil, "\(name)")
            }
        }
    }

    @Test func checkpointRoundTrips() throws {
        let payload = PlayPayload(trackID: "t", startedAtMs: 1, listenedMs: 2, endReason: .interrupted, context: .crate, contextRef: "c")
        let json = try #require(PlayRecorder.checkpointJSON(payload, atMs: 99))
        let parsed = try #require(PlayRecorder.parseCheckpoint(json))
        #expect(parsed.0 == payload)
        #expect(parsed.1 == 99)
    }

    @MainActor @Test func recoversInterruptedPlayOnLaunch() async throws {
        let history = try HistoryStore(directory: nil)
        let recorder = PlayRecorder(history: history, library: try LibraryStore(directory: nil))
        let payload = PlayPayload(trackID: "gone", startedAtMs: 1_000, listenedMs: 15_000, endReason: .interrupted, context: .library)
        try await history.setSyncValue(PlayRecorder.checkpointJSON(payload, atMs: 16_000), for: PlayRecorder.checkpointKey)
        await recorder.recoverInterrupted()
        let plays = try await history.events(ofType: "play")
        #expect(plays.count == 1)
        #expect(plays.first?.atMs == 16_000)
        #expect(plays.first.flatMap { PlayPayload(json: $0.payload) } == payload)
        #expect(await history.syncValue(PlayRecorder.checkpointKey) == nil)
        await recorder.recoverInterrupted()
        #expect(try await history.events(ofType: "play").count == 1)
    }

    @MainActor @Test func pausedHistoryWritesNothing() async throws {
        let history = try HistoryStore(directory: nil)
        let recorder = PlayRecorder(history: history, library: try LibraryStore(directory: nil))
        recorder.isPaused = { true }
        let track = Track(id: UUID(), title: "T", durationMs: 1000, filePath: "t.flac", format: "flac", source: .local, addedAt: .now)
        recorder.recordListen(track: track, startedAtMs: 0, listenedMs: 1000, endReason: .completed, mixedIn: false, mixedOut: false)
        recorder.isPaused = { false }
        recorder.recordListen(track: track, startedAtMs: 0, listenedMs: 0, endReason: .skipped, mixedIn: false, mixedOut: false)
        try await Task.sleep(for: .milliseconds(100))
        #expect(try await history.events(ofType: "play").isEmpty)
    }
}
