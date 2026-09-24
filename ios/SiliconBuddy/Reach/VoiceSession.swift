import Foundation

/// Push-to-talk, as a state machine with no microphone in it.
///
/// The awkward parts of voice are not the recogniser or the synthesiser, they are the
/// order things happen in: a person who presses the button while the phone is still
/// talking wants to interrupt it, not to queue behind it; a release before anything was
/// heard should send nothing rather than an empty prompt; an answer that arrives after
/// the person has already started their next question must not be spoken over them.
/// All of that is here, where a test can drive it in a microsecond.
public enum VoiceState: Sendable, Equatable {
    /// Nothing happening.
    case idle
    /// The first press, before this app may listen: the system is asking the person.
    /// Nothing is recorded, and nothing will be for this press — see `.permissionAnswered`.
    case askingPermission
    /// The microphone is open. Carries what has been heard so far.
    case listening(String)
    /// The button is up and the Mac is answering.
    case thinking(String)
    /// The answer is being read out.
    case speaking(String)
    /// Something went wrong, in the words to show.
    case failed(String)

    public var isListening: Bool { if case .listening = self { true } else { false } }
    public var isSpeaking: Bool { if case .speaking = self { true } else { false } }
    public var isBusy: Bool {
        switch self {
        case .thinking, .speaking: true
        default: false
        }
    }

    /// What has been heard so far, for the live caption under the button.
    public var partial: String? {
        if case .listening(let text) = self { return text }
        return nil
    }
}

/// Everything that can happen to a voice session.
public enum VoiceEvent: Sendable, Equatable {
    /// The push-to-talk button went down.
    case pressed
    /// It went down before this app was allowed to listen, so the system is asked first.
    case pressedWithoutPermission
    /// The system's question was answered: nil when both were allowed, otherwise the
    /// words to show.
    case permissionAnswered(String?)
    /// The recogniser has heard more.
    case heard(String)
    /// The button came up.
    case released
    /// The Mac finished answering.
    case answered(String)
    /// The synthesiser reached the end.
    case finishedSpeaking
    /// The person tapped somewhere else, or the app went to the background.
    case interrupted
    /// The recogniser, the microphone or the Mac gave up.
    case failed(String)
    /// The person turned spoken replies off or on mid-session.
    case speechEnabled(Bool)
}

/// What the app should do about it. The session decides; the caller performs.
public enum VoiceEffect: Sendable, Equatable {
    case startListening
    case stopListening
    case send(String)
    case speak(String)
    case stopSpeaking
}

/// The session itself: a value, so a test is three lines and a view can hold it.
public struct VoiceSession: Sendable, Equatable {
    public private(set) var state: VoiceState = .idle
    /// Whether an answer is read out loud. The toggle in Settings sets this.
    public var speaksReplies: Bool

    public init(speaksReplies: Bool = true) {
        self.speaksReplies = speaksReplies
    }

    /// The shortest thing worth sending. A tap that catches one syllable of room noise
    /// should not wake a 27B model.
    public static let minimumCharacters = 2

    @discardableResult
    public mutating func apply(_ event: VoiceEvent) -> [VoiceEffect] {
        switch (state, event) {

        case (_, .speechEnabled(let enabled)):
            speaksReplies = enabled
            guard !enabled, state.isSpeaking else { return [] }
            // Turning it off mid-sentence stops the sentence. Anything else would be
            // the app ignoring the switch it just showed being flipped.
            state = .idle
            return [.stopSpeaking]

        // Pressing while the phone is talking is an interruption, and it is the most
        // common one: the answer is long, the person has heard enough, they press to
        // ask the next thing. Stop the voice and open the microphone in one move.
        case (.speaking, .pressed):
            state = .listening("")
            return [.stopSpeaking, .startListening]

        case (.idle, .pressed), (.failed, .pressed):
            state = .listening("")
            return [.startListening]

        // Pressing while the Mac is still answering abandons that answer. The person
        // has moved on, and an answer to the previous question arriving on top of the
        // next one is worse than no answer.
        case (.thinking, .pressed):
            state = .listening("")
            return [.startListening]

        case (.listening, .pressed), (.askingPermission, .pressed):
            return []

        // The first press asks. It is a question, not a recording: nothing listens until
        // the system has its answer.
        case (.listening, .pressedWithoutPermission), (.askingPermission, .pressedWithoutPermission):
            return []

        case (.speaking, .pressedWithoutPermission):
            state = .askingPermission
            return [.stopSpeaking]

        case (_, .pressedWithoutPermission):
            state = .askingPermission
            return []

        // Answering is a tap on the system's sheet, which takes the finger off the button
        // and swallows the release. So the press that asked never listens, whatever the
        // answer: opening the microphone now would record a room nobody is talking to and
        // send what it heard to the Mac. The next press listens.
        case (.askingPermission, .permissionAnswered(let problem)):
            state = problem.map { .failed($0) } ?? .idle
            return []

        case (_, .permissionAnswered):
            return []

        case (.listening, .heard(let text)):
            state = .listening(text)
            return []

        case (_, .heard):
            // The recogniser's last words, arriving after the button came up. The
            // prompt has already gone; adding to it now would change the question
            // after it was asked.
            return []

        case (.listening(let heard), .released):
            let text = heard.trimmingCharacters(in: .whitespacesAndNewlines)
            guard text.count >= Self.minimumCharacters else {
                state = .idle
                return [.stopListening]
            }
            state = .thinking(text)
            return [.stopListening, .send(text)]

        case (_, .released):
            return []

        case (.thinking, .answered(let answer)):
            guard speaksReplies, !answer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            else {
                state = .idle
                return []
            }
            state = .speaking(answer)
            return [.speak(answer)]

        case (_, .answered):
            // An answer to a question the person has already moved on from. Shown in
            // the transcript by whoever asked for it, never spoken over them.
            return []

        case (.speaking, .finishedSpeaking):
            state = .idle
            return []

        case (_, .finishedSpeaking):
            return []

        case (.listening, .interrupted):
            state = .idle
            return [.stopListening]

        case (.speaking, .interrupted):
            state = .idle
            return [.stopSpeaking]

        case (_, .interrupted):
            state = .idle
            return []

        case (.listening, .failed(let message)):
            state = .failed(message)
            return [.stopListening]

        case (.speaking, .failed(let message)):
            state = .failed(message)
            return [.stopSpeaking]

        case (_, .failed(let message)):
            state = .failed(message)
            return []
        }
    }

    /// What the button says it will do if pressed now.
    public var buttonLabel: String {
        switch state {
        case .listening: "Listening — let go to ask"
        case .thinking: "Asking the Mac"
        case .speaking: "Press to interrupt"
        case .failed: "Hold to talk"
        case .idle: "Hold to talk"
        case .askingPermission: "Asking to use the microphone"
        }
    }
}
