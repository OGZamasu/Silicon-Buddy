import XCTest
@testable import SiliconBuddy

/// Turning what somebody said into what the Mac wants.
final class IntentMappingTests: XCTestCase {

    private func model(_ id: String, _ name: String, vision: Bool = false) -> ControlAPI.InstalledModel {
        ControlAPI.InstalledModel(
            id: id, name: name, quantization: "Q4_K_M", sizeOnDiskBytes: 1,
            isLoaded: false, supportsVision: vision
        )
    }

    private var installed: [ControlAPI.InstalledModel] {
        [
            model("qwen3-4b-mlx@MLX-4bit", "Qwen3 4B (MLX)"),
            model("gemma-3-12b-it@Q4_K_M", "Gemma 3 12B", vision: true),
            model("gemma-3-27b-it@Q4_K_M", "Gemma 3 27B", vision: true),
            model("qwen3-coder-30b-a3b@Q4_K_M", "Qwen3-Coder 30B A3B"),
        ]
    }

    // MARK: - Ask my Mac

    func testAQuestionBecomesOneUserMessageWithNoHistory() throws {
        let request = try IntentMapping.chatRequest(prompt: "  What is a monad?  ")
        XCTAssertEqual(request.messages.count, 1)
        XCTAssertEqual(request.messages[0].role, "user")
        XCTAssertEqual(request.messages[0].content, "What is a monad?")
        XCTAssertEqual(request.maxTokens, IntentMapping.spokenMaxTokens)
    }

    func testAnEmptyQuestionIsRefusedBeforeItReachesTheMac() {
        XCTAssertThrowsError(try IntentMapping.chatRequest(prompt: "   \n ")) { error in
            XCTAssertEqual(error as? IntentMapping.Refusal, .emptyPrompt)
        }
    }

    func testAPictureWithNoWordsIsStillAQuestion() throws {
        let request = try IntentMapping.chatRequest(prompt: "", images: ["data:image/jpeg;base64,AA"])
        XCTAssertEqual(request.messages[0].images.count, 1)
    }

    // MARK: - Load a model

    func testAnExactIdWins() throws {
        let request = try IntentMapping.loadRequest(
            named: "gemma-3-12b-it@Q4_K_M", in: installed, scope: .full
        )
        XCTAssertEqual(request.modelID, "gemma-3-12b-it@Q4_K_M")
    }

    func testTheNameAsAPersonSaysItResolves() throws {
        let request = try IntentMapping.loadRequest(named: "Qwen3 4B (MLX)", in: installed, scope: .full)
        XCTAssertEqual(request.modelID, "qwen3-4b-mlx@MLX-4bit")
    }

    func testTheIdWithoutItsQuantizationResolves() throws {
        let request = try IntentMapping.loadRequest(named: "gemma-3-27b-it", in: installed, scope: .full)
        XCTAssertEqual(request.modelID, "gemma-3-27b-it@Q4_K_M")
    }

    func testCaseAndSpacingDoNotMatter() throws {
        let request = try IntentMapping.loadRequest(named: "qwen3 CODER 30b a3b", in: installed, scope: .full)
        XCTAssertEqual(request.modelID, "qwen3-coder-30b-a3b@Q4_K_M")
    }

    /// The case that matters: two 27B-sized mistakes are a gigabyte of memory and
    /// several minutes each, so a phrase that could mean either has to ask again.
    func testAPhraseThatMatchesTwoModelsRefusesRatherThanGuessing() {
        XCTAssertThrowsError(
            try IntentMapping.loadRequest(named: "gemma", in: installed, scope: .full)
        ) { error in
            guard case .ambiguous(let phrase, let candidates)? = error as? IntentMapping.Refusal
            else { return XCTFail("expected an ambiguity, got \(error)") }
            XCTAssertEqual(phrase, "gemma")
            XCTAssertEqual(candidates.count, 2)
        }
    }

    func testAPhraseThatMatchesNothingSaysSo() {
        XCTAssertThrowsError(
            try IntentMapping.loadRequest(named: "llama", in: installed, scope: .full)
        ) { error in
            XCTAssertEqual(error as? IntentMapping.Refusal, .noSuchModel("llama"))
        }
    }

    /// A device paired for chat cannot spend the machine, and it is told that here
    /// rather than by a 403 after a round trip.
    func testAChatOnlyDeviceCannotLoadAnything() {
        XCTAssertThrowsError(
            try IntentMapping.loadRequest(named: "Gemma 3 12B", in: installed, scope: .chat)
        ) { error in
            XCTAssertEqual(error as? IntentMapping.Refusal, .notAllowed)
        }
    }

    func testAPrefixOfOneNameResolvesButAPrefixOfTwoDoesNot() throws {
        XCTAssertEqual(
            try IntentMapping.resolveModel(named: "Qwen3 4B", in: installed, scope: .full).id,
            "qwen3-4b-mlx@MLX-4bit"
        )
        XCTAssertThrowsError(
            try IntentMapping.resolveModel(named: "Gemma 3", in: installed, scope: .full)
        )
    }

    // MARK: - What is loaded

    func testTheSpokenSummaryNamesTheModelAndTheMac() {
        let status = ControlAPI.Status(
            state: "Ready", loadedModelID: "gemma-3-12b-it@Q4_K_M",
            loadedModelName: "Gemma 3 12B", contextLength: 65536,
            lastGenerationTokensPerSecond: 42.4
        )
        let sentence = IntentMapping.loadedSummary(status, macName: "Studio")
        XCTAssertEqual(sentence, "Studio has Gemma 3 12B loaded, with a 64K context, last answering at 42 tokens a second.")
    }

    func testNothingLoadedIsSaidPlainly() {
        let sentence = IntentMapping.loadedSummary(ControlAPI.Status(state: "Idle"), macName: nil)
        XCTAssertEqual(sentence, "Nothing is loaded on your Mac right now.")
    }

    func testAnOddContextLengthKeepsItsDecimal() {
        XCTAssertEqual(IntentMapping.contextPhrase(65536), "64K")
        XCTAssertEqual(IntentMapping.contextPhrase(40960), "40K")
        XCTAssertEqual(IntentMapping.contextPhrase(1536), "1.5K")
        XCTAssertEqual(IntentMapping.contextPhrase(512), "512 token")
    }
}
