import XCTest
@testable import SiliconBuddy

/// "Cancel render" on the render queue, from the model down.
///
/// The Mac answers a cancel only once the clip's node has answered it, so what is worth
/// pinning is what the phone does around that wait — one request, for that clip, not two —
/// and that what it shows afterwards is the Mac's record of how the cancel went, not a
/// guess. And who sees the button: only a clip the Mac marks, and never a chat-only phone.
@MainActor
final class QueueModelTests: XCTestCase {

    private let clip = "9C2F-0005"

    private func item(
        status: String = "rendering", canCancel: Bool? = true,
        cancelState: String? = nil, cancelDetail: String? = nil, id: String? = nil,
        uncertain: Bool = false
    ) -> ControlAPI.VideoQueueView.Item {
        ControlAPI.VideoQueueView.Item(
            id: id ?? clip, batchID: "9C2F", title: "Lisbon",
            prompt: "Laundry lines over the Alfama steps", scene: 5, variation: 1, seed: 424246,
            modelID: "ltx2-distilled", seconds: 5, resolution: "720p", h3Turbo: nil,
            status: status, nodeJobID: "job-1191", file: nil,
            outputDirectory: "/Users/you/Movies/Silicon/Lisbon", error: nil,
            uncertainSubmission: uncertain, cancelState: cancelState, cancelDetail: cancelDetail,
            canCancel: canCancel
        )
    }

    private func queue(
        _ items: ControlAPI.VideoQueueView.Item..., message: String? = nil
    ) -> ControlAPI.VideoQueueView {
        ControlAPI.VideoQueueView(paused: false, activeID: clip, message: message, items: items)
    }

    private func mac(_ view: ControlAPI.VideoQueueView) -> StubTransport {
        let mac = StubTransport()
        mac.videoQueueResult = .success(view)
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

    // MARK: - Who sees it

    func testCancelRenderIsOfferedOnlyWhereTheMacMarksTheClipAndNeverToAChatOnlyPhone() async {
        let model = QueueModel()
        await model.refresh(using: mac(queue(
            item(), item(canCancel: false, id: "9C2F-0001"), item(canCancel: nil, id: "9C2F-0002")
        )))
        XCTAssertEqual(model.items.filter { $0.offersCancelRender(canControl: true) }.map(\.id), [clip])
        XCTAssertFalse(
            model.items.contains { $0.offersCancelRender(canControl: false) },
            "a chat-only pairing is not shown it"
        )
    }

    /// A Mac from before `cancel` sends none of its fields; the queue still reads, and
    /// nothing in it can be cancelled.
    func testAnOlderMacsQueueStillDecodesWithNothingToCancel() throws {
        let json = """
            {"paused":false,"items":[{"id":"9C2F-0001","batchID":"9C2F","title":"Lisbon",
            "prompt":"A tram","scene":1,"variation":1,"modelID":"hailuo-h3","seconds":5,
            "resolution":"720p","status":"rendering","outputDirectory":"/Users/you/Movies",
            "uncertainSubmission":false}]}
            """
        let view = try JSONDecoder.buddy.decode(ControlAPI.VideoQueueView.self, from: Data(json.utf8))
        let only = try XCTUnwrap(view.items.first)
        XCTAssertNil(only.canCancel)
        XCTAssertNil(QueueCancelState(wire: only.cancelState))
        XCTAssertFalse(only.offersCancelRender(canControl: true))
    }

    // MARK: - Asking

    func testOneCancelForThatClipAndTheClipThenSaysTheNodeIsStoppingIt() async throws {
        let model = QueueModel()
        let stopping = "The node is stopping this render. The queue follows it until the node confirms."
        let mac = mac(queue(item()))
        await model.refresh(using: mac)
        mac.controlResult = .success(queue(item(canCancel: false, cancelState: "requested"), message: stopping))
        mac.controlDelay = .milliseconds(200)

        let first = Task { await model.cancelRender(clip, using: mac) }
        try await until("the cancel is on its way") { model.cancelling.contains(clip) }
        XCTAssertEqual(model.cancelOutcome(for: try XCTUnwrap(model.item(clip))), .sending)
        // A second tap while the node is being asked sends nothing more.
        await model.cancelRender(clip, using: mac)
        await first.value

        XCTAssertEqual(mac.sentControls, [.init(action: "cancel", id: clip)])
        XCTAssertTrue(model.cancelling.isEmpty)
        let answered = try XCTUnwrap(model.item(clip))
        XCTAssertEqual(model.cancelOutcome(for: answered), .requested)
        XCTAssertTrue(QueueCancelState.requested.note.hasPrefix("Cancel requested"))
        XCTAssertFalse(answered.offersCancelRender(canControl: true), "the Mac no longer offers it")
        XCTAssertEqual(model.queue?.message, stopping, "the Mac's own sentence about it")
        XCTAssertNil(model.error)
    }

    func testANodeThatCannotStopItSaysSoAndTheRenderCarriesOn() async throws {
        let model = QueueModel()
        let mac = mac(queue(item()))
        await model.refresh(using: mac)
        mac.controlResult = .success(queue(item(
            cancelState: "unsupported", cancelDetail: "Another job shares that renderer."
        )))
        await model.cancelRender(clip, using: mac)
        let row = try XCTUnwrap(model.item(clip))
        XCTAssertEqual(model.cancelOutcome(for: row), .unsupported)
        XCTAssertTrue(QueueCancelState.unsupported.note.contains("keeps rendering"))
        XCTAssertTrue(QueueCancelState.unsupported.isWarning)
        XCTAssertEqual(row.cancelDetail, "Another job shares that renderer.")
        XCTAssertEqual(row.phase, .rendering)
    }

    func testAConfirmedCancelEndsTheClipAndItCanBeRenderedAgain() async throws {
        let model = QueueModel()
        let mac = mac(queue(item()))
        await model.refresh(using: mac)
        mac.controlResult = .success(queue(item(
            status: "cancelled", canCancel: false, cancelState: "confirmed",
            cancelDetail: "Cancelled; the renderer was stopped."
        )))
        await model.cancelRender(clip, using: mac)
        let row = try XCTUnwrap(model.item(clip))
        XCTAssertEqual(row.phase, .cancelled)
        XCTAssertEqual(row.phase.label, "Cancelled")
        XCTAssertEqual(model.cancelOutcome(for: row), .confirmed)
        XCTAssertFalse(row.offersCancelRender(canControl: true))
        XCTAssertTrue(row.canRetry)
    }

    func testARefusedCancelIsShownAndTheQueueIsReadAgainForWhatTheMacKept() async throws {
        let model = QueueModel()
        let mac = mac(queue(item()))
        await model.refresh(using: mac)
        let refusal = "This clip's node does not offer to cancel its render. Use stop_following."
        mac.controlResult = .failure(TransportError.badRequest(refusal))
        mac.videoQueueResult = .success(queue(item(canCancel: false)))
        let reads = mac.videoQueueReads
        await model.cancelRender(clip, using: mac)
        XCTAssertEqual(model.error, refusal)
        XCTAssertEqual(mac.videoQueueReads, reads + 1)
        XCTAssertFalse(try XCTUnwrap(model.item(clip)).offersCancelRender(canControl: true))
        XCTAssertTrue(model.cancelling.isEmpty)
    }

    // MARK: - The rest of the Mac's verbs, as it spells them

    func testTheOtherVerbsGoOutAsTheMacReadsThem() async {
        let model = QueueModel()
        let mac = mac(queue(item(status: "failed", uncertain: true)))
        await model.refresh(using: mac)
        await model.retry(clip, confirmNewRender: true, using: mac)
        await model.stopFollowing(clip, using: mac)
        await model.remove(clip, using: mac)
        await model.pauseOrResume(using: mac)
        await model.clearFinished(using: mac)
        XCTAssertEqual(mac.sentControls, [
            .init(action: "retry", id: clip, confirmNewRender: true),
            .init(action: "stop_following", id: clip),
            .init(action: "remove", id: clip),
            .init(action: "pause"),
            .init(action: "clear_finished"),
        ])
        for sent in mac.sentControls {
            XCTAssertEqual(
                sent.id != nil, ControlAPI.VideoQueueControl.needsID.contains(sent.action),
                "\(sent.action) names an item exactly when it needs one"
            )
        }
    }

    func testTheMacsStatusWordsAndWhatEachAllows() {
        XCTAssertEqual(QueuePhase(wire: "pending"), .queued)
        XCTAssertEqual(QueuePhase(wire: "rendering"), .rendering)
        XCTAssertEqual(QueuePhase(wire: "cancelled"), .cancelled)
        XCTAssertEqual(QueuePhase(wire: "reticulating"), .other("reticulating"))
        XCTAssertFalse(QueuePhase(wire: "reticulating").isFinished)
        XCTAssertTrue(item(status: "pending").canRemove)
        XCTAssertFalse(item(status: "failed").canRemove, "the Mac removes only what it has not handed over")
        XCTAssertTrue(item(status: "failed").canRetry)
        XCTAssertEqual(QueueCancelState(wire: "reticulating"), .unknown)
        XCTAssertTrue(QueueCancelState.requested.isPending)
    }

    func testAnotherMacStartsFromNothing() async {
        let model = QueueModel()
        await model.refresh(using: mac(queue(item())))
        model.reset()
        XCTAssertNil(model.queue)
        XCTAssertTrue(model.items.isEmpty)
    }

    // MARK: - Leaving the screen, and a re-pair

    /// The screen reads the queue again each time it comes back. That must not blank the
    /// list or forget a cancel still waiting on the node — a forgotten one is a button
    /// that sends it twice.
    func testComingBackToTheScreenKeepsWhatIsOnItsWay() async throws {
        let model = QueueModel()
        let mac = mac(queue(item()))
        await model.refresh(using: mac)
        mac.controlResult = .success(queue(item(canCancel: false, cancelState: "requested")))
        mac.controlDelay = .milliseconds(300)

        let cancel = Task { await model.cancelRender(clip, using: mac) }
        try await until("the cancel is on its way") { model.cancelling.contains(clip) }
        // What the screen's task does when the tab reappears: read, and nothing else.
        await model.refresh(using: mac)
        XCTAssertNotNil(model.queue, "the list is not blanked")
        XCTAssertTrue(model.cancelling.contains(clip), "the cancel on its way is not forgotten")
        let row = try XCTUnwrap(model.item(clip))
        XCTAssertEqual(
            model.controls(for: row, canControl: true).first { $0.verb == .cancelRender }?.enabled,
            false, "and its button stays held"
        )
        await model.cancelRender(clip, using: mac)
        await cancel.value
        XCTAssertEqual(mac.sentControls.count, 1, "one cancel, however often the screen came back")
    }

    /// An answer the last Mac sends after a re-pair is not the new Mac's queue.
    func testAnAnswerFromBeforeARepairIsDropped() async throws {
        let model = QueueModel()
        let old = mac(queue(item()))
        await model.refresh(using: old)
        old.controlResult = .success(queue(item(status: "cancelled", canCancel: false, cancelState: "confirmed")))
        old.controlDelay = .milliseconds(300)
        let cancel = Task { await model.cancelRender(clip, using: old) }
        try await until("the cancel is on its way") { model.cancelling.contains(clip) }

        model.reset()
        let other = mac(queue(item(canCancel: false, id: "4D00-0001")))
        await model.refresh(using: other)
        await cancel.value
        XCTAssertEqual(model.items.map(\.id), ["4D00-0001"], "the new Mac's queue stands")
        XCTAssertTrue(model.cancelling.isEmpty)
        XCTAssertNil(model.error)
    }

    /// A re-pair on the Queue screen: the root and the screen both say which pairing they
    /// mean, in whichever order SwiftUI runs them. The new Mac's first read, already under
    /// way when the second of them runs, must not be dropped — that left the list blank
    /// until the next read, five seconds later.
    func testARepairOnTheScreenKeepsTheNewMacsFirstReadWhicheverSaysItFirst() async throws {
        for screenFirst in [true, false] {
            let model = QueueModel()
            model.connect(0)
            await model.refresh(using: mac(queue(item())))
            XCTAssertEqual(model.items.map(\.id), [clip])

            let other = mac(queue(item(canCancel: false, id: "4D00-0001")))
            other.videoQueueDelay = .milliseconds(150)
            if !screenFirst { model.connect(1) }   // the root's onChange first
            model.connect(1)                       // the screen's task
            let reading = Task { await model.refresh(using: other) }
            try await until("the first read is out") { other.videoQueueReads == 1 }
            model.connect(1)                       // the other of the two, while it is out
            await reading.value
            XCTAssertEqual(
                model.items.map(\.id), ["4D00-0001"],
                "the new Mac's first read lands (\(screenFirst ? "screen" : "root") first)"
            )
        }
    }

    func testAFailedReadKeepsTheLastQueueAndSaysSo() async {
        let model = QueueModel()
        let mac = mac(queue(item()))
        await model.refresh(using: mac)
        XCTAssertFalse(model.isStale)
        mac.videoQueueResult = .failure(TransportError.appNotRunning)
        await model.refresh(using: mac)
        XCTAssertEqual(model.items.map(\.id), [clip], "the last queue is still shown")
        XCTAssertTrue(model.isStale, "and the screen says it is the last one")
        XCTAssertNil(model.error)
        mac.videoQueueResult = .success(queue(item()))
        await model.refresh(using: mac)
        XCTAssertFalse(model.isStale)
    }

    // MARK: - What each row offers

    func testEachRowOffersTheMacsVerbsAndChatOnlyGetsNoCancel() async throws {
        let model = QueueModel()
        await model.refresh(using: mac(queue(
            item(),                                                   // followed, cancellable
            item(status: "failed", id: "9C2F-0006"),                  // stop-followed, cancellable
            item(status: "rendering", canCancel: false, id: "9C2F-0007"),
            item(status: "pending", canCancel: false, id: "9C2F-0008")
        )))
        func verbs(_ id: String, _ canControl: Bool) throws -> [QueueModel.Control] {
            model.controls(for: try XCTUnwrap(model.item(id)), canControl: canControl)
        }
        XCTAssertEqual(try verbs(clip, true).map(\.verb), [.stopFollowing, .cancelRender])
        XCTAssertEqual(try verbs("9C2F-0006", true).map(\.verb), [.retry, .cancelRender])
        XCTAssertEqual(try verbs("9C2F-0007", true).map(\.verb), [], "not followed, not cancellable")
        XCTAssertEqual(try verbs("9C2F-0008", true).map(\.verb), [.remove])
        // Chat-only: the same rows, Cancel render gone, the rest shown and held.
        XCTAssertEqual(try verbs(clip, false), [.init(verb: .stopFollowing, enabled: false)])
        XCTAssertEqual(try verbs("9C2F-0006", false), [.init(verb: .retry, enabled: false)])
        XCTAssertEqual(try verbs("9C2F-0008", false), [.init(verb: .remove, enabled: false)])
    }
}
