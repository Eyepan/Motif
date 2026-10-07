import AVFoundation
import Foundation
import ImageIO

/// Album art, one image per track in the library's Artwork folder, as the
/// picture embedded in the file or a cover image from its folder. Images
/// are stored as found and decoded on demand at the size asked for, so
/// memory stays proportional to what's on screen.
public final class ArtworkStore: @unchecked Sendable { // NSCache and FileManager are thread-safe.
    public let directory: URL
    private let cache = NSCache<NSString, Entry>()

    private final class Entry {
        let image: CGImage?
        init(_ image: CGImage?) { self.image = image }
    }

    /// Covers larger than this are ignored as implausible.
    static let maxBytes = 20 * 1024 * 1024
    /// Folder image names players commonly write, in order of preference.
    static let coverNames = ["cover", "folder", "front", "album", "albumart", "albumartsmall"]
    static let imageExtensions: Set<String> = ["jpg", "jpeg", "png", "webp"]

    init(directory: URL) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        self.directory = directory
        cache.countLimit = 400
    }

    private func file(for id: UUID) -> URL { directory.appending(path: id.uuidString) }
    /// Marks a track already checked and found without art, so backfill skips it.
    private func marker(for id: UUID) -> URL { directory.appending(path: "\(id.uuidString).none") }

    public func hasArtwork(_ id: UUID) -> Bool {
        FileManager.default.fileExists(atPath: file(for: id).path(percentEncoded: false))
    }

    /// Whether `extract` has run for this track, with or without finding art.
    public func wasChecked(_ id: UUID) -> Bool {
        hasArtwork(id) || FileManager.default.fileExists(atPath: marker(for: id).path(percentEncoded: false))
    }

    /// Saves the picture embedded in `audio`, else `fallback` (a folder cover or a
    /// catalog's art). Returns whether the track now has art.
    @discardableResult
    public func extract(_ id: UUID, from audio: URL, fallback: Data? = nil) async -> Bool {
        if let embedded = await Self.embeddedPicture(of: audio), save(embedded, for: id) { return true }
        if let fallback, save(fallback, for: id) { return true }
        FileManager.default.createFile(atPath: marker(for: id).path(percentEncoded: false), contents: nil)
        return false
    }

    /// Stores `data` if it decodes as an image.
    @discardableResult
    public func save(_ data: Data, for id: UUID) -> Bool {
        guard data.count <= Self.maxBytes, let source = CGImageSourceCreateWithData(data as CFData, nil),
              CGImageSourceGetCount(source) > 0
        else { return false }
        do {
            try data.write(to: file(for: id), options: .atomic)
            try? FileManager.default.removeItem(at: marker(for: id))
            cache.removeAllObjects()
            return true
        } catch {
            return false
        }
    }

    public func delete(_ id: UUID) {
        try? FileManager.default.removeItem(at: file(for: id))
        try? FileManager.default.removeItem(at: marker(for: id))
        cache.removeAllObjects()
    }

    /// The track's art scaled to fit `maxPixels` on its longer edge, or nil without art.
    public func image(for id: UUID, maxPixels: Int) -> CGImage? {
        let key = "\(id.uuidString)@\(maxPixels)" as NSString
        if let hit = cache.object(forKey: key) { return hit.image }
        var image: CGImage?
        if let source = CGImageSourceCreateWithURL(file(for: id) as CFURL, nil) {
            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceShouldCacheImmediately: true,
                kCGImageSourceThumbnailMaxPixelSize: max(1, maxPixels),
            ]
            image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
        }
        cache.setObject(Entry(image), forKey: key)
        return image
    }

    // MARK: - Finding art

    static func embeddedPicture(of url: URL) async -> Data? {
        let asset = AVURLAsset(url: url)
        var items = (try? await asset.load(.commonMetadata)) ?? []
        if items.isEmpty { items = (try? await asset.load(.metadata)) ?? [] }
        for item in AVMetadataItem.metadataItems(from: items, filteredByIdentifier: .commonIdentifierArtwork) {
            if let data = try? await item.load(.dataValue), !data.isEmpty { return data }
        }
        return nil
    }

    /// The cover image in `folder` by the names players commonly write, or nil.
    public static func folderCover(in folder: URL) -> URL? {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: folder.path(percentEncoded: false))) ?? []
        return pickFolderCover(names).map { folder.appending(path: $0) }
    }

    static func pickFolderCover(_ names: [String]) -> String? {
        let images = names.filter { imageExtensions.contains(($0 as NSString).pathExtension.lowercased()) }
        for stem in coverNames {
            if let match = images.first(where: { ($0 as NSString).deletingPathExtension.lowercased() == stem }) {
                return match
            }
        }
        return nil
    }
}
