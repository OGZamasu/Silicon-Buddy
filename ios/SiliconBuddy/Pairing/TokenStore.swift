import Foundation
import Security

/// The paired Mac's bearer token, in the Keychain.
///
/// The host and port are ordinary preferences; the token is a credential, so it lives
/// where a backup or a file dump cannot read it, and it never appears in a log line.
///
/// Filed under a shared access group so the widget, the share sheet and the Shortcuts
/// actions can read the same token without the app handing it to them. When there is no
/// shared group — an unsigned build, a Simulator without the entitlement — every call
/// falls back to this process's own default group, which is what M1 used: the app keeps
/// working and the extensions find nothing, which is the honest failure.
public struct TokenStore: Sendable {
    public static let service = "dev.siliconoptimizer.buddy.control-token"

    private let service: String
    private let accessGroup: String?

    public init(
        service: String = TokenStore.service,
        accessGroup: String? = SharedKeychain.accessGroup
    ) {
        self.service = service
        self.accessGroup = accessGroup
    }

    /// The lookup for one token, with the shared group when there is one.
    private func query(account: String, group: String?) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        if let group { query[kSecAttrAccessGroup as String] = group }
        return query
    }

    /// The groups to try, in order: the shared one, then none. A Keychain that refuses
    /// the group answers `errSecMissingEntitlement`, and falling back is better than a
    /// build that cannot store a token at all.
    private var groups: [String?] {
        accessGroup.map { [$0, nil] } ?? [nil]
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
        var lastStatus: OSStatus = errSecSuccess
        for group in groups {
            switch write(token, account: account, group: group) {
            case errSecSuccess:
                return
            case let status:
                lastStatus = status
            }
        }
        throw StoreError.keychain(lastStatus)
    }

    private func write(_ token: String, account: String, group: String?) -> OSStatus {
        let lookup = query(account: account, group: group)
        let attributes: [String: Any] = [
            kSecValueData as String: Data(token.utf8),
            // The token is useless to anyone but this device, and the app needs it on a
            // locked phone only after a first unlock.
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemUpdate(lookup as CFDictionary, attributes as CFDictionary)
        guard status == errSecItemNotFound else { return status }
        var insert = lookup
        insert.merge(attributes) { current, _ in current }
        return SecItemAdd(insert as CFDictionary, nil)
    }

    public func read(account: String = "default") -> String? {
        for group in groups {
            var lookup = query(account: account, group: group)
            lookup[kSecReturnData as String] = true
            lookup[kSecMatchLimit as String] = kSecMatchLimitOne
            var item: CFTypeRef?
            guard SecItemCopyMatching(lookup as CFDictionary, &item) == errSecSuccess,
                  let data = item as? Data,
                  let token = String(data: data, encoding: .utf8)
            else { continue }
            return token
        }
        return nil
    }

    /// Forgets the token everywhere it could be, not only where this process would
    /// have written it: a build that once stored it without the shared group must not
    /// leave a working credential behind when the owner says "forget this Mac".
    @discardableResult
    public func delete(account: String = "default") -> Bool {
        var deleted = true
        for group in groups {
            let status = SecItemDelete(query(account: account, group: group) as CFDictionary)
            deleted = deleted && (status == errSecSuccess || status == errSecItemNotFound)
        }
        return deleted
    }
}
