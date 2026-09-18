import XCTest
@testable import SiliconBuddy

final class TransportErrorTests: XCTestCase {

    // MARK: - URLSession failures

    func testConnectionRefusedMeansTheAppIsNotRunning() {
        // The Mac answered the SYN with a RST: the machine is up, the port is closed.
        let error = URLError(.cannotConnectToHost)
        XCTAssertEqual(TransportError.from(urlError: error), .appNotRunning)
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

    func testForbiddenIsAlsoUnauthorized() {
        XCTAssertEqual(TransportError.from(status: 403, body: Data(), path: "/status"), .unauthorized)
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
        XCTAssertEqual(
            TransportError.from(status: 503, body: body("gone fishing"), path: "/status"),
            .server(status: 503, message: "gone fishing")
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
