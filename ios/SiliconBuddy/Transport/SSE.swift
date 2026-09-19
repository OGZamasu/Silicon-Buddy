import Foundation

/// One server-sent event, as the spec defines it: a name, a body, and an optional id.
public struct SSEEvent: Sendable, Equatable {
    /// The `event:` field, or "message" when the stream did not name one.
    public var name: String
    /// The `data:` fields joined with newlines, as the spec requires.
    public var data: String
    public var id: String?
    /// A `retry:` field, in milliseconds.
    public var retry: Int?

    public init(name: String = "message", data: String, id: String? = nil, retry: Int? = nil) {
        self.name = name
        self.data = data
        self.id = id
        self.retry = retry
    }

    /// Decodes the data field as JSON.
    public func decode<T: Decodable>(_ type: T.Type, using decoder: JSONDecoder = .buddy) throws -> T {
        try decoder.decode(type, from: Data(data.utf8))
    }
}

/// A line-at-a-time parser for `text/event-stream`.
///
/// Written as a value that takes lines rather than a thing that owns a socket, because
/// the interesting cases — multi-line data, comments, a stream that ends mid-event —
/// are worth testing without a server, and because the same parser then serves both
/// `/chat/stream` and `/events`.
public struct SSEParser: Sendable {
    private var name: String?
    private var data: [String] = []
    private var id: String?
    private var retry: Int?
    public init() {}

    /// Feeds one line, without its terminator. Returns an event when the line was the
    /// blank one that ends a block.
    public mutating func consume(line: String) -> SSEEvent? {
        // A stray carriage return from a CRLF stream is not part of the value.
        let line = line.hasSuffix("\r") ? String(line.dropLast()) : line

        if line.isEmpty {
            defer { reset() }
            // A block with no data fields dispatches nothing, per the spec.
            guard !data.isEmpty else { return nil }
            return SSEEvent(
                name: name ?? "message",
                data: data.joined(separator: "\n"),
                id: id,
                retry: retry
            )
        }

        // A comment — the Mac's keep-alive is one. Nothing to dispatch.
        if line.hasPrefix(":") { return nil }

        let field: String
        var value: String
        if let colon = line.firstIndex(of: ":") {
            field = String(line[line.startIndex..<colon])
            value = String(line[line.index(after: colon)...])
            // Exactly one leading space is stripped.
            if value.hasPrefix(" ") { value.removeFirst() }
        } else {
            field = line
            value = ""
        }

        switch field {
        case "event": name = value
        case "data": data.append(value)
        case "id": id = value.contains("\0") ? id : value
        case "retry": retry = Int(value) ?? retry
        default: break // Unknown fields are ignored, not an error.
        }
        return nil
    }

    /// Feeds a chunk of bytes that may hold any number of lines, returning every
    /// complete event in it. Any trailing partial line is kept for the next chunk.
    public mutating func consume(chunk: String) -> [SSEEvent] {
        pending += chunk
        var events: [SSEEvent] = []
        while let breakRange = pending.rangeOfCharacter(from: CharacterSet(charactersIn: "\n")) {
            let line = String(pending[pending.startIndex..<breakRange.lowerBound])
            pending.removeSubrange(pending.startIndex..<breakRange.upperBound)
            if let event = consume(line: line) { events.append(event) }
        }
        return events
    }

    /// Whatever is left when the stream ends: a final event, if the server hung up
    /// after the data but before the blank line.
    public mutating func finish() -> SSEEvent? {
        if !pending.isEmpty {
            let line = pending
            pending = ""
            if let event = consume(line: line) { return event }
        }
        guard !data.isEmpty else { return nil }
        defer { reset() }
        return SSEEvent(
            name: name ?? "message",
            data: data.joined(separator: "\n"),
            id: id,
            retry: retry
        )
    }

    private var pending = ""

    private mutating func reset() {
        name = nil
        data = []
        // `id` and `retry` persist across events, as the spec says; the block-scoped
        // fields do not.
    }
}

extension JSONDecoder {
    /// The decoder the whole app uses: ISO-8601 dates, because that is what the Mac's
    /// conversation timestamps are.
    public static var buddy: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let text = try container.decode(String.self)
            if let date = ISO8601DateFormatter.buddyWithFractionalSeconds.date(from: text) {
                return date
            }
            if let date = ISO8601DateFormatter.buddyPlain.date(from: text) {
                return date
            }
            throw DecodingError.dataCorruptedError(
                in: container, debugDescription: "Not an ISO-8601 date: \(text)"
            )
        }
        return decoder
    }
}

extension JSONEncoder {
    public static var buddy: JSONEncoder {
        let encoder = JSONEncoder()
        // Seconds, no milliseconds: this is the spelling the Mac uses, and a contract
        // test compares what this app writes with what the Mac wrote.
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(ISO8601DateFormatter.buddyPlain.string(from: date))
        }
        return encoder
    }
}

extension ISO8601DateFormatter {
    // Formatting is thread-safe on these; they are configured once and only read.
    nonisolated(unsafe) static let buddyWithFractionalSeconds: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    nonisolated(unsafe) static let buddyPlain: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()
}
