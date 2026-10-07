import Foundation
import Testing
@testable import MotifKit

@Suite struct LibraryFilterTests {
    let track = Track(title: "Night Ferry", artist: "Halden Coast", durationMs: 1, filePath: "a.flac",
                      format: "flac", source: .local, bpm: 122, musicalKey: "8A")

    @Test func parsesFilters() {
        let f = LibraryFilter("ferry bpm:120-126 key:8a")
        #expect(f.text == "ferry")
        #expect(f.bpm == 120...126)
        #expect(f.key == "8A")
        #expect(f.matches(track))
    }

    @Test func rejectsOutsideRange() {
        #expect(!LibraryFilter("bpm:124").matches(track))
        #expect(LibraryFilter("bpm:122").matches(track))
        #expect(!LibraryFilter("key:9A").matches(track))
        #expect(LibraryFilter("halden").matches(track))
        #expect(LibraryFilter("").isEmpty)
    }

    @Test func compatibleKeysWrapAroundTheWheel() {
        #expect(track.compatibleKeys == ["8A", "9A", "7A", "8B"])
        var twelve = track
        twelve.musicalKey = "12B"
        #expect(twelve.compatibleKeys == ["12B", "1B", "11B", "12A"])
        twelve.musicalKey = "1B"
        #expect(twelve.compatibleKeys == ["1B", "2B", "12B", "1A"])
    }
}
