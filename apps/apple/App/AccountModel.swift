import MotifKit
import SwiftUI
#if os(iOS)
import UIKit
#endif

/// Sign-in, the account and its devices, sync, listening history and server details.
/// Everything here is optional: signed out (or offline), the app works the same and history stays on the device.
@MainActor
@Observable
final class AccountModel {
    let history: HistoryStore
    private(set) var api: MotifAPI
    private(set) var syncService: SyncService

    /// The server this device talks to; the Motif server unless the user picked a self-hosted one.
    private(set) var serverURL: URL
    var isCustomServer: Bool { serverURL != MotifAPI.defaultBaseURL }

    private(set) var isSignedIn = false
    private(set) var account: Account?
    private(set) var sessions: [DeviceSession] = []
    private(set) var summary: HistorySummary?

    private(set) var health: ServerHealth?
    private(set) var healthError: String?
    private(set) var latencyMs: Int?
    private(set) var healthCheckedAt: Date?

    private(set) var isSyncing = false
    private(set) var syncError: String?
    private(set) var lastSync: Date?
    private(set) var unsyncedCount = 0
    private(set) var playCount = 0

    /// Pause history: lasts until the app is next launched (docs/analytics.md).
    var historyPaused = false
    /// The sign in / create account / continue offline screens, shown until the user picks one.
    var showWelcome: Bool
    var errorMessage: String?

    init(history: HistoryStore) {
        self.history = history
        let saved = UserDefaults.standard.string(forKey: "serverURL").flatMap(URL.init(string:))
        let url = saved ?? MotifAPI.defaultBaseURL
        serverURL = url
        let api = MotifAPI(baseURL: url, tokens: KeychainTokenStore(server: url))
        self.api = api
        syncService = SyncService(api: api, history: history)
        showWelcome = !UserDefaults.standard.bool(forKey: "welcomeDone")
    }

    /// On launch and whenever the app comes to the foreground.
    func start() async {
        isSignedIn = await api.isSignedIn
        if isSignedIn { showWelcome = false }
        lastSync = await syncService.lastSync()
        await refreshCounts()
        await syncNow()
        if isSignedIn { await refreshAccount() }
    }

    // MARK: - Sign in

    static var device: DeviceInfo {
        #if os(macOS)
        DeviceInfo(name: Host.current().localizedName ?? "Mac", platform: "macos")
        #else
        DeviceInfo(name: UIDevice.current.name, platform: UIDevice.current.userInterfaceIdiom == .pad ? "ipados" : "ios")
        #endif
    }

    static var deviceKind: String {
        #if os(macOS)
        "Mac"
        #else
        UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"
        #endif
    }

    /// Throws `APIError` so the form can show the server's message under the field.
    func signIn(username: String, password: String) async throws {
        try await api.login(username: username, password: password, device: Self.device)
        await didSignIn()
    }

    func register(username: String, password: String) async throws {
        try await api.register(username: username, password: password, device: Self.device)
        await didSignIn()
    }

    func checkUsername(_ username: String) async throws -> UsernameCheck {
        try await api.checkUsername(username)
    }

    func continueOffline() {
        UserDefaults.standard.set(true, forKey: "welcomeDone")
        showWelcome = false
    }

    private func didSignIn() async {
        UserDefaults.standard.set(true, forKey: "welcomeDone")
        isSignedIn = true
        showWelcome = false
        await refreshAccount()
        await syncNow()
    }

    func signOut() async {
        await api.logout()
        clearAccount()
    }

    private func clearAccount() {
        isSignedIn = false
        account = nil
        sessions = []
        summary = nil
        syncError = nil
    }

    // MARK: - Account

    func refreshAccount() async {
        guard isSignedIn else { return }
        let api = self.api
        do {
            async let me = api.me()
            async let devices = api.sessions()
            async let held = api.historySummary()
            account = try await me
            sessions = try await devices
            summary = try await held
        } catch {
            await handle(error)
        }
    }

    func updateAccount(username: String?, displayName: String?) async throws {
        account = try await api.updateAccount(username: username, displayName: displayName)
    }

    func changePassword(current: String, new: String) async throws {
        try await api.changePassword(current: current, new: new)
        await refreshAccount()
    }

    func signOut(_ session: DeviceSession) async {
        do {
            try await api.signOut(session: session.id)
            if session.current { clearAccount() } else { await refreshAccount() }
        } catch {
            await handle(error)
        }
    }

    func signOutOtherDevices() async {
        do {
            _ = try await api.signOutOtherDevices()
            await refreshAccount()
        } catch {
            await handle(error)
        }
    }

    /// Deletes the account and its synced copy on the server. Music and history on this device stay.
    func deleteAccount(password: String) async throws {
        try await api.deleteAccount(password: password)
        clearAccount()
    }

    /// A revoked sign-in turns into "signed out"; other failures show as a message.
    private func handle(_ error: Error) async {
        let signedIn = await api.isSignedIn
        if !signedIn {
            clearAccount()
        } else if let error = error as? APIError, error.isNetwork {
            syncError = error.message
        } else {
            errorMessage = (error as? APIError)?.message ?? error.localizedDescription
        }
    }

    // MARK: - Sync

    func syncNow() async {
        guard isSignedIn, !isSyncing else { return }
        isSyncing = true
        defer { isSyncing = false }
        do {
            let report = try await syncService.sync()
            syncError = nil
            lastSync = await syncService.lastSync()
            if report.pulled > 0 || report.uploaded > 0 { NotificationCenter.default.post(name: .historyDidSync, object: nil) }
        } catch let error as APIError where error.isNetwork {
            syncError = "Offline. Changes upload when the server is reachable."
        } catch {
            await handle(error)
        }
        isSignedIn = await api.isSignedIn
        await refreshCounts()
    }

    func refreshCounts() async {
        unsyncedCount = (try? await history.unsyncedCount()) ?? 0
        playCount = (try? await history.count(types: ["play"])) ?? 0
    }

    // MARK: - Listening history

    enum DeleteRange: String, CaseIterable, Identifiable {
        case lastHour = "Last hour", today = "Today", lastWeek = "Last 7 days", everything = "Everything"
        var id: Self { self }

        var fromMs: Int64? {
            let now = Date.now
            let start: Date? = switch self {
            case .lastHour: now.addingTimeInterval(-3600)
            case .today: Calendar.current.startOfDay(for: now)
            case .lastWeek: now.addingTimeInterval(-7 * 86_400)
            case .everything: nil
            }
            return start.map { Int64($0.timeIntervalSince1970 * 1000) }
        }
    }

    /// Signed in, the server deletes first so every device follows; offline-only, just this device.
    func deleteHistory(_ range: DeleteRange) async {
        let from = range.fromMs
        do {
            if isSignedIn { _ = try await api.deleteHistory(fromMs: from, toMs: nil) }
            try await history.delete(types: HistoryStore.listeningTypes, fromMs: from, toMs: nil)
            await refreshCounts()
            if isSignedIn { summary = try? await api.historySummary() }
            NotificationCenter.default.post(name: .historyDidSync, object: nil)
        } catch {
            errorMessage = "Couldn't delete history. \((error as? APIError)?.message ?? error.localizedDescription)"
        }
    }

    func exportHistory() async -> Data {
        (try? await history.exportJSONLines()) ?? Data()
    }

    // MARK: - Server

    func checkHealth() async {
        let started = Date.now
        do {
            health = try await api.health()
            latencyMs = Int(Date.now.timeIntervalSince(started) * 1000)
            healthError = nil
        } catch {
            health = nil
            latencyMs = nil
            healthError = (error as? APIError)?.message ?? error.localizedDescription
        }
        healthCheckedAt = .now
    }

    /// Switching servers signs out; history stays here and uploads to the new server after signing in there.
    func useServer(_ url: URL?) async {
        let target = url ?? MotifAPI.defaultBaseURL
        guard target != serverURL else { return }
        await signOut()
        serverURL = target
        if url == nil {
            UserDefaults.standard.removeObject(forKey: "serverURL")
        } else {
            UserDefaults.standard.set(target.absoluteString, forKey: "serverURL")
        }
        api = MotifAPI(baseURL: target, tokens: KeychainTokenStore(server: target))
        syncService = SyncService(api: api, history: history)
        health = nil
        await checkHealth()
    }

    /// `https://` is added when missing; plain http only for local servers.
    static func parseServer(_ text: String) -> URL? {
        var trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        while trimmed.hasSuffix("/") { trimmed.removeLast() }
        if !trimmed.contains("://") { trimmed = "https://" + trimmed }
        guard let url = URL(string: trimmed), let host = url.host(), !host.isEmpty else { return nil }
        let local = host == "localhost" || host.hasSuffix(".local") || host.hasPrefix("192.168.") || host.hasPrefix("10.")
        return url.scheme == "https" || (url.scheme == "http" && local) ? url : nil
    }
}

extension Notification.Name {
    /// Pulled events or a deletion changed the local history (and maybe crates).
    static let historyDidSync = Notification.Name("historyDidSync")
}

/// One play in the history list.
struct HistoryRow: Identifiable {
    let id: String
    let at: Date
    let title: String
    let artist: String?
    let track: Track?
    let fromThisDevice: Bool
    let detail: String

    init(event: HistoryEvent, track: Track?, thisDevice: String) {
        id = event.id
        at = Date(timeIntervalSince1970: Double(event.atMs) / 1000)
        self.track = track
        title = track?.title ?? "Track not on this device"
        artist = track?.artist
        fromThisDevice = event.deviceID == thisDevice
        let payload = (try? JSONSerialization.jsonObject(with: Data(event.payload.utf8))) as? [String: Any] ?? [:]
        let listened = (payload["listened_ms"] as? NSNumber).map { formatTime($0.doubleValue / 1000) }
        switch payload["end_reason"] as? String {
        case "skipped": detail = listened.map { "Skipped at \($0)" } ?? "Skipped"
        case _ where payload["context"] as? String == "mix": detail = "DJ Mix" + (listened.map { " · \($0)" } ?? "")
        default: detail = listened.map { "Played \($0)" } ?? "Played"
        }
    }
}
