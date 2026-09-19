import Foundation
import Observation
import UIKit

/// What a device may send, from the Mac's own contract: 4 MiB a body, about 1.5 MB an
/// image, eight images a message. Checked before sending rather than discovered as a 413
/// after a slow upload over a tailnet.
public enum SendLimits {
    public static let maximumAttachments = 8
    public static let maximumImageBytes = 1_500_000
    public static let maximumBodyBytes = 4 * 1024 * 1024
}

/// A picture on its way to a vision model.
public struct ChatAttachment: Identifiable, Sendable, Equatable {
    public let id = UUID()
    /// JPEG bytes, already scaled down.
    public let jpeg: Data

    public init(jpeg: Data) { self.jpeg = jpeg }

    /// The form the control API wants: a base64 `data:` URL.
    public var dataURL: String {
        "data:image/jpeg;base64," + jpeg.base64EncodedString()
    }
}

/// The chat screen's state and the one piece of logic worth testing here: how a reply
/// arrives, whether it streams or not.
@MainActor
@Observable
public final class ChatModel {
    public private(set) var conversations: [BuddyAPI.ConversationSummary] = []
    public private(set) var current: Conversation?
    public private(set) var isSending = false
    public private(set) var error: String?

    /// Whether the Mac answered `/chat/stream` and `/conversations`, learned by asking.
    /// Conversations start out local: claiming they are synced before anything has
    /// asked would be a promise the app cannot keep.
    public private(set) var usesStreaming = true
    public private(set) var usesRemoteConversations = false
    /// True while the Mac is answering in the open conversation — including an answer
    /// this device did not ask for, which is what `isGenerating` on the transcript
    /// means. The composer is closed rather than letting a send be refused.
    public private(set) var isConversationBusy = false
    private var askedAboutConversations = false

    public var draft = ""
    public var attachments: [ChatAttachment] = []

    /// How much history to send. The Mac has the whole transcript when it stores the
    /// conversation; when the phone does, the prompt has to stay bounded.
    public var historyLimit = 24

    /// A ceiling on the answer.
    ///
    /// Without one, a reasoning model asked a small question can think for ten minutes
    /// and the phone shows a spinner the whole time — which is what happened the first
    /// time this was pointed at a 27B. A generous cap is better than an open one: it is
    /// the difference between a slow answer and no answer.
    public var maxTokens = 2048

    /// When the current reply was asked for, so the transcript can say how long it has
    /// been rather than spinning silently.
    public private(set) var sendingSince: Date?

    private let store: ConversationStore
    private var sendTask: Task<Void, Never>?

    public init(store: ConversationStore = ConversationStore()) {
        self.store = store
    }

    // MARK: - Conversations

    public func loadConversations(using transport: (any ControlTransport)?) async {
        if let transport, usesRemoteConversations || !askedAboutConversations {
            askedAboutConversations = true
            do {
                conversations = try await transport.conversations()
                usesRemoteConversations = true
                return
            } catch {
                // The Mac has not grown conversations yet; the device keeps them.
                usesRemoteConversations = false
            }
        }
        conversations = await store.all().map(\.summary)
        // A conversation started but not yet sent to is not in the store. Losing it from
        // the list because another screen reloaded would be a small betrayal.
        if let current, !conversations.contains(where: { $0.id == current.id }) {
            conversations.insert(current.summary, at: 0)
        }
    }

    public func newConversation(using transport: (any ControlTransport)?) async {
        if usesRemoteConversations, let transport {
            if let created = try? await transport.createConversation(title: nil) {
                current = Conversation(
                    id: created.id, title: created.title, updatedAt: created.updatedAt
                )
                await loadConversations(using: transport)
                return
            }
            usesRemoteConversations = false
        }
        let fresh = Conversation()
        current = fresh
        conversations.insert(fresh.summary, at: 0)
    }

    public func open(id: String, using transport: (any ControlTransport)?) async {
        if usesRemoteConversations, let transport {
            if let detail = try? await transport.conversation(id: id) {
                current = Conversation(
                    id: detail.id,
                    title: detail.title,
                    updatedAt: Date(),
                    messages: detail.messages.map {
                        ChatMessage(
                            role: ChatMessage.Role(rawValue: $0.role) ?? .assistant,
                            content: $0.content,
                            reasoning: $0.reasoning,
                            images: $0.images ?? [],
                            createdAt: $0.createdAt
                        )
                    }
                )
                isConversationBusy = detail.isGenerating
                return
            }
            usesRemoteConversations = false
        }
        current = await store.conversation(id: id) ?? Conversation(id: id)
    }

    public func delete(id: String, using transport: (any ControlTransport)?) async {
        await store.delete(id: id)
        conversations.removeAll { $0.id == id }
        if current?.id == id { current = nil }
    }

    // MARK: - Sending

    public func send(using transport: (any ControlTransport)?) {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty || !attachments.isEmpty else { return }
        guard let transport else {
            error = TransportError.notConfigured.localizedDescription
            return
        }
        if let problem = Self.attachmentProblem(for: attachments, message: text) {
            error = problem
            return
        }
        let images = attachments.map(\.dataURL)
        draft = ""
        attachments = []

        var conversation = current ?? Conversation()
        conversation.messages.append(
            ChatMessage(role: .user, content: text, images: images)
        )
        let placeholder = ChatMessage(role: .assistant, content: "", isStreaming: true)
        conversation.messages.append(placeholder)
        conversation = conversation.titledFromFirstMessage()
        current = conversation
        isSending = true
        sendingSince = Date()
        isConversationBusy = false
        error = nil

        sendTask?.cancel()
        sendTask = Task { [weak self] in
            await self?.run(replyTo: placeholder.id, using: transport)
            self?.isSending = false
            self?.sendingSince = nil
        }
    }

    /// Adds a picture, or says why it cannot be added. The cap is the Mac's.
    public func attach(_ attachment: ChatAttachment) {
        guard attachments.count < SendLimits.maximumAttachments else {
            error = "The Mac takes at most \(SendLimits.maximumAttachments) pictures a message."
            return
        }
        if attachment.jpeg.count > SendLimits.maximumImageBytes {
            error = "That picture is still too large after shrinking. Try a smaller one."
            return
        }
        attachments.append(attachment)
    }

    public func cancel() {
        sendTask?.cancel()
        sendTask = nil
        isSending = false
        sendingSince = nil
        finishStreamingMessage(failure: "Stopped.")
    }

    /// How a streamed attempt ended, so the caller can decide whether to fall further
    /// back or to stop and show what the Mac said.
    private enum StreamOutcome {
        case answered
        case missingRoute
        /// The Mac is already answering in this conversation (409).
        case busy(String)
        case failed(String)
        case stopped
    }

    private func run(replyTo messageID: String, using transport: any ControlTransport) async {
        let history = Array(
            (current?.messages ?? [])
                .filter { $0.role != .assistant || !$0.content.isEmpty }
                .suffix(historyLimit)
                .map(\.wireMessage)
        )
        let request = ControlAPI.ChatRequest(messages: history, maxTokens: maxTokens)

        if usesStreaming {
            // First choice: the conversation route, so the Mac keeps the transcript.
            if usesRemoteConversations, let id = current?.id, let last = history.last {
                switch await consume(
                    transport.sendMessage(
                        conversationID: id, message: last, maxTokens: maxTokens
                    ),
                    into: messageID
                ) {
                case .answered:
                    finishStreamingMessage(failure: nil)
                    await persist()
                    return
                case .missingRoute:
                    // Only this route is missing. Plain streaming may still be there.
                    usesRemoteConversations = false
                case .busy(let message):
                    // The Mac is still answering the previous message in this
                    // conversation. Sending it again would only be refused again.
                    isConversationBusy = true
                    finishStreamingMessage(failure: message)
                    error = message
                    return
                case .stopped:
                    finishStreamingMessage(failure: "Stopped.")
                    return
                case .failed(let message):
                    finishStreamingMessage(failure: message)
                    error = message
                    await persist()
                    return
                }
            }

            // Second: streaming without a stored conversation.
            switch await consume(transport.chatStream(request), into: messageID) {
            case .answered:
                finishStreamingMessage(failure: nil)
                await persist()
                return
            case .missingRoute:
                usesStreaming = false
            case .busy(let message):
                finishStreamingMessage(failure: message)
                error = message
                return
            case .stopped:
                finishStreamingMessage(failure: "Stopped.")
                return
            case .failed(let message):
                finishStreamingMessage(failure: message)
                error = message
                await persist()
                return
            }
        }

        // Last: one request, one answer. Every Mac has this.
        do {
            let response = try await transport.chat(request)
            update(messageID) { message in
                message.content = response.content
                message.reasoning = response.reasoning
                message.metrics = BuddyAPI.ChatMetrics(
                    promptTokens: response.promptTokens,
                    generatedTokens: response.generatedTokens,
                    tokensPerSecond: response.tokensPerSecond
                )
                message.isStreaming = false
                message.failure = Self.truncationNote(for: message, limit: maxTokens)
            }
        } catch {
            let description = (error as? TransportError)?.localizedDescription
                ?? error.localizedDescription
            finishStreamingMessage(failure: description)
            self.error = description
        }
        await persist()
    }

    /// Drains one SSE stream into the placeholder message.
    private func consume(
        _ stream: AsyncThrowingStream<BuddyAPI.ChatStreamEvent, Error>, into messageID: String
    ) async -> StreamOutcome {
        do {
            var sawAnything = false
            for try await event in stream {
                sawAnything = true
                apply(event, to: messageID)
            }
            // A stream that ends without one event is not an answer; try the next thing.
            return sawAnything ? .answered : .missingRoute
        } catch let error as TransportError where error.isMissingRoute {
            return .missingRoute
        } catch let error as TransportError where error == .cancelled {
            return .stopped
        } catch let error as TransportError {
            if case .conflict(let message) = error { return .busy(message) }
            return .failed(error.localizedDescription)
        } catch is CancellationError {
            return .stopped
        } catch {
            return .failed(
                (error as? TransportError)?.localizedDescription ?? error.localizedDescription
            )
        }
    }

    private func apply(_ event: BuddyAPI.ChatStreamEvent, to messageID: String) {
        switch event {
        case .token(let text):
            update(messageID) { $0.content += text }
        case .reasoning(let text):
            update(messageID) { $0.reasoning = ($0.reasoning ?? "") + text }
        case .finished(let metrics):
            update(messageID) {
                $0.metrics = metrics
                $0.isStreaming = false
                $0.failure = Self.truncationNote(for: $0, limit: maxTokens) ?? $0.failure
            }
        case .failed(let message):
            update(messageID) {
                $0.failure = message
                $0.isStreaming = false
            }
            error = message
        }
    }

    /// Why this message cannot be sent as it stands, if it cannot.
    ///
    /// The numbers are the Mac's, and it answers 413 to anything over them. Saying so
    /// here costs nothing; finding out afterwards costs the upload.
    public static func attachmentProblem(
        for attachments: [ChatAttachment], message: String
    ) -> String? {
        guard !attachments.isEmpty else { return nil }
        if attachments.count > SendLimits.maximumAttachments {
            return "The Mac takes at most \(SendLimits.maximumAttachments) pictures a message. "
                + "Remove \(attachments.count - SendLimits.maximumAttachments) and send again."
        }
        if let oversized = attachments.first(where: { $0.jpeg.count > SendLimits.maximumImageBytes }) {
            let megabytes = Double(oversized.jpeg.count) / 1_000_000
            return String(
                format: "One picture is %.1f MB; the Mac takes about 1.5 MB each.", megabytes
            )
        }
        // Base64 costs a third on top, and the text and JSON ride along with it.
        let encoded = attachments.reduce(0) { $0 + ($1.jpeg.count * 4 / 3) + 64 }
        if encoded + message.utf8.count + 512 > SendLimits.maximumBodyBytes {
            return "That message is larger than the 4 MB the Mac accepts. Send fewer pictures."
        }
        return nil
    }

    /// A reasoning model can spend its whole budget thinking and answer nothing. An
    /// empty bubble would look like a bug; saying what happened is the honest version.
    static func truncationNote(for message: ChatMessage, limit: Int) -> String? {
        guard message.content.isEmpty,
              let metrics = message.metrics,
              metrics.generatedTokens >= limit
        else { return nil }
        return "The model spent all \(limit) tokens thinking and never got to an answer. "
            + "Ask again more narrowly, or load a model that thinks less."
    }

    private func update(_ messageID: String, _ change: (inout ChatMessage) -> Void) {
        guard var conversation = current,
              let index = conversation.messages.firstIndex(where: { $0.id == messageID })
        else { return }
        change(&conversation.messages[index])
        current = conversation
    }

    private func finishStreamingMessage(failure: String?) {
        guard var conversation = current,
              let index = conversation.messages.lastIndex(where: { $0.isStreaming })
        else { return }
        conversation.messages[index].isStreaming = false
        if let failure, conversation.messages[index].content.isEmpty {
            conversation.messages[index].failure = failure
        }
        current = conversation
    }

    private func persist() async {
        guard let conversation = current, !usesRemoteConversations else { return }
        let saved = await store.save(conversation)
        current = saved
        if let index = conversations.firstIndex(where: { $0.id == saved.id }) {
            conversations[index] = saved.summary
        } else {
            conversations.insert(saved.summary, at: 0)
        }
    }

    public func clearError() { error = nil }

    /// Where the transcript came from, said plainly in the UI so nobody wonders why
    /// their Mac does not show the same list.
    public var storageNote: String {
        usesRemoteConversations
            ? "Synced with the Mac."
            : "Kept on this device — the Mac doesn't store conversations yet."
    }
}

// MARK: - Attachments

public enum ImagePreparation {
    /// Scales a picture down and re-encodes it as JPEG.
    ///
    /// A 12-megapixel photo as a base64 data URL is about 15 MB of JSON, which is both
    /// slower to send than the model is to answer and larger than most vision encoders
    /// can use. 1024 on the long edge is what they actually look at.
    public static func attachment(
        from image: UIImage, maxEdge: CGFloat = 1024, quality: CGFloat = 0.8
    ) -> ChatAttachment? {
        var edge = maxEdge
        var compression = quality
        // Four tries at most: a 12-megapixel photo of a page of text can still beat the
        // Mac's per-image limit at 1024px, and a picture that is refused on arrival is
        // worse than one that was made smaller before it left.
        for _ in 0..<4 {
            let scaled = resize(image, maxEdge: edge)
            guard let data = scaled.jpegData(compressionQuality: compression) else { return nil }
            if data.count <= SendLimits.maximumImageBytes {
                return ChatAttachment(jpeg: data)
            }
            edge *= 0.75
            compression = max(0.4, compression - 0.15)
        }
        return resize(image, maxEdge: edge).jpegData(compressionQuality: 0.4)
            .map(ChatAttachment.init(jpeg:))
    }

    static func resize(_ image: UIImage, maxEdge: CGFloat) -> UIImage {
        let longest = max(image.size.width, image.size.height)
        guard longest > maxEdge, longest > 0 else { return image }
        let scale = maxEdge / longest
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }
}
