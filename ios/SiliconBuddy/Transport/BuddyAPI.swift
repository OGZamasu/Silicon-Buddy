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

        public init(deviceID: String, token: String, macName: String, port: Int) {
            self.deviceID = deviceID
            self.token = token
            self.macName = macName
            self.port = port
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
        public var messages: [StoredMessage]

        public init(id: String, title: String, messages: [StoredMessage]) {
            self.id = id
            self.title = title
            self.messages = messages
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

    public struct ChatMetrics: Codable, Sendable, Equatable {
        public var promptTokens: Int
        public var generatedTokens: Int
        public var tokensPerSecond: Double

        public init(promptTokens: Int, generatedTokens: Int, tokensPerSecond: Double) {
            self.promptTokens = promptTokens
            self.generatedTokens = generatedTokens
            self.tokensPerSecond = tokensPerSecond
        }
    }

    /// `GET /events`: everything the Mac wants to push without being asked.
    public enum ServerEvent: Sendable, Equatable {
        case status(ControlAPI.Status)
        case download(DownloadProgress)
        case job(JobProgress)
        /// The keep-alive comment or a named heartbeat; proof the stream is alive.
        case heartbeat
    }

    public struct DownloadProgress: Codable, Sendable, Equatable {
        public var modelID: String
        public var receivedBytes: Int64
        public var totalBytes: Int64?
        public var state: String
        public var detail: String?

        public init(
            modelID: String, receivedBytes: Int64, totalBytes: Int64?,
            state: String, detail: String? = nil
        ) {
            self.modelID = modelID
            self.receivedBytes = receivedBytes
            self.totalBytes = totalBytes
            self.state = state
            self.detail = detail
        }

        public var fraction: Double? {
            guard let totalBytes, totalBytes > 0 else { return nil }
            return min(1, Double(receivedBytes) / Double(totalBytes))
        }
    }

    public struct JobProgress: Codable, Sendable, Equatable {
        public var id: String
        public var kind: String
        public var state: String
        public var progress: Double?
        public var detail: String?

        public init(
            id: String, kind: String, state: String,
            progress: Double? = nil, detail: String? = nil
        ) {
            self.id = id
            self.kind = kind
            self.state = state
            self.progress = progress
            self.detail = detail
        }
    }
}
