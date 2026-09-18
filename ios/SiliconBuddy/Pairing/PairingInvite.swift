import Foundation

/// What the QR on the Mac's screen encodes:
/// `siliconbuddy://pair?host=<tailnet address>&port=<port>&code=<6 digits>`.
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
