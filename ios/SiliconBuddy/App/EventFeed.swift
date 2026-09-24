import Foundation
import Observation

/// What the Mac is doing, pushed rather than asked for.
///
/// One stream for the whole app: the Mac answers 429 to a device that opens several, and
/// the dashboard and the models list want the same three facts anyway. When `GET /events`
/// is not there — a Mac from before M0 — this says so, and the screens that care go back
/// to polling. That is the only reason polling still exists.
@MainActor
@Observable
public final class EventFeed {

    /// The last `status` event, which is the same shape as `GET /status`.
    public private(set) var status: ControlAPI.Status?
    /// Downloads in flight, by model id.
    public private(set) var downloads: [String: BuddyAPI.DownloadProgress] = [:]
    /// Renders in flight, by job id.
    public private(set) var jobs: [String: BuddyAPI.JobProgress] = [:]
    /// The last answer check the Mac published, and the one before it, by conversation.
    /// Kept rather than consumed: the chat screen may not be on screen when it arrives.
    public private(set) var verdicts: [String: BuddyAPI.Verdict] = [:]
    /// When the Mac last said anything, heartbeat included.
    public private(set) var lastEvent: Date?

    /// True once the stream is open and delivering.
    public private(set) var isLive = false
    /// True when this Mac has no `/events` and the screens must poll instead.
    public private(set) var mustPoll = false

    private var task: Task<Void, Never>?

    public init() {}

    public func start(using transport: (any ControlTransport)?) {
        stop()
        guard let transport else {
            mustPoll = true
            return
        }
        mustPoll = false
        task = Task { [weak self] in
            do {
                for try await event in transport.events() {
                    guard let self else { return }
                    self.isLive = true
                    self.lastEvent = Date()
                    switch event {
                    case .status(let status):
                        self.status = status
                        // What the Mac is running is the one fact a widget shows
                        // without asking, so it is written down every time it changes.
                        SnapshotStore.note(status: status, macName: nil)
                    case .download(let progress):
                        // Arrived, failed or removed: no longer happening. A failed one
                        // kept here sat under "Happening now" for good, and stood in for
                        // the model's Load button in the Models list.
                        if (progress.progress ?? 0) >= 1 || progress.error != nil {
                            self.downloads.removeValue(forKey: progress.id)
                        } else {
                            self.downloads[progress.id] = progress
                        }
                    case .job(let job):
                        // The queue screen's vocabulary for "over", not a second list of
                        // the Mac's words: this one lacked "completed", which is the word
                        // the Mac uses, so every finished render stayed "running" here.
                        if QueuePhase(wire: job.status).isFinished {
                            self.jobs.removeValue(forKey: job.id)
                        } else {
                            self.jobs[job.id] = job
                        }
                    case .verdict(let verdict):
                        // Keyed by conversation, because that is the only key the
                        // transcript shares with the Mac today: `StoredMessage` carries
                        // no id, so a per-message match is not possible until the Mac
                        // exports one. Until then the newest verdict decorates the
                        // newest reply in that conversation.
                        self.verdicts[verdict.conversationID ?? ""] = verdict
                    case .heartbeat(let at):
                        self.lastEvent = at ?? Date()
                    }
                }
                self?.isLive = false
            } catch let error as TransportError where error.isMissingRoute {
                // Pre-M0 Mac. Nothing is wrong; the screens just have to ask.
                self?.isLive = false
                self?.mustPoll = true
            } catch {
                self?.isLive = false
                self?.mustPoll = true
            }
        }
    }

    public func stop() {
        task?.cancel()
        task = nil
        isLive = false
    }

    public func clear() {
        stop()
        status = nil
        downloads = [:]
        jobs = [:]
        verdicts = [:]
        lastEvent = nil
        mustPoll = false
    }

    /// The download for a model, whichever spelling of its id the list is holding: with
    /// its quantization or without. Never another quantization's — downloading Q8 of a
    /// model is not something to show in place of the Load button of the Q4 on disk.
    public func download(forModel id: String) -> BuddyAPI.DownloadProgress? {
        if let exact = downloads[id] { return exact }
        return downloads.first { ControlAPI.Status.sameModel($0.key, id) }?.value
    }
}
