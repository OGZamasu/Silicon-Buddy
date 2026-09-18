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

    /// What `/chat/stream` sends. Nil means the route 404s.
    var streamEvents: [BuddyAPI.ChatStreamEvent]?
    var streamError: Error?

    private(set) var chatCallCount = 0
    private(set) var streamCallCount = 0
    private(set) var lastChatRequest: ControlAPI.ChatRequest?
    private(set) var lastSentMessage: ControlAPI.ChatRequest.Message?

    func health() async throws -> ControlAPI.Health { try healthResult.get() }
    func status() async throws -> ControlAPI.Status { try statusResult.get() }
    func profile() async throws -> ControlAPI.Profile { try profileResult.get() }
    func metrics() async throws -> ControlAPI.Metrics { try metricsResult.get() }
    func installed() async throws -> [ControlAPI.InstalledModel] { try installedResult.get() }
    func catalog(category: String?, onlyRunnable: Bool) async throws -> [ControlAPI.CatalogModel] {
        try catalogResult.get()
    }
    func swarm() async throws -> ControlAPI.SwarmView { try swarmResult.get() }
    func node() async throws -> ControlAPI.NodeAdvertisement { try nodeResult.get() }
    func videoModels() async throws -> [ControlAPI.VideoModel] { [] }
    func imageModels() async throws -> [ControlAPI.ImageModel] { [] }
    func load(_ request: ControlAPI.LoadRequest) async throws -> ControlAPI.Status {
        try loadResult.get()
    }
    func install(_ request: ControlAPI.LoadRequest) async throws -> String { "Downloading." }
    func unload() async throws {}

    func chat(_ request: ControlAPI.ChatRequest) async throws -> ControlAPI.ChatResponse {
        chatCallCount += 1
        lastChatRequest = request
        return try chatResult.get()
    }

    func pair(code: String, deviceName: String, platform: String) async throws -> BuddyAPI.PairResponse {
        try pairResult.get()
    }

    func chatStream(_ request: ControlAPI.ChatRequest) -> AsyncThrowingStream<BuddyAPI.ChatStreamEvent, Error> {
        streamCallCount += 1
        lastChatRequest = request
        return makeStream()
    }

    func sendMessage(
        conversationID: String, message: ControlAPI.ChatRequest.Message
    ) -> AsyncThrowingStream<BuddyAPI.ChatStreamEvent, Error> {
        streamCallCount += 1
        lastSentMessage = message
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
        AsyncThrowingStream { $0.finish(throwing: TransportError.routeUnavailable("/events")) }
    }

    func conversations() async throws -> [BuddyAPI.ConversationSummary] {
        try conversationsResult.get()
    }

    func createConversation(title: String?) async throws -> BuddyAPI.ConversationSummary {
        throw TransportError.routeUnavailable("/conversations")
    }

    func conversation(id: String) async throws -> BuddyAPI.ConversationDetail {
        throw TransportError.routeUnavailable("/conversations/\(id)")
    }
}
