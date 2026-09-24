import Foundation

/// Why a call to the Mac did not answer.
///
/// The distinctions here are the ones a person can act on. "Unreachable" means turn
/// Tailscale on; "connection refused" means check the listener and port; "unauthorized"
/// means pair again. Collapsing those into one "network error" would make the app
/// useless exactly when something is wrong.
public enum TransportError: Error, Equatable, Sendable {
    /// Nothing at that address answered: wrong host, tailnet down, Mac asleep.
    case unreachable(String)
    /// The connection was refused. The app may be closed, or the address/port may be wrong.
    case appNotRunning
    /// The request took too long to get anywhere.
    case timedOut
    /// 401. The token is wrong, or the Mac revoked this device.
    case unauthorized
    /// 403. The token is good but this device may not do that — a chat-scope device
    /// asking to load a model, or a wrong pairing code. The Mac's own words matter
    /// here, because "not allowed" and "no longer paired" call for different actions.
    case forbidden(String)
    /// 409. The Mac is already answering in that conversation.
    case conflict(String)
    /// 413. More than a device may send: 4 MiB a body, about 1.5 MB an image.
    case tooLarge(String)
    /// 411. The Mac will not read a chunked body. This one is ours to fix, not the
    /// owner's: every request this app sends sets a Content-Length.
    case chunkedNotAccepted(String)
    /// 404 on a route that exists, about a thing that does not — a conversation that
    /// was deleted on the Mac, say. Not the same as "this Mac is too old".
    case notFound(String)
    /// 404 on a route this app knows about — which for the M0 routes means "that Mac
    /// has not shipped them yet", and the caller should fall back rather than fail.
    case routeUnavailable(String)
    /// 400 with the Mac's own explanation; the most common one is "no model is loaded".
    case badRequest(String)
    /// 415: not a picture or a clip the Mac will keep. Only `POST /uploads` answers it.
    case unsupportedMedia(String)
    /// 416: a `Range` outside the file. Only `GET /media/{id}` answers it.
    case rangeNotSatisfiable(String)
    /// 429: the Mac is already doing as much of this as it will do at once.
    case busy(String)
    /// 503: the Mac cannot reach something it needs for this right now — the drive its
    /// model library lives on, for the models it keeps for the phone. Its sentence names it.
    case unavailable(String)
    /// 507: the Mac has no room for what was asked, said before anything moved.
    case insufficientStorage(String)
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
            "Nothing is listening at this address and port. Open Silicon Optimizer and check the address and port."
        case .timedOut:
            "The Mac took too long to answer."
        case .unauthorized:
            "This device isn't paired any more. Pair it again from the Mac."
        case .forbidden(let message):
            message.isEmpty ? "The Mac wouldn't allow that." : message
        case .conflict(let message):
            message.isEmpty
                ? "That conversation is still being answered." : message
        case .tooLarge(let message):
            message.isEmpty ? "That was too large to send." : message
        case .chunkedNotAccepted(let message):
            message.isEmpty ? "The Mac wouldn't read that request." : message
        case .notFound(let message):
            message.isEmpty ? "The Mac doesn't have that any more." : message
        case .routeUnavailable(let path):
            "This Mac doesn't have \(path) yet."
        case .badRequest(let message):
            message
        case .unsupportedMedia(let message):
            message.isEmpty ? "That file is not one this Mac will keep." : message
        case .rangeNotSatisfiable(let message):
            message.isEmpty ? "That part of the file is not there any more." : message
        case .busy(let message):
            message
        case .unavailable(let message):
            message.isEmpty ? "The Mac can't do that right now." : message
        case .insufficientStorage(let message):
            message.isEmpty ? "The Mac has no room for that." : message
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
        case .appNotRunning: "Open Silicon Optimizer and check the address and port."
        case .unauthorized: "Settings → Silicon Buddy → Pair a device."
        case .forbidden: "Pair again from the Mac with full control."
        case .conflict: "Wait for the answer, or start another conversation."
        case .tooLarge: "Send fewer or smaller pictures."
        case .notFound: "It may have been deleted on the Mac."
        case .badRequest: "Load a model from the Models tab."
        case .insufficientStorage: "Free some space on the Mac, then try again."
        default: nil
        }
    }

    /// True when a caller should quietly use its fallback instead of showing this.
    public var isMissingRoute: Bool {
        if case .routeUnavailable = self { return true }
        return false
    }

    /// A 404 about a thing rather than a route: the same status, a different meaning,
    /// and only the caller knows which it asked for.
    public var asNotFound: TransportError {
        if case .routeUnavailable(let path) = self {
            return .notFound("The Mac has nothing at \(path) any more.")
        }
        return self
    }

    /// True when the Mac refused because of what this device is allowed to do, rather
    /// than because it does not know this device.
    public var isForbidden: Bool {
        if case .forbidden = self { return true }
        return false
    }
}

extension TransportError {
    /// Maps what URLSession and the server say into the vocabulary above.
    ///
    /// `NSURLErrorCannotConnectToHost` is the interesting one: the TCP connection was
    /// refused. That alone cannot distinguish a closed app from the wrong address or port.
    public static func from(urlError error: URLError) -> TransportError {
        switch error.code {
        case .cancelled:
            return .cancelled
        case .timedOut:
            return .timedOut
        case .cannotConnectToHost:
            return .appNotRunning
        case .appTransportSecurityRequiresSecureConnection:
            // Only reachable if the app's ATS exception is ever narrowed: it means the
            // system refused plain HTTP to that address, not that the Mac is away.
            return .forbidden(
                "iOS refused a plain HTTP connection to that address. "
                    + "Use the Mac's Tailscale name or address."
            )
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
        case 401: return .unauthorized
        case 403: return .forbidden(message)
        case 404: return .routeUnavailable(path)
        case 409: return .conflict(message)
        case 411: return .chunkedNotAccepted(message)
        case 413: return .tooLarge(message)
        case 400: return .badRequest(message.isEmpty ? "The Mac rejected the request." : message)
        case 415: return .unsupportedMedia(message)
        case 416: return .rangeNotSatisfiable(message)
        case 429: return .busy(message.isEmpty ? "The Mac is busy." : message)
        case 503: return .unavailable(message)
        case 507: return .insufficientStorage(message)
        default: return .server(status: status, message: message)
        }
    }
}
