import Foundation

/// The routes the Mac grows in M0, which this app already speaks.
///
/// Every one of them is optional at runtime: a Mac that has not shipped M0 answers 404,
/// and the client falls back — streaming to plain `POST /chat`, conversations to the
/// on-device store, pairing to the advanced form. Nothing here may be required for the
/// app to work.
public enum BuddyAPI {

    // MARK: - Pairing

    /// `POST /buddy/pair`. The code comes from the QR the Mac shows.
    public struct PairRequest: Codable, Sendable, Equatable {
        public var code: String
        public var deviceName: String
        public var platform: String

        public init(code: String, deviceName: String, platform: String) {
            self.code = code
            self.deviceName = deviceName
            self.platform = platform
        }
    }

    public struct PairResponse: Codable, Sendable, Equatable {
        public var deviceID: String
        public var token: String
        public var macName: String
        /// The port to keep talking to, which need not be the one the QR carried.
        public var port: Int
        /// What the owner granted this device when they put the code on screen.
        /// Absent on an early build of the Mac; treat that as full, which is what
        /// it was before scopes existed.
        public var scope: String?

        public init(
            deviceID: String, token: String, macName: String, port: Int, scope: String? = nil
        ) {
            self.deviceID = deviceID
            self.token = token
            self.macName = macName
            self.port = port
            self.scope = scope
        }
    }

    /// What a device may do. The Mac decides this at pairing; the app's job is to stop
    /// offering what this device cannot have, rather than to let it be refused later.
    public enum DeviceScope: String, Codable, Sendable, CaseIterable {
        /// Everything: loading, installing, rendering, the device list.
        case full
        /// Reading and asking: status, metrics, catalog, chat and conversations.
        case chat

        public init(wire: String?) {
            self = DeviceScope(rawValue: wire ?? "") ?? .full
        }

        /// Whether this device may change what the Mac is running.
        public var canControl: Bool { self == .full }

        public var explanation: String {
            switch self {
            case .full: "This device has full control of the Mac."
            case .chat:
                "This device is paired for chat only. Loading, installing and rendering "
                    + "are hidden. Pair it again with full control from Settings → "
                    + "Silicon Buddy on the Mac."
            }
        }
    }

    /// One row of `GET /buddy/devices`. The Mac's own token can read this; a device
    /// cannot, so the app only ever decodes it in tests today.
    public struct PairedDevice: Codable, Sendable, Equatable, Identifiable {
        public var id: String
        public var name: String
        public var platform: String
        public var scope: String
        public var pairedAt: Date
        public var lastSeen: Date?

        public init(
            id: String, name: String, platform: String, scope: String,
            pairedAt: Date, lastSeen: Date?
        ) {
            self.id = id
            self.name = name
            self.platform = platform
            self.scope = scope
            self.pairedAt = pairedAt
            self.lastSeen = lastSeen
        }
    }

    // MARK: - Conversations

    public struct ConversationSummary: Codable, Sendable, Equatable, Identifiable {
        public var id: String
        public var title: String
        public var updatedAt: Date
        public var messageCount: Int

        public init(id: String, title: String, updatedAt: Date, messageCount: Int) {
            self.id = id
            self.title = title
            self.updatedAt = updatedAt
            self.messageCount = messageCount
        }
    }

    public struct NewConversation: Codable, Sendable, Equatable {
        public var title: String?
        public init(title: String? = nil) { self.title = title }
    }

    public struct StoredMessage: Codable, Sendable, Equatable {
        public var role: String
        public var content: String
        public var createdAt: Date
        /// Base64 `data:` URLs, when the phone attached pictures. Absent on a Mac that
        /// does not keep them.
        public var images: [String]?
        /// The model's thinking, when it produced any.
        public var reasoning: String?

        public init(
            role: String, content: String, createdAt: Date,
            images: [String]? = nil, reasoning: String? = nil
        ) {
            self.role = role
            self.content = content
            self.createdAt = createdAt
            self.images = images
            self.reasoning = reasoning
        }
    }

    public struct ConversationDetail: Codable, Sendable, Equatable {
        public var id: String
        public var title: String
        public var updatedAt: Date
        /// True while the Mac is still answering the last message in this conversation.
        /// Sending another one would be answered 409, so the composer says so instead.
        public var isGenerating: Bool
        public var messages: [StoredMessage]

        public init(
            id: String, title: String, updatedAt: Date,
            isGenerating: Bool = false, messages: [StoredMessage]
        ) {
            self.id = id
            self.title = title
            self.updatedAt = updatedAt
            self.isGenerating = isGenerating
            self.messages = messages
        }
    }

    /// `POST /conversations/{id}/messages`. One message, not a transcript: the Mac
    /// already has the history, which is the whole point of storing it there.
    public struct NewMessageRequest: Codable, Sendable, Equatable {
        public var content: String
        /// Base64 `data:` URLs. Empty rather than absent, as the Mac decodes it.
        public var images: [String]
        public var temperature: Double?
        public var maxTokens: Int?

        public init(
            content: String, images: [String] = [],
            temperature: Double? = nil, maxTokens: Int? = nil
        ) {
            self.content = content
            self.images = images
            self.temperature = temperature
            self.maxTokens = maxTokens
        }
    }

    // MARK: - Streams

    /// The events `POST /chat/stream` sends, one per SSE `event:` name.
    public enum ChatStreamEvent: Sendable, Equatable {
        /// A piece of the answer.
        case token(String)
        /// A piece of the model's thinking.
        case reasoning(String)
        /// The metrics that a non-streaming `/chat` would have returned in one lump.
        case finished(ChatMetrics)
        /// The Mac gave up on this generation.
        case failed(String)
    }

    /// A `token` or `reasoning` event: one piece of text.
    public struct TokenEvent: Codable, Sendable, Equatable {
        public var text: String
        public init(text: String) { self.text = text }
    }

    public struct ChatMetrics: Codable, Sendable, Equatable {
        public var promptTokens: Int
        public var generatedTokens: Int
        public var tokensPerSecond: Double
        /// How long the Mac took to say anything at all. On a phone this is the number
        /// that decides whether the app feels broken, so it is worth keeping.
        public var timeToFirstToken: Double?

        public init(
            promptTokens: Int, generatedTokens: Int, tokensPerSecond: Double,
            timeToFirstToken: Double? = nil
        ) {
            self.promptTokens = promptTokens
            self.generatedTokens = generatedTokens
            self.tokensPerSecond = tokensPerSecond
            self.timeToFirstToken = timeToFirstToken
        }
    }

    /// `GET /events`: everything the Mac wants to push without being asked.
    public enum ServerEvent: Sendable, Equatable {
        case status(ControlAPI.Status)
        case download(DownloadProgress)
        case job(JobProgress)
        /// The keep-alive, with the Mac's clock when it sent one.
        case heartbeat(Date?)
    }

    /// A model coming down, as `GET /events` reports it.
    public struct DownloadProgress: Codable, Sendable, Equatable, Identifiable {
        /// The model id, which is what the Models list matches rows on.
        public var id: String
        public var name: String
        public var bytesReceived: Int64
        public var bytesExpected: Int64?
        public var bytesPerSecond: Double?
        /// The Mac's own 0–1, which knows about resumed downloads and this does not.
        public var fraction: Double?

        public init(
            id: String, name: String, bytesReceived: Int64, bytesExpected: Int64?,
            bytesPerSecond: Double? = nil, fraction: Double? = nil
        ) {
            self.id = id
            self.name = name
            self.bytesReceived = bytesReceived
            self.bytesExpected = bytesExpected
            self.bytesPerSecond = bytesPerSecond
            self.fraction = fraction
        }

        /// The fraction the Mac gave, or one worked out from the byte counts.
        public var progress: Double? {
            if let fraction { return min(1, max(0, fraction)) }
            guard let bytesExpected, bytesExpected > 0 else { return nil }
            return min(1, Double(bytesReceived) / Double(bytesExpected))
        }
    }

    /// A render in flight: video, image or mesh.
    public struct JobProgress: Codable, Sendable, Equatable, Identifiable {
        public var id: String
        public var kind: String
        public var status: String
        public var fraction: Double?
        public var title: String?

        public init(
            id: String, kind: String, status: String,
            fraction: Double? = nil, title: String? = nil
        ) {
            self.id = id
            self.kind = kind
            self.status = status
            self.fraction = fraction
            self.title = title
        }
    }

    /// The Mac's keep-alive. Carries the time so a client can notice a stream that is
    /// open but asleep.
    public struct Heartbeat: Codable, Sendable, Equatable {
        public var at: Date?
        public init(at: Date? = nil) { self.at = at }
    }
}
