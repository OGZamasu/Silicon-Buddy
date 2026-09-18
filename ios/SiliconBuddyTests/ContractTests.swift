import XCTest
@testable import SiliconBuddy

/// The mirrored types, checked against real answers from a running Mac.
///
/// The fixtures in `contract/` were captured from Silicon Optimizer's control server,
/// not written by hand. The round trip is the part that matters: decode, re-encode,
/// decode again, and compare the JSON. A field this app forgot to mirror disappears in
/// the re-encode, so the comparison fails — which is exactly the drift that would
/// otherwise be discovered by a person staring at a blank row.
final class ContractTests: XCTestCase {

    // MARK: - Fixtures

    private func fixture(_ name: String) throws -> Data {
        let bundle = Bundle(for: ContractTests.self)
        let url = try XCTUnwrap(
            bundle.url(forResource: name, withExtension: "json", subdirectory: "contract")
                ?? bundle.url(forResource: name, withExtension: "json"),
            "Missing fixture \(name).json — capture it from a running Mac into contract/"
        )
        return try Data(contentsOf: url)
    }

    /// Decodes, re-encodes and compares. Returns the decoded value for further checks.
    @discardableResult
    private func roundTrip<T: Codable & Equatable>(
        _ type: T.Type, _ name: String, file: StaticString = #filePath, line: UInt = #line
    ) throws -> T {
        let data = try fixture(name)
        let decoded: T
        do {
            decoded = try JSONDecoder.buddy.decode(type, from: data)
        } catch {
            XCTFail("\(name).json does not decode as \(type): \(error)", file: file, line: line)
            throw error
        }
        let reencoded = try JSONEncoder.buddy.encode(decoded)
        let again = try JSONDecoder.buddy.decode(type, from: reencoded)
        XCTAssertEqual(decoded, again, "\(name) changed shape on a round trip", file: file, line: line)

        let original = try JSONValue(data: data).strippingNulls()
        let rebuilt = try JSONValue(data: reencoded).strippingNulls()
        if original != rebuilt {
            XCTFail(
                "\(name).json lost or changed fields on a round trip:\n"
                    + original.difference(from: rebuilt).joined(separator: "\n"),
                file: file, line: line
            )
        }
        return decoded
    }

    // MARK: - Every type this app mirrors

    func testHealth() throws {
        let health = try roundTrip(ControlAPI.Health.self, "health")
        XCTAssertEqual(health.status, "ok")
    }

    func testStatus() throws {
        try roundTrip(ControlAPI.Status.self, "status")
    }

    func testProfile() throws {
        let profile = try roundTrip(ControlAPI.Profile.self, "profile")
        XCTAssertFalse(profile.chip.isEmpty)
        XCTAssertGreaterThan(profile.totalMemoryBytes, 0)
    }

    func testMetrics() throws {
        let metrics = try roundTrip(ControlAPI.Metrics.self, "metrics")
        XCTAssertGreaterThan(metrics.memoryTotalBytes, 0)
        XCTAssertTrue((0...1).contains(metrics.gpuUtilization))
    }

    func testInstalledModels() throws {
        let installed = try roundTrip([ControlAPI.InstalledModel].self, "installed")
        XCTAssertFalse(installed.isEmpty, "The fixture should have been captured with models installed")
        XCTAssertTrue(installed.contains { $0.supportsVision }, "One installed model is a vision model")
    }

    func testCatalog() throws {
        let catalog = try roundTrip([ControlAPI.CatalogModel].self, "catalog")
        XCTAssertGreaterThan(catalog.count, 5)
        XCTAssertTrue(catalog.contains { $0.recommendation != nil }, "The Mac plans for this machine")
        XCTAssertTrue(catalog.contains { $0.featured == true }, "Optional fields must survive")
    }

    func testSwarm() throws {
        try roundTrip(ControlAPI.SwarmView.self, "swarm")
    }

    func testNodeAdvertisement() throws {
        let node = try roundTrip(ControlAPI.NodeAdvertisement.self, "v1-node")
        XCTAssertFalse(node.name.isEmpty)
        XCTAssertGreaterThan(node.profile.memoryGB, 0, "snake_case keys must map")
    }

    func testVideoModels() throws {
        let models = try roundTrip([ControlAPI.VideoModel].self, "video-models")
        XCTAssertTrue(models.contains { $0.node != nil })
        XCTAssertTrue(models.contains { $0.node == nil }, "An unavailable model has no node")
    }

    func testVideoQueue() throws {
        try roundTrip(ControlAPI.VideoQueueView.self, "video-queue")
    }

    func testImageModels() throws {
        let models = try roundTrip([ControlAPI.ImageModel].self, "image-models")
        XCTAssertTrue(models.contains { $0.recommendation != nil })
    }

    func testMeshModels() throws {
        try roundTrip([ControlAPI.MeshModel].self, "mesh-models")
    }

    func testChatResponse() throws {
        let response = try roundTrip(ControlAPI.ChatResponse.self, "chat")
        XCTAssertFalse(response.content.isEmpty)
    }

    func testErrorBodies() throws {
        let unauthorized = try roundTrip(ControlAPI.ErrorResponse.self, "error-401")
        XCTAssertTrue(unauthorized.error.lowercased().contains("token"))
        try roundTrip(ControlAPI.ErrorResponse.self, "error-404")
    }

    // MARK: - Requests

    func testRequestsEncodeTheWayTheMacReadsThem() throws {
        let load = ControlAPI.LoadRequest(modelID: "bonsai-2-27b", quantization: "PTQ1_0")
        let json = try JSONValue(data: JSONEncoder.buddy.encode(load))
        XCTAssertEqual(json["modelID"], .string("bonsai-2-27b"))
        XCTAssertEqual(json["quantization"], .string("PTQ1_0"))
        XCTAssertNil(json["contextLength"], "Absent options must not be sent as null")
    }

    func testChatRequestCarriesImagesAsAnArray() throws {
        let request = ControlAPI.ChatRequest(
            messages: [
                .init(role: "user", content: "What is this?", images: ["data:image/jpeg;base64,AAA"])
            ],
            maxTokens: 64
        )
        let json = try JSONValue(data: JSONEncoder.buddy.encode(request))
        XCTAssertEqual(json["messages"]?[0]?["role"], .string("user"))
        XCTAssertEqual(json["messages"]?[0]?["images"]?[0], .string("data:image/jpeg;base64,AAA"))
        XCTAssertEqual(json["maxTokens"], .number(64))
    }

    func testAnEmptyImageListIsStillSent() throws {
        // The Mac's Message.images is not optional; sending no key at all would be a
        // decode failure on a stricter server.
        let request = ControlAPI.ChatRequest(messages: [.init(role: "user", content: "hi")])
        let json = try JSONValue(data: JSONEncoder.buddy.encode(request))
        XCTAssertEqual(json["messages"]?[0]?["images"], .array([]))
    }

    // MARK: - Decoding is forgiving where the Mac says it may be

    func testAStatusFromAnOlderMacStillDecodes() throws {
        let json = Data(#"{"state":"Not loaded","expertStreaming":false}"#.utf8)
        let status = try JSONDecoder.buddy.decode(ControlAPI.Status.self, from: json)
        XCTAssertNil(status.loadedModelID)
        XCTAssertFalse(status.hasLoadedModel)
    }

    func testACatalogEntryWithoutTheOptionalFieldsDecodes() throws {
        let json = Data("""
        {"id":"x","name":"X","author":"A","license":"MIT","summary":"s","category":"General",
         "parameters":"7B","isMoE":false,"capabilities":[],"rating":3,"maxContext":8192,
         "quantizations":["Q4_K_M"]}
        """.utf8)
        let entry = try JSONDecoder.buddy.decode(ControlAPI.CatalogModel.self, from: json)
        XCTAssertNil(entry.featured)
        XCTAssertNil(entry.recommendation)
        XCTAssertNil(entry.runtimeNote)
    }

    func testConversationTimestampsDecodeBothISOSpellings() throws {
        let withFraction = Data(#"{"id":"1","title":"t","updatedAt":"2026-09-18T10:11:12.345Z","messageCount":2}"#.utf8)
        let plain = Data(#"{"id":"1","title":"t","updatedAt":"2026-09-18T10:11:12Z","messageCount":2}"#.utf8)
        XCTAssertNoThrow(try JSONDecoder.buddy.decode(BuddyAPI.ConversationSummary.self, from: withFraction))
        XCTAssertNoThrow(try JSONDecoder.buddy.decode(BuddyAPI.ConversationSummary.self, from: plain))
    }
}
