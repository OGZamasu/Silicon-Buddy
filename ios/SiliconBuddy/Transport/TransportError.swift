import Foundation

/// Why a call to the Mac did not answer.
///
/// The distinctions here are the ones a person can act on. "Unreachable" means turn
/// Tailscale on; "the app is not running" means open Silicon Optimizer; "unauthorized"
/// means pair again. Collapsing those into one "network error" would make the app
/// useless exactly when something is wrong.
public enum TransportError: Error, Equatable, Sendable {
    /// Nothing at that address answered: wrong host, tailnet down, Mac asleep.
    case unreachable(String)
    /// The address answered but nothing is listening on the port: the Mac is up,
    /// Silicon Optimizer is not.
    case appNotRunning
    /// The request took too long to get anywhere.
    case timedOut
    /// 401. The token is wrong, or the Mac revoked this device.
    case unauthorized
    /// 404 on a route this app knows about — which for the M0 routes means "that Mac
    /// has not shipped them yet", and the caller should fall back rather than fail.
    case routeUnavailable(String)
    /// 400 with the Mac's own explanation; the most common one is "no model is loaded".
    case badRequest(String)
    /// 429: the Mac is already doing as much of this as it will do at once.
    case busy(String)
    /// Any other HTTP status, with whatever the Mac said.
    case server(status: Int, message: String)
    /// The body did not match the contract.
    case decoding(String)
    /// No Mac has been paired yet.
    case notConfigured
    /// The caller went away.
    case cancelled
}

extension TransportError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .unreachable(let host):
            "Can't reach \(host). Check that Tailscale is on and the Mac is awake."
        case .appNotRunning:
            "The Mac answered, but Silicon Optimizer isn't running on it."
        case .timedOut:
            "The Mac took too long to answer."
        case .unauthorized:
            "This device isn't paired any more. Pair it again from the Mac."
        case .routeUnavailable(let path):
            "This Mac doesn't have \(path) yet."
        case .badRequest(let message):
            message
        case .busy(let message):
            message
        case .server(let status, let message):
            message.isEmpty ? "The Mac returned an error (\(status))." : message
        case .decoding(let detail):
            "The Mac's answer didn't match what this app expects: \(detail)"
        case .notConfigured:
            "No Mac is paired yet."
        case .cancelled:
            "Cancelled."
        }
    }

    /// What to offer the person, when there is something to offer.
    public var recoverySuggestion: String? {
        switch self {
        case .unreachable: "Open Tailscale, then pull to refresh."
        case .appNotRunning: "Open Silicon Optimizer on the Mac."
        case .unauthorized: "Settings → Silicon Buddy → Pair a device."
        case .badRequest: "Load a model from the Models tab."
        default: nil
        }
    }

    /// True when a caller should quietly use its fallback instead of showing this.
    public var isMissingRoute: Bool {
        if case .routeUnavailable = self { return true }
        return false
    }
}

extension TransportError {
    /// Maps what URLSession and the server say into the vocabulary above.
    ///
    /// `NSURLErrorCannotConnectToHost` is the interesting one: the TCP connection was
    /// actively refused, which means the host is there and the port is not — the Mac is
    /// awake and the app is closed.
    public static func from(urlError error: URLError) -> TransportError {
        switch error.code {
        case .cancelled:
            return .cancelled
        case .timedOut:
            return .timedOut
        case .cannotConnectToHost:
            return .appNotRunning
        case .cannotFindHost, .dnsLookupFailed, .notConnectedToInternet,
             .networkConnectionLost, .internationalRoamingOff, .dataNotAllowed,
             .secureConnectionFailed, .resourceUnavailable:
            let host = error.failingURL?.host ?? "the Mac"
            return .unreachable(host)
        default:
            let host = error.failingURL?.host ?? "the Mac"
            return .unreachable(host)
        }
    }

    /// Maps an HTTP status plus the Mac's error body.
    public static func from(status: Int, body: Data, path: String) -> TransportError? {
        guard !(200..<300).contains(status) else { return nil }
        let message = (try? JSONDecoder().decode(ControlAPI.ErrorResponse.self, from: body))?.error
            ?? String(data: body, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
            ?? ""
        switch status {
        case 401, 403: return .unauthorized
        case 404: return .routeUnavailable(path)
        case 400: return .badRequest(message.isEmpty ? "The Mac rejected the request." : message)
        case 429: return .busy(message.isEmpty ? "The Mac is busy." : message)
        default: return .server(status: status, message: message)
        }
    }
}
