import Foundation
import Security

/// The paired Mac's bearer token, in the Keychain.
///
/// The host and port are ordinary preferences; the token is a credential, so it lives
/// where a backup or a file dump cannot read it, and it never appears in a log line.
public struct TokenStore: Sendable {
    public static let service = "dev.siliconoptimizer.buddy.control-token"

    private let service: String
    public init(service: String = TokenStore.service) {
        self.service = service
    }

    public enum StoreError: Error, Equatable, LocalizedError {
        case keychain(OSStatus)

        public var errorDescription: String? {
            switch self {
            case .keychain(let status):
                let detail = SecCopyErrorMessageString(status, nil) as String?
                return "Keychain error \(status)" + (detail.map { ": \($0)" } ?? "")
            }
        }
    }

    public func save(_ token: String, account: String = "default") throws {
        let data = Data(token.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            // The token is useless to anyone but this device, and the app needs it on a
            // locked phone only after a first unlock.
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        switch status {
        case errSecSuccess:
            return
        case errSecItemNotFound:
            var insert = query
            insert.merge(attributes) { current, _ in current }
            let addStatus = SecItemAdd(insert as CFDictionary, nil)
            guard addStatus == errSecSuccess else { throw StoreError.keychain(addStatus) }
        default:
            throw StoreError.keychain(status)
        }
    }

    public func read(account: String = "default") -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let token = String(data: data, encoding: .utf8)
        else { return nil }
        return token
    }

    @discardableResult
    public func delete(account: String = "default") -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let status = SecItemDelete(query as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }
}
