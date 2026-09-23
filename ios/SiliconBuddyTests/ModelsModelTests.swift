import XCTest
@testable import SiliconBuddy

/// A load outlives its request (#13). `POST /load` answers after 25 seconds whether or not
/// the model is in, so the answer can be "still loading" — and the Models screen has to stay
/// with the load, through the Mac's pushed status or by asking, until it has an ending.
@MainActor
final class ModelsModelTests: XCTestCase {

    private let id = "test-model@Q4_K_M"
    private lazy var onDisk = ControlAPI.InstalledModel(
        id: id, name: "Test Model", quantization: "Q4_K_M", sizeOnDiskBytes: 100,
        isLoaded: false, supportsVision: false
    )
    private let loading = ControlAPI.Status(state: "Loading Test Model…")
    private let weights = ControlAPI.Status(state: "Loading weights… 42%")
    private lazy var loaded = ControlAPI.Status(
        state: "Ready", loadedModelID: id, loadedModelName: "Test Model", contextLength: 4096
    )
    private let killed = "llama-server was killed (signal 9) after 8 seconds, which usually means "
        + "the system reclaimed its memory."
    private let log = "load_tensors: loading model tensors\nloaded multimodal model, 'mmproj-Q8_0.gguf'"

    private func failed(
        _ reason: String, detail: String?? = .none, replaced: Bool = false, state: String? = nil,
        modelID: String? = nil
    ) -> ControlAPI.Status {
        ControlAPI.Status(
            state: state ?? killed,
            failure: ControlAPI.LoadFailure(
                reason: reason, detail: detail ?? log, runtime: "llama.cpp", signal: 9,
                wasReplaced: replaced, at: "2026-09-19T11:04:38Z", modelID: modelID
            )
        )
    }

    /// What a Mac with `interruptedLoads` (OGZamasu/silicon-optimizer#97) says of a load it stopped.
    private func stopped(
        _ reason: String, replacedBy: String? = nil, state: String = "Loading weights… 42%"
    ) -> ControlAPI.Status {
        ControlAPI.Status(
            state: state,
            interruptedLoads: [.init(modelID: id, reason: reason, replacedBy: replacedBy, at: "2026-09-19T11:04:38Z")]
        )
    }

    /// Starts a load, lets it follow, and pushes `pushed` as the Mac's next status frame.
    private func follow(until pushed: ControlAPI.Status) async throws -> ModelsModel {
        let model = quickModel()
        model.eventsLive = true
        let mac = mac(answering: loading)
        await model.refresh(using: mac)
        let following = Task { await model.load(modelID: id, using: mac) }
        try await until("following") { model.job?.message == "Loading Test Model…" }
        model.statusChanged(pushed)
        await following.value
        return model
    }

    /// A model whose clock runs in milliseconds, so a ten-minute follow fits in a test.
    private func quickModel() -> ModelsModel {
        let model = ModelsModel()
        model.pollWithoutEvents = .milliseconds(20)
        model.pollWithEvents = .seconds(30)
        model.followLimit = .milliseconds(400)
        return model
    }

    private func mac(answering answer: ControlAPI.Status, then readings: [ControlAPI.Status] = []) -> StubTransport {
        let mac = StubTransport()
        mac.loadResult = .success(answer)
        mac.statusResult = .success(answer)
        mac.statusReadings = readings
        mac.installedResult = .success([onDisk])
        return mac
    }

    private func until(
        _ what: String, within limit: Duration = .seconds(3), _ condition: () -> Bool
    ) async throws {
        let deadline = ContinuousClock.now + limit
        while !condition() {
            guard ContinuousClock.now < deadline else { return XCTFail("never: \(what)") }
            try await Task.sleep(for: .milliseconds(5))
        }
    }

    // MARK: - Following a load

    func testAQuickLoadIsOverWhenTheAnswerSaysSo() async throws {
        let model = quickModel()
        let mac = mac(answering: loaded)
        await model.load(modelID: id, using: mac)
        XCTAssertNil(model.job)
        XCTAssertNil(model.problem)
        XCTAssertTrue(model.isLoaded(id))
        XCTAssertEqual(mac.statusReads, 1, "only the refresh afterwards reads the status")
    }

    func testADelayedSuccessIsFollowedByAskingWhenThereIsNoFeed() async throws {
        let model = quickModel()
        let mac = mac(answering: loading, then: [weights, weights, weights, weights, weights, loaded])
        let following = Task { await model.load(modelID: id, using: mac) }
        try await until("the answer is not the end of it") { model.job?.kind == .load }
        try await until("the row says what the Mac says") { model.job?.message == "Loading weights… 42%" }
        await following.value
        XCTAssertNil(model.job)
        XCTAssertNil(model.problem)
        XCTAssertTrue(model.isLoaded(id))
        let reads = mac.statusReads
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertEqual(mac.statusReads, reads, "a finished load is not asked about again")
    }

    func testADelayedSuccessArrivesOnTheEventFeedWithoutAsking() async throws {
        let model = quickModel()
        model.eventsLive = true
        let mac = mac(answering: loading, then: [loaded])
        let following = Task { await model.load(modelID: id, using: mac) }
        try await until("following") { model.job?.message == "Loading Test Model…" }
        model.statusChanged(weights)
        try await until("the pushed stage") { model.job?.message == "Loading weights… 42%" }
        model.statusChanged(loaded)
        await following.value
        XCTAssertNil(model.job)
        XCTAssertTrue(model.isLoaded(id))
        XCTAssertEqual(mac.statusReads, 1, "the frames were enough: only the refresh afterwards asked")
    }

    func testADelayedFailureSaysTheSentenceWithTheLogBehindIt() async throws {
        let model = quickModel()
        model.eventsLive = true
        let mac = mac(answering: loading, then: [failed("killed")])
        await model.refresh(using: mac)
        let following = Task { await model.load(modelID: id, using: mac) }
        try await until("following") { model.job?.message == "Loading Test Model…" }
        model.statusChanged(failed("killed"))
        await following.value

        XCTAssertNil(model.job)
        let problem = try XCTUnwrap(model.problem)
        XCTAssertEqual(problem.title, "Couldn't load Test Model")
        XCTAssertEqual(problem.message, killed, "the one line comes first")
        XCTAssertEqual(problem.detail, log, "the log is there, for the disclosure")
        XCTAssertTrue(problem.isFault)
        XCTAssertEqual(problem.retry, .load(modelID: id, quantization: nil))
        XCTAssertNil(model.standingFailure, "the list does not say it a second time")
    }

    func testALoadThatAnotherLoadReplacedIsNotAFault() async throws {
        let model = quickModel()
        model.eventsLive = true
        let mac = mac(answering: loading)
        await model.refresh(using: mac)
        let following = Task { await model.load(modelID: id, using: mac) }
        try await until("following") { model.job?.message == "Loading Test Model…" }
        let replaced = failed(
            "replaced", detail: .some(nil), replaced: true,
            state: "llama-server was replaced by another load (Qwen3-Coder 30B)."
        )
        model.statusChanged(replaced)
        await following.value
        XCTAssertEqual(model.problem?.title, "Test Model wasn't loaded")
        XCTAssertEqual(model.problem?.message, replaced.state)
        XCTAssertEqual(model.problem?.isFault, false)
        XCTAssertNil(model.problem?.retry, "asking again would undo somebody's choice")
    }

    func testAnotherModelResidentInsteadIsAReplacementToo() async throws {
        let model = quickModel()
        let other = ControlAPI.Status(
            state: "Ready", loadedModelID: "qwen3-coder-30b", loadedModelName: "Qwen3-Coder 30B A3B"
        )
        let mac = mac(answering: loading, then: [loading, other])
        await model.load(modelID: id, using: mac)
        XCTAssertNil(model.job)
        XCTAssertEqual(model.problem?.message, "The Mac loaded Qwen3-Coder 30B A3B instead.")
        XCTAssertEqual(model.problem?.isFault, false)
        XCTAssertTrue(model.isLoaded("qwen3-coder-30b"))
    }

    func testALoadCalledOffOnTheMacEndsAsCancelled() async throws {
        let model = quickModel()
        model.eventsLive = true
        let mac = mac(answering: loading)
        let following = Task { await model.load(modelID: id, using: mac) }
        try await until("following") { model.job?.message == "Loading Test Model…" }
        let cancelled = failed(
            "cancelled", detail: .some(nil),
            state: "llama-server was stopped by an unload before it finished loading."
        )
        model.statusChanged(cancelled)
        await following.value
        XCTAssertEqual(model.problem?.message, cancelled.state)
        XCTAssertEqual(model.problem?.isFault, false)
        XCTAssertEqual(ModelsModel.LoadOutcome.of(cancelled, modelID: id), .cancelled)
    }

    func testAReasonThisAppHasNeverHeardOfIsAFailureShownByItsSentence() async throws {
        let model = quickModel()
        model.eventsLive = true
        let mac = mac(answering: loading)
        let following = Task { await model.load(modelID: id, using: mac) }
        try await until("following") { model.job?.message == "Loading Test Model…" }
        let novel = failed("outOfCheese", state: "The runtime ran out of cheese.")
        XCTAssertEqual(novel.failure?.kind, .exited)
        model.statusChanged(novel)
        await following.value
        XCTAssertEqual(model.problem?.message, "The runtime ran out of cheese.")
        XCTAssertEqual(model.problem?.isFault, true)
        XCTAssertEqual(model.problem?.detail, log)
    }

    func testAChatScopeReadingHasNoLogAndNothingPretendsItHas() async throws {
        let model = quickModel()
        let withheld = failed("killed", detail: .some(nil))
        let mac = mac(answering: withheld)
        await model.refresh(using: mac)
        let standing = try XCTUnwrap(model.standingFailure, "a chat device still sees that the last load failed")
        XCTAssertNil(standing.detail, "and not the runtime's log, which it was not sent")
        XCTAssertEqual(standing.facts, "llama.cpp · signal 9")
        XCTAssertEqual(model.status?.state, killed)
    }

    func testAPushedFrameOlderThanTheAnswerIsNotTheEnding() async throws {
        let model = quickModel()
        model.eventsLive = true
        // Asked at once because a frame was dropped, the Mac says it is still loading.
        let mac = mac(answering: loading, then: [loading, loaded])
        mac.loadDelay = .milliseconds(150)
        let following = Task { await model.load(modelID: id, using: mac) }
        try await until("asking") { mac.loads == 1 }
        // The Mac's previous failure, arriving while the request is out.
        model.statusChanged(failed("killed"))
        try await until("the answer, and the reading after it") { mac.statusReads == 1 }
        XCTAssertEqual(model.status, loading)
        XCTAssertNil(model.problem, "a failure from before this load is not this load's")
        XCTAssertEqual(model.job?.kind, .load)
        model.statusChanged(loaded)
        await following.value
        XCTAssertNil(model.problem)
        XCTAssertTrue(model.isLoaded(id))
    }

    func testFollowingIsBoundedAndSaysSoWhenTheMacNeverDid() async throws {
        let model = quickModel()
        let mac = mac(answering: loading)
        await model.refresh(using: mac)
        await model.load(modelID: id, using: mac)
        XCTAssertNil(model.job)
        XCTAssertEqual(model.problem?.title, "Still loading Test Model")
        XCTAssertEqual(model.problem?.isFault, false)
        XCTAssertEqual(model.problem?.retry, .refresh)
        let reads = mac.statusReads
        XCTAssertGreaterThan(reads, 5, "it kept asking while it waited")
        try await Task.sleep(for: .milliseconds(150))
        XCTAssertEqual(mac.statusReads, reads, "and then stopped")
    }

    func testAResetWhileWaitingStopsFollowingAndTheOldAnswerLandsNowhere() async throws {
        let model = quickModel()
        let old = mac(answering: loaded)
        old.loadDelay = .milliseconds(100)
        let following = Task { await model.load(modelID: id, using: old) }
        try await until("asking") { old.loads == 1 }
        model.reset()
        await following.value
        try await Task.sleep(for: .milliseconds(150))
        XCTAssertNil(model.job)
        XCTAssertNil(model.status, "the old Mac's status is not the new pairing's")
        XCTAssertNil(model.problem)
        XCTAssertTrue(model.installed.isEmpty)
    }

    func testARePairStopsAskingTheOldMacAboutItsSlowLoad() async throws {
        let model = quickModel()
        let old = mac(answering: loading)
        let following = Task { await model.load(modelID: id, using: old) }
        try await until("asking about it") { old.statusReads > 1 }
        model.reset()
        await following.value
        let asked = old.statusReads
        let next = mac(answering: ControlAPI.Status(state: "Not loaded"))
        await model.refresh(using: next)
        model.statusChanged(failed("killed"))
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertEqual(old.statusReads, asked, "the old Mac is not asked again")
        XCTAssertNil(model.job)
        XCTAssertNil(model.problem, "nothing was being followed, so nothing failed")
    }

    func testARePairWhileAnInstallPollIsOutLeavesNoInstallBehind() async throws {
        let model = quickModel()
        model.installPollInterval = .milliseconds(20)
        let old = mac(answering: loading)
        old.installedHangs = true
        let entry = ControlAPI.CatalogModel(
            id: "test-model", name: "Test Model", author: "Test", license: "MIT",
            summary: "", category: "chat", parameters: "2B", activeParameters: nil, isMoE: false,
            capabilities: ["chat"], rating: 1, maxContext: 4096, quantizations: ["Q4_K_M"],
            recommendation: nil
        )
        let installing = Task { await model.install(model: entry, quantization: nil, using: old) }
        try await until("the poll is out") { model.job?.message == "Downloading." }
        try await Task.sleep(for: .milliseconds(60))
        model.reset()
        await installing.value
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertNil(model.job, "no phantom install on the next Mac's screen, and Unload is not held off")
        XCTAssertTrue(model.installed.isEmpty)
    }

    /// A re-pair while the follow-up GET /status is out — the old Mac asleep, say.
    func testARePairWhileAFollowUpPollIsOutLeavesNoLoadBehind() async throws {
        let model = quickModel()
        let old = mac(answering: loading)
        old.statusHangs = true
        let following = Task { await model.load(modelID: id, using: old) }
        try await until("following") { model.job?.message == "Loading Test Model…" }
        try await Task.sleep(for: .milliseconds(80)) // past the 20 ms wait: the poll is out
        model.reset()
        await following.value
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertNil(model.job, "no phantom load on the next Mac's screen, and Unload is not held off")
    }

    func testALoadThatEndedWhileTheAnswerWasOutIsReadAtOnce() async throws {
        let model = quickModel()
        model.eventsLive = true
        // The load finished just after the Mac stopped waiting: its answer says "loading",
        // the frame saying "loaded" arrived first, and the Mac will not push it again.
        let mac = mac(answering: loading, then: [loaded])
        mac.loadDelay = .milliseconds(150)
        let following = Task { await model.load(modelID: id, using: mac) }
        try await until("asking") { mac.loads == 1 }
        model.statusChanged(loaded)
        // The slow poll behind a live feed is 30 s here, so only asking at once ends this.
        await following.value
        XCTAssertNil(model.job)
        XCTAssertTrue(model.isLoaded(id))
        XCTAssertNil(model.problem)
    }

    func testALoadThatFailsBeforeTheMacStopsWaitingCarriesItsLogOnce() async throws {
        let model = quickModel()
        let mac = mac(answering: failed("killed"))
        // What the Mac answers a load that fails inside its patience: `ControlHostError
        // .loadFailed(state)`, a 400 whose sentence ends with the status line.
        mac.loadResult = .failure(TransportError.badRequest("The model failed to load: \(killed)"))
        await model.refresh(using: mac)
        await model.load(modelID: id, using: mac)
        XCTAssertEqual(model.problem?.title, "Couldn't load Test Model")
        XCTAssertEqual(model.problem?.message, killed, "the line itself, not the Mac's prefix around it")
        XCTAssertEqual(model.problem?.detail, log)
        XCTAssertNil(model.standingFailure, "the alert already says it; the list does not say it twice")
        model.clearError()
        XCTAssertEqual(model.standingFailure?.reason, "killed")
    }

    func testARefusalThatIsNotAboutTheStandingFailureDoesNotBorrowItsLog() async throws {
        let model = quickModel()
        let refusal = "Context length must be between 1 and 4096 tokens for this model."
        let mac = mac(answering: failed("killed"))
        mac.loadResult = .failure(TransportError.badRequest(refusal))
        await model.load(modelID: id, using: mac)
        XCTAssertEqual(model.problem?.message, refusal)
        XCTAssertNil(model.problem?.detail, "that log belongs to another load")
        XCTAssertEqual(model.standingFailure?.reason, "killed")
        XCTAssertTrue(ModelsModel.describes("The model failed to load: \(killed)", state: killed))
        XCTAssertFalse(ModelsModel.describes("Something else. \(killed)", state: killed))
        XCTAssertFalse(ModelsModel.describes("The model failed to load: ", state: ""))
    }

    func testARefreshGivenUpOnByItsScreenIsNotAFailure() async throws {
        let model = quickModel()
        let mac = mac(answering: loaded)
        await model.refresh(using: mac)
        mac.installedHangs = true
        let reading = Task { await model.refresh(using: mac) }
        try await until("reading") { model.isLoading }
        reading.cancel()
        await reading.value
        XCTAssertNil(model.problem, "a screen that went away is not a Mac that did not answer")
        XCTAssertFalse(model.installedFailed)
        XCTAssertEqual(model.installed.map(\.id), [id])
        XCTAssertFalse(model.isLoading)
    }

    // MARK: - A Mac that says which loads it stopped, and whose failure it is (#97)

    func testALoadAnotherLoadReplacedEndsAtOnceSaysByWhatAndOffersNoRetry() async throws {
        // The replacing load's line and no failure at all: only `interruptedLoads` says it.
        let model = try await follow(until: stopped("replaced", replacedBy: "qwen3-coder-30b"))
        XCTAssertNil(model.job, "following ends at the Mac's word")
        XCTAssertEqual(model.problem?.title, "Test Model wasn't loaded")
        XCTAssertEqual(model.problem?.message, "Replaced by qwen3-coder-30b before it finished loading.")
        XCTAssertEqual(model.problem?.isFault, false)
        XCTAssertNil(model.problem?.retry, "asking again would undo somebody's choice")
    }

    func testALoadAnUnloadStoppedSaysSoBeforeTheFailureLineHasSettled() async throws {
        let model = try await follow(until: stopped("cancelled", state: "Not loaded"))
        XCTAssertNil(model.job)
        XCTAssertEqual(model.problem?.message, "Stopped by an unload before it finished loading.")
        XCTAssertEqual(model.problem?.isFault, false)
        XCTAssertNil(model.problem?.retry)
    }

    func testAnotherModelsFailureIsNotThisLoadsFault() async throws {
        let theirs = failed("killed", modelID: "fails-to-load@Q4_K_M")
        XCTAssertEqual(ModelsModel.LoadOutcome.of(theirs, modelID: id), .replaced)
        let model = try await follow(until: theirs)
        XCTAssertNil(model.job)
        XCTAssertEqual(model.problem?.title, "Test Model wasn't loaded")
        XCTAssertEqual(model.problem?.isFault, false, "not this load's fault")
        XCTAssertNil(model.problem?.detail, "nor its log")
        XCTAssertNil(model.problem?.retry)
    }

    func testThisModelsOwnFailureIsStillAFailureWithItsLogAndARetry() async throws {
        let ours = failed("killed", modelID: id)
        XCTAssertEqual(ModelsModel.LoadOutcome.of(ours, modelID: id), .failed)
        XCTAssertEqual(ModelsModel.LoadOutcome.of(ours, modelID: "test-model"), .failed, "the catalogue spelling too")
        let model = try await follow(until: ours)
        XCTAssertEqual(model.problem?.title, "Couldn't load Test Model")
        XCTAssertEqual(model.problem?.isFault, true)
        XCTAssertEqual(model.problem?.detail, log)
        XCTAssertEqual(model.problem?.retry, .load(modelID: id, quantization: nil))
    }

    func testAnOlderMacWithoutEitherFieldIsReadAsBefore() {
        // No `modelID`: the failure is taken as this load's, as it always was.
        XCTAssertEqual(ModelsModel.LoadOutcome.of(failed("killed"), modelID: id), .failed)
        XCTAssertEqual(ModelsModel.LoadOutcome.of(failed("cancelled"), modelID: id), .cancelled)
        XCTAssertEqual(ModelsModel.LoadOutcome.of(failed("killed", replaced: true), modelID: id), .replaced)
        XCTAssertEqual(ModelsModel.LoadOutcome.of(loading, modelID: id), .pending)
        // Another model's stopped load says nothing about this one.
        let someoneElses = ControlAPI.Status(
            state: "Loading Test Model…",
            interruptedLoads: [.init(modelID: "qwen3-coder-30b", reason: "replaced", replacedBy: id, at: "2026-09-19T11:04:38Z")]
        )
        XCTAssertEqual(ModelsModel.LoadOutcome.of(someoneElses, modelID: id), .pending)
    }

    func testTheSameModelAskedForAgainIsFollowedToItsEndNotCalledReplaced() async throws {
        // A newer load of the same model: the answer is the live status, nothing is listed.
        let model = quickModel()
        let mac = mac(answering: weights, then: [weights, loaded])
        await model.load(modelID: id, using: mac)
        XCTAssertNil(model.job)
        XCTAssertNil(model.problem)
        XCTAssertTrue(model.isLoaded(id))
    }

    func testA409ForALoadThatWasStoppedIsTheMacsSentenceAndNotAFault() async throws {
        let sentence = "Test Model was not loaded: another load (Qwen3-Coder 30B A3B) replaced it before it finished."
        let model = quickModel()
        let mac = mac(answering: stopped("replaced", replacedBy: "qwen3-coder-30b"))
        await model.refresh(using: mac)
        mac.loadResult = .failure(TransportError.conflict(sentence))
        let reads = mac.statusReads
        await model.load(modelID: id, using: mac)
        XCTAssertNil(model.job)
        XCTAssertEqual(model.problem?.title, "Test Model wasn't loaded")
        XCTAssertEqual(model.problem?.message, sentence)
        XCTAssertEqual(model.problem?.isFault, false)
        XCTAssertNil(model.problem?.retry)
        XCTAssertEqual(mac.statusReads, reads + 1, "the status is read once, for the list")
    }

    func testA409BecauseAnotherLoadIsRunningIsStillSomethingToRetry() async throws {
        let busy = "This Mac is already loading bonsai-2-27b (started 12s ago), and this route runs one load at a time. Nothing was changed."
        let model = quickModel()
        let mac = mac(answering: ControlAPI.Status(state: "Loading Bonsai 2 27B…"))
        await model.refresh(using: mac)
        mac.loadResult = .failure(TransportError.conflict(busy))
        await model.load(modelID: id, using: mac)
        XCTAssertEqual(model.problem?.title, "Couldn't load Test Model")
        XCTAssertEqual(model.problem?.message, busy)
        XCTAssertNotNil(model.problem?.retry)
    }

    /// The round-3 nit. A load of this model was stopped earlier, so the Mac still lists that;
    /// then this one is refused with the older 409, because another load is running. That
    /// refusal comes before the Mac's load begins — which is what clears the entry — so the
    /// entry was still there, and the refusal read as this load being stopped: "wasn't
    /// loaded", with no Retry, though nothing had been tried.
    func testA409BecauseAnotherLoadIsRunningStaysRetryableWithAnEarlierInterruptionListed() async throws {
        let busy = "This Mac is already loading bonsai-2-27b (started 12s ago), and this route runs one load "
            + "at a time. Nothing was changed. Follow it with GET /status, or POST /unload to stop it and "
            + "then load again."
        let model = quickModel()
        let mac = mac(answering: stopped("cancelled", state: "Loading Bonsai 2 27B…"))
        await model.refresh(using: mac)
        mac.loadResult = .failure(TransportError.conflict(busy))
        await model.load(modelID: id, using: mac)
        XCTAssertEqual(model.problem?.title, "Couldn't load Test Model")
        XCTAssertEqual(model.problem?.message, busy)
        XCTAssertNotNil(model.problem?.retry)
    }

    /// Words this app does not know: the list decides, and only an entry it had not seen.
    func testA409InOtherWordsCountsAnInterruptionOnlyWhenItIsNew() async throws {
        let words = "Test Model could not be loaded just now."
        let known = stopped("cancelled")
        let fresh = ControlAPI.Status(
            state: "Not loaded",
            interruptedLoads: [
                .init(modelID: id, reason: "replaced", replacedBy: "qwen3-coder-30b", at: "2026-09-19T11:09:02Z")
            ]
        )
        for (after, stoppedHere) in [(known, false), (fresh, true)] {
            let model = quickModel()
            let mac = mac(answering: known)
            await model.refresh(using: mac)
            mac.loadResult = .failure(TransportError.conflict(words))
            mac.statusResult = .success(after)
            await model.load(modelID: id, using: mac)
            if stoppedHere {
                XCTAssertEqual(model.problem?.title, "Test Model wasn't loaded")
                XCTAssertNil(model.problem?.retry)
            } else {
                XCTAssertEqual(model.problem?.title, "Couldn't load Test Model", "the entry from before is not this load's")
                XCTAssertNotNil(model.problem?.retry)
            }
        }
    }

    /// The same rule while a load is followed: an entry the phone already knew of before it
    /// asked belongs to an earlier load. A current Mac clears it when the load starts; one
    /// that was still listed is not this load's ending.
    func testAnInterruptionListedBeforeTheLoadWasAskedForDoesNotEndIt() async throws {
        let leftover = ControlAPI.Status(
            state: "Loading Test Model…",
            interruptedLoads: [
                .init(modelID: id, reason: "replaced", replacedBy: "qwen3-coder-30b", at: "2026-09-19T11:04:38Z")
            ]
        )
        let model = quickModel()
        let mac = mac(answering: leftover)
        await model.refresh(using: mac)
        mac.statusReadings = [leftover, leftover, loaded]
        await model.load(modelID: id, using: mac)
        XCTAssertNil(model.job)
        XCTAssertNil(model.problem)
        XCTAssertTrue(model.isLoaded(id))
    }

    func testTheTwo409sAreToldApartByTheMacsOwnSentences() {
        // Word for word from the Mac: LoadDispatcher's refusal, and InterruptedLoad.sentence.
        let busy = "This Mac is already loading qwen3-coder-30b@Q4_K_M (started 3s ago), and this route runs "
            + "one load at a time. Nothing was changed. Follow it with GET /status, or POST /unload to stop it "
            + "and then load again."
        let unloaded = "Test Model was not loaded: an unload stopped it before it finished loading."
        let replaced = "Test Model was not loaded: another load (Qwen3-Coder 30B A3B) replaced it before it finished."
        let reloaded = "Test Model is being loaded again, by a newer load with its own settings."
        XCTAssertTrue(ControlAPI.LoadConflict.isAlreadyLoading(busy))
        XCTAssertFalse(ControlAPI.LoadConflict.wasStopped(busy))
        for sentence in [unloaded, replaced] {
            XCTAssertTrue(ControlAPI.LoadConflict.wasStopped(sentence), sentence)
            XCTAssertFalse(ControlAPI.LoadConflict.isAlreadyLoading(sentence), sentence)
        }
        // Never a 409: the Mac answers a reload 200, with the status to follow.
        XCTAssertFalse(ControlAPI.LoadConflict.wasStopped(reloaded))
    }

    func testAnOutcomeIsReadFromOneStatus() {
        XCTAssertEqual(ModelsModel.LoadOutcome.of(loading, modelID: id), .pending)
        XCTAssertEqual(ModelsModel.LoadOutcome.of(loaded, modelID: id), .loaded)
        XCTAssertEqual(ModelsModel.LoadOutcome.of(loaded, modelID: "test-model"), .loaded)
        XCTAssertEqual(ModelsModel.LoadOutcome.of(failed("timedOut"), modelID: id), .failed)
        XCTAssertEqual(ModelsModel.LoadOutcome.of(failed("replaced"), modelID: id), .replaced)
        XCTAssertEqual(ModelsModel.LoadOutcome.of(failed("killed", replaced: true), modelID: id), .replaced)
        XCTAssertEqual(ModelsModel.LoadOutcome.of(failed("somethingNew"), modelID: id), .failed)
    }

    // MARK: - Failures of the operations themselves

    func testALoadTheMacRefusesSaysWhyAndARetryThatWorksClearsIt() async throws {
        let model = quickModel()
        let mac = mac(answering: loaded)
        mac.loadResult = .failure(TransportError.badRequest("The model drive is disconnected."))
        await model.refresh(using: mac)
        await model.load(modelID: id, using: mac)
        XCTAssertNil(model.job)
        XCTAssertEqual(model.problem?.title, "Couldn't load Test Model")
        XCTAssertEqual(model.error, "The model drive is disconnected.")

        mac.loadResult = .success(loaded)
        await model.retry(using: mac)
        XCTAssertNil(model.problem, "a retry that worked leaves no stale text")
        XCTAssertTrue(model.isLoaded(id))
    }

    func testARefreshThatFailsKeepsTheListAndDoesNotWipeAnOperationsReason() async throws {
        let model = quickModel()
        let mac = mac(answering: loaded)
        await model.refresh(using: mac)
        XCTAssertEqual(model.installed.map(\.id), [id])

        mac.installedResult = .failure(TransportError.timedOut)
        mac.catalogResult = .failure(TransportError.timedOut)
        await model.refresh(using: mac)
        XCTAssertEqual(model.installed.map(\.id), [id], "the list the phone had stays")
        XCTAssertTrue(model.installedFailed)
        XCTAssertEqual(model.problem?.retry, .refresh)

        mac.unloadError = TransportError.badRequest("No model is loaded.")
        await model.unload(using: mac)
        XCTAssertEqual(model.error, "No model is loaded.")
        mac.installedResult = .success([onDisk])
        mac.catalogResult = .success([])
        await model.refresh(using: mac)
        XCTAssertEqual(model.error, "No model is loaded.", "a working refresh is no answer to a failed unload")
        model.clearError()
        XCTAssertNil(model.problem)
    }
}
