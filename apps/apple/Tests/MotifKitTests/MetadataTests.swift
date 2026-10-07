import Foundation
import SQLite3
import Testing
@testable import MotifKit

/// The Swift side of the shared rules in core/dsp/src/meta.rs; the rules themselves are tested in Rust.
@Suite struct TagCleanerTests {
    @Test func stripsSiteSuffixes() {
        let raw = RawTags(title: "Jailer 2 - MassTamilan", artist: "Anirudh Ravichander - MassTamilan")
        let suffix = TagCleaner.siteSuffix(raw)
        #expect(suffix == "MassTamilan")
        #expect(TagCleaner.clean(raw.title, suffixes: [suffix!]) == "Jailer 2")
        #expect(TagCleaner.clean("Intro - Live", suffixes: [suffix!]) == "Intro - Live")
        #expect(TagCleaner.clean("   ", suffixes: [String]()) == nil)
        #expect(TagCleaner.siteSuffix(RawTags(title: "Song")) == nil)
    }

    @Test func splitsArtistCredits() {
        let credits = TagCleaner.splitArtists("A, B feat. C", known: [String]())
        #expect(credits.map(\.name) == ["A", "B", "C"])
        #expect(credits.map(\.role) == [.primary, .primary, .featured])
        #expect(TagCleaner.splitArtists("Simon & Garfunkel", known: [String]()).map(\.name) == ["Simon & Garfunkel"])
        let known = ["simon", "garfunkel"]
        #expect(TagCleaner.splitArtists("Simon & Garfunkel", known: known).map(\.name) == ["Simon", "Garfunkel"])
        #expect(TagCleaner.norm("  Ab  C ") == "ab c")
        #expect(TagCleaner.version > 0)
    }

    @Test func recleansExistingLibrary() async throws {
        let store = try LibraryStore(directory: nil)
        for (title, artist) in [("Jailer 2 - MassTamilan", "Anirudh - MassTamilan"), ("Hukum - MassTamilan", "Anirudh")] {
            try await store.upsert(Track(title: title, artist: artist, durationMs: 0, filePath: "\(title).flac",
                                         format: "flac", source: .local))
        }
        #expect(try await store.prepareTagCleaning(previousVersion: 0) == TagCleaner.version)
        #expect(await store.siteSuffixes == ["MassTamilan"])
        let titles = try await store.allTracks().map(\.title).sorted()
        #expect(titles == ["Hukum", "Jailer 2"])
        #expect(try await store.allTracks().allSatisfy { $0.artist == "Anirudh" })
    }

    @Test func insertKeepsRawTags() async throws {
        let store = try LibraryStore(directory: nil)
        let raw = RawTags(title: "Song - Site", artist: "Band - Site", albumArtist: "Band")
        let track = Track(title: "Song", artist: "Band", durationMs: 0, filePath: "s.flac", format: "flac",
                          source: .local, albumArtist: "Band")
        try await store.insert(track, raw: raw)
        #expect(await store.siteSuffixes == ["Site"])
        #expect(try await store.allTracks().first?.albumArtist == "Band")
        // Re-cleaning from the stored raw tags gives the same values.
        try await store.prepareTagCleaning(previousVersion: 0)
        #expect(try await store.allTracks().first?.title == "Song")
    }
}

@Suite struct ArtworkTests {
    @Test func picksFolderCoverByName() {
        #expect(ArtworkStore.pickFolderCover(["back.jpg", "Folder.JPG", "cover.png"]) == "cover.png")
        #expect(ArtworkStore.pickFolderCover(["scan.jpg", "notes.txt"]) == nil)
        #expect(ArtworkStore.pickFolderCover(["front.webp"]) == "front.webp")
    }

    @Test func savesOnlyImages() throws {
        let store = try ArtworkStore(directory: FileManager.default.temporaryDirectory.appending(path: UUID().uuidString))
        let id = UUID()
        #expect(!store.save(Data("not an image".utf8), for: id))
        #expect(!store.hasArtwork(id))
        // 1x1 PNG.
        let png = Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")!
        #expect(store.save(png, for: id))
        #expect(store.image(for: id, maxPixels: 64)?.width == 1)
        store.delete(id)
        #expect(store.image(for: id, maxPixels: 64) == nil)
    }
}

@Suite struct MixMatchTests {
    func track(bpm: Double?, key: String?) -> Track {
        Track(title: "t", durationMs: 0, filePath: "t.flac", format: "flac", source: .local, bpm: bpm, musicalKey: key)
    }

    @Test func matchesKeysAndTempos() {
        let playing = track(bpm: 124, key: "8A")
        #expect(MixMatch.keys(playing, track(bpm: nil, key: "9A")))
        #expect(MixMatch.keys(playing, track(bpm: nil, key: "8B")))
        #expect(!MixMatch.keys(playing, track(bpm: nil, key: "10A")))
        #expect(MixMatch.tempos(playing, track(bpm: 120, key: nil)))
        #expect(MixMatch.tempos(playing, track(bpm: 62, key: nil)))
        #expect(!MixMatch.tempos(playing, track(bpm: 100, key: nil)))
        #expect(!MixMatch.tempos(playing, track(bpm: nil, key: nil)))
    }
}
