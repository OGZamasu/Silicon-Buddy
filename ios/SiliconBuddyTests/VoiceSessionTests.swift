import XCTest
@testable import SiliconBuddy

/// Push-to-talk, driven without a microphone.
final class VoiceSessionTests: XCTestCase {

    func testHoldingOpensTheMicrophone() {
        var session = VoiceSession()
        XCTAssertEqual(session.apply(.pressed), [.startListening])
        XCTAssertTrue(session.state.isListening)
    }

    func testLettingGoSendsWhatWasHeard() {
        var session = VoiceSession()
        session.apply(.pressed)
        session.apply(.heard("what is"))
        session.apply(.heard("what is a monad"))
        XCTAssertEqual(session.state.partial, "what is a monad")
        XCTAssertEqual(session.apply(.released), [.stopListening, .send("what is a monad")])
        XCTAssertEqual(session.state, .thinking("what is a monad"))
    }

    /// A tap that catches one syllable of room noise should not wake a 27B model.
    func testLettingGoWithNothingHeardSendsNothing() {
        var session = VoiceSession()
        session.apply(.pressed)
        XCTAssertEqual(session.apply(.released), [.stopListening])
        XCTAssertEqual(session.state, .idle)
    }

    func testOneStraySyllableIsNotAQuestion() {
        var session = VoiceSession()
        session.apply(.pressed)
        session.apply(.heard("a"))
        XCTAssertEqual(session.apply(.released), [.stopListening])
        XCTAssertEqual(session.state, .idle)
    }

    func testWhitespaceIsTrimmedBeforeSending() {
        var session = VoiceSession()
        session.apply(.pressed)
        session.apply(.heard("  hello there \n"))
        XCTAssertEqual(session.apply(.released), [.stopListening, .send("hello there")])
    }

    // MARK: - The first press

    /// The first press asks for permission, and answering is a tap on the system's sheet:
    /// the finger is off the button by the time the answer comes. Opening the microphone
    /// then recorded a room nobody was talking to and sent it to the Mac.
    func testThePressThatAskedForPermissionNeverListens() {
        var session = VoiceSession()
        XCTAssertEqual(session.apply(.pressedWithoutPermission), [])
        XCTAssertEqual(session.state, .askingPermission)
        XCTAssertEqual(session.apply(.released), [], "The sheet took the finger")
        XCTAssertEqual(session.apply(.pressed), [], "Nothing listens while the question is up")
        XCTAssertEqual(session.apply(.permissionAnswered(nil)), [])
        XCTAssertEqual(session.state, .idle)
        // The next press is the one that listens.
        XCTAssertEqual(session.apply(.pressed), [.startListening])
    }

    func testARefusedPermissionIsSaidAndOpensNothing() {
        var session = VoiceSession()
        session.apply(.pressedWithoutPermission)
        XCTAssertEqual(session.apply(.permissionAnswered("Turn it on in Settings.")), [])
        XCTAssertEqual(session.state, .failed("Turn it on in Settings."))
    }

    func testLeavingTheScreenWhileItAsksOpensNothingWhenTheAnswerComes() {
        var session = VoiceSession()
        session.apply(.pressedWithoutPermission)
        XCTAssertEqual(session.apply(.interrupted), [])
        XCTAssertEqual(session.apply(.permissionAnswered(nil)), [])
        XCTAssertEqual(session.state, .idle)
    }

    // MARK: - Speaking the answer

    func testTheAnswerIsReadOutWhenTheToggleIsOn() {
        var session = VoiceSession(speaksReplies: true)
        session.apply(.pressed)
        session.apply(.heard("hello"))
        session.apply(.released)
        XCTAssertEqual(session.apply(.answered("Hi there.")), [.speak("Hi there.")])
        XCTAssertTrue(session.state.isSpeaking)
        XCTAssertEqual(session.apply(.finishedSpeaking), [])
        XCTAssertEqual(session.state, .idle)
    }

    func testTheAnswerIsSilentWhenTheToggleIsOff() {
        var session = VoiceSession(speaksReplies: false)
        session.apply(.pressed)
        session.apply(.heard("hello"))
        session.apply(.released)
        XCTAssertEqual(session.apply(.answered("Hi there.")), [])
        XCTAssertEqual(session.state, .idle)
    }

    func testAnEmptyAnswerIsNotSpoken() {
        var session = VoiceSession()
        session.apply(.pressed)
        session.apply(.heard("hello"))
        session.apply(.released)
        XCTAssertEqual(session.apply(.answered("   ")), [])
        XCTAssertEqual(session.state, .idle)
    }

    func testTurningTheToggleOffMidSentenceStopsTheSentence() {
        var session = VoiceSession(speaksReplies: true)
        session.apply(.pressed)
        session.apply(.heard("hello"))
        session.apply(.released)
        session.apply(.answered("A very long answer."))
        XCTAssertEqual(session.apply(.speechEnabled(false)), [.stopSpeaking])
        XCTAssertEqual(session.state, .idle)
    }

    // MARK: - Interruption

    /// The commonest interruption: the answer is long, the person has heard enough, and
    /// they press to ask the next thing.
    func testPressingWhileItIsTalkingInterruptsAndListens() {
        var session = VoiceSession()
        session.apply(.pressed)
        session.apply(.heard("hello"))
        session.apply(.released)
        session.apply(.answered("A long answer that goes on."))
        XCTAssertEqual(session.apply(.pressed), [.stopSpeaking, .startListening])
        XCTAssertTrue(session.state.isListening)
    }

    func testPressingWhileTheMacIsStillThinkingAbandonsThatAnswer() {
        var session = VoiceSession()
        session.apply(.pressed)
        session.apply(.heard("first question"))
        session.apply(.released)
        XCTAssertEqual(session.apply(.pressed), [.startListening])
        session.apply(.heard("second question"))
        XCTAssertEqual(session.apply(.released), [.stopListening, .send("second question")])
    }

    /// And then the first answer arrives. It must not be spoken over the new question.
    func testAnAnswerToAnAbandonedQuestionIsNotSpoken() {
        var session = VoiceSession()
        session.apply(.pressed)
        session.apply(.heard("first"))
        session.apply(.released)
        session.apply(.pressed)
        XCTAssertEqual(session.apply(.answered("An answer to the first question.")), [])
        XCTAssertTrue(session.state.isListening)
    }

    func testLeavingTheScreenWhileListeningClosesTheMicrophone() {
        var session = VoiceSession()
        session.apply(.pressed)
        XCTAssertEqual(session.apply(.interrupted), [.stopListening])
        XCTAssertEqual(session.state, .idle)
    }

    func testLeavingTheScreenWhileTalkingStopsTheVoice() {
        var session = VoiceSession()
        session.apply(.pressed)
        session.apply(.heard("hello"))
        session.apply(.released)
        session.apply(.answered("Speaking."))
        XCTAssertEqual(session.apply(.interrupted), [.stopSpeaking])
    }

    // MARK: - Failures

    func testARecogniserThatGivesUpShowsWhyAndCanBeRetried() {
        var session = VoiceSession()
        session.apply(.pressed)
        XCTAssertEqual(session.apply(.failed("No microphone.")), [.stopListening])
        XCTAssertEqual(session.state, .failed("No microphone."))
        XCTAssertEqual(session.apply(.pressed), [.startListening])
    }

    func testLateRecognitionAfterTheButtonCameUpDoesNotChangeTheQuestion() {
        var session = VoiceSession()
        session.apply(.pressed)
        session.apply(.heard("what is a monad"))
        session.apply(.released)
        XCTAssertEqual(session.apply(.heard("what is a monad transformer")), [])
        XCTAssertEqual(session.state, .thinking("what is a monad"))
    }

    func testPressingTwiceDoesNotOpenTwoMicrophones() {
        var session = VoiceSession()
        session.apply(.pressed)
        XCTAssertEqual(session.apply(.pressed), [])
    }
}
