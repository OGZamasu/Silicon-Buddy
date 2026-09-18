import Foundation
import Observation
import UIKit

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
    public private(set) var usesStreaming = true
    public private(set) var usesRemoteConversations = true

    public var draft = ""
    public var attachments: [ChatAttachment] = []

    /// How much history to send. The Mac has the whole transcript when it stores the
    /// conversation; when the phone does, the prompt has to stay bounded.
    public var historyLimit = 24

    private let store: ConversationStore
    private var sendTask: Task<Void, Never>?

    public init(store: ConversationStore = ConversationStore()) {
        self.store = store
    }

    // MARK: - Conversations

    public func loadConversations(using transport: (any ControlTransport)?) async {
        if let transport, usesRemoteConversations {
            do {
                conversations = try await transport.conversations()
                usesRemoteConversations = true
                return
            } catch let error as TransportError where error.isMissingRoute {
                // The Mac has not grown conversations yet; the device keeps them.
                usesRemoteConversations = false
            } catch {
                usesRemoteConversations = false
            }
        }
        conversations = await store.all().map(\.summary)
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
        error = nil

        sendTask?.cancel()
        sendTask = Task { [weak self] in
            await self?.run(replyTo: placeholder.id, using: transport)
            self?.isSending = false
        }
    }

    public func cancel() {
        sendTask?.cancel()
        sendTask = nil
        isSending = false
        finishStreamingMessage(failure: "Stopped.")
    }

    /// How a streamed attempt ended, so the caller can decide whether to fall further
    /// back or to stop and show what the Mac said.
    private enum StreamOutcome {
        case answered
        case missingRoute
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
        let request = ControlAPI.ChatRequest(messages: history)

        if usesStreaming {
            // First choice: the conversation route, so the Mac keeps the transcript.
            if usesRemoteConversations, let id = current?.id, let last = history.last {
                switch await consume(
                    transport.sendMessage(conversationID: id, message: last), into: messageID
                ) {
                case .answered:
                    finishStreamingMessage(failure: nil)
                    await persist()
                    return
                case .missingRoute:
                    // Only this route is missing. Plain streaming may still be there.
                    usesRemoteConversations = false
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
            }
        case .failed(let message):
            update(messageID) {
                $0.failure = message
                $0.isStreaming = false
            }
            error = message
        }
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
    public static func attachment(from image: UIImage, maxEdge: CGFloat = 1024, quality: CGFloat = 0.8) -> ChatAttachment? {
        let scaled = resize(image, maxEdge: maxEdge)
        guard let data = scaled.jpegData(compressionQuality: quality) else { return nil }
        return ChatAttachment(jpeg: data)
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
