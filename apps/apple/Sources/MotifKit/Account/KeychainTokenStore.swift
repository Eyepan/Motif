import Foundation
import Security

/// Keeps the refresh token in the Keychain, one item per server address.
/// On iOS it stays readable after the first unlock, so background sync can use it.
/// On the Mac it uses the login keychain: the data protection keychain needs a team-signed
/// access group, and CI builds are ad-hoc signed.
public struct KeychainTokenStore: TokenStore {
    private let service = "app.motif.session"
    private let account: String

    public init(server: URL) {
        account = server.absoluteString
    }

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    public func load() -> StoredSession? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return try? JSONDecoder().decode(StoredSession.self, from: data)
    }

    public func save(_ session: StoredSession?) {
        SecItemDelete(query as CFDictionary)
        guard let session, let data = try? JSONEncoder().encode(session) else { return }
        var q = query
        q[kSecValueData as String] = data
        #if os(iOS)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        #endif
        SecItemAdd(q as CFDictionary, nil)
    }
}
