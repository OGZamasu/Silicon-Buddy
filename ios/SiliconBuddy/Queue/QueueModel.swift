import Foundation
import Observation

/// The Mac's render queue, as this phone shows it.
///
/// `GET /video/queue` is the whole story here: it knows the prompts, the settings, why a
/// clip failed and — since the Mac grew `cancel` — whether a clip's node can stop its
/// render and how a cancel went. The screen reads it when it opens and every few seconds
/// while it stays open, and every verb it sends is answered with the queue again. The
/// verbs are the Mac's own and no more: "Cancel render" appears only on a clip the Mac
/// marks `canCancel`, and everywhere else Stop following is all there is.
@MainActor
@Observable
public final class QueueModel {
    public private(set) var queue: ControlAPI.VideoQueueView?
    public private(set) var isLoading = false
    /// The Mac's refusal, or why it could not be asked.
    public private(set) var error: String?
    /// Clips with a cancel on its way to the Mac, which answers once the node has.
    public private(set) var cancelling: Set<String> = []
    /// A verb other than cancel on its way, so its buttons do not send it twice.
    public private(set) var sending = false

    public init() {}

    public var items: [ControlAPI.VideoQueueView.Item] { queue?.items ?? [] }
    public var finished: [ControlAPI.VideoQueueView.Item] { items.filter { $0.phase.isFinished } }

    public func item(_ id: String) -> ControlAPI.VideoQueueView.Item? {
        items.first { $0.id == id }
    }

    /// Whether this clip is the one the Mac is following now.
    public func isActive(_ item: ControlAPI.VideoQueueView.Item) -> Bool {
        item.id == queue?.activeID && item.phase.isRunning
    }

    /// What one row says about a cancel: on its way, or the Mac's record of how it went.
    public func cancelOutcome(for item: ControlAPI.VideoQueueView.Item) -> QueueCancelState? {
        cancelling.contains(item.id) ? .sending : QueueCancelState(wire: item.cancelState)
    }

    // MARK: - Reading

    public func refresh(using transport: (any ControlTransport)?) async {
        guard let transport else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            queue = try await transport.videoQueue()
            if error == Self.unreadable { error = nil }
        } catch {
            if queue == nil { self.error = Self.unreadable }
        }
    }

    static let unreadable = "Couldn't read the Mac's render queue."

    /// A different Mac has a different queue.
    public func reset() {
        queue = nil
        error = nil
        cancelling = []
        sending = false
    }

    public func clearError() { error = nil }

    // MARK: - The Mac's verbs

    public func pauseOrResume(using transport: (any ControlTransport)?) async {
        let action = queue?.paused == true
            ? ControlAPI.VideoQueueControl.resume : ControlAPI.VideoQueueControl.pause
        await send(.init(action: action), using: transport)
    }

    public func clearFinished(using transport: (any ControlTransport)?) async {
        await send(.init(action: ControlAPI.VideoQueueControl.clearFinished), using: transport)
    }

    public func stopFollowing(_ id: String, using transport: (any ControlTransport)?) async {
        await send(.init(action: ControlAPI.VideoQueueControl.stopFollowing, id: id), using: transport)
    }

    /// `confirmNewRender` is true only because somebody read the warning about an
    /// unconfirmed handover and asked again — never because the app filled it in.
    public func retry(_ id: String, confirmNewRender: Bool, using transport: (any ControlTransport)?) async {
        await send(
            .init(action: ControlAPI.VideoQueueControl.retry, id: id,
                  confirmNewRender: confirmNewRender ? true : nil),
            using: transport
        )
    }

    public func remove(_ id: String, using transport: (any ControlTransport)?) async {
        await send(.init(action: ControlAPI.VideoQueueControl.remove, id: id), using: transport)
    }

    /// `cancel`: asks the clip's node to stop this one render.
    ///
    /// The screen offers it only where the Mac said `canCancel`, and only once the person
    /// has confirmed it, because the GPU work so far is thrown away. The Mac answers after
    /// the node does — up to a minute — with the queue, and the clip in it says how the
    /// cancel went. A cancel that fails on the way may still have reached the node, so the
    /// queue is read again for what the Mac kept.
    public func cancelRender(_ id: String, using transport: (any ControlTransport)?) async {
        guard let transport, !cancelling.contains(id) else { return }
        cancelling.insert(id)
        defer { cancelling.remove(id) }
        do {
            queue = try await transport.controlVideoQueue(
                .init(action: ControlAPI.VideoQueueControl.cancel, id: id)
            )
            error = nil
        } catch {
            self.error = Self.describe(error)
            if let read = try? await transport.videoQueue() { queue = read }
        }
    }

    private func send(
        _ request: ControlAPI.VideoQueueControl, using transport: (any ControlTransport)?
    ) async {
        guard let transport, !sending else { return }
        sending = true
        defer { sending = false }
        do {
            queue = try await transport.controlVideoQueue(request)
            error = nil
        } catch {
            self.error = Self.describe(error)
        }
    }

    static func describe(_ error: Error) -> String {
        (error as? TransportError)?.errorDescription ?? error.localizedDescription
    }
}

/// What became of asking a clip's node to stop its render, as the Mac records it on the
/// clip. Its vocabulary, and it may grow: a word this build has not heard is read as
/// `unknown`, the one that promises nothing.
public enum QueueCancelState: String, Sendable, Equatable {
    case sending, requested, confirmed, completed, failed, unsupported, unknown

    public init?(wire: String?) {
        guard let wire else { return nil }
        self = QueueCancelState(rawValue: wire.lowercased()) ?? .unknown
    }

    /// Asked for and not settled: the node may still be stopping it.
    public var isPending: Bool { self == .sending || self == .requested }

    /// Whether the line should read as a warning: the render may well still be running.
    public var isWarning: Bool { self == .unsupported || self == .unknown }

    /// One line under the clip, in this app's words; the node's own follow when it gave any.
    public var note: String {
        switch self {
        case .sending: "Asking the node to cancel this render…"
        case .requested:
            "Cancel requested. The node is stopping this render, and the Mac follows it until the node says it has."
        case .confirmed: "Cancelled on the node. Nothing will be published for it."
        case .completed: "Too late to cancel: the render finished first, and the clip is kept."
        case .failed: "Nothing to cancel: the render had already failed."
        case .unsupported:
            "The node can't stop this render without risking other work, so it keeps rendering."
        case .unknown:
            "The node didn't confirm the cancel, so the render may still be running. Nothing was sent again."
        }
    }
}

/// Where a clip is, in the Mac's words: `pending`, `submitting`, `rendering`, `completed`,
/// `failed`, `cancelled`. A word this build has never heard is work, not an ending.
public enum QueuePhase: Sendable, Equatable {
    case queued, submitting, rendering, done, failed, cancelled, other(String)

    public init(wire: String) {
        switch wire.lowercased() {
        case "pending", "queued", "waiting": self = .queued
        case "submitting", "submitted": self = .submitting
        case "rendering", "running", "generating": self = .rendering
        case "completed", "complete", "finished", "done", "succeeded": self = .done
        case "failed", "error": self = .failed
        case "cancelled", "canceled", "stopped": self = .cancelled
        default: self = .other(wire)
        }
    }

    public var isRunning: Bool { self == .submitting || self == .rendering }
    public var isFinished: Bool { self == .done || self == .failed || self == .cancelled }

    public var label: String {
        switch self {
        case .queued: "Queued"
        case .submitting: "Handing to the node"
        case .rendering: "Rendering"
        case .done: "Done"
        case .failed: "Failed"
        case .cancelled: "Cancelled"
        case .other(let word): word.capitalized
        }
    }
}

extension ControlAPI.VideoQueueView.Item {
    public var phase: QueuePhase { QueuePhase(wire: status) }

    /// Whether this phone shows "Cancel render" on the clip: the Mac said `canCancel`, and
    /// the device may control the Mac. A chat-only pairing is refused every queue verb,
    /// and this one is not even shown to it.
    public func offersCancelRender(canControl: Bool) -> Bool {
        canControl && canCancel == true
    }

    /// The Mac retries a failed clip, or one whose cancel the node confirmed.
    public var canRetry: Bool { phase == .failed || phase == .cancelled }

    /// The Mac removes only a clip it has not handed to a node yet.
    public var canRemove: Bool { phase == .queued }

    /// Model, length and size, and what the batch asked to keep out of the shot.
    public var settings: String {
        var parts = [modelID, "\(seconds)s", resolution]
        if let h3Steps { parts.append("\(h3Steps) steps") }
        if h3Turbo == true { parts.append("turbo") }
        if let negativePrompt, !negativePrompt.isEmpty { parts.append("without: \(negativePrompt)") }
        return parts.joined(separator: " · ")
    }
}
