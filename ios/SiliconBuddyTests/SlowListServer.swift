import Foundation
import Network

/// A Mac that keeps conversations and is slow to list them the second time.
///
/// `GET /conversations` answers "[]" at once the first time and after `delay` seconds from
/// then on; `POST /conversations/<id>/messages` answers a finished reply at once. So a send's
/// last step — reading the Mac's list again after the answer — can be caught in the middle.
final class SlowListServer: @unchecked Sendable {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "dev.siliconoptimizer.buddy.tests.slowlist")
    private let lock = NSLock()
    private var seen: [String: Int] = [:]
    private var held: [NWConnection] = []
    private(set) var port: Int = 0

    /// How many times `key` ("GET /conversations", "POST messages") was asked.
    func count(_ key: String) -> Int { lock.withLock { seen[key] ?? 0 } }

    init(delay: TimeInterval = 3) throws {
        let parameters = NWParameters.tcp
        parameters.requiredInterfaceType = .loopback
        listener = try NWListener(using: parameters, on: .any)
        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { [weak self] state in
            guard case .ready = state, let self else { return }
            self.port = Int(self.listener.port?.rawValue ?? 0)
            ready.signal()
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return }
            self.lock.withLock { self.held.append(connection) }
            connection.start(queue: self.queue)
            connection.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { data, _, _, _ in
                let text = String(decoding: data ?? Data(), as: UTF8.self)
                let requestLine = text.split(separator: "\r\n").first.map(String.init) ?? ""
                let parts = requestLine.split(separator: " ")
                let method = parts.first.map(String.init) ?? ""
                let path = parts.count > 1 ? String(parts[1]) : ""
                var key = "\(method) \(path)"
                if method == "POST", path.hasPrefix("/conversations/"), path.hasSuffix("/messages") {
                    key = "POST messages"
                }
                let asked: Int = self.lock.withLock {
                    self.seen[key, default: 0] += 1
                    return self.seen[key] ?? 0
                }
                var body = "{}"
                var type = "application/json"
                var wait = 0.0
                if key == "GET /conversations" {
                    body = "[]"
                    wait = asked == 1 ? 0 : delay
                } else if key == "POST messages" {
                    type = "text/event-stream"
                    body = "event: token\ndata: {\"text\":\"A\"}\n\n"
                        + "event: finished\ndata: {\"promptTokens\":1,\"generatedTokens\":1,\"tokensPerSecond\":1}\n\n"
                }
                self.queue.asyncAfter(deadline: .now() + wait) {
                    let head = "HTTP/1.1 200 OK\r\nContent-Type: \(type)\r\n"
                        + "Content-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n"
                    connection.send(content: Data((head + body).utf8), completion: .contentProcessed { _ in
                        connection.cancel()
                    })
                }
            }
        }
        listener.start(queue: queue)
        _ = ready.wait(timeout: .now() + 5)
    }

    deinit { listener.cancel() }

    func stop() {
        listener.cancel()
        lock.withLock { held.forEach { $0.cancel() } }
    }
}
