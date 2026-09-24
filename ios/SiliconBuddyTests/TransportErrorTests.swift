import XCTest
@testable import SiliconBuddy

final class TransportErrorTests: XCTestCase {

    // MARK: - URLSession failures

    func testConnectionRefusedDoesNotProveTheAppIsClosed() {
        // A wrong port and a closed app produce the same transport error.
        let error = URLError(.cannotConnectToHost)
        XCTAssertEqual(TransportError.from(urlError: error), .appNotRunning)
        XCTAssertTrue(TransportError.appNotRunning.errorDescription!.contains("address and port"))
        XCTAssertTrue(TransportError.appNotRunning.recoverySuggestion!.contains("control.json"))
    }

    func testTimeoutIsItsOwnThing() {
        XCTAssertEqual(TransportError.from(urlError: URLError(.timedOut)), .timedOut)
    }

    func testCancellationIsNotAFailure() {
        XCTAssertEqual(TransportError.from(urlError: URLError(.cancelled)), .cancelled)
    }

    func testHostNotFoundIsUnreachable() {
        let error = URLError(
            .cannotFindHost,
            userInfo: [NSURLErrorFailingURLErrorKey: URL(string: "http://mac.tailnet:8788/status")!]
        )
        XCTAssertEqual(TransportError.from(urlError: error), .unreachable("mac.tailnet"))
    }

    func testNoNetworkIsUnreachable() {
        guard case .unreachable = TransportError.from(urlError: URLError(.notConnectedToInternet))
        else { return XCTFail("Expected unreachable") }
    }

    func testAnUnknownURLErrorStillLandsSomewhereUseful() {
        guard case .unreachable = TransportError.from(urlError: URLError(.unknown))
        else { return XCTFail("Expected unreachable") }
    }

    // MARK: - HTTP statuses

    private func body(_ message: String) -> Data {
        Data(#"{"error":"\#(message)"}"#.utf8)
    }

    func testSuccessIsNotAnError() {
        XCTAssertNil(TransportError.from(status: 200, body: Data(), path: "/status"))
        XCTAssertNil(TransportError.from(status: 204, body: Data(), path: "/unload"))
    }

    func testUnauthorized() {
        XCTAssertEqual(
            TransportError.from(
                status: 401, body: body("Invalid or missing control token."), path: "/status"
            ),
            .unauthorized
        )
    }

    /// 403 and 401 mean different things and ask for different things: one is "pair
    /// again", the other is "this device was paired for chat only".
    func testForbiddenIsNotUnauthorized() {
        let refusal = TransportError.from(
            status: 403,
            body: Data(#"{"error":"This device is paired for chat only."}"#.utf8),
            path: "/load"
        )
        XCTAssertEqual(refusal, .forbidden("This device is paired for chat only."))
        XCTAssertNotEqual(refusal, .unauthorized)
        XCTAssertEqual(refusal?.errorDescription, "This device is paired for chat only.")
        XCTAssertNotEqual(refusal?.recoverySuggestion, TransportError.unauthorized.recoverySuggestion)
    }

    func testAConversationConflictIsItsOwnCase() {
        let conflict = TransportError.from(
            status: 409,
            body: Data(#"{"error":"That conversation is still being answered."}"#.utf8),
            path: "/conversations/1/messages"
        )
        XCTAssertEqual(conflict, .conflict("That conversation is still being answered."))
    }

    func testTooLargeIsItsOwnCase() {
        let tooLarge = TransportError.from(
            status: 413,
            body: Data(#"{"error":"That request body is larger than this device may send."}"#.utf8),
            path: "/chat"
        )
        guard case .tooLarge? = tooLarge else {
            return XCTFail("413 should be its own case")
        }
    }

    func testNotFoundNamesTheRouteSoTheCallerCanFallBack() {
        let error = TransportError.from(status: 404, body: Data(), path: "/chat/stream")
        XCTAssertEqual(error, .routeUnavailable("/chat/stream"))
        XCTAssertTrue(error?.isMissingRoute == true)
    }

    func testBadRequestKeepsTheMacsWords() {
        let error = TransportError.from(
            status: 400, body: body("No model is loaded. Use load_model first."), path: "/chat"
        )
        XCTAssertEqual(error, .badRequest("No model is loaded. Use load_model first."))
        XCTAssertEqual(error?.errorDescription, "No model is loaded. Use load_model first.")
    }

    func testTooManyRequestsIsBusy() {
        let error = TransportError.from(
            status: 429, body: body("Too many synchronous video requests."), path: "/video/generate"
        )
        XCTAssertEqual(error, .busy("Too many synchronous video requests."))
    }

    func testAnUnknownStatusCarriesItsNumber() {
        // 502: nothing the Mac documents. (503 was the example here until the phone's models
        // made it one — the Mac's library drive gone — with a case of its own.)
        XCTAssertEqual(
            TransportError.from(status: 502, body: body("gone fishing"), path: "/status"),
            .server(status: 502, message: "gone fishing")
        )
    }

    func testTheDriveAndTheRoomTheMacNeedsForPhoneModelsAreTheirOwnCases() {
        XCTAssertEqual(
            TransportError.from(status: 503, body: body("The drive “Demo SSD” is not connected."), path: "/x"),
            .unavailable("The drive “Demo SSD” is not connected.")
        )
        XCTAssertEqual(
            TransportError.from(status: 507, body: body("No room."), path: "/x"),
            .insufficientStorage("No room.")
        )
    }

    func testANonJSONErrorBodyIsStillReadable() {
        let error = TransportError.from(
            status: 500, body: Data("something broke".utf8), path: "/status"
        )
        XCTAssertEqual(error, .server(status: 500, message: "something broke"))
    }

    func testAnEmptyBadRequestStillSaysSomething() {
        let error = TransportError.from(status: 400, body: Data(), path: "/load")
        XCTAssertEqual(error, .badRequest("The Mac rejected the request."))
    }

    // MARK: - What the person is told

    func testEveryErrorHasADescription() {
        let errors: [TransportError] = [
            .unreachable("mac"), .appNotRunning, .timedOut, .unauthorized,
            .forbidden(""), .conflict(""), .tooLarge(""),
            .routeUnavailable("/events"), .badRequest("x"), .busy("y"),
            .server(status: 500, message: ""), .decoding("z"), .notConfigured, .cancelled,
        ]
        for error in errors {
            XCTAssertFalse(
                (error.errorDescription ?? "").isEmpty,
                "\(error) has nothing to say to the person looking at it"
            )
        }
    }

    func testTheThreeFailuresThatMatterHaveSeparateAdvice() {
        XCTAssertNotNil(TransportError.unreachable("mac").recoverySuggestion)
        XCTAssertNotNil(TransportError.appNotRunning.recoverySuggestion)
        XCTAssertNotNil(TransportError.unauthorized.recoverySuggestion)
        XCTAssertNotEqual(
            TransportError.unreachable("mac").recoverySuggestion,
            TransportError.appNotRunning.recoverySuggestion
        )
    }

    func testAServerErrorWithNoMessageStillReadsAsAnError() {
        let error = TransportError.server(status: 500, message: "")
        XCTAssertEqual(error.errorDescription, "The Mac returned an error (500).")
    }
}
