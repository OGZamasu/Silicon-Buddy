import Foundation
import Network

/// An event stream that goes quiet: a 200 head, `frames` heartbeat frames `every` seconds
/// apart, and then either a close or silence for good — the connection a Mac leaves behind
/// when it goes away without its goodbye reaching the phone.
///
/// Not `LoopbackServer`, which answers and closes: the point here is a socket that stays
/// open and says nothing.
final class StallServer: @unchecked Sendable {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "dev.siliconoptimizer.buddy.tests.stall")
    private let lock = NSLock()
    private var held: [NWConnection] = []
    private var opened = 0
    private(set) var port: Int = 0

    /// How many connections have been opened to it.
    var connections: Int { lock.withLock { opened } }

    init(frames: Int, every: TimeInterval, thenClose: Bool) throws {
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
            self.lock.withLock {
                self.held.append(connection)
                self.opened += 1
            }
            connection.start(queue: self.queue)
            connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { _, _, _, _ in
                let head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                    + "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
                connection.send(content: Data(head.utf8), completion: .contentProcessed { _ in })
                for index in 0..<frames {
                    self.queue.asyncAfter(deadline: .now() + Double(index) * every) {
                        let frame = "event: heartbeat\ndata: {}\n\n"
                        connection.send(content: Data(frame.utf8), completion: .contentProcessed { _ in
                            if thenClose, index == frames - 1 { connection.cancel() }
                        })
                    }
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
