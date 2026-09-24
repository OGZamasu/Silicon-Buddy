import Foundation
import Observation
import UIKit

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
    /// Conversations this device keeps although the Mac keeps the rest: ones the Mac
    /// answered 404 about. Started here before the Mac said it keeps conversations — a
    /// widget's "Ask" on a cold start — or deleted on the Mac while open here.
    private var keptHere: Set<String> = []

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
    /// The reply being written. Readable by tests, which need to know when a stopped
    /// send has finished unwinding — `isSending` goes false the moment Stop is tapped.
    private(set) var sendTask: Task<Void, Never>?

    public init(store: ConversationStore = ConversationStore()) {
        self.store = store
    }

    // MARK: - Conversations

    public func loadConversations(using transport: (any ControlTransport)?) async {
        if let transport, usesRemoteConversations || !askedAboutConversations {
            askedAboutConversations = true
            do {
                let remote = try await transport.conversations()
                usesRemoteConversations = true
                // The ones the Mac has never heard of are still this device's to show.
                let here = await store.all()
                    .filter { stored in
                        keptHere.contains(stored.id) && !remote.contains { $0.id == stored.id }
                    }
                    .map(\.summary)
                conversations = here + remote
                if let current, keptHere.contains(current.id),
                   !conversations.contains(where: { $0.id == current.id }) {
                    conversations.insert(current.summary, at: 0)
                }
                return
            } catch let failure as TransportError where failure.isMissingRoute {
                // This Mac has no /conversations at all; the device keeps them.
                usesRemoteConversations = false
            } catch {
                // A timeout, a dropped tailnet, a Mac mid-restart. The Mac still owns
                // these conversations — moving them to the device over a bad minute
                // would fork the transcript, and nothing would merge it back.
                self.error = (error as? TransportError)?.localizedDescription
                    ?? error.localizedDescription
                if usesRemoteConversations { return }
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
            do {
                let created = try await transport.createConversation(title: nil)
                current = Conversation(
                    id: created.id, title: created.title, updatedAt: created.updatedAt
                )
                await loadConversations(using: transport)
                return
            } catch let failure as TransportError where failure.isMissingRoute {
                usesRemoteConversations = false
            } catch {
                // The Mac keeps these; a failure now is a failure to say so.
                self.error = (error as? TransportError)?.localizedDescription
                    ?? error.localizedDescription
                return
            }
        }
        let fresh = Conversation()
        current = fresh
        conversations.insert(fresh.summary, at: 0)
    }

    public func open(id: String, using transport: (any ControlTransport)?) async {
        if usesRemoteConversations, !keptHere.contains(id), let transport {
            do {
                let detail = try await transport.conversation(id: id)
                current = Conversation(
                    id: detail.id,
                    title: detail.title,
                    updatedAt: Date(),
                    messages: detail.messages.map {
                        ChatMessage(
                            // The Mac's own id where it gives one, so a `verdict`
                            // event lands on the reply it is about.
                            id: $0.id ?? UUID().uuidString,
                            role: ChatMessage.Role(rawValue: $0.role) ?? .assistant,
                            content: $0.content,
                            reasoning: $0.reasoning,
                            images: $0.images ?? [],
                            createdAt: $0.createdAt,
                            verdict: $0.verification
                        )
                    }
                )
                isConversationBusy = detail.isGenerating
                return
            } catch let failure as TransportError where failure.isMissingRoute {
                // No /conversations on this Mac at all.
                usesRemoteConversations = false
            } catch let failure as TransportError {
                // Including "no such conversation": the Mac still owns the rest.
                error = failure.localizedDescription
                conversations.removeAll { $0.id == id }
                current = nil
                return
            } catch let failure {
                error = failure.localizedDescription
                return
            }
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
        // A send still in flight — a question spoken while the last answer is arriving —
        // ends first, so the reply streaming after this is only ever the new one.
        stopSending()

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

        sendTask = Task { [weak self] in
            await self?.run(replyTo: placeholder.id, using: transport)
            // Stopped or replaced: whoever did that closed this reply, and what is sending
            // now is theirs. Ending it here would open the composer under the new answer.
            guard !Task.isCancelled else { return }
            self?.isSending = false
            self?.sendingSince = nil
            self?.noteLastExchange(question: text)
        }
    }

    /// Ends the send in flight, if there is one: its reply is closed as stopped and its
    /// task stops where it is (`run` checks for that after every wait).
    private func stopSending() {
        if isSending { finishStreamingMessage(failure: "Stopped.") }
        sendTask?.cancel()
        sendTask = nil
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
        /// A Mac that keeps conversations, and not this one (404 on the conversation).
        case noSuchConversation
        /// The route answered and closed without a word: a Mac that quit or gave up before
        /// its first token. Worth trying the next route for this reply — and no evidence at
        /// all about which routes the Mac has.
        case silent
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
            if usesRemoteConversations, let id = current?.id, !keptHere.contains(id),
               let last = history.last {
                let outcome = await consume(
                    transport.sendMessage(
                        conversationID: id, message: last, maxTokens: maxTokens
                    ),
                    into: messageID
                )
                // Stopped or replaced, this send ends where it is: its reply is already
                // closed, and "the streaming reply" from here on is the next send's.
                guard !Task.isCancelled else { return }
                switch outcome {
                case .answered:
                    finishStreamingMessage(failure: nil)
                    await persist(using: transport)
                    return
                case .missingRoute:
                    // Only this route is missing. Plain streaming may still be there.
                    usesRemoteConversations = false
                case .noSuchConversation:
                    // This Mac keeps conversations and has never heard of this one. It
                    // goes as plain history instead and this device keeps the
                    // transcript; the Mac goes on keeping every other one.
                    keptHere.insert(id)
                case .silent:
                    // Plain streaming next. The Mac listed its conversations a moment ago,
                    // so its silence says nothing about whether it keeps them, and reading
                    // it as "it keeps none" moved every one of them onto the phone.
                    break
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
                    await persist(using: transport)
                    return
                }
            }

            // Second: streaming without a stored conversation.
            let outcome = await consume(transport.chatStream(request), into: messageID)
            guard !Task.isCancelled else { return }
            switch outcome {
            case .answered:
                finishStreamingMessage(failure: nil)
                await persist(using: transport)
                return
            case .missingRoute, .noSuchConversation:
                usesStreaming = false
            case .silent:
                // One request, one answer, for this reply only: the route is there.
                break
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
                await persist(using: transport)
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
            // The failure of a request that was given up on is the cancellation's.
            guard !Task.isCancelled else { return }
            let description = (error as? TransportError)?.localizedDescription
                ?? error.localizedDescription
            finishStreamingMessage(failure: description)
            self.error = description
        }
        guard !Task.isCancelled else { return }
        await persist(using: transport)
    }

    /// Drains one SSE stream into the placeholder message.
    private func consume(
        _ stream: AsyncThrowingStream<BuddyAPI.ChatStreamEvent, Error>, into messageID: String
    ) async -> StreamOutcome {
        var sawAnything = false
        do {
            for try await event in stream {
                sawAnything = true
                apply(event, to: messageID)
                // `finished` is the end of the reply, whatever else the Mac sends after
                // it. Jev's answer checking appends a `verdict` frame, and a client that
                // kept the composer closed until the socket closed would sit on a
                // finished answer waiting for a check it can get from `/events` instead.
                if case .finished = event { return .answered }
            }
            // Stop ends this loop exactly the way an empty stream does: a stream returns
            // nil to a consumer that was cancelled rather than throwing. Read as a missing
            // route, one Stop before the first token turned the Mac's conversations and
            // its streaming off for the rest of the session.
            if Task.isCancelled { return .stopped }
            // A stream that ends without one event is not an answer; try the next thing.
            return sawAnything ? .answered : .silent
        } catch let error as TransportError where error.isMissingRoute {
            return .missingRoute
        } catch let error as TransportError where error == .cancelled {
            return .stopped
        } catch TransportError.notFound where !sawAnything {
            // Nothing is written into the reply yet, so the next route can still fill it.
            return .noSuchConversation
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

    private func persist(using transport: (any ControlTransport)? = nil) async {
        if usesRemoteConversations, !keptHere.contains(current?.id ?? "") {
            // The Mac keeps the transcript, so the list it publishes is the one worth
            // showing: the title and the count are its answers, not ours.
            await loadConversations(using: transport)
            return
        }
        guard let conversation = current else { return }
        let saved = await store.save(conversation)
        current = saved
        if let index = conversations.firstIndex(where: { $0.id == saved.id }) {
            conversations[index] = saved.summary
        } else {
            conversations.insert(saved.summary, at: 0)
        }
    }

    /// Attaches an answer check to the reply it belongs to.
    ///
    /// By the Mac's message id when it names one, so a check that arrives after the
    /// person has sent something else still lands on the right reply.
    ///
    /// A named id that matches nothing here is dropped rather than guessed at. It means
    /// the check is about a message this screen does not have — an older one scrolled
    /// out of a transcript the device kept, or a reply in a conversation that was
    /// reopened since — and stamping it on the newest reply would put the Mac's words
    /// under an answer it never read.
    ///
    /// Only an unnamed check falls back to the newest finished reply, which is right
    /// whenever the Mac checks a reply as it finishes, and is the only thing a
    /// device-kept transcript can do: its ids are its own and the Mac has never seen
    /// them.
    public func apply(verdict: BuddyAPI.Verdict) {
        guard var conversation = current else { return }
        if let id = verdict.conversationID, !id.isEmpty, id != conversation.id { return }
        let index: Int?
        if let messageID = verdict.messageID, !messageID.isEmpty {
            index = conversation.messages.firstIndex { $0.id == messageID }
        } else {
            index = conversation.messages.lastIndex {
                $0.role == .assistant && !$0.isStreaming && !$0.content.isEmpty
            }
        }
        guard let index else { return }
        conversation.messages[index].verdict = verdict
        current = conversation
    }

    /// Leaves the exchange where the widget and the Lock Screen can find it. Only the
    /// text: a picture is not something a widget has room for, and the snapshot is
    /// shared with other processes, so the less it carries the better.
    private func noteLastExchange(question: String) {
        guard let answer = current?.messages.last(where: {
            $0.role == .assistant && !$0.content.isEmpty
        })?.content else { return }
        SnapshotStore.note(question: question, answer: answer)
    }

    public func clearError() { error = nil }

    /// Called when the paired Mac changes. What this Mac supports is a fact about that
    /// Mac, and so is its transcript: neither survives a re-pair.
    public func macChanged() {
        sendTask?.cancel()
        sendTask = nil
        isSending = false
        sendingSince = nil
        isConversationBusy = false
        usesStreaming = true
        usesRemoteConversations = false
        askedAboutConversations = false
        keptHere = []
        conversations = []
        current = nil
        error = nil
    }

    /// Where the transcript came from, said plainly in the UI so nobody wonders why
    /// their Mac does not show the same list.
    public var storageNote: String {
        if usesRemoteConversations, let current, keptHere.contains(current.id) {
            return "Kept on this device — the Mac doesn't have this conversation."
        }
        return usesRemoteConversations
            ? "Synced with the Mac."
            : "Kept on this device — the Mac doesn't store conversations yet."
    }
}
