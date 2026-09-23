import Foundation

/// Where the Mac is and how to prove we may talk to it.
///
/// The token never lives here at rest — it is read from the Keychain into this value for
/// the life of a request and is deliberately excluded from `description`.
public struct ServerConfig: Sendable, Equatable {
    public var host: String
    public var port: Int
    public var token: String
    /// What the Mac calls itself, once pairing or `/v1/node` has told us.
    public var macName: String?
    /// The id the Mac minted for this device at pairing, when it did.
    public var deviceID: String?
    /// What the owner granted this device. A Mac that predates scopes says nothing,
    /// and that means full — which is what it was.
    public var scope: BuddyAPI.DeviceScope

    public init(
        host: String, port: Int, token: String,
        macName: String? = nil, deviceID: String? = nil,
        scope: BuddyAPI.DeviceScope = .full
    ) {
        self.host = host
        self.port = port
        self.token = token
        self.macName = macName
        self.deviceID = deviceID
        self.scope = scope
    }

    /// Whether this device may change what the Mac is running, as opposed to asking it
    /// questions. Checked before an action is offered, not after it is refused.
    public var canControl: Bool { scope.canControl }

    public var baseURL: URL? {
        var components = URLComponents()
        components.scheme = "http"
        components.host = TailnetHost.forURL(host)
        components.port = port
        return components.url
    }

    /// Nil when the host cannot be put in a URL. It used to fall back to `file:///`, and a
    /// request sent there never answered.
    public func url(path: String, query: [URLQueryItem] = []) -> URL? {
        guard let baseURL, var components = URLComponents(
            url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false
        ) else { return nil }
        if !query.isEmpty { components.queryItems = query }
        return components.url
    }

    /// A one-line description for the connection row. Never includes the token.
    public var displayAddress: String { "\(TailnetHost.forURL(host)):\(port)" }
}

extension ServerConfig: CustomStringConvertible {
    public var description: String { "ServerConfig(\(displayAddress), token: <redacted>)" }
}
