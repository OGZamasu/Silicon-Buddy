import Foundation

/// Everything `siliconbuddy://` can mean.
///
/// M1 had one: a pairing code. M2 adds two more, because a widget and a share sheet
/// need a way to hand the app a half-written message. All three are requests rather
/// than instructions — anything on the phone can open a URL in this app, so a link may
/// fill the composer in and may never send it. The person presses send.
public enum BuddyLink: Sendable, Equatable {

    /// `siliconbuddy://pair?host&port&code`
    case pair(PairingInvite)
    /// `siliconbuddy://ask` — open the composer, optionally with something in it.
    case compose(String?)
    /// `siliconbuddy://conversation?id=…` — open a transcript.
    case conversation(String)

    public enum ParseError: Error, Equatable, LocalizedError {
        case notAURL
        case wrongScheme(String?)
        case unknownAction(String?)
        case pairing(PairingInvite.ParseError)

        public var errorDescription: String? {
            switch self {
            case .notAURL: "That isn't a Silicon Buddy link."
            case .wrongScheme(let scheme):
                "That link is for \(scheme ?? "something else"), not Silicon Buddy."
            case .unknownAction(let action):
                "Silicon Buddy doesn't know how to \(action ?? "do that")."
            case .pairing(let error):
                error.errorDescription ?? "That isn't a Silicon Buddy code."
            }
        }
    }

    /// The most a link may put in the composer.
    ///
    /// A URL can be opened by anything — a web page, a message, a QR on a wall — and a
    /// megabyte of text arriving in the box would be a denial of service dressed as a
    /// shortcut. Long enough for a paragraph somebody meant to send.
    public static let maximumComposedCharacters = 4_000

    public static func parse(_ text: String) throws -> BuddyLink {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let components = URLComponents(string: trimmed) else {
            throw ParseError.notAURL
        }
        guard components.scheme?.lowercased() == "siliconbuddy" else {
            throw ParseError.wrongScheme(components.scheme)
        }
        // The same three spellings M1 accepted: the action can be the host or the path.
        let hostAction = components.host.flatMap { $0.isEmpty ? nil : $0.lowercased() }
        let pathAction = components.path
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            .lowercased()
        let action = hostAction ?? pathAction

        func value(_ name: String) -> String? {
            components.queryItems?
                .first { $0.name == name }?.value?
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }

        switch action {
        case "pair":
            do {
                return .pair(try PairingInvite.parse(trimmed))
            } catch let error as PairingInvite.ParseError {
                throw ParseError.pairing(error)
            }
        case "ask", "compose":
            let text = value("text").flatMap { $0.isEmpty ? nil : $0 }
            return .compose(text.map { String($0.prefix(maximumComposedCharacters)) })
        case "conversation":
            guard let id = value("id"), !id.isEmpty else { throw ParseError.unknownAction(action) }
            return .conversation(id)
        default:
            throw ParseError.unknownAction(action)
        }
    }

    /// The link the widget's "Ask" button opens.
    public static func composeURL(text: String? = nil) -> URL? {
        var components = URLComponents()
        components.scheme = "siliconbuddy"
        components.host = "ask"
        if let text, !text.isEmpty {
            components.queryItems = [URLQueryItem(name: "text", value: text)]
        }
        return components.url
    }
}
