import Foundation
import SwiftUI
import UIKit

/// The one piece of state every screen needs: which Mac we are talking to, whether it is
/// answering, and which of the newer routes it turned out to have.
@MainActor
@Observable
public final class AppModel {
    public private(set) var config: ServerConfig?
    public private(set) var reachability: Reachability = .unknown
    /// What the Mac last said about itself, kept so the connection row can name it.
    public private(set) var status: ControlAPI.Status?

    /// Which M0 routes this Mac turned out to have. Discovered by using them, never
    /// assumed: an older Mac 404s and the app quietly uses its fallback.
    public var supportsStreaming = true
    public var supportsRemoteConversations = true

    private let defaults: UserDefaults
    private let tokens: TokenStore

    public init(defaults: UserDefaults = .standard, tokens: TokenStore = TokenStore()) {
        self.defaults = defaults
        self.tokens = tokens
        self.config = Self.loadConfig(defaults: defaults, tokens: tokens)
    }

    /// The client for the paired Mac, or nil when there is none.
    public var transport: (any ControlTransport)? {
        config.map { ControlClient(config: $0) }
    }

    public var isPaired: Bool { config != nil }

    /// What this device calls itself when it asks the Mac to pair.
    public static var deviceName: String {
        UIDevice.current.name
    }

    public static var platform: String {
        UIDevice.current.userInterfaceIdiom == .pad ? "ipados" : "ios"
    }

    // MARK: - Pairing

    /// Trades a scanned invite for a per-device token.
    ///
    /// Falls back to the invite's own code as a bearer token when the Mac has no
    /// `/buddy/pair` yet — that is the advanced path in disguise, and it is what makes
    /// this app testable against today's Mac.
    public func pair(with invite: PairingInvite) async throws {
        let probe = ControlClient(config: ServerConfig(host: invite.host, port: invite.port, token: ""))
        let paired = try await probe.pair(
            code: invite.code, deviceName: Self.deviceName, platform: Self.platform
        )
        try connect(
            ServerConfig(
                host: invite.host, port: paired.port, token: paired.token,
                macName: paired.macName, deviceID: paired.deviceID
            )
        )
        await refreshReachability()
    }

    /// The advanced form: host, port and the token from the Mac's control.json.
    public func connect(_ newConfig: ServerConfig) throws {
        try tokens.save(newConfig.token)
        defaults.set(newConfig.host, forKey: Keys.host)
        defaults.set(newConfig.port, forKey: Keys.port)
        defaults.set(newConfig.macName, forKey: Keys.macName)
        defaults.set(newConfig.deviceID, forKey: Keys.deviceID)
        config = newConfig
        reachability = .unknown
    }

    public func forget() {
        tokens.delete()
        for key in [Keys.host, Keys.port, Keys.macName, Keys.deviceID] {
            defaults.removeObject(forKey: key)
        }
        config = nil
        status = nil
        reachability = .unknown
    }

    // MARK: - Connection state

    public func refreshReachability() async {
        guard let transport else {
            reachability = .unknown
            return
        }
        reachability = .checking
        let result = await ConnectivityProbe(transport: transport).check()
        reachability = result
        if result.isReady {
            status = try? await transport.status()
            if let name = status?.loadedModelName ?? config?.macName {
                _ = name
            }
        }
    }

    /// Records the Mac's name once something has learned it.
    public func noteMacName(_ name: String) {
        guard var current = config, current.macName != name else { return }
        current.macName = name
        config = current
        defaults.set(name, forKey: Keys.macName)
    }

    public var macDisplayName: String {
        config?.macName ?? config?.host ?? "No Mac"
    }

    // MARK: - Storage

    private enum Keys {
        static let host = "buddy.host"
        static let port = "buddy.port"
        static let macName = "buddy.macName"
        static let deviceID = "buddy.deviceID"
    }

    private static func loadConfig(defaults: UserDefaults, tokens: TokenStore) -> ServerConfig? {
        guard let host = defaults.string(forKey: Keys.host),
              case let port = defaults.integer(forKey: Keys.port), port > 0,
              let token = tokens.read()
        else { return nil }
        return ServerConfig(
            host: host, port: port, token: token,
            macName: defaults.string(forKey: Keys.macName),
            deviceID: defaults.string(forKey: Keys.deviceID)
        )
    }
}
