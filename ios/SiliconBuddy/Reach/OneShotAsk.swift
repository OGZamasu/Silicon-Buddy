import Foundation

/// One question, one answer, from a process that has no chat screen.
///
/// The share sheet, a widget's button and a Shortcuts action all want the same thing:
/// ask the Mac once and get text back. What they must not do is invent a second way of
/// talking to it, so this is the one — and it prefers the conversation route, because a
/// question asked from a share sheet belongs in the same history as one asked in the
/// app. A Mac that does not store conversations gets the plain route instead, and the
/// answer says which happened.
public enum OneShotAsk {

    public struct Outcome: Sendable, Equatable {
        public var answer: String
        /// The Mac's conversation this was filed under, when it filed it.
        public var conversationID: String?
        /// False when this Mac has no `/conversations` and the exchange lives nowhere
        /// but the screen it was asked from.
        public var storedOnMac: Bool

        public init(answer: String, conversationID: String? = nil, storedOnMac: Bool = false) {
            self.answer = answer
            self.conversationID = conversationID
            self.storedOnMac = storedOnMac
        }
    }

    public enum Failure: Error, Equatable, LocalizedError {
        case notPaired
        case empty
        case refused(String)

        public var errorDescription: String? {
            switch self {
            case .notPaired:
                "Silicon Buddy isn't paired with a Mac. Open the app and pair first."
            case .empty:
                "Your Mac answered with nothing."
            case .refused(let message):
                message
            }
        }
    }

    /// Asks, streaming when the Mac streams, and returns the whole answer.
    ///
    /// `onToken` is called as text arrives so a share sheet can show the answer being
    /// written rather than a spinner. It is never required.
    public static func send(
        message: String,
        images: [String] = [],
        title: String? = nil,
        maxTokens: Int = 1024,
        using transport: (any ControlTransport)?,
        defaults: UserDefaults = BuddyShared.defaults,
        onToken: (@Sendable (String) -> Void)? = nil
    ) async throws -> Outcome {
        guard let transport else { throw Failure.notPaired }
        let text = message.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty || !images.isEmpty else { throw IntentMapping.Refusal.emptyPrompt }

        // First choice: a conversation on the Mac, so this turns up in the app's list
        // and on the Mac's own screen rather than only here.
        if let conversation = try? await transport.createConversation(title: title) {
            let wire = ControlAPI.ChatRequest.Message(role: "user", content: text, images: images)
            let answer = try await drain(
                transport.sendMessage(
                    conversationID: conversation.id, message: wire, maxTokens: maxTokens
                ),
                onToken: onToken
            )
            if let answer {
                SnapshotStore.note(question: text, answer: answer, to: defaults)
                return Outcome(
                    answer: answer, conversationID: conversation.id, storedOnMac: true
                )
            }
        }

        let request = ControlAPI.ChatRequest(
            messages: [
                ControlAPI.ChatRequest.Message(role: "user", content: text, images: images)
            ],
            maxTokens: maxTokens
        )
        if let streamed = try await drain(transport.chatStream(request), onToken: onToken) {
            SnapshotStore.note(question: text, answer: streamed, to: defaults)
            return Outcome(answer: streamed)
        }

        // Last: one request, one answer. Every Mac has this.
        do {
            let response = try await transport.chat(request)
            let answer = response.content.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !answer.isEmpty else { throw Failure.empty }
            onToken?(answer)
            SnapshotStore.note(question: text, answer: answer, to: defaults)
            return Outcome(answer: answer)
        } catch let error as TransportError {
            throw Failure.refused(error.localizedDescription)
        }
    }

    /// Reads a stream to its end and returns the text, or nil when the route was not
    /// there at all. `finished` ends the answer: a `verdict` frame after it is a
    /// decoration, never something to wait for.
    private static func drain(
        _ stream: AsyncThrowingStream<BuddyAPI.ChatStreamEvent, Error>,
        onToken: (@Sendable (String) -> Void)?
    ) async throws -> String? {
        var answer = ""
        var sawAnything = false
        do {
            for try await event in stream {
                sawAnything = true
                switch event {
                case .token(let piece):
                    answer += piece
                    onToken?(piece)
                case .finished:
                    return answer.isEmpty ? nil : answer
                case .failed(let message):
                    throw Failure.refused(message)
                case .reasoning:
                    break
                }
            }
        } catch let error as TransportError where error.isMissingRoute {
            return nil
        } catch let error as TransportError {
            throw Failure.refused(error.localizedDescription)
        }
        guard sawAnything, !answer.isEmpty else { return nil }
        return answer
    }
}
