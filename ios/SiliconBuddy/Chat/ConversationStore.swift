import Foundation

/// A conversation as this app keeps it, whether the Mac is the one storing it or not.
public struct Conversation: Codable, Sendable, Equatable, Identifiable {
    public var id: String
    public var title: String
    public var updatedAt: Date
    public var messages: [ChatMessage]

    public init(
        id: String = UUID().uuidString, title: String = "New conversation",
        updatedAt: Date = Date(), messages: [ChatMessage] = []
    ) {
        self.id = id
        self.title = title
        self.updatedAt = updatedAt
        self.messages = messages
    }

    public var summary: BuddyAPI.ConversationSummary {
        BuddyAPI.ConversationSummary(
            id: id, title: title, updatedAt: updatedAt,
            messageCount: messages.filter { $0.role != .system }.count
        )
    }

    /// The first thing the person said, which makes a better title than "New conversation".
    public func titledFromFirstMessage() -> Conversation {
        guard title == "New conversation",
              let first = messages.first(where: { $0.role == .user })?.content,
              !first.isEmpty
        else { return self }
        var copy = self
        let trimmed = first.trimmingCharacters(in: .whitespacesAndNewlines)
        copy.title = String(trimmed.prefix(48)) + (trimmed.count > 48 ? "…" : "")
        return copy
    }
}

public struct ChatMessage: Codable, Sendable, Equatable, Identifiable {
    public enum Role: String, Codable, Sendable { case user, assistant, system }

    public var id: String
    public var role: Role
    public var content: String
    /// The model's thinking, shown collapsed.
    public var reasoning: String?
    /// Base64 `data:` URLs sent with the message.
    public var images: [String]
    public var createdAt: Date
    /// Set while a streamed reply is still arriving.
    public var isStreaming: Bool
    /// The Mac's own numbers for this reply.
    public var metrics: BuddyAPI.ChatMetrics?
    /// Set when the generation failed, with the Mac's words.
    public var failure: String?

    public init(
        id: String = UUID().uuidString, role: Role, content: String,
        reasoning: String? = nil, images: [String] = [], createdAt: Date = Date(),
        isStreaming: Bool = false, metrics: BuddyAPI.ChatMetrics? = nil,
        failure: String? = nil
    ) {
        self.id = id
        self.role = role
        self.content = content
        self.reasoning = reasoning
        self.images = images
        self.createdAt = createdAt
        self.isStreaming = isStreaming
        self.metrics = metrics
        self.failure = failure
    }

    public var wireMessage: ControlAPI.ChatRequest.Message {
        ControlAPI.ChatRequest.Message(role: role.rawValue, content: content, images: images)
    }
}

/// Conversations on the device.
///
/// This is the fallback for a Mac without `/conversations`, and it is also what makes
/// the app usable on a train: the transcript is here, not only there. One JSON file,
/// because a handful of conversations is not a database.
public actor ConversationStore {
    private let url: URL
    private var cache: [Conversation]?

    public init(directory: URL? = nil) {
        let base = directory ?? FileManager.default.urls(
            for: .applicationSupportDirectory, in: .userDomainMask
        )[0].appendingPathComponent("SiliconBuddy", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        self.url = base.appendingPathComponent("conversations.json")
    }

    public func all() -> [Conversation] {
        if let cache { return cache }
        guard let data = try? Data(contentsOf: url),
              let stored = try? JSONDecoder.buddy.decode([Conversation].self, from: data)
        else {
            cache = []
            return []
        }
        let sorted = stored.sorted { $0.updatedAt > $1.updatedAt }
        cache = sorted
        return sorted
    }

    public func conversation(id: String) -> Conversation? {
        all().first { $0.id == id }
    }

    @discardableResult
    public func save(_ conversation: Conversation) -> Conversation {
        var stored = all()
        var updated = conversation.titledFromFirstMessage()
        updated.updatedAt = Date()
        if let index = stored.firstIndex(where: { $0.id == updated.id }) {
            stored[index] = updated
        } else {
            stored.insert(updated, at: 0)
        }
        persist(stored.sorted { $0.updatedAt > $1.updatedAt })
        return updated
    }

    public func delete(id: String) {
        persist(all().filter { $0.id != id })
    }

    public func deleteAll() {
        persist([])
    }

    private func persist(_ conversations: [Conversation]) {
        cache = conversations
        guard let data = try? JSONEncoder.buddy.encode(conversations) else { return }
        try? data.write(to: url, options: .atomic)
    }
}
