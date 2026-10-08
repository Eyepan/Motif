import Foundation

/// Calls `onChange` on the main queue when entries are added to, removed from
/// or renamed in a folder (not when files deeper down change). Downloads land
/// as a new top-level file or folder, which is what this catches.
public final class FolderWatcher: @unchecked Sendable { // The source is only touched in init and deinit.
    private let source: DispatchSourceFileSystemObject

    public init?(_ folder: URL, onChange: @escaping @Sendable () -> Void) {
        let fd = open(folder.path(percentEncoded: false), O_EVTONLY)
        guard fd >= 0 else { return nil }
        source = DispatchSource.makeFileSystemObjectSource(fileDescriptor: fd, eventMask: [.write, .rename, .delete],
                                                           queue: .main)
        source.setEventHandler(handler: onChange)
        source.setCancelHandler { close(fd) }
        source.resume()
    }

    deinit { source.cancel() }
}
