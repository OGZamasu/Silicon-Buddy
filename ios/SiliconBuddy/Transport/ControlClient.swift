import Foundation

/// Every call this app makes to a Mac.
///
/// One protocol so views and models can be driven by a stub in tests and in previews
/// without a Mac anywhere near them, and so a relay transport could be slid underneath
/// later without touching a single screen.
public protocol ControlTransport: Sendable {
    func health() async throws -> ControlAPI.Health
    func status() async throws -> ControlAPI.Status
    func profile() async throws -> ControlAPI.Profile
    func metrics() async throws -> ControlAPI.Metrics
    func installed() async throws -> [ControlAPI.InstalledModel]
    func catalog(category: String?, onlyRunnable: Bool) async throws -> [ControlAPI.CatalogModel]
    func swarm() async throws -> ControlAPI.SwarmView
    func node() async throws -> ControlAPI.NodeAdvertisement
    func videoModels() async throws -> [ControlAPI.VideoModel]
    func imageModels() async throws -> [ControlAPI.ImageModel]
    func load(_ request: ControlAPI.LoadRequest) async throws -> ControlAPI.Status
    func install(_ request: ControlAPI.LoadRequest) async throws -> String
    func unload() async throws
    func chat(_ request: ControlAPI.ChatRequest) async throws -> ControlAPI.ChatResponse

    // M0-pending. Each of these throws `.routeUnavailable` on a Mac without them.
    func pair(code: String, deviceName: String, platform: String) async throws -> BuddyAPI.PairResponse
    func chatStream(_ request: ControlAPI.ChatRequest) -> AsyncThrowingStream<BuddyAPI.ChatStreamEvent, Error>
    func events() -> AsyncThrowingStream<BuddyAPI.ServerEvent, Error>
    func conversations() async throws -> [BuddyAPI.ConversationSummary]
    func createConversation(title: String?) async throws -> BuddyAPI.ConversationSummary
    func conversation(id: String) async throws -> BuddyAPI.ConversationDetail
    func sendMessage(
        conversationID: String, message: ControlAPI.ChatRequest.Message
    ) -> AsyncThrowingStream<BuddyAPI.ChatStreamEvent, Error>
}

/// The real thing: URLSession against the Mac's tiny HTTP server.
public struct ControlClient: ControlTransport {
    public let config: ServerConfig
    private let session: URLSession

    public init(config: ServerConfig, session: URLSession = .buddy) {
        self.config = config
        self.session = session
    }

    // MARK: - Plumbing

    private func makeRequest(
        _ method: String, _ path: String,
        query: [URLQueryItem] = [], body: Data? = nil,
        authorized: Bool = true, accept: String = "application/json",
        timeout: TimeInterval = 30
    ) throws -> URLRequest {
        guard let url = config.url(path: path, query: query) else {
            throw TransportError.notConfigured
        }
        var urlRequest = URLRequest(url: url, timeoutInterval: timeout)
        urlRequest.httpMethod = method
        urlRequest.setValue(accept, forHTTPHeaderField: "Accept")
        if authorized {
            guard !config.token.isEmpty else { throw TransportError.notConfigured }
            urlRequest.setValue("Bearer \(config.token)", forHTTPHeaderField: "Authorization")
        }
        if let body {
            urlRequest.httpBody = body
            urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        return urlRequest
    }

    private func send(_ urlRequest: URLRequest, path: String) async throws -> Data {
        do {
            let (body, response) = try await session.data(for: urlRequest)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if let error = TransportError.from(status: status, body: body, path: path) {
                throw error
            }
            return body
        } catch let error as URLError {
            throw TransportError.from(urlError: error)
        } catch is CancellationError {
            throw TransportError.cancelled
        }
    }

    private func get<T: Decodable>(
        _ type: T.Type, _ path: String, query: [URLQueryItem] = [],
        authorized: Bool = true, timeout: TimeInterval = 30
    ) async throws -> T {
        let body = try await send(
            try makeRequest("GET", path, query: query, authorized: authorized, timeout: timeout),
            path: path
        )
        return try decode(type, from: body, path: path)
    }

    private func post<T: Decodable>(
        _ type: T.Type, _ path: String, body: some Encodable, timeout: TimeInterval = 600
    ) async throws -> T {
        let payload = try JSONEncoder.buddy.encode(body)
        let received = try await send(
            try makeRequest("POST", path, body: payload, timeout: timeout), path: path
        )
        return try decode(type, from: received, path: path)
    }

    private func decode<T: Decodable>(_ type: T.Type, from data: Data, path: String) throws -> T {
        do {
            return try JSONDecoder.buddy.decode(type, from: data)
        } catch {
            throw TransportError.decoding("\(path): \(error.localizedDescription)")
        }
    }

    // MARK: - Routes that exist today

    public func health() async throws -> ControlAPI.Health {
        try await get(ControlAPI.Health.self, "/health", authorized: false, timeout: 8)
    }

    public func status() async throws -> ControlAPI.Status {
        try await get(ControlAPI.Status.self, "/status", timeout: 15)
    }

    public func profile() async throws -> ControlAPI.Profile {
        try await get(ControlAPI.Profile.self, "/profile")
    }

    public func metrics() async throws -> ControlAPI.Metrics {
        try await get(ControlAPI.Metrics.self, "/metrics", timeout: 15)
    }

    public func installed() async throws -> [ControlAPI.InstalledModel] {
        try await get([ControlAPI.InstalledModel].self, "/installed")
    }

    public func catalog(
        category: String? = nil, onlyRunnable: Bool = false
    ) async throws -> [ControlAPI.CatalogModel] {
        var query: [URLQueryItem] = [
            URLQueryItem(name: "onlyRunnable", value: onlyRunnable ? "true" : "false")
        ]
        if let category { query.append(URLQueryItem(name: "category", value: category)) }
        return try await get([ControlAPI.CatalogModel].self, "/catalog", query: query, timeout: 45)
    }

    public func swarm() async throws -> ControlAPI.SwarmView {
        try await get(ControlAPI.SwarmView.self, "/swarm")
    }

    public func node() async throws -> ControlAPI.NodeAdvertisement {
        try await get(ControlAPI.NodeAdvertisement.self, "/v1/node")
    }

    public func videoModels() async throws -> [ControlAPI.VideoModel] {
        try await get([ControlAPI.VideoModel].self, "/video/models")
    }

    public func imageModels() async throws -> [ControlAPI.ImageModel] {
        try await get([ControlAPI.ImageModel].self, "/image/models", timeout: 45)
    }

    public func load(_ request: ControlAPI.LoadRequest) async throws -> ControlAPI.Status {
        try await post(ControlAPI.Status.self, "/load", body: request, timeout: 900)
    }

    public func install(_ request: ControlAPI.LoadRequest) async throws -> String {
        try await post(ControlAPI.StatusMessage.self, "/install", body: request, timeout: 60).status
    }

    public func unload() async throws {
        _ = try await post(
            ControlAPI.StatusMessage.self, "/unload",
            body: [String: String](), timeout: 120
        )
    }

    public func chat(_ request: ControlAPI.ChatRequest) async throws -> ControlAPI.ChatResponse {
        try await post(ControlAPI.ChatResponse.self, "/chat", body: request, timeout: 900)
    }

    // MARK: - Routes the Mac grows in M0

    public func pair(
        code: String, deviceName: String, platform: String
    ) async throws -> BuddyAPI.PairResponse {
        let payload = try JSONEncoder.buddy.encode(
            BuddyAPI.PairRequest(code: code, deviceName: deviceName, platform: platform)
        )
        // Pairing is the one call made before there is a token.
        let body = try await send(
            try makeRequest(
                "POST", "/buddy/pair", body: payload, authorized: false, timeout: 20
            ),
            path: "/buddy/pair"
        )
        return try decode(BuddyAPI.PairResponse.self, from: body, path: "/buddy/pair")
    }

    public func chatStream(
        _ request: ControlAPI.ChatRequest
    ) -> AsyncThrowingStream<BuddyAPI.ChatStreamEvent, Error> {
        chatEventStream(path: "/chat/stream", body: request)
    }

    public func sendMessage(
        conversationID: String, message: ControlAPI.ChatRequest.Message
    ) -> AsyncThrowingStream<BuddyAPI.ChatStreamEvent, Error> {
        chatEventStream(
            path: "/conversations/\(conversationID)/messages",
            body: ControlAPI.ChatRequest(messages: [message])
        )
    }

    private func chatEventStream(
        path: String, body: some Encodable & Sendable
    ) -> AsyncThrowingStream<BuddyAPI.ChatStreamEvent, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let payload = try JSONEncoder.buddy.encode(body)
                    let urlRequest = try makeRequest(
                        "POST", path, body: payload,
                        accept: "text/event-stream", timeout: 900
                    )
                    for try await event in stream(urlRequest, path: path) {
                        switch event.name {
                        case "token":
                            continuation.yield(.token(Self.text(from: event)))
                        case "reasoning":
                            continuation.yield(.reasoning(Self.text(from: event)))
                        case "finished":
                            let metrics = (try? event.decode(BuddyAPI.ChatMetrics.self))
                                ?? BuddyAPI.ChatMetrics(
                                    promptTokens: 0, generatedTokens: 0, tokensPerSecond: 0
                                )
                            continuation.yield(.finished(metrics))
                        case "error":
                            continuation.yield(.failed(Self.text(from: event)))
                        default:
                            break
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    public func events() -> AsyncThrowingStream<BuddyAPI.ServerEvent, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let urlRequest = try makeRequest(
                        "GET", "/events", accept: "text/event-stream", timeout: 86_400
                    )
                    for try await event in stream(urlRequest, path: "/events") {
                        switch event.name {
                        case "status":
                            if let status = try? event.decode(ControlAPI.Status.self) {
                                continuation.yield(.status(status))
                            }
                        case "download":
                            if let progress = try? event.decode(BuddyAPI.DownloadProgress.self) {
                                continuation.yield(.download(progress))
                            }
                        case "job":
                            if let job = try? event.decode(BuddyAPI.JobProgress.self) {
                                continuation.yield(.job(job))
                            }
                        case "heartbeat", "ping":
                            continuation.yield(.heartbeat)
                        default:
                            break
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// The shared body of every SSE call: check the status line, then run the bytes
    /// through `SSEParser`.
    private func stream(
        _ urlRequest: URLRequest, path: String
    ) -> AsyncThrowingStream<SSEEvent, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let (bytes, response) = try await session.bytes(for: urlRequest)
                    let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                    if !(200..<300).contains(status) {
                        // The error body is small; read it so the Mac's own words survive.
                        var body = Data()
                        for try await byte in bytes {
                            body.append(byte)
                            if body.count >= 8192 { break }
                        }
                        throw TransportError.from(status: status, body: body, path: path)
                            ?? TransportError.server(status: status, message: "")
                    }
                    var parser = SSEParser()
                    for try await line in bytes.lines {
                        if let event = parser.consume(line: line) { continuation.yield(event) }
                    }
                    if let event = parser.finish() { continuation.yield(event) }
                    continuation.finish()
                } catch let error as URLError {
                    continuation.finish(throwing: TransportError.from(urlError: error))
                } catch is CancellationError {
                    continuation.finish(throwing: TransportError.cancelled)
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// A token event may be a bare string or `{"text": "…"}`; accept both, because the
    /// Mac's shape is not frozen until M0 lands.
    private static func text(from event: SSEEvent) -> String {
        struct Payload: Decodable {
            let text: String?
            let content: String?
            let delta: String?
            let error: String?
        }
        guard event.data.hasPrefix("{"),
              let payload = try? JSONDecoder().decode(Payload.self, from: Data(event.data.utf8))
        else { return event.data }
        return payload.text ?? payload.content ?? payload.delta ?? payload.error ?? event.data
    }

    // MARK: - Conversations (M0-pending)

    public func conversations() async throws -> [BuddyAPI.ConversationSummary] {
        try await get([BuddyAPI.ConversationSummary].self, "/conversations", timeout: 20)
    }

    public func createConversation(title: String?) async throws -> BuddyAPI.ConversationSummary {
        try await post(
            BuddyAPI.ConversationSummary.self, "/conversations",
            body: BuddyAPI.NewConversation(title: title), timeout: 20
        )
    }

    public func conversation(id: String) async throws -> BuddyAPI.ConversationDetail {
        try await get(BuddyAPI.ConversationDetail.self, "/conversations/\(id)", timeout: 20)
    }
}

extension URLSession {
    /// No caching: every answer here is a live reading of a machine.
    public static let buddy: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 900
        configuration.waitsForConnectivity = false
        configuration.httpAdditionalHeaders = ["User-Agent": "SiliconBuddy-iOS/0.1"]
        return URLSession(configuration: configuration)
    }()
}
