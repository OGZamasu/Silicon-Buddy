import Foundation
@testable import SiliconBuddy

/// A Mac that does whatever a test tells it to.
///
/// Every route defaults to "this Mac does not have it", which is the honest default for
/// the M0 routes and makes a test that forgets to configure one fail loudly rather than
/// silently pass.
final class StubTransport: ControlTransport, @unchecked Sendable {
    var healthResult: Result<ControlAPI.Health, Error> = .failure(TransportError.appNotRunning)
    var statusResult: Result<ControlAPI.Status, Error> = .failure(TransportError.appNotRunning)
    var profileResult: Result<ControlAPI.Profile, Error> = .failure(TransportError.appNotRunning)
    var metricsResult: Result<ControlAPI.Metrics, Error> = .failure(TransportError.appNotRunning)
    var installedResult: Result<[ControlAPI.InstalledModel], Error> = .success([])
    var catalogResult: Result<[ControlAPI.CatalogModel], Error> = .success([])
    var swarmResult: Result<ControlAPI.SwarmView, Error> = .failure(TransportError.appNotRunning)
    var nodeResult: Result<ControlAPI.NodeAdvertisement, Error> = .failure(TransportError.appNotRunning)
    var chatResult: Result<ControlAPI.ChatResponse, Error> = .failure(TransportError.badRequest("No model is loaded."))
    var loadResult: Result<ControlAPI.Status, Error> = .failure(TransportError.appNotRunning)
    var pairResult: Result<BuddyAPI.PairResponse, Error> = .failure(TransportError.routeUnavailable("/buddy/pair"))
    var conversationsResult: Result<[BuddyAPI.ConversationSummary], Error> = .failure(TransportError.routeUnavailable("/conversations"))
    /// Every code `POST /buddy/pair` was sent, in order.
    private(set) var pairedCodes: [String] = []
    /// Awaited after a code arrives and before it is answered: a Mac still thinking.
    var pairHold: (@Sendable () async -> Void)?

    /// What `/chat/stream` sends. Nil means the route 404s.
    var streamEvents: [BuddyAPI.ChatStreamEvent]?
    var streamError: Error?

    /// Set to make a call hang for ever instead of answering. A Mac that is awake,
    /// reachable and thinking is not the same as one that is down, and a widget has to
    /// survive it.
    var statusHangs = false
    var chatHangs = false
    var installedHangs = false

    /// Readings `GET /status` walks through before falling back to `statusResult`; the
    /// last one repeats.
    var statusReadings: [ControlAPI.Status] = []
    private(set) var statusReads = 0
    private(set) var loads = 0
    /// How long `POST /load` takes to answer. Not cut short when the caller gives up: the
    /// Mac answers when it answers, and a late answer is the case this exists for.
    var loadDelay: Duration?
    var installError: Error?
    var unloadError: Error?

    private(set) var chatCallCount = 0
    private(set) var streamCallCount = 0
    private(set) var lastChatRequest: ControlAPI.ChatRequest?
    private(set) var lastSentMessage: ControlAPI.ChatRequest.Message?
    private(set) var lastSentMaxTokens: Int?
    /// What the conversation route throws, when it should throw.
    var conversationError: Error?
    /// What `GET /events` sends before it ends.
    var serverEvents: [BuddyAPI.ServerEvent]?
    var conversationDetail: BuddyAPI.ConversationDetail?

    func health() async throws -> ControlAPI.Health { try healthResult.get() }
    func status() async throws -> ControlAPI.Status {
        if statusHangs { try await Task.sleep(for: .seconds(3600)) }
        statusReads += 1
        if statusReadings.count > 1 { return statusReadings.removeFirst() }
        if let last = statusReadings.first { return last }
        return try statusResult.get()
    }
    func profile() async throws -> ControlAPI.Profile { try profileResult.get() }
    func metrics() async throws -> ControlAPI.Metrics { try metricsResult.get() }
    func installed() async throws -> [ControlAPI.InstalledModel] {
        if installedHangs { try await Task.sleep(for: .seconds(3600)) }
        return try installedResult.get()
    }
    func catalog(category: String?, onlyRunnable: Bool) async throws -> [ControlAPI.CatalogModel] {
        try catalogResult.get()
    }
    func swarm() async throws -> ControlAPI.SwarmView { try swarmResult.get() }
    func node() async throws -> ControlAPI.NodeAdvertisement { try nodeResult.get() }
    func videoModels() async throws -> [ControlAPI.VideoModel] { [] }
    func imageModels() async throws -> [ControlAPI.ImageModel] { [] }

    /// What `GET /video/queue` answers; a control verb answers `controlResult`, which by
    /// default is the queue as it stands.
    var videoQueueResult: Result<ControlAPI.VideoQueueView, Error> =
        .failure(TransportError.routeUnavailable("/video/queue"))
    var controlResult: Result<ControlAPI.VideoQueueView, Error>?
    /// Held until the test lets it go, as a node deciding about a cancel is.
    var controlDelay: Duration?
    private(set) var videoQueueReads = 0
    private(set) var sentControls: [ControlAPI.VideoQueueControl] = []

    /// How long `GET /video/queue` takes to answer; the answer is the queue as it was asked.
    var videoQueueDelay: Duration?

    func videoQueue() async throws -> ControlAPI.VideoQueueView {
        videoQueueReads += 1
        let answer = videoQueueResult
        if let videoQueueDelay {
            await Task.detached { try? await Task.sleep(for: videoQueueDelay) }.value
        }
        return try answer.get()
    }
    func controlVideoQueue(
        _ request: ControlAPI.VideoQueueControl
    ) async throws -> ControlAPI.VideoQueueView {
        sentControls.append(request)
        if let controlDelay {
            await Task.detached { try? await Task.sleep(for: controlDelay) }.value
        }
        return try (controlResult ?? videoQueueResult).get()
    }
    func load(_ request: ControlAPI.LoadRequest) async throws -> ControlAPI.Status {
        loads += 1
        if let loadDelay {
            await Task.detached { try? await Task.sleep(for: loadDelay) }.value
        }
        return try loadResult.get()
    }
    func install(_ request: ControlAPI.LoadRequest) async throws -> String {
        if let installError { throw installError }
        return "Downloading."
    }
    func unload() async throws {
        if let unloadError { throw unloadError }
    }

    func chat(_ request: ControlAPI.ChatRequest) async throws -> ControlAPI.ChatResponse {
        chatCallCount += 1
        lastChatRequest = request
        if chatHangs { try await Task.sleep(for: .seconds(3600)) }
        return try chatResult.get()
    }

    func pair(code: String, deviceName: String, platform: String) async throws -> BuddyAPI.PairResponse {
        pairedCodes.append(code)
        await pairHold?()
        return try pairResult.get()
    }

    func chatStream(_ request: ControlAPI.ChatRequest) -> AsyncThrowingStream<BuddyAPI.ChatStreamEvent, Error> {
        streamCallCount += 1
        lastChatRequest = request
        return makeStream()
    }

    func sendMessage(
        conversationID: String, message: ControlAPI.ChatRequest.Message, maxTokens: Int?
    ) -> AsyncThrowingStream<BuddyAPI.ChatStreamEvent, Error> {
        streamCallCount += 1
        lastSentMessage = message
        lastSentMaxTokens = maxTokens
        if let conversationError {
            return AsyncThrowingStream { $0.finish(throwing: conversationError) }
        }
        return makeStream()
    }

    private func makeStream() -> AsyncThrowingStream<BuddyAPI.ChatStreamEvent, Error> {
        let events = streamEvents
        let failure = streamError
        return AsyncThrowingStream { continuation in
            if let failure {
                continuation.finish(throwing: failure)
                return
            }
            guard let events else {
                continuation.finish(throwing: TransportError.routeUnavailable("/chat/stream"))
                return
            }
            for event in events { continuation.yield(event) }
            continuation.finish()
        }
    }

    func events() -> AsyncThrowingStream<BuddyAPI.ServerEvent, Error> {
        let events = serverEvents
        return AsyncThrowingStream { continuation in
            guard let events else {
                continuation.finish(throwing: TransportError.routeUnavailable("/events"))
                return
            }
            for event in events { continuation.yield(event) }
            continuation.finish()
        }
    }

    func conversations() async throws -> [BuddyAPI.ConversationSummary] {
        try conversationsResult.get()
    }

    func createConversation(title: String?) async throws -> BuddyAPI.ConversationSummary {
        throw TransportError.routeUnavailable("/conversations")
    }

    func conversation(id: String) async throws -> BuddyAPI.ConversationDetail {
        if let conversationDetail { return conversationDetail }
        throw TransportError.routeUnavailable("/conversations/\(id)")
    }
}
