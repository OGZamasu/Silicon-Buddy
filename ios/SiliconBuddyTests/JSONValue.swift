import Foundation

/// A JSON document as a comparable value, for checking that a round trip changed nothing.
enum JSONValue: Equatable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    init(data: Data) throws {
        self = try JSONDecoder().decode(JSONValue.self, from: data)
    }

    subscript(key: String) -> JSONValue? {
        if case .object(let dictionary) = self { return dictionary[key] }
        return nil
    }

    subscript(index: Int) -> JSONValue? {
        if case .array(let values) = self, values.indices.contains(index) { return values[index] }
        return nil
    }

    /// An absent optional and an explicit null are the same thing to this API, and the
    /// Mac's encoder omits them. Drop them before comparing.
    func strippingNulls() -> JSONValue {
        switch self {
        case .object(let dictionary):
            var kept: [String: JSONValue] = [:]
            for (key, value) in dictionary where value != .null {
                kept[key] = value.strippingNulls()
            }
            return .object(kept)
        case .array(let values):
            return .array(values.map { $0.strippingNulls() })
        default:
            return self
        }
    }

    /// A readable account of what differs, so a failing contract test names the field.
    func difference(from other: JSONValue, path: String = "") -> [String] {
        switch (self, other) {
        case (.object(let mine), .object(let theirs)):
            var notes: [String] = []
            for key in Set(mine.keys).union(theirs.keys).sorted() {
                let childPath = path.isEmpty ? key : "\(path).\(key)"
                switch (mine[key], theirs[key]) {
                case (nil, .some):
                    notes.append("+ \(childPath) appeared after the round trip")
                case (.some, nil):
                    notes.append("- \(childPath) was lost — is it mirrored?")
                case let (.some(a), .some(b)):
                    notes += a.difference(from: b, path: childPath)
                case (nil, nil):
                    break
                }
            }
            return notes
        case (.array(let mine), .array(let theirs)):
            guard mine.count == theirs.count else {
                return ["\(path) has \(mine.count) items, round trip has \(theirs.count)"]
            }
            return zip(mine, theirs).enumerated().flatMap { index, pair in
                pair.0.difference(from: pair.1, path: "\(path)[\(index)]")
            }
        default:
            return self == other ? [] : ["\(path.isEmpty ? "(root)" : path): \(self) ≠ \(other)"]
        }
    }
}

extension JSONValue: Codable {
    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else if let value = try? container.decode([String: JSONValue].self) {
            self = .object(value)
        } else {
            throw DecodingError.dataCorruptedError(
                in: container, debugDescription: "Not JSON this comparison understands"
            )
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null: try container.encodeNil()
        case .bool(let value): try container.encode(value)
        case .number(let value): try container.encode(value)
        case .string(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        }
    }
}
