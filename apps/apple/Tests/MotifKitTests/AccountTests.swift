import Foundation
import Testing
@testable import MotifKit

/// Answers requests from a table instead of the network, and records what was asked.
final class StubTransport: HTTPTransport, @unchecked Sendable {
    typealias Handler = (URLRequest) -> (Int, String)
    private let lock = NSLock()
    private var handler: Handler
    private(set) var requests: [URLRequest] = []

    init(_ handler: @escaping Handler) { self.handler = handler }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (status, body) = lock.withLock {
            requests.append(request)
            return handler(request)
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
        return (Data(body.utf8), response)
    }

    var paths: [String] { lock.withLock { requests.map { "\($0.httpMethod ?? "") \($0.url!.path)" } } }
}

private let sessionJSON = #"{"user_id":"u1","access_token":"a1","token_type":"Bearer","expires_in":900,"refresh_token":"r1"}"#
private let base = URL(string: "https://motif.test")!

@Suite struct AccountTests {
    @Test func loginStoresSessionAndSendsDevice() async throws {
        let transport = StubTransport { _ in (200, sessionJSON) }
        let tokens = MemoryTokenStore()
        let api = MotifAPI(baseURL: base, tokens: tokens, transport: transport)
        try await api.login(username: "pan", password: "correct horse", device: DeviceInfo(name: "Pan's iPhone", platform: "ios"))
        #expect(tokens.load() == StoredSession(userID: "u1", refreshToken: "r1"))
        let body = try #require(transport.requests.first?.httpBody)
        let obj = try #require(JSONSerialization.jsonObject(with: body) as? [String: Any])
        #expect((obj["device"] as? [String: String])?["platform"] == "ios")
        #expect(transport.paths == ["POST /v1/auth/login"])
    }

    @Test func serverErrorsCarryCodeAndMessage() async throws {
        let transport = StubTransport { _ in (401, #"{"error":{"code":"unauthorized","message":"invalid username or password"}}"#) }
        let api = MotifAPI(baseURL: base, tokens: MemoryTokenStore(), transport: transport)
        do {
            try await api.login(username: "pan", password: "wrong-pass", device: DeviceInfo(name: "x", platform: "ios"))
            Issue.record("login should have failed")
        } catch let error as APIError {
            #expect(error.code == "unauthorized")
            #expect(error.message == "invalid username or password")
        }
    }

    @Test func refreshesOnceThenRetries() async throws {
        let transport = StubTransport { request in
            switch request.url!.path {
            case "/v1/auth/refresh": return (200, sessionJSON.replacingOccurrences(of: "a1", with: "a2"))
            case "/v1/me":
                if request.value(forHTTPHeaderField: "Authorization") == "Bearer a2" {
                    return (200, #"{"user_id":"u1","username":"pan","display_name":null,"created_at_ms":1759795200000,"identities":[]}"#)
                }
                return (401, #"{"error":{"code":"unauthorized","message":"expired"}}"#)
            default: return (404, "")
            }
        }
        let api = MotifAPI(baseURL: base, tokens: MemoryTokenStore(StoredSession(userID: "u1", refreshToken: "r0")), transport: transport)
        let me = try await api.me()
        #expect(me.username == "pan")
        #expect(me.createdAtMs == 1_759_795_200_000)
        #expect(transport.paths == ["POST /v1/auth/refresh", "GET /v1/me"])
    }

    @Test func revokedRefreshSignsOut() async throws {
        let transport = StubTransport { _ in (401, #"{"error":{"code":"unauthorized","message":"revoked"}}"#) }
        let tokens = MemoryTokenStore(StoredSession(userID: "u1", refreshToken: "r0"))
        let api = MotifAPI(baseURL: base, tokens: tokens, transport: transport)
        await #expect(throws: APIError.self) { _ = try await api.me() }
        #expect(tokens.load() == nil)
        #expect(await api.isSignedIn == false)
    }

    @Test func historyDeleteRemovesOnlyListeningTypesInRange() async throws {
        let store = try HistoryStore(directory: nil)
        for at in [1_000, 2_000, 3_000] as [Int64] {
            try await store.insert([HistoryEvent(id: HistoryStore.uuidV7(ms: at), type: "play", v: 1, atMs: at, tzMin: 0,
                                                 deviceID: store.deviceID, payload: "{}")])
        }
        try await store.insert([HistoryEvent(id: HistoryStore.uuidV7(ms: 2_500), type: "crate_changed", v: 1, atMs: 2_500,
                                             tzMin: 0, deviceID: store.deviceID, payload: #"{"crate_id":"c"}"#)])
        let deleted = try await store.delete(types: HistoryStore.listeningTypes, fromMs: 1_500, toMs: 3_000)
        #expect(deleted == 1)
        #expect(try await store.recent(types: ["play"], limit: 10).map(\.atMs) == [3_000, 1_000])
        #expect(try await store.count(types: ["crate_changed"]) == 1)
    }

    @Test func syncUploadsPullsAndAppliesTombstones() async throws {
        let store = try HistoryStore(directory: nil)
        let mine = try await store.append(type: "play", v: 1, payload: #"{"listened_ms":1000}"#, at: Date(timeIntervalSince1970: 10))
        // Another device's play that the pulled tombstone below deletes.
        try await store.insert([HistoryEvent(id: HistoryStore.uuidV7(ms: 5_000), type: "play", v: 1, atMs: 5_000, tzMin: 0,
                                             deviceID: "other", payload: "{}")])
        let pulledPlay = HistoryStore.uuidV7(ms: 20_000)
        let transport = StubTransport { request in
            switch (request.httpMethod!, request.url!.path) {
            case ("POST", "/v1/auth/refresh"): return (200, sessionJSON)
            case ("POST", "/v1/events"): return (200, #"{"inserted":1,"duplicates":0,"rejected":[]}"#)
            case ("GET", "/v1/events"):
                return (200, """
                {"events":[
                  {"id":"\(pulledPlay)","type":"play","v":1,"at_ms":20000,"tz_min":330,"device_id":"mac","payload":{"listened_ms":5}},
                  {"id":"\(HistoryStore.uuidV7(ms: 30_000))","type":"history_deleted","v":1,"at_ms":30000,"tz_min":0,"device_id":"server",
                   "payload":{"from_ms":0,"to_ms":6000,"types":["play"]}}
                ],"next_cursor":"c1","has_more":false}
                """)
            default: return (404, "")
            }
        }
        let api = MotifAPI(baseURL: base, tokens: MemoryTokenStore(StoredSession(userID: "u1", refreshToken: "r0")), transport: transport)
        let report = try await SyncService(api: api, history: store).sync()
        #expect(report == SyncService.Report(uploaded: 1, rejected: 0, pulled: 2))
        #expect(try await store.unsyncedCount() == 0)
        #expect(await store.syncValue("pull_cursor") == "c1")
        let plays = try await store.recent(types: ["play"], limit: 10).map(\.id)
        #expect(plays == [pulledPlay, mine.id])
        let uploaded = try #require(transport.requests.first { $0.url!.path == "/v1/events" && $0.httpMethod == "POST" }?.httpBody)
        let events = try #require((JSONSerialization.jsonObject(with: uploaded) as? [String: Any])?["events"] as? [[String: Any]])
        #expect(events.map { $0["id"] as? String } == [mine.id])
        #expect((events.first?["payload"] as? [String: Any])?["listened_ms"] as? Int == 1000)
    }
}
