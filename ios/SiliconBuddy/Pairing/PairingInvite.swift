import Foundation

/// What the QR on the Mac's screen encodes:
/// `siliconbuddy://pair?host=<tailnet address>&port=<port>&code=<6 digits>`.
/// The same three things, typed in by hand, come through `typed(address:code:port:)`.
public struct PairingInvite: Sendable, Equatable {
    public var host: String
    public var port: Int
    public var code: String

    public init(host: String, port: Int, code: String) {
        self.host = host
        self.port = port
        self.code = code
    }

    public enum ParseError: Error, Equatable, LocalizedError {
        case notAURL
        case wrongScheme(String?)
        case wrongAction(String?)
        case missing(String)
        case badPort(String)
        case badCode(String)
        case noAddress
        case linkNotTyped
        case hostNotOnTailnet(String)

        public var errorDescription: String? {
            switch self {
            case .notAURL: "That isn't a Silicon Buddy code."
            case .wrongScheme(let scheme):
                "That code is for \(scheme ?? "something else"), not Silicon Buddy."
            case .wrongAction: "That Silicon Buddy code isn't a pairing code."
            case .missing(let field): "The pairing code is missing its \(field)."
            case .badPort(let value): "\"\(value)\" isn't a port number."
            case .badCode(let value):
                "\"\(value)\" isn't a six-digit pairing code."
            case .noAddress:
                "Type the Mac's address too. Silicon Optimizer shows it beside the code."
            case .linkNotTyped:
                "That's a pairing link. Silicon Buddy asks before it uses one."
            case .hostNotOnTailnet(let host):
                "\(host) isn't a tailnet address. " + TailnetHost.explanation
            }
        }
    }

    /// Parses the scanned text. Strict on purpose: a QR code is scanned from whatever
    /// happens to be in frame, so anything that is not exactly our invite is rejected
    /// rather than half-understood.
    public static func parse(_ text: String) throws -> PairingInvite {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let components = URLComponents(string: trimmed) else {
            throw ParseError.notAURL
        }
        guard components.scheme?.lowercased() == "siliconbuddy" else {
            throw ParseError.wrongScheme(components.scheme)
        }
        // `siliconbuddy://pair?…` puts "pair" in the host; `siliconbuddy:///pair?…`
        // and `siliconbuddy:pair?…` put it in the path. Accept all three spellings.
        // `siliconbuddy:///pair` parses with an empty host, not a nil one.
        let hostAction = components.host.flatMap { $0.isEmpty ? nil : $0.lowercased() }
        let pathAction = components.path
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            .lowercased()
        let action = hostAction ?? pathAction
        guard action == "pair" else { throw ParseError.wrongAction(action) }

        let items = components.queryItems ?? []
        func value(_ name: String) -> String? {
            items.first { $0.name == name }?.value?.trimmingCharacters(in: .whitespaces)
        }

        guard let host = value("host"), !host.isEmpty else { throw ParseError.missing("host") }
        // A code is only text until something dials the host inside it. This is where
        // `siliconbuddy://pair?host=evil.example.com&…` stops.
        guard TailnetHost.isAllowed(host) else { throw ParseError.hostNotOnTailnet(host) }
        guard let portText = value("port"), !portText.isEmpty else {
            throw ParseError.missing("port")
        }
        guard let port = Int(portText), (1...65535).contains(port) else {
            throw ParseError.badPort(portText)
        }
        guard let code = value("code"), !code.isEmpty else { throw ParseError.missing("code") }
        guard code.count == 6, code.allSatisfy(\.isASCII), code.allSatisfy(\.isNumber) else {
            throw ParseError.badCode(code)
        }
        return PairingInvite(host: host, port: port, code: code)
    }

    /// The Mac's Silicon Buddy listener. Fixed across launches, so a code read off the
    /// screen needs only the address the Mac prints beside it.
    public static let defaultPort = 8788

    /// Whether pasted text is a whole pairing link rather than an address.
    public static func isLink(_ text: String) -> Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased().hasPrefix("siliconbuddy:")
    }

    /// A `siliconbuddy://pair` link pasted where an address goes: the invite when `text` is
    /// one, nil when it is not a link at all, and the parse error when it is a link that
    /// doesn't parse.
    ///
    /// The code form's own Pair button never spends one. Someone else chose a link's host —
    /// the Mac has no button that copies one — so it goes to the confirmation a tapped link
    /// gets, with its "only pair with a code you can see on your own Mac's screen".
    public static func pasted(_ text: String) throws -> PairingInvite? {
        isLink(text) ? try parse(text) : nil
    }

    /// `text` with every decimal digit, from any script, as its ASCII one, and everything
    /// else but a space dropped. For the code and port fields: a keyboard that types ٤ or
    /// ４ puts 4 in the field, rather than a character `typed` then refuses as "not a
    /// six-digit pairing code" without saying why.
    public static func asciiDigits(_ text: String, keepSpaces: Bool = false) -> String {
        var result = ""
        for character in text {
            // One scalar, and a decimal digit by Unicode's own account: not "五", which
            // has a numeric value but is not a digit anyone types into a code.
            if character.unicodeScalars.count == 1,
               let scalar = character.unicodeScalars.first,
               scalar.properties.numericType == .decimal,
               let value = scalar.properties.numericValue {
                result.append(String(Int(value)))
            } else if keepSpaces, character == " " {
                result.append(" ")
            }
        }
        return result
    }

    /// What someone types when the camera won't do: the code the Mac shows under its QR,
    /// the address beside it, and — only if it isn't `defaultPort` — a port.
    ///
    /// Held to the same rules as a scan, because it ends at the same `/buddy/pair`. The
    /// code may carry the space the Mac shows it with, or a dash; nothing else. A link is
    /// refused here: see `pasted(_:)`.
    public static func typed(
        address: String, code: String, port: String = ""
    ) throws -> PairingInvite {
        if isLink(address) { throw ParseError.linkNotTyped }
        var host = address.trimmingCharacters(in: .whitespacesAndNewlines)
        var portText = port.trimmingCharacters(in: .whitespacesAndNewlines)
        if host.hasPrefix("["), let close = host.range(of: "]:") {
            // `[fd7a:115c:a1e0::9]:8788`: the only way to put a port after IPv6.
            portText = host[close.upperBound...].trimmingCharacters(in: .whitespaces)
            host = String(host[host.index(after: host.startIndex)..<close.lowerBound])
        } else if host.filter({ $0 == ":" }).count == 1, let colon = host.firstIndex(of: ":") {
            // `100.64.0.9:8788`, as the address is often written. One colon only: a bare
            // IPv6 address is all colons and is never split.
            portText = host[host.index(after: colon)...]
                .trimmingCharacters(in: .whitespaces)
            host = host[..<colon].trimmingCharacters(in: .whitespaces)
        }
        host = host.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        guard !host.isEmpty else { throw ParseError.noAddress }
        guard TailnetHost.isAllowed(host) else { throw ParseError.hostNotOnTailnet(host) }
        let portNumber: Int
        if portText.isEmpty {
            portNumber = defaultPort
        } else {
            guard let value = Int(portText), (1...65535).contains(value) else {
                throw ParseError.badPort(portText)
            }
            portNumber = value
        }
        let digits = code.filter { !$0.isWhitespace && $0 != "-" }
        guard digits.count == 6, digits.allSatisfy(\.isASCII), digits.allSatisfy(\.isNumber)
        else {
            throw ParseError.badCode(code.trimmingCharacters(in: .whitespacesAndNewlines))
        }
        return PairingInvite(host: host, port: portNumber, code: digits)
    }

    /// Spends the code at `POST /buddy/pair` — with no token, because the code is the
    /// credential — and returns where and how this device talks to the Mac from now on.
    /// However the invite arrived, this is the one way it becomes a token.
    public func exchange(
        deviceName: String, platform: String,
        client: @Sendable (ServerConfig) -> any ControlTransport = { ControlClient(config: $0) }
    ) async throws -> ServerConfig {
        guard TailnetHost.isAllowed(host) else {
            throw TransportError.forbidden(TailnetHost.explanation)
        }
        let paired = try await client(ServerConfig(host: host, port: port, token: ""))
            .pair(code: code, deviceName: deviceName, platform: platform)
        return ServerConfig(
            host: host, port: paired.port, token: paired.token,
            macName: paired.macName, deviceID: paired.deviceID,
            scope: BuddyAPI.DeviceScope(wire: paired.scope)
        )
    }

    /// The other direction, so the Mac's format has one definition in this repo too and
    /// the tests can round-trip it.
    public var url: URL? {
        var components = URLComponents()
        components.scheme = "siliconbuddy"
        components.host = "pair"
        components.queryItems = [
            URLQueryItem(name: "host", value: host),
            URLQueryItem(name: "port", value: String(port)),
            URLQueryItem(name: "code", value: code),
        ]
        return components.url
    }
}
