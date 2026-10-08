import AVFoundation
import Foundation
import Testing
@testable import MotifKit

@Suite struct DedupeTests {
    @Test func keysIgnoreNoise() {
        let key = Dedupe.key(title: "Hukum Reloaded", artist: "Anirudh Ravichander")
        #expect(Dedupe.key(title: "01 - Hukum Reloaded (320kbps)", artist: "Anirudh Ravichander, Arivu") == key)
        #expect(Dedupe.key(title: "Hukum Reloaded (Remix)", artist: "Anirudh Ravichander") != key)
    }

    @Test func keepsTheBestCopy() {
        let mp3 = Dedupe.Copy(title: "Veramaari", artist: "Anirudh", durationMs: 214_000, format: "mp3",
                              sampleRate: 44_100, bitrateKbps: 320)
        var flac = mp3
        flac.format = "flac"
        flac.bitDepth = 16
        flac.bitrateKbps = nil
        let other = Dedupe.Copy(title: "Bindaas", artist: "Anirudh", durationMs: 214_000, format: "mp3")
        #expect(Dedupe.resolve(mp3, among: []) == .new)
        #expect(Dedupe.resolve(mp3, among: [other]) == .new)
        #expect(Dedupe.resolve(flac, among: [other, mp3]) == .upgrade(1))
        #expect(Dedupe.resolve(mp3, among: [flac]) == .duplicate(0))
        #expect(Dedupe.isBetter(flac, than: mp3))
        #expect(!Dedupe.isBetter(mp3, than: flac))
        #expect(Dedupe.bitrateKbps(bytes: 8_000_000, durationMs: 200_000) == 320)
    }

    @Test func aacInM4AIsLossy() {
        #expect(ImportService.formatName("m4a", codec: kAudioFormatMPEG4AAC) == "aac")
        #expect(ImportService.formatName("m4a", codec: kAudioFormatAppleLossless) == "alac")
    }
}

@Suite struct FolderImporterTests {
    /// A short mono sine as WAV, untagged, so its title comes from the file name.
    static func writeWAV(_ url: URL, sampleRate: Double = 44_100, bits: Int = 16, seconds: Double = 2) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: sampleRate, AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: bits, AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false,
        ]
        let file = try AVAudioFile(forWriting: url, settings: settings)
        let frames = AVAudioFrameCount(sampleRate * seconds)
        let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: frames)!
        buffer.frameLength = frames
        for i in 0..<Int(frames) { buffer.floatChannelData![0][i] = 0.3 * sin(Float(i) * 0.05) }
        try file.write(from: buffer)
    }

    static func scratch() throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appending(path: "motif-folder-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    @Test func importsOnceAndKeepsTheBestCopy() async throws {
        let dir = try Self.scratch()
        defer { try? FileManager.default.removeItem(at: dir) }
        let downloads = dir.appending(path: "Downloads")
        let store = try LibraryStore(directory: dir.appending(path: "Library"))
        let folder = FolderImporter(importer: ImportService(store: store), store: store,
                                    stateFile: dir.appending(path: "seen.json"))
        let later = Date.now + 60

        try Self.writeWAV(downloads.appending(path: "Album/01 - Song.wav"))
        try Self.writeWAV(downloads.appending(path: "Album/02 - Other.wav"))
        // The same song again, in a second download: a duplicate.
        try Self.writeWAV(downloads.appending(path: "Again/01 - Song.wav"))
        // Still downloading in Safari: ignored.
        try Self.writeWAV(downloads.appending(path: "Partial.download/Song.wav"))
        try "not audio".write(to: downloads.appending(path: "notes.txt"), atomically: true, encoding: .utf8)

        var report = await folder.scan(downloads, analyze: false, now: later)
        #expect(report.added == 2)
        #expect(report.duplicates == 1)
        #expect(report.failed == 0)
        #expect(try await store.allTracks().map(\.title).sorted() == ["01 - Song", "02 - Other"])

        // Nothing new: nothing happens.
        report = await folder.scan(downloads, analyze: false, now: later)
        #expect(report == FolderImporter.Report())

        // A hi-res copy replaces the CD-quality file, keeping the track.
        let before = try await store.allTracks().first { $0.title == "01 - Song" }!
        try Self.writeWAV(downloads.appending(path: "HiRes/Song.wav"), sampleRate: 48_000, bits: 24)
        report = await folder.scan(downloads, analyze: false, now: later)
        #expect(report.upgraded == 1)
        let after = try await store.allTracks().first { $0.title == "01 - Song" }!
        #expect(after.id == before.id)
        #expect(after.sampleRate == 48_000 && after.bitDepth == 24)
        #expect(after.filePath != before.filePath)
        #expect(!FileManager.default.fileExists(atPath: store.mediaDirectory.appending(path: before.filePath).path(percentEncoded: false)))
        #expect(try await store.allTracks().count == 2)
    }

    @Test func waitsForFilesStillBeingWritten() async throws {
        let dir = try Self.scratch()
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = try LibraryStore(directory: dir.appending(path: "Library"))
        let folder = FolderImporter(importer: ImportService(store: store), store: store,
                                    stateFile: dir.appending(path: "seen.json"))
        try Self.writeWAV(dir.appending(path: "Downloads/Fresh.wav"))
        let report = await folder.scan(dir.appending(path: "Downloads"), analyze: false)
        #expect(report.waiting)
        #expect(report.added == 0)
    }

    @Test func destinationsStayInside() {
        let base = URL(filePath: "/tmp/x")
        #expect(FolderImporter.destination(in: base, for: "Album/a.flac") == base.appending(path: "Album").appending(path: "a.flac"))
        #expect(FolderImporter.destination(in: base, for: "../a.flac") == nil)
        #expect(FolderImporter.destination(in: base, for: "") == nil)
    }

    #if os(macOS)
    @Test func importsZipArchives() async throws {
        let dir = try Self.scratch()
        defer { try? FileManager.default.removeItem(at: dir) }
        let staging = dir.appending(path: "staging")
        try Self.writeWAV(staging.appending(path: "Jailer 2/Hukum.wav"))
        try Self.writeWAV(staging.appending(path: "Jailer 2/Veramaari.wav"))
        let downloads = dir.appending(path: "Downloads")
        try FileManager.default.createDirectory(at: downloads, withIntermediateDirectories: true)
        let zip = Process()
        zip.executableURL = URL(filePath: "/usr/bin/zip")
        zip.currentDirectoryURL = staging
        zip.arguments = ["-q", "-r", downloads.appending(path: "Jailer-2.zip").path(percentEncoded: false), "Jailer 2"]
        try zip.run()
        zip.waitUntilExit()
        #expect(zip.terminationStatus == 0)
        try "junk".write(to: downloads.appending(path: "Broken.zip"), atomically: true, encoding: .utf8)

        let store = try LibraryStore(directory: dir.appending(path: "Library"))
        let folder = FolderImporter(importer: ImportService(store: store), store: store,
                                    stateFile: dir.appending(path: "seen.json"))
        let report = await folder.scan(downloads, analyze: false, now: .now + 60)
        #expect(report.added == 2)
        #expect(report.failed == 1)
        #expect(try await store.allTracks().map(\.title).sorted() == ["Hukum", "Veramaari"])
        // Unpacked copies are gone; only the library's own copies remain.
        let leftovers = try FileManager.default.contentsOfDirectory(atPath: FileManager.default.temporaryDirectory.path(percentEncoded: false))
            .filter { $0.hasPrefix("motif-unzip-") }
        #expect(leftovers.isEmpty)
    }
    #endif
}
