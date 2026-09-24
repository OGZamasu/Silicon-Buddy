import Foundation

/// Which addresses this app will ever dial.
///
/// The Mac binds its Silicon Buddy listener to a tailnet address or loopback and
/// nothing else — `ControlServer.isBindableTailnetAddress` refuses anything wider,
/// because binding one of those is how a private API becomes a public one. This is the
/// same rule on the client side, and it is the thing that makes a scanned QR safe: a
/// code is just text until something dials the host inside it, and a link that says
/// `siliconbuddy://pair?host=evil.example.com` must not get that far.
///
/// Parsed as an address, never scanned for digits: `100.64.0.1.evil.example.com`
/// contains a tailnet address and is a name someone else controls.
///
/// A Mac on this machine — loopback, or 10.0.2.2, the Android emulator's name for the
/// computer it runs on — is dialled only by a DEBUG build (`allowsLocal`), which is what
/// the Simulator and the test suite run against a Mac or a stand-in there. There one name
/// is accepted, `localhost`, because it cannot resolve anywhere but this device. No other
/// name is, including Tailscale's own `*.ts.net`: accepting a name means trusting
/// whatever answers for it, and the Mac advertises an address.
public enum TailnetHost {

    /// Whether this build dials a Mac on the same machine: loopback, and 10.0.2.2.
    ///
    /// The DEBUG build does — the Simulator shares the Mac's loopback, and the tests pair
    /// with a stand-in there. The build the owner installs does not: on a phone, loopback
    /// is whichever other app is listening on it, and 10.0.2.2 is an ordinary private
    /// address on whatever Wi-Fi the phone has joined, so a pairing link naming either
    /// would hand this device's token to somebody else's machine. The same rule as the
    /// Android app's `LOCAL_MACS`.
    public static var allowsLocal: Bool {
        #if DEBUG
        true
        #else
        false
        #endif
    }

    public static var explanation: String { explanation(local: allowsLocal) }

    static func explanation(local: Bool) -> String {
        "Silicon Buddy only pairs over your tailnet. "
            + "Use the Mac's Tailscale address (100.x.y.z)"
            + (local ? ", or 127.0.0.1 in the Simulator." : ".")
    }

    /// True for the addresses the Mac can actually be listening on:
    ///
    /// - 100.64.0.0/10 — Tailscale's IPv4 range (CGNAT space).
    /// - fd7a:115c:a1e0::/48 — Tailscale's IPv6 range.
    /// - and, in a build that dials one (`local`), a Mac on this machine (`isLocal`).
    public static func isAllowed(_ host: String, local: Bool = allowsLocal) -> Bool {
        if isLocal(host) { return local }
        let trimmed = normalized(host)
        if let bytes = ipv4Bytes(trimmed) {
            return bytes[0] == 100 && (64...127).contains(bytes[1])
        }
        if let words = ipv6Groups(trimmed) {
            // fd7a:115c:a1e0::/48
            return words[0] == 0xfd7a && words[1] == 0x115c && words[2] == 0xa1e0
        }
        return false
    }

    /// This device, or the computer an emulator runs on: `localhost`, 127.0.0.0/8, ::1
    /// and 10.0.2.2. Whether this build may dial them at all is `allowsLocal`.
    public static func isLocal(_ host: String) -> Bool {
        isLoopback(host) || ipv4Bytes(normalized(host)) == [10, 0, 2, 2]
    }

    /// This device itself: `localhost`, 127.0.0.0/8 and ::1. From the Simulator, the Mac.
    public static func isLoopback(_ host: String) -> Bool {
        let trimmed = normalized(host)
        if trimmed == "localhost" { return true }
        if let bytes = ipv4Bytes(trimmed) { return bytes[0] == 127 }
        if let words = ipv6Groups(trimmed) {
            // ::1
            return words == [0, 0, 0, 0, 0, 0, 0, 1]
        }
        return false
    }

    private static func normalized(_ host: String) -> String {
        host.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
            .lowercased()
    }

    /// `host` as it goes into a URL or an address line: an IPv6 literal in brackets, which
    /// a URL without them cannot hold; anything else as it is. Brackets already there are
    /// not doubled.
    public static func forURL(_ host: String) -> String {
        let bare = host.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        return bare.contains(":") ? "[\(bare)]" : bare
    }

    /// Four bytes, or nil when this is not a dotted-quad IPv4 literal. Strict: no
    /// octal, no shorthand, no trailing text.
    static func ipv4Bytes(_ text: String) -> [UInt8]? {
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return nil }
        var bytes: [UInt8] = []
        for part in parts {
            guard !part.isEmpty, part.count <= 3, part.allSatisfy(\.isASCII),
                  part.allSatisfy(\.isNumber), let value = UInt16(part), value <= 255
            else { return nil }
            // "064" is 52 to inet_aton and 64 to a naive parser. An address that two
            // readers disagree about is not an address this app will dial.
            guard part == "0" || !part.hasPrefix("0") else { return nil }
            bytes.append(UInt8(value))
        }
        return bytes
    }

    /// Eight groups, or nil when this is not an IPv6 literal. Handles one `::`.
    static func ipv6Groups(_ text: String) -> [UInt16]? {
        guard text.contains(":") else { return nil }
        // A zone index ("%en0") names an interface, not an address.
        guard !text.contains("%") else { return nil }

        let halves = text.components(separatedBy: "::")
        guard halves.count <= 2 else { return nil }

        func groups(_ piece: String) -> [UInt16]? {
            guard !piece.isEmpty else { return [] }
            var result: [UInt16] = []
            for part in piece.split(separator: ":", omittingEmptySubsequences: false) {
                guard !part.isEmpty, part.count <= 4,
                      let value = UInt16(part, radix: 16)
                else { return nil }
                result.append(value)
            }
            return result
        }

        guard let head = groups(halves[0]) else { return nil }
        if halves.count == 1 {
            return head.count == 8 ? head : nil
        }
        guard let tail = groups(halves[1]), head.count + tail.count <= 7 else { return nil }
        return head + Array(repeating: 0, count: 8 - head.count - tail.count) + tail
    }
}
