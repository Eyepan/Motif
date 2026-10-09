import Foundation

/// An error the server returned (`{"error": {"code", "message"}}`), or a transport failure.
public struct APIError: Error, Sendable, Equatable, CustomStringConvertible {
    /// HTTP status, 0 when the request never got an answer.
    public let status: Int
    /// The server's code (`unauthorized`, `conflict`, ...), or `network` / `decoding`.
    public let code: String
    public let message: String

    public init(status: Int, code: String, message: String) {
        self.status = status; self.code = code; self.message = message
    }

    public var description: String { message }
    /// No answer at all: offline, DNS, TLS, timeout.
    public var isNetwork: Bool { status == 0 }
}

/// Sends one request. A protocol so tests can answer without a network.
public protocol HTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)
}

public struct URLSessionTransport: HTTPTransport {
    public init() {}

    public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw APIError(status: 0, code: "network", message: "The server sent something that isn't HTTP.")
            }
            return (data, http)
        } catch let error as APIError {
            throw error
        } catch {
            throw APIError(status: 0, code: "network", message: "Couldn't reach the server. \(error.localizedDescription)")
        }
    }
}

// MARK: - Wire types (schemas/api/openapi.yaml)

public struct DeviceInfo: Codable, Sendable, Equatable {
    public var name: String
    public var platform: String

    public init(name: String, platform: String) {
        self.name = name; self.platform = platform
    }
}

public struct AuthSession: Codable, Sendable {
    public let userID: String
    public let accessToken: String
    public let expiresIn: Int
    public let refreshToken: String

    enum CodingKeys: String, CodingKey {
        case userID = "user_id", accessToken = "access_token", expiresIn = "expires_in", refreshToken = "refresh_token"
    }
}

public struct Account: Codable, Sendable, Equatable {
    public let userID: String
    public let username: String?
    public let displayName: String?
    public let createdAtMs: Int64

    enum CodingKeys: String, CodingKey {
        case userID = "user_id", username, displayName = "display_name", createdAtMs = "created_at_ms"
    }

    public init(userID: String, username: String?, displayName: String?, createdAtMs: Int64) {
        self.userID = userID; self.username = username; self.displayName = displayName; self.createdAtMs = createdAtMs
    }
}

public struct DeviceSession: Codable, Sendable, Equatable, Identifiable {
    public let id: String
    public let deviceName: String?
    public let platform: String?
    public let createdAtMs: Int64
    public let lastUsedAtMs: Int64
    public let current: Bool

    enum CodingKeys: String, CodingKey {
        case id, deviceName = "device_name", platform, createdAtMs = "created_at_ms", lastUsedAtMs = "last_used_at_ms", current
    }
}

public struct HistorySummary: Codable, Sendable, Equatable {
    public struct Device: Codable, Sendable, Equatable {
        public let deviceID: String
        public let events: Int
        public let lastUploadAtMs: Int64

        enum CodingKeys: String, CodingKey {
            case deviceID = "device_id", events, lastUploadAtMs = "last_upload_at_ms"
        }
    }

    public let events: Int
    public let plays: Int
    public let listenedMs: Int64
    public let firstAtMs: Int64?
    public let lastAtMs: Int64?
    public let lastUploadAtMs: Int64?
    public let bytes: Int64
    public let devices: [Device]

    enum CodingKeys: String, CodingKey {
        case events, plays, listenedMs = "listened_ms", firstAtMs = "first_at_ms", lastAtMs = "last_at_ms",
             lastUploadAtMs = "last_upload_at_ms", bytes, devices
    }
}

public struct ServerHealth: Codable, Sendable, Equatable {
    public let status: String
    public let version: String
    public let database: String
    public let region: String?

    public var isOK: Bool { status == "ok" }
}

public struct UsernameCheck: Codable, Sendable, Equatable {
    public let username: String
    public let available: Bool
}

public struct HistoryDeletion: Codable, Sendable, Equatable {
    public let deleted: Int
    public let fromMs: Int64
    public let toMs: Int64

    enum CodingKeys: String, CodingKey {
        case deleted, fromMs = "from_ms", toMs = "to_ms"
    }
}

/// What a pull returns: events as `HistoryEvent`s with the payload re-encoded as text.
public struct EventPage: Sendable {
    public let events: [HistoryEvent]
    public let nextCursor: String
    public let hasMore: Bool
}

public struct UploadResult: Sendable, Equatable {
    /// Inserted plus duplicates: both mean the server has the event.
    public let acknowledged: Int
    /// Positions in the uploaded batch the server will never accept.
    public let rejected: [Int]
}

// MARK: - Tokens

/// Where the refresh token lives between launches (the Keychain in the app).
public protocol TokenStore: Sendable {
    func load() -> StoredSession?
    func save(_ session: StoredSession?)
}

public struct StoredSession: Codable, Sendable, Equatable {
    public var userID: String
    public var refreshToken: String

    public init(userID: String, refreshToken: String) {
        self.userID = userID; self.refreshToken = refreshToken
    }
}

/// For tests and previews.
public final class MemoryTokenStore: TokenStore, @unchecked Sendable {
    private let lock = NSLock()
    private var session: StoredSession?

    public init(_ session: StoredSession? = nil) { self.session = session }
    public func load() -> StoredSession? { lock.withLock { session } }
    public func save(_ session: StoredSession?) { lock.withLock { self.session = session } }
}

// MARK: - Client

/// The Motif server API (docs/server.md). Keeps the access token in memory, the refresh
/// token in a `TokenStore`, and refreshes once on a 401 before giving up.
public actor MotifAPI {
    public static let defaultBaseURL = URL(string: "https://motif-server.vercel.app")!

    public nonisolated let baseURL: URL
    private let transport: HTTPTransport
    private let tokens: TokenStore
    private var accessToken: String?
    private var accessExpiry: Date = .distantPast
    private var refreshing: Task<Void, Error>?

    public init(baseURL: URL, tokens: TokenStore, transport: HTTPTransport = URLSessionTransport()) {
        self.baseURL = baseURL
        self.tokens = tokens
        self.transport = transport
    }

    /// True while a refresh token is stored. Expired sessions turn false on the next call.
    public var isSignedIn: Bool { tokens.load() != nil }
    public var userID: String? { tokens.load()?.userID }

    // MARK: Auth

    public func register(username: String, password: String, device: DeviceInfo) async throws {
        try await signIn(path: "/v1/auth/register", username: username, password: password, device: device)
    }

    public func login(username: String, password: String, device: DeviceInfo) async throws {
        try await signIn(path: "/v1/auth/login", username: username, password: password, device: device)
    }

    private func signIn(path: String, username: String, password: String, device: DeviceInfo) async throws {
        struct Body: Encodable { let username: String; let password: String; let device: DeviceInfo }
        let session: AuthSession = try await call("POST", path, body: Body(username: username, password: password, device: device), auth: false)
        store(session)
    }

    public func checkUsername(_ username: String) async throws -> UsernameCheck {
        try await call("GET", "/v1/auth/username", query: [("username", username)], auth: false)
    }

    public func changePassword(current: String, new: String) async throws {
        struct Body: Encodable { let current_password: String; let new_password: String }
        let session: AuthSession = try await call("POST", "/v1/auth/password", body: Body(current_password: current, new_password: new))
        store(session)
    }

    /// Forgets the session on this device; tells the server when it can.
    public func logout() async {
        if let stored = tokens.load() {
            struct Body: Encodable { let refresh_token: String }
            _ = try? await send("POST", "/v1/auth/logout", body: Body(refresh_token: stored.refreshToken), auth: false)
        }
        clear()
    }

    // MARK: Account

    public func me() async throws -> Account { try await call("GET", "/v1/me") }

    public func updateAccount(username: String? = nil, displayName: String? = nil) async throws -> Account {
        struct Body: Encodable { let username: String?; let display_name: String? }
        return try await call("PATCH", "/v1/me", body: Body(username: username, display_name: displayName))
    }

    /// Deletes the account and its synced history on the server, then signs out here.
    public func deleteAccount(password: String) async throws {
        struct Body: Encodable { let password: String }
        _ = try await send("DELETE", "/v1/me", body: Body(password: password))
        clear()
    }

    public func sessions() async throws -> [DeviceSession] {
        struct Page: Decodable { let sessions: [DeviceSession] }
        let page: Page = try await call("GET", "/v1/sessions")
        return page.sessions
    }

    public func signOut(session id: String) async throws {
        _ = try await send("DELETE", "/v1/sessions/\(id)")
    }

    /// Returns how many devices were signed out.
    public func signOutOtherDevices() async throws -> Int {
        struct Result: Decodable { let signed_out: Int }
        let result: Result = try await call("DELETE", "/v1/sessions")
        return result.signed_out
    }

    // MARK: History and server

    public func historySummary() async throws -> HistorySummary { try await call("GET", "/v1/history/summary") }

    /// Deletes play, transition, app_session and search events with from <= at < to (nil: open-ended).
    public func deleteHistory(fromMs: Int64?, toMs: Int64?) async throws -> HistoryDeletion {
        struct Body: Encodable { let from_ms: Int64?; let to_ms: Int64? }
        return try await call("DELETE", "/v1/history", body: Body(from_ms: fromMs, to_ms: toMs))
    }

    public func health() async throws -> ServerHealth { try await call("GET", "/health", auth: false) }

    /// Uploads events from this device (at most 1,000 per call).
    public func upload(_ events: [HistoryEvent]) async throws -> UploadResult {
        let body = try JSONSerialization.data(withJSONObject: ["events": events.map(Self.wire)])
        let data = try await send("POST", "/v1/events", rawBody: body)
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw APIError(status: 200, code: "decoding", message: "The server sent an unreadable upload result.")
        }
        let rejected = (obj["rejected"] as? [[String: Any]] ?? []).compactMap { $0["index"] as? Int }
        return UploadResult(acknowledged: ((obj["inserted"] as? Int) ?? 0) + ((obj["duplicates"] as? Int) ?? 0), rejected: rejected)
    }

    /// One page of other devices' events, in server arrival order.
    public func pull(after cursor: String?, excludeDevice: String, limit: Int = 500) async throws -> EventPage {
        var query = [("limit", String(limit)), ("exclude_device", excludeDevice)]
        if let cursor { query.append(("after", cursor)) }
        let data = try await send("GET", "/v1/events", query: query)
        return try Self.decodePage(data)
    }

    static func wire(_ e: HistoryEvent) -> [String: Any] {
        var obj: [String: Any] = [
            "id": e.id, "type": e.type, "v": e.v, "at_ms": e.atMs, "tz_min": e.tzMin, "device_id": e.deviceID,
            "payload": (try? JSONSerialization.jsonObject(with: Data(e.payload.utf8))) ?? [String: Any](),
        ]
        if let key = e.trackKey { obj["track_key"] = key }
        return obj
    }

    static func decodePage(_ data: Data) throws -> EventPage {
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let raw = obj["events"] as? [[String: Any]] else {
            throw APIError(status: 200, code: "decoding", message: "The server sent an unreadable page of history.")
        }
        let events = raw.compactMap { e -> HistoryEvent? in
            guard let id = e["id"] as? String, let type = e["type"] as? String, let v = e["v"] as? Int,
                  let at = (e["at_ms"] as? NSNumber)?.int64Value, let tz = e["tz_min"] as? Int,
                  let device = e["device_id"] as? String else { return nil }
            let payload = (e["payload"] as? [String: Any]).flatMap { try? JSONSerialization.data(withJSONObject: $0) }
                .map { String(decoding: $0, as: UTF8.self) } ?? "{}"
            return HistoryEvent(id: id, type: type, v: v, atMs: at, tzMin: tz, deviceID: device,
                                trackKey: e["track_key"] as? String, payload: payload)
        }
        return EventPage(events: events, nextCursor: obj["next_cursor"] as? String ?? "", hasMore: obj["has_more"] as? Bool ?? false)
    }

    // MARK: Plumbing

    private func store(_ session: AuthSession) {
        accessToken = session.accessToken
        // A little early, so a token never expires mid-request.
        accessExpiry = Date.now.addingTimeInterval(TimeInterval(max(session.expiresIn - 30, 0)))
        tokens.save(StoredSession(userID: session.userID, refreshToken: session.refreshToken))
    }

    private func clear() {
        accessToken = nil
        accessExpiry = .distantPast
        tokens.save(nil)
    }

    private func call<T: Decodable>(_ method: String, _ path: String, query: [(String, String)] = [],
                                    auth: Bool = true) async throws -> T {
        try Self.decode(try await send(method, path, query: query, rawBody: nil, auth: auth))
    }

    private func call<T: Decodable, B: Encodable>(_ method: String, _ path: String, body: B, auth: Bool = true) async throws -> T {
        try Self.decode(try await send(method, path, rawBody: try JSONEncoder().encode(body), auth: auth))
    }

    static func decode<T: Decodable>(_ data: Data) throws -> T {
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw APIError(status: 200, code: "decoding", message: "The server sent an answer this version of Motif can't read.")
        }
    }

    private func send<B: Encodable>(_ method: String, _ path: String, body: B, auth: Bool = true) async throws -> Data {
        try await send(method, path, rawBody: try JSONEncoder().encode(body), auth: auth)
    }

    private func send(_ method: String, _ path: String, query: [(String, String)] = [],
                      rawBody: Data? = nil, auth: Bool = true) async throws -> Data {
        var components = URLComponents(url: baseURL.appending(path: path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { components.queryItems = query.map { URLQueryItem(name: $0.0, value: $0.1) } }
        var request = URLRequest(url: components.url!, timeoutInterval: 30)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let rawBody {
            request.httpBody = rawBody
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        guard auth else { return try await perform(request) }

        try await ensureAccessToken()
        request.setValue("Bearer \(accessToken ?? "")", forHTTPHeaderField: "Authorization")
        do {
            return try await perform(request)
        } catch let error as APIError where error.status == 401 {
            // The access token may have been revoked or expired early: refresh once and retry.
            accessToken = nil
            try await ensureAccessToken()
            request.setValue("Bearer \(accessToken ?? "")", forHTTPHeaderField: "Authorization")
            return try await perform(request)
        }
    }

    private func ensureAccessToken() async throws {
        if accessToken != nil, accessExpiry > .now { return }
        if let refreshing { return try await refreshing.value }
        let task = Task { try await self.refresh() }
        refreshing = task
        defer { refreshing = nil }
        try await task.value
    }

    private func refresh() async throws {
        guard let stored = tokens.load() else {
            throw APIError(status: 401, code: "unauthorized", message: "You're signed out.")
        }
        struct Body: Encodable { let refresh_token: String }
        var request = URLRequest(url: baseURL.appending(path: "/v1/auth/refresh"), timeoutInterval: 30)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(Body(refresh_token: stored.refreshToken))
        do {
            let data = try await perform(request)
            store(try JSONDecoder().decode(AuthSession.self, from: data))
        } catch let error as APIError where error.status == 401 {
            // The sign-in was revoked (signed out from another device, password changed, account deleted).
            clear()
            throw error
        }
    }

    private func perform(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await transport.send(request)
        guard (200..<300).contains(response.statusCode) else { throw Self.error(status: response.statusCode, data: data) }
        return data
    }

    static func error(status: Int, data: Data) -> APIError {
        struct Envelope: Decodable { struct Body: Decodable { let code: String; let message: String }; let error: Body }
        if let envelope = try? JSONDecoder().decode(Envelope.self, from: data) {
            return APIError(status: status, code: envelope.error.code, message: envelope.error.message)
        }
        return APIError(status: status, code: "http_\(status)", message: "The server answered HTTP \(status).")
    }
}
