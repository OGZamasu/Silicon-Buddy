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
                    case .download(let progress):
                        self.downloads[progress.id] = progress
                        // A download that has arrived stops being news.
                        if (progress.progress ?? 0) >= 1 {
                            self.downloads.removeValue(forKey: progress.id)
                        }
                    case .job(let job):
                        if ["finished", "failed", "cancelled"].contains(job.status.lowercased()) {
                            self.jobs.removeValue(forKey: job.id)
                        } else {
                            self.jobs[job.id] = job
                        }
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
        lastEvent = nil
        mustPoll = false
    }

    /// The download for a model, whichever spelling of its id the list is holding.
    public func download(forModel id: String) -> BuddyAPI.DownloadProgress? {
        if let exact = downloads[id] { return exact }
        let base = id.split(separator: "@").first.map(String.init) ?? id
        return downloads.first { $0.key == base || $0.key.hasPrefix(base + "@") }?.value
    }
}
