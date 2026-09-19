import Foundation
import SwiftUI
import UIKit

/// The one piece of state every screen needs: which Mac we are talking to, whether it is
/// answering, and what this device was allowed to do when it was paired.
@MainActor
@Observable
public final class AppModel {
    public private(set) var config: ServerConfig?
    public private(set) var reachability: Reachability = .unknown
    /// What the Mac last said about itself, kept so the connection row can name it.
    public private(set) var status: ControlAPI.Status?

    /// Bumped whenever the Mac changes. Screens watch it and throw away what they were
    /// showing: a dashboard still displaying the last Mac's memory after a re-pair is
    /// not a stale reading, it is the wrong machine.
    public private(set) var connectionGeneration = 0

    /// The one `GET /events` stream, shared by every screen that wants it.
    public let events = EventFeed()

    /// An invite that arrived from a QR or a link and has not been agreed to yet.
    /// Nothing is dialled and nothing is stored until the person says yes.
    public var pendingInvite: PairingInvite?

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

    /// Whether this device may change what the Mac is running.
    public var canControl: Bool { config?.canControl ?? false }

    public var scope: BuddyAPI.DeviceScope { config?.scope ?? .full }

    /// What this device calls itself when it asks the Mac to pair.
    public static var deviceName: String {
        UIDevice.current.name
    }

    public static var platform: String {
        UIDevice.current.userInterfaceIdiom == .pad ? "ipados" : "ios"
    }

    // MARK: - Pairing

    /// Trades an invite the person has agreed to for a per-device token.
    ///
    /// Only ever called from a confirmation the person tapped. Scanning a code, or
    /// following a link, sets `pendingInvite`; this is the other side of that.
    public func pair(with invite: PairingInvite) async throws {
        guard TailnetHost.isAllowed(invite.host) else {
            throw TransportError.forbidden(TailnetHost.explanation)
        }
        let probe = ControlClient(config: ServerConfig(host: invite.host, port: invite.port, token: ""))
        let paired = try await probe.pair(
            code: invite.code, deviceName: Self.deviceName, platform: Self.platform
        )
        try connect(
            ServerConfig(
                host: invite.host, port: paired.port, token: paired.token,
                macName: paired.macName, deviceID: paired.deviceID,
                scope: BuddyAPI.DeviceScope(wire: paired.scope)
            )
        )
        await refreshReachability()
    }

    /// The advanced form: host, port and the token from the Mac's control.json.
    public func connect(_ newConfig: ServerConfig) throws {
        guard TailnetHost.isAllowed(newConfig.host) else {
            throw TransportError.forbidden(TailnetHost.explanation)
        }
        try tokens.save(newConfig.token)
        defaults.set(newConfig.host, forKey: Keys.host)
        defaults.set(newConfig.port, forKey: Keys.port)
        defaults.set(newConfig.macName, forKey: Keys.macName)
        defaults.set(newConfig.deviceID, forKey: Keys.deviceID)
        defaults.set(newConfig.scope.rawValue, forKey: Keys.scope)
        config = newConfig
        status = nil
        reachability = .unknown
        events.clear()
        connectionGeneration += 1
    }

    public func forget() {
        tokens.delete()
        for key in [Keys.host, Keys.port, Keys.macName, Keys.deviceID, Keys.scope] {
            defaults.removeObject(forKey: key)
        }
        config = nil
        status = nil
        reachability = .unknown
        events.clear()
        connectionGeneration += 1
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
        static let scope = "buddy.scope"
    }

    private static func loadConfig(defaults: UserDefaults, tokens: TokenStore) -> ServerConfig? {
        guard let host = defaults.string(forKey: Keys.host),
              case let port = defaults.integer(forKey: Keys.port), port > 0,
              let token = tokens.read()
        else { return nil }
        return ServerConfig(
            host: host, port: port, token: token,
            macName: defaults.string(forKey: Keys.macName),
            deviceID: defaults.string(forKey: Keys.deviceID),
            scope: BuddyAPI.DeviceScope(wire: defaults.string(forKey: Keys.scope))
        )
    }
}
