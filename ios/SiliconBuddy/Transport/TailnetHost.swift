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
public enum TailnetHost {

    public static let explanation =
        "Silicon Buddy only pairs over your tailnet. "
        + "Use the Mac's Tailscale address (100.x.y.z), or 127.0.0.1 in the Simulator."

    /// True for the addresses the Mac can actually be listening on.
    ///
    /// - 127.0.0.0/8 and ::1 — the Simulator, where the Mac is the same machine.
    /// - 10.0.2.2 — what an Android emulator calls its host's loopback.
    /// - 100.64.0.0/10 — Tailscale's IPv4 range (CGNAT space).
    /// - fd7a:115c:a1e0::/48 — Tailscale's IPv6 range.
    public static func isAllowed(_ host: String) -> Bool {
        let trimmed = host.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
            .lowercased()
        if trimmed == "localhost" { return true }
        if let bytes = ipv4Bytes(trimmed) {
            if bytes[0] == 127 { return true }
            if bytes == [10, 0, 2, 2] { return true }
            return bytes[0] == 100 && (64...127).contains(bytes[1])
        }
        if let words = ipv6Groups(trimmed) {
            // ::1
            if words == [0, 0, 0, 0, 0, 0, 0, 1] { return true }
            // fd7a:115c:a1e0::/48
            return words[0] == 0xfd7a && words[1] == 0x115c && words[2] == 0xa1e0
        }
        return false
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
