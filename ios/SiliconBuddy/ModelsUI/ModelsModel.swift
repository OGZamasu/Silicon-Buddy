import Foundation
import Observation

/// The model list: what is on the Mac's disk, what the catalog offers, and what is
/// running somewhere else.
@MainActor
@Observable
public final class ModelsModel {
    public enum Section: String, CaseIterable, Identifiable, Sendable {
        case installed = "Installed"
        case catalog = "Catalog"
        case cloud = "Cloud"
        public var id: String { rawValue }
    }

    /// What a long-running model operation is doing, so the row can say so.
    public struct Job: Equatable, Sendable {
        public enum Kind: String, Sendable { case load, unload, install }
        public var modelID: String
        public var kind: Kind
        public var message: String
        /// 0–1 when the Mac tells us enough to know; nil while it is indeterminate.
        public var fraction: Double?
    }

    /// Something this screen asked the Mac for, kept so a failure can offer to ask again.
    public enum Operation: Equatable, Sendable {
        case refresh
        case load(modelID: String, quantization: String?)
        case unload
        case install(ControlAPI.CatalogModel, quantization: String?)
    }

    /// What went wrong, said once: a heading, the one line to read first, the Mac's log
    /// behind a tap when there is one, and what Retry would do when retrying could help.
    public struct Problem: Equatable, Sendable {
        public var title: String
        public var message: String
        public var detail: String?
        public var retry: Operation?
        /// False for an ending nobody got wrong — another load taking the Mac, say.
        public var isFault = true
        /// The Mac's own account this was built from, when it was.
        public var failure: ControlAPI.LoadFailure?
    }

    /// How a load this screen started has ended, read from one status.
    public enum LoadOutcome: Equatable, Sendable {
        case pending, loaded, failed, replaced, cancelled

        public static func of(_ status: ControlAPI.Status, modelID: String) -> LoadOutcome {
            if let loaded = status.loadedModelID, sameModel(loaded, modelID) { return .loaded }
            if let failure = status.failure {
                switch failure.kind {
                case .replaced: return .replaced
                case .cancelled: return .cancelled
                default: return .failed
                }
            }
            // Nothing is resident while a load runs, so another model resident now is one
            // somebody asked for instead.
            return status.loadedModelID == nil ? .pending : .replaced
        }

        /// An installed id carries its quantization ("model@Q4_K_M"); a status may use either.
        static func sameModel(_ a: String, _ b: String) -> Bool {
            a == b || a.hasPrefix(b + "@") || b.hasPrefix(a + "@")
        }
    }

    public private(set) var installed: [ControlAPI.InstalledModel] = []
    public private(set) var catalog: [ControlAPI.CatalogModel] = []
    public private(set) var status: ControlAPI.Status?
    public private(set) var isLoading = false
    public private(set) var job: Job?
    public private(set) var problem: Problem?
    public var search = ""
    public var section: Section = .installed

    /// The line a person reads first, when something went wrong.
    public var error: String? { problem?.message }

    /// True when the last read of the Mac's disk failed: an empty list then says nothing
    /// about the disk.
    public private(set) var installedFailed = false
    public private(set) var catalogFailed = false

    /// Set from the event feed. While it is live a load is followed by the Mac's own
    /// status frames, with a slow poll behind them in case one is missed; without it, by
    /// asking.
    public var eventsLive = false

    /// Without an event feed, how often a load is asked about.
    var pollWithoutEvents: Duration = .seconds(2)
    /// With one, how long to wait for a frame before asking anyway.
    var pollWithEvents: Duration = .seconds(10)
    /// How long a load is followed. The Mac gives a runtime ten minutes to answer and then
    /// says it timed out; a little over that, and the Mac has had its say.
    var followLimit: Duration = .seconds(11 * 60)

    private var poller: Task<Void, Never>?
    private var loader: Task<Void, Never>?
    /// Bumped by `reset()`. Anything that was asking the last Mac checks it before it
    /// writes, so a late answer never lands on the next Mac's screen.
    private var generation = 0
    /// A load waiting for its next status frame, and the frame that arrived while nothing
    /// was waiting.
    private var waiter: CheckedContinuation<ControlAPI.Status?, Never>?
    private var waiterTimeout: Task<Void, Never>?
    private var pendingFrame: ControlAPI.Status?

    public init() {}

    /// Reads the three lists. A list that fails to arrive keeps what was there — a refresh
    /// that fails must not empty the screen — and says so, with a retry.
    ///
    /// Only a refresh's own complaint is replaced or cleared here: this also runs after
    /// every operation and whenever the screen appears, and must not wipe the reason an
    /// operation failed before anyone has read it.
    public func refresh(using transport: (any ControlTransport)?) async {
        guard let transport else { return }
        let generation = generation
        isLoading = true
        defer { if generation == self.generation { isLoading = false } }
        async let installedTask = Self.attempt { try await transport.installed() }
        async let catalogTask = Self.attempt { try await transport.catalog(category: nil, onlyRunnable: false) }
        async let statusTask = Self.attempt { try await transport.status() }
        let newInstalled = await installedTask
        let newCatalog = await catalogTask
        let newStatus = await statusTask
        guard generation == self.generation else { return }
        if case .success(let list) = newInstalled { installed = list }
        if case .success(let list) = newCatalog { catalog = list }
        if case .success(let reading) = newStatus { status = reading }
        installedFailed = newInstalled.failed != nil
        catalogFailed = newCatalog.failed != nil

        let ours = problem?.retry == .refresh
        if let cause = newInstalled.failed ?? newCatalog.failed {
            guard problem == nil || ours else { return }
            let title = switch (installedFailed, catalogFailed) {
            case (true, true): "Couldn't read the model list"
            case (true, false): "Couldn't read the models on the Mac"
            default: "Couldn't read the catalog"
            }
            problem = Problem(title: title, message: Self.describe(cause), retry: .refresh)
        } else if ours {
            problem = nil
        }
    }

    /// A status frame from the event feed: the list shows it, and a load being followed
    /// hears it.
    public func statusChanged(_ pushed: ControlAPI.Status) {
        status = pushed
        if let waiter {
            self.waiter = nil
            waiterTimeout?.cancel()
            waiter.resume(returning: pushed)
        } else if loader != nil {
            pendingFrame = pushed
        }
    }

    // MARK: - Filtering

    public var filteredInstalled: [ControlAPI.InstalledModel] {
        guard !search.isEmpty else { return installed }
        return installed.filter { $0.matches(search) }
    }

    /// Catalog entries that are not cloud-hosted, newest-first by the Mac's own order
    /// with anything featured lifted to the top.
    public var filteredCatalog: [ControlAPI.CatalogModel] {
        let local = catalog.filter { !$0.isCloud }
        let matched = search.isEmpty ? local : local.filter { $0.matches(search) }
        return matched.sorted { lhs, rhs in
            if (lhs.featured ?? false) != (rhs.featured ?? false) { return lhs.featured ?? false }
            return lhs.rating > rhs.rating
        }
    }

    /// Cloud providers, once the Mac's catalog carries them. Empty until then, and the
    /// section says so rather than pretending the feature is missing from this app.
    public var filteredCloud: [ControlAPI.CatalogModel] {
        let cloud = catalog.filter(\.isCloud)
        return search.isEmpty ? cloud : cloud.filter { $0.matches(search) }
    }

    public var categories: [String] {
        Array(Set(catalog.map(\.category))).sorted()
    }

    public func isLoaded(_ id: String) -> Bool {
        guard let loaded = status?.loadedModelID else { return false }
        return LoadOutcome.sameModel(loaded, id)
    }

    /// The Mac's last failed load, when nothing on screen is already saying so. A device
    /// paired for chat is sent it without the log, and is shown it that way.
    public var standingFailure: ControlAPI.LoadFailure? {
        guard job == nil, let failure = status?.failure, problem?.failure != failure else { return nil }
        return failure
    }

    private func name(of id: String) -> String {
        installed.first { $0.id == id }?.name ?? catalog.first { $0.id == id }?.name ?? id
    }

    // MARK: - Operations

    /// Loads a model and stays with it until it has an ending.
    ///
    /// `POST /load` answers after 25 seconds whether or not the load is done: a slow one
    /// comes back as the live, still-loading status and carries on on the Mac. So the
    /// answer is only the first reading. The load is followed through the status frames
    /// the event feed pushes, asked about when there is no feed or a frame seems to be
    /// missing, until the model is resident, the Mac says how it failed, another load took
    /// its place — or the Mac's own ten-minute limit has passed with nothing said.
    public func load(
        modelID: String, quantization: String? = nil, using transport: (any ControlTransport)?
    ) async {
        guard let transport else { return }
        problem = nil
        stopFollowing()
        let task = Task { [weak self] in
            await self?.runLoad(modelID: modelID, quantization: quantization, transport: transport)
            // A cancelled one was replaced or reset, and `loader` is no longer it.
            guard let self, !Task.isCancelled else { return }
            self.loader = nil
        }
        loader = task
        await task.value
    }

    private func runLoad(
        modelID: String, quantization: String?, transport: any ControlTransport
    ) async {
        let generation = generation
        let name = name(of: modelID)
        let retry = Operation.load(modelID: modelID, quantization: quantization)
        job = Job(modelID: modelID, kind: .load, message: "Loading…", fraction: nil)
        let answer: ControlAPI.Status
        do {
            answer = try await transport.load(
                ControlAPI.LoadRequest(modelID: modelID, quantization: quantization)
            )
        } catch {
            guard generation == self.generation, !Task.isCancelled else { return }
            job = nil
            let failed = Problem(title: "Couldn't load \(name)", message: Self.describe(error), retry: retry)
            problem = failed
            // A load the Mac tried and lost has its log on /status, next to the same
            // sentence this error carries.
            if case .success(let after) = await Self.attempt({ try await transport.status() }),
               generation == self.generation {
                status = after
                if let failure = after.failure, after.state == failed.message, problem == failed {
                    problem?.detail = failure.detail
                    problem?.failure = failure
                }
            }
            return
        }
        guard generation == self.generation, !Task.isCancelled else { return }
        // Whatever the feed pushed while the request was out is older than its answer.
        pendingFrame = nil
        status = answer

        var latest = answer
        var outcome = LoadOutcome.of(answer, modelID: modelID)
        let deadline = ContinuousClock.now + followLimit
        while outcome == .pending, ContinuousClock.now < deadline {
            job = Job(
                modelID: modelID, kind: .load,
                message: latest.state.isEmpty ? "Loading…" : latest.state, fraction: nil
            )
            let every = min(eventsLive ? pollWithEvents : pollWithoutEvents, deadline - ContinuousClock.now)
            var next = await nextFrame(within: every)
            guard generation == self.generation, !Task.isCancelled else { return }
            if next == nil, ContinuousClock.now < deadline,
               case .success(let asked) = await Self.attempt({ try await transport.status() }) {
                guard generation == self.generation, !Task.isCancelled else { return }
                status = asked
                next = asked
            }
            guard let next else { continue }
            latest = next
            outcome = LoadOutcome.of(next, modelID: modelID)
        }

        job = nil
        let failure = latest.failure
        switch outcome {
        case .loaded:
            problem = nil
        case .failed:
            problem = Problem(
                title: "Couldn't load \(name)", message: latest.state,
                detail: failure?.detail, retry: retry, failure: failure
            )
        case .replaced:
            let instead = latest.loadedModelName ?? latest.loadedModelID ?? "another model"
            problem = Problem(
                title: "\(name) wasn't loaded",
                message: failure != nil ? latest.state : "The Mac loaded \(instead) instead.",
                detail: failure?.detail, isFault: false, failure: failure
            )
        case .cancelled:
            problem = Problem(
                title: "\(name) wasn't loaded", message: latest.state,
                detail: failure?.detail, isFault: false, failure: failure
            )
        case .pending:
            // Not refreshed after this: the status has just been read, and a refresh that
            // succeeded would clear the one thing there is to say.
            problem = Problem(
                title: "Still loading \(name)",
                message: "The Mac hasn't said how this load ended. Pull to refresh to ask it again.",
                retry: .refresh, isFault: false
            )
            return
        }
        await refresh(using: transport)
    }

    /// The next status frame, or nil when none came within `interval`.
    private func nextFrame(within interval: Duration) async -> ControlAPI.Status? {
        if let frame = pendingFrame {
            pendingFrame = nil
            return frame
        }
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                waiter = continuation
                waiterTimeout = Task { [weak self] in
                    try? await Task.sleep(for: interval)
                    // Cancelled means a frame answered this wait; the next wait is not ours.
                    guard !Task.isCancelled else { return }
                    self?.resumeWaiter()
                }
            }
        } onCancel: {
            Task { @MainActor [weak self] in self?.resumeWaiter() }
        }
    }

    private func resumeWaiter() {
        guard let waiter else { return }
        self.waiter = nil
        waiterTimeout?.cancel()
        waiterTimeout = nil
        waiter.resume(returning: nil)
    }

    public func unload(using transport: (any ControlTransport)?) async {
        guard let transport else { return }
        problem = nil
        let generation = generation
        let loaded = status?.loadedModelID ?? ""
        let loadedName = status?.loadedModelName ?? (loaded.isEmpty ? "the model" : name(of: loaded))
        job = Job(modelID: loaded, kind: .unload, message: "Unloading…", fraction: nil)
        do {
            try await transport.unload()
            guard generation == self.generation else { return }
            job = nil
            await refresh(using: transport)
        } catch {
            guard generation == self.generation else { return }
            job = nil
            problem = Problem(
                title: "Couldn't unload \(loadedName)", message: Self.describe(error), retry: .unload
            )
        }
    }

    /// Starts a download and follows it by polling.
    ///
    /// `POST /install` answers as soon as the download starts, so progress has to be
    /// inferred: `/installed` shows the file growing, `/status` shows the app's own
    /// wording. When `GET /events` lands in M0 this becomes a fallback.
    public func install(
        model: ControlAPI.CatalogModel, quantization: String?,
        using transport: (any ControlTransport)?
    ) async {
        guard let transport else { return }
        problem = nil
        let generation = generation
        let quant = quantization ?? model.recommendation?.quantization
        let expected = model.recommendation?.downloadBytes
        job = Job(modelID: model.id, kind: .install, message: "Asking the Mac…", fraction: nil)
        do {
            let message = try await transport.install(
                ControlAPI.LoadRequest(modelID: model.id, quantization: quant)
            )
            guard generation == self.generation else { return }
            job = Job(modelID: model.id, kind: .install, message: message, fraction: nil)
            await followInstall(of: model.id, expecting: expected, using: transport)
        } catch {
            guard generation == self.generation else { return }
            problem = Problem(
                title: "Couldn't install \(model.name)", message: Self.describe(error),
                retry: .install(model, quantization: quantization)
            )
            job = nil
        }
    }

    /// Polls until the model appears in `/installed` and stops growing.
    private func followInstall(
        of modelID: String, expecting expected: Int64?, using transport: any ControlTransport
    ) async {
        poller?.cancel()
        let task = Task { [weak self] in
            var settled = 0
            var lastSize: Int64 = -1
            for _ in 0..<600 { // Up to ~20 minutes at 2s.
                try? await Task.sleep(for: .seconds(2))
                if Task.isCancelled { return }
                guard let self else { return }
                let list = (try? await transport.installed()) ?? []
                let state = try? await transport.status()
                if Task.isCancelled { return }
                let entry = list.first { $0.id == modelID || $0.id.hasPrefix(modelID + "@") }
                let size = entry?.sizeOnDiskBytes ?? 0
                let fraction = expected.map { total in
                    total > 0 ? min(1, Double(size) / Double(total)) : 0
                }
                self.installed = list
                if let state { self.status = state }
                self.job = Job(
                    modelID: modelID, kind: .install,
                    message: size > 0
                        ? "\(Format.bytes(size)) of \(Format.bytes(expected)) downloaded"
                        : (state?.state ?? "Downloading…"),
                    fraction: fraction
                )
                if entry != nil, size == lastSize {
                    settled += 1
                    if settled >= 2 { break } // Two identical readings: it stopped growing.
                } else {
                    settled = 0
                }
                lastSize = size
            }
            guard let self, !Task.isCancelled else { return }
            self.job = nil
            await self.refresh(using: transport)
        }
        poller = task
        await task.value
    }

    /// Asks again for whatever failed. A retry is a new attempt, so the old words go first.
    public func retry(using transport: (any ControlTransport)?) async {
        guard let operation = problem?.retry else { return }
        await perform(operation, using: transport)
    }

    /// Runs an operation again. Separate from `retry` so a button can hold on to the
    /// operation while the alert that offered it is being put away.
    public func perform(_ operation: Operation, using transport: (any ControlTransport)?) async {
        problem = nil
        switch operation {
        case .refresh: await refresh(using: transport)
        case .load(let id, let quantization): await load(modelID: id, quantization: quantization, using: transport)
        case .unload: await unload(using: transport)
        case .install(let entry, let quantization): await install(model: entry, quantization: quantization, using: transport)
        }
    }

    public func cancelPolling() {
        poller?.cancel()
        poller = nil
        stopFollowing()
        job = nil
    }

    private func stopFollowing() {
        loader?.cancel()
        loader = nil
        pendingFrame = nil
        resumeWaiter()
    }

    public func clearError() { problem = nil }

    /// Throws away the last Mac's lists, and stops everything still asking it, so a re-pair
    /// never shows another machine's disk — or its answer, arriving late.
    public func reset() {
        generation += 1
        cancelPolling()
        installed = []
        catalog = []
        status = nil
        problem = nil
        installedFailed = false
        catalogFailed = false
        isLoading = false
        search = ""
    }

    /// A call to the Mac, with its failure kept rather than thrown.
    private nonisolated static func attempt<T: Sendable>(
        _ call: @Sendable () async throws -> T
    ) async -> Result<T, any Error> {
        do { return .success(try await call()) } catch { return .failure(error) }
    }

    private static func describe(_ error: any Error) -> String {
        (error as? TransportError)?.localizedDescription ?? error.localizedDescription
    }
}

private extension Result {
    var failed: Failure? {
        if case .failure(let error) = self { return error }
        return nil
    }
}

extension ControlAPI.InstalledModel {
    func matches(_ query: String) -> Bool {
        let needle = query.lowercased()
        return name.lowercased().contains(needle)
            || id.lowercased().contains(needle)
            || quantization.lowercased().contains(needle)
    }
}

extension ControlAPI.CatalogModel {
    func matches(_ query: String) -> Bool {
        let needle = query.lowercased()
        return name.lowercased().contains(needle)
            || id.lowercased().contains(needle)
            || author.lowercased().contains(needle)
            || summary.lowercased().contains(needle)
            || category.lowercased().contains(needle)
            || capabilities.contains { $0.lowercased().contains(needle) }
    }

    /// The Mac has no cloud entries yet; when it grows them, this is where they are
    /// recognised — by category or by an id that names a provider rather than a file.
    var isCloud: Bool {
        if category.lowercased() == "cloud" { return true }
        let providers = ["openai:", "anthropic:", "google:", "xai:", "groq:", "cloud:"]
        return providers.contains { id.lowercased().hasPrefix($0) }
    }

    /// What the Mac thinks this machine would do with it.
    var verdict: String? { recommendation?.plan.verdict }
}
