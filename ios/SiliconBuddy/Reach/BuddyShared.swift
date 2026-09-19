import Foundation
import Security

/// What the app and its extensions have in common.
///
/// A widget, a share sheet and a Shortcuts action are separate processes with separate
/// sandboxes. They cannot read the app's `UserDefaults` or its Keychain items unless
/// they are told to share, and the two names below are that permission written down —
/// the app group for the ordinary facts, the Keychain group for the one credential.
public enum BuddyShared {

    /// The container the app and its extensions all write into. The address of the Mac
    /// and the last thing it said are preferences, not secrets, and they live here so a
    /// widget can draw itself without waking the app.
    public static let appGroup = "group.dev.siliconoptimizer.buddy"

    /// The Keychain group the token is filed under, without the team prefix that iOS
    /// puts in front of it at runtime. `SharedKeychain` works the prefix out.
    public static let keychainGroupSuffix = "dev.siliconoptimizer.buddy"

    /// The app's own bundle identifier, which is also the Keychain group's suffix. An
    /// extension's bundle id is this with `.widgets` or `.share` after it.
    public static let appBundleID = "dev.siliconoptimizer.buddy"

    /// Preferences every process in the family can read. Falls back to the ordinary
    /// domain when the app group is not available — an unsigned build, a Simulator
    /// without the entitlement — so nothing crashes and the app alone still works.
    ///
    /// `UserDefaults` is documented as thread-safe and this is written once, at first
    /// use, so the unchecked annotation is a statement about the API rather than a
    /// hole: there is no mutable state here for a race to find.
    nonisolated(unsafe) public static let defaults: UserDefaults =
        UserDefaults(suiteName: appGroup) ?? .standard

    /// True when the app group really is shared, rather than quietly private to this
    /// process. Extensions say so on screen instead of showing an empty widget.
    public static var hasSharedContainer: Bool {
        UserDefaults(suiteName: appGroup) != nil
    }
}

/// The Keychain group the token is filed under, resolved at runtime.
///
/// An access group is written `<team prefix>.<name>`, and the prefix is not known at
/// compile time: it comes from the signing identity, and it is empty in some Simulator
/// builds. Asking the Keychain is the only reliable way to learn it — add an item with
/// no group, read back the group it was filed under, and take the prefix off the front.
public enum SharedKeychain {

    /// The full access group to pass to `SecItem*`, or nil when this build has no
    /// shared Keychain at all and the token stays private to whoever wrote it.
    ///
    /// Resolved once. A `String?` computed from the Keychain at launch is immutable
    /// afterwards, which is what makes the unchecked annotation true rather than
    /// convenient.
    nonisolated(unsafe) public static let accessGroup: String? = resolve()

    private static func resolve() -> String? {
        guard let prefix = teamPrefix() else { return nil }
        return prefix + BuddyShared.keychainGroupSuffix
    }

    /// The `<team prefix>.` in front of every access group on this device, including
    /// the empty string when there is none.
    static func teamPrefix() -> String? {
        let account = "access-group-probe"
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "dev.siliconoptimizer.buddy.probe",
            kSecAttrAccount as String: account,
        ]
        // Left behind on purpose: it costs one row and saves this dance on every launch
        // after the first. It holds no secret — the value is a single zero byte.
        var attributes = query
        attributes[kSecValueData as String] = Data([0])
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let added = SecItemAdd(attributes as CFDictionary, nil)
        guard added == errSecSuccess || added == errSecDuplicateItem else { return nil }

        var read = query
        read[kSecReturnAttributes as String] = true
        read[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(read as CFDictionary, &item) == errSecSuccess,
              let found = item as? [String: Any],
              let group = found[kSecAttrAccessGroup as String] as? String
        else { return nil }

        // The default group is the prefix followed by this process's own bundle id —
        // `dev.siliconoptimizer.buddy` in the app, `…buddy.widgets` in the widget. Only
        // the prefix is wanted.
        guard let bundleID = Bundle.main.bundleIdentifier, group.hasSuffix(bundleID) else {
            return nil
        }
        return String(group.dropLast(bundleID.count))
    }
}
