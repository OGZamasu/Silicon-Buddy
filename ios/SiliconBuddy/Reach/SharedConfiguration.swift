import Foundation

/// The paired Mac, as any process in the family can read it.
///
/// `AppModel` owns this while the app is running; a widget, a share sheet or an intent
/// runs when the app does not, so each of them rebuilds a `ServerConfig` from the same
/// two places the app wrote it: the shared preferences and the shared Keychain.
public enum SharedConfiguration {

    public enum Keys {
        public static let host = "buddy.host"
        public static let port = "buddy.port"
        public static let macName = "buddy.macName"
        public static let deviceID = "buddy.deviceID"
        public static let scope = "buddy.scope"
        public static let snapshot = "buddy.snapshot"
        public static let quickPrompt = "buddy.quickPrompt"
        public static let quickAnswer = "buddy.quickAnswer"
        public static let quickAskedAt = "buddy.quickAskedAt"
        public static let speaksReplies = "buddy.speaksReplies"
    }

    /// The Mac this device is paired with, or nil when it is not paired — or when the
    /// app group is not shared, which from out here looks the same and is said so.
    public static func load(
        defaults: UserDefaults = BuddyShared.defaults,
        tokens: TokenStore = TokenStore()
    ) -> ServerConfig? {
        guard let host = defaults.string(forKey: Keys.host),
              case let port = defaults.integer(forKey: Keys.port), port > 0,
              let token = tokens.read(), !token.isEmpty
        else { return nil }
        // The same gate the app applies. A host that reached the shared container by
        // some other route still never gets dialled from an extension either.
        guard TailnetHost.isAllowed(host) else { return nil }
        return ServerConfig(
            host: host, port: port, token: token,
            macName: defaults.string(forKey: Keys.macName),
            deviceID: defaults.string(forKey: Keys.deviceID),
            scope: BuddyAPI.DeviceScope(wire: defaults.string(forKey: Keys.scope))
        )
    }

    /// A transport for the paired Mac, for the processes that have no `AppModel`.
    public static func transport(
        defaults: UserDefaults = BuddyShared.defaults,
        tokens: TokenStore = TokenStore()
    ) -> ControlClient? {
        load(defaults: defaults, tokens: tokens).map { ControlClient(config: $0) }
    }
}

/// The last thing worth showing without asking the Mac again.
///
/// A widget gets a few seconds and a small budget; a Lock Screen accessory gets less.
/// So the app leaves this behind every time it learns something, and the widget draws
/// it immediately and then refreshes in the background. Nothing here is a secret: the
/// model's name, the last question and the last answer, and when.
public struct BuddySnapshot: Codable, Sendable, Equatable {
    public var macName: String?
    public var state: String
    public var loadedModelName: String?
    public var loadedModelID: String?
    public var lastQuestion: String?
    public var lastAnswer: String?
    public var updatedAt: Date

    public init(
        macName: String? = nil, state: String = "Unknown",
        loadedModelName: String? = nil, loadedModelID: String? = nil,
        lastQuestion: String? = nil, lastAnswer: String? = nil,
        updatedAt: Date = Date()
    ) {
        self.macName = macName
        self.state = state
        self.loadedModelName = loadedModelName
        self.loadedModelID = loadedModelID
        self.lastQuestion = lastQuestion
        self.lastAnswer = lastAnswer
        self.updatedAt = updatedAt
    }

    /// What the widget puts on its one line when nothing is loaded.
    public var modelLine: String { BuddySnapshot.headline(loadedModelName) }

    /// A model name that fits on one line of a small widget.
    ///
    /// Names carry a parenthesised qualifier — "Qwen3 4B (MLX)", "Gemma 3 12B (it)" —
    /// and a widget two icons wide truncates mid-bracket to "Qwen3 4B (…", which reads
    /// like the name itself is broken. The qualifier is the least load-bearing part, so
    /// it is what goes first, and only when keeping it would not have fitted anyway.
    public static func headline(_ name: String?, limit: Int = 18) -> String {
        guard let name, !name.isEmpty else { return "Nothing loaded" }
        guard name.count > limit, let bracket = name.lastIndex(of: "(") else { return name }
        let withoutQualifier = name[..<bracket].trimmingCharacters(in: .whitespaces)
        return withoutQualifier.isEmpty ? name : withoutQualifier
    }

    /// An answer trimmed to what a widget can actually show. Cutting on a word boundary
    /// rather than mid-syllable is the difference between a summary and a glitch.
    public func answerPreview(limit: Int) -> String? {
        guard let lastAnswer else { return nil }
        return BuddySnapshot.trim(lastAnswer, to: limit)
    }

    public static func trim(_ text: String, to limit: Int) -> String {
        let flattened = text
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "  ", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard flattened.count > limit else { return flattened }
        let cut = flattened.prefix(limit)
        guard let space = cut.lastIndex(of: " "), space > cut.startIndex else {
            return String(cut) + "…"
        }
        return String(cut[..<space]) + "…"
    }
}

/// Where the snapshot lives, and the one place that decides when a widget is stale
/// enough to redraw.
public enum SnapshotStore {

    public static func read(from defaults: UserDefaults = BuddyShared.defaults) -> BuddySnapshot? {
        guard let data = defaults.data(forKey: SharedConfiguration.Keys.snapshot) else {
            return nil
        }
        return try? JSONDecoder.buddy.decode(BuddySnapshot.self, from: data)
    }

    public static func write(
        _ snapshot: BuddySnapshot, to defaults: UserDefaults = BuddyShared.defaults
    ) {
        guard let data = try? JSONEncoder.buddy.encode(snapshot) else { return }
        defaults.set(data, forKey: SharedConfiguration.Keys.snapshot)
    }

    /// Records what the Mac says it is running, keeping the last answer that is already
    /// there. Called from the dashboard and from the event feed, which learn this often.
    public static func note(
        status: ControlAPI.Status, macName: String?,
        to defaults: UserDefaults = BuddyShared.defaults
    ) {
        var snapshot = read(from: defaults) ?? BuddySnapshot()
        snapshot.state = status.state
        snapshot.loadedModelID = status.loadedModelID
        snapshot.loadedModelName = status.loadedModelName
        if let macName { snapshot.macName = macName }
        snapshot.updatedAt = Date()
        write(snapshot, to: defaults)
    }

    /// Records an exchange. Called when a reply finishes, wherever it finished — the
    /// chat screen, the share sheet, a widget's quick prompt or a Shortcuts action, so
    /// "the last answer" means the last one, not the last one from the app.
    public static func note(
        question: String, answer: String,
        to defaults: UserDefaults = BuddyShared.defaults
    ) {
        var snapshot = read(from: defaults) ?? BuddySnapshot()
        snapshot.lastQuestion = BuddySnapshot.trim(question, to: 240)
        snapshot.lastAnswer = answer
        snapshot.updatedAt = Date()
        write(snapshot, to: defaults)
    }

    /// Forgets everything about a Mac, the widget's own answer included.
    ///
    /// "Forget this Mac" has to mean it. The snapshot alone is not the whole of what
    /// this Mac left behind: the widget's preset answer is a reply from that Mac
    /// sitting on the Home Screen, and the preset question is a setting about it. A
    /// re-pair that left either in place would show the last Mac's words under the new
    /// one's name.
    public static func clear(from defaults: UserDefaults = BuddyShared.defaults) {
        for key in [
            SharedConfiguration.Keys.snapshot,
            SharedConfiguration.Keys.quickAnswer,
            SharedConfiguration.Keys.quickAskedAt,
            SharedConfiguration.Keys.quickPrompt,
        ] {
            defaults.removeObject(forKey: key)
        }
    }
}
