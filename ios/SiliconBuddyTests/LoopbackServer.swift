import Foundation
import Network

/// A real HTTP server on 127.0.0.1, for tests that are about what goes over a socket.
///
/// `URLProtocol` is the usual shortcut, but `URLSession.bytes(for:)` — which is how the
/// app reads an event stream — does not deliver a custom protocol's body. So this binds
/// a port, speaks the little of HTTP the Mac speaks, and answers whatever the test set.
/// It also records what arrived, which is how "what did the client actually send?" gets
/// an answer rather than an assumption.
final class LoopbackServer: @unchecked Sendable {

    struct Reply {
        var status: Int
        var body: String
        var contentType = "application/json"
        /// Sent in pieces with a pause between them, the way an event stream arrives.
        var chunked = false
    }

    struct Received {
        var method: String
        var path: String
        var headers: [String: String]
        var body: Data
    }

    private let listener: NWListener
    private let queue = DispatchQueue(label: "dev.siliconoptimizer.buddy.tests.server")
    private let lock = NSLock()
    private var repliesByPath: [String: Reply] = [:]
    private var receivedRequests: [Received] = []

    private(set) var port: Int = 0

    init() throws {
        let parameters = NWParameters.tcp
        parameters.requiredInterfaceType = .loopback
        parameters.allowLocalEndpointReuse = true
        listener = try NWListener(using: parameters, on: .any)

        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { [weak self] state in
            guard case .ready = state, let self else { return }
            self.port = Int(self.listener.port?.rawValue ?? 0)
            ready.signal()
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.serve(connection)
        }
        listener.start(queue: queue)
        _ = ready.wait(timeout: .now() + 5)
    }

    deinit { listener.cancel() }

    func stop() { listener.cancel() }

    // MARK: - What it answers

    func reply(_ path: String, _ status: Int, _ body: String) {
        lock.lock()
        repliesByPath[path] = Reply(status: status, body: body)
        lock.unlock()
    }

    func events(_ path: String, _ body: String, chunked: Bool = true) {
        lock.lock()
        repliesByPath[path] = Reply(
            status: 200, body: body, contentType: "text/event-stream", chunked: chunked
        )
        lock.unlock()
    }

    // MARK: - What it saw

    var requests: [Received] {
        lock.lock()
        defer { lock.unlock() }
        return receivedRequests
    }

    func request(to path: String) -> Received? {
        requests.last { $0.path == path }
    }

    // MARK: - The little of HTTP the Mac speaks

    private func serve(_ connection: NWConnection) {
        connection.start(queue: queue)
        read(connection, buffer: Data())
    }

    private func read(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) {
            [weak self] data, _, isComplete, error in
            guard let self else { return }
            var buffer = buffer
            if let data { buffer.append(data) }
            if error != nil { return connection.cancel() }

            guard let headerEnd = buffer.range(of: Data("\r\n\r\n".utf8)) else {
                if isComplete { return connection.cancel() }
                return self.read(connection, buffer: buffer)
            }
            let headerText = String(decoding: buffer[..<headerEnd.lowerBound], as: UTF8.self)
            var lines = headerText.components(separatedBy: "\r\n")
            let requestLine = lines.removeFirst().split(separator: " ")
            var headers: [String: String] = [:]
            for line in lines {
                guard let colon = line.firstIndex(of: ":") else { continue }
                headers[line[..<colon].lowercased()] =
                    line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            }
            var body = Data(buffer[headerEnd.upperBound...])
            if let length = headers["content-length"].flatMap(Int.init), body.count < length {
                if isComplete { return connection.cancel() }
                return self.read(connection, buffer: buffer)
            }

            let target = requestLine.count > 1 ? String(requestLine[1]) : "/"
            let path = URLComponents(string: "http://localhost\(target)")?.path ?? target
            self.lock.lock()
            self.receivedRequests.append(
                Received(
                    method: requestLine.first.map(String.init) ?? "GET",
                    path: path, headers: headers, body: body
                )
            )
            let reply = self.repliesByPath[path]
                ?? Reply(status: 404, body: #"{"error":"Unknown endpoint"}"#)
            self.lock.unlock()
            body = Data()
            self.write(reply, to: connection)
        }
    }

    private func write(_ reply: Reply, to connection: NWConnection) {
        let payload = Data(reply.body.utf8)
        let head = [
            "HTTP/1.1 \(reply.status) \(reply.status == 200 ? "OK" : "Error")",
            "Content-Type: \(reply.contentType)",
            "Content-Length: \(payload.count)",
            "Cache-Control: no-store",
            "Connection: close",
        ].joined(separator: "\r\n") + "\r\n\r\n"

        connection.send(content: Data(head.utf8), completion: .contentProcessed { _ in
            guard reply.chunked, payload.count > 32 else {
                connection.send(content: payload, completion: .contentProcessed { _ in
                    connection.cancel()
                })
                return
            }
            // An event stream arrives in pieces, and the parser has to survive a split
            // that lands in the middle of a line.
            let half = payload.count / 2
            connection.send(content: payload.prefix(half), completion: .contentProcessed { _ in
                self.queue.asyncAfter(deadline: .now() + 0.05) {
                    connection.send(
                        content: payload.suffix(from: half),
                        completion: .contentProcessed { _ in connection.cancel() }
                    )
                }
            })
        })
    }
}
