import Foundation
import SwiftUI
import UIKit

/// A composer opened from outside the app, with text already in it.
public struct ComposeRequest: Identifiable, Sendable, Equatable {
    public let id = UUID()
    public var text: String?
    public init(text: String?) { self.text = text }
}

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

    /// Something a widget, a link or a Shortcut asked the composer to be opened with.
    ///
    /// Filled in, never sent: a URL can be opened by anything on the phone, so the most
    /// a link may do is type into the box. The person presses send.
    public var pendingCompose: ComposeRequest?

    /// Whether an answer is read out loud after a spoken question.
    public var speaksReplies: Bool {
        didSet { defaults.set(speaksReplies, forKey: SharedConfiguration.Keys.speaksReplies) }
    }

    private let defaults: UserDefaults
    private let tokens: TokenStore

    /// The shared container by default, not this process's own: a widget, a share
    /// sheet and a Shortcuts action all have to find the same Mac, and they cannot read
    /// the app's private preferences. Tests pass their own suite.
    public init(defaults: UserDefaults = BuddyShared.defaults, tokens: TokenStore = TokenStore()) {
        self.defaults = defaults
        self.tokens = tokens
        // Before anything is read out of it: what the shared container holds is the
        // address of somebody's Mac and the last thing it said, and none of that
        // belongs in a backup. Android's equivalent is data_extraction_rules.xml.
        BuddyShared.excludeContainerFromBackup()
        self.config = Self.loadConfig(defaults: defaults, tokens: tokens)
        // On by default: a spoken question that answers silently is a worse experience
        // than one that answers out loud, and the toggle is one tap away in Settings.
        self.speaksReplies = defaults.object(forKey: SharedConfiguration.Keys.speaksReplies)
            as? Bool ?? true
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
    /// Only ever called from something the person tapped: the confirmation a scanned
    /// code or a followed link gets (they set `pendingInvite`), or Pair under a code they
    /// typed themselves.
    public func pair(with invite: PairingInvite) async throws {
        try connect(
            try await invite.exchange(deviceName: Self.deviceName, platform: Self.platform)
        )
        await refreshReachability()
    }

    /// A link pasted into the code form, held for the confirmation a tapped link gets —
    /// never spent by that form's own Pair button, because whoever made the link chose its
    /// host. False when `text` is not a link at all; throws for a link that doesn't parse,
    /// including one whose host is off the tailnet, and then holds nothing.
    public func holdPastedLink(_ text: String) throws -> Bool {
        guard let invite = try PairingInvite.pasted(text) else { return false }
        pendingInvite = invite
        return true
    }

    /// Stores a Mac this device can already talk to: the end of `pair(with:)`, and the
    /// Developer form's host, port and control.json token, which only the Simulator can use.
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
        // A widget showing the last Mac's model after a re-pair would be showing the
        // wrong machine, so the snapshot goes with the pairing.
        SnapshotStore.clear(from: defaults)
        SnapshotStore.note(
            status: ControlAPI.Status(state: "Paired"),
            macName: newConfig.macName, to: defaults
        )
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
        SnapshotStore.clear(from: defaults)
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
            if let status {
                SnapshotStore.note(status: status, macName: config?.macName, to: defaults)
            }
        }
    }

    /// The preferences this Mac's facts are filed under, so the screens that learn
    /// something — the dashboard, the event feed — can leave it where a widget looks.
    public var sharedDefaults: UserDefaults { defaults }

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

    /// The same names `SharedConfiguration` reads out of the shared container. One
    /// definition, because two would drift and a widget would go blank.
    private enum Keys {
        static let host = SharedConfiguration.Keys.host
        static let port = SharedConfiguration.Keys.port
        static let macName = SharedConfiguration.Keys.macName
        static let deviceID = SharedConfiguration.Keys.deviceID
        static let scope = SharedConfiguration.Keys.scope
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
