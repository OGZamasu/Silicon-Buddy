import AVFoundation
import Foundation
import Observation
import Speech

/// Push-to-talk, and the answer read back.
///
/// The decisions live in `VoiceSession`, which has no microphone in it and is tested on
/// its own. This is the half that cannot be tested without hardware: permissions, the
/// audio session, the recogniser and the synthesiser. Keeping the two apart is the only
/// reason the awkward cases — press while speaking, release before anything was heard —
/// are covered at all.
@MainActor
@Observable
public final class VoiceController: NSObject {

    public private(set) var session: VoiceSession
    /// What the recogniser has heard so far, for the caption under the button.
    public var partial: String? { session.state.partial }
    /// Set when the microphone or speech recognition was refused, in words to show.
    public private(set) var permissionProblem: String?
    /// True when this phone recognises speech without sending the audio anywhere.
    ///
    /// False is not a detail: it means the recording of the question goes to Apple to
    /// be turned into text. The app says so on screen rather than leaving the earlier
    /// unconditional promise standing.
    public private(set) var isOnDevice = false

    /// What the caption says about where the audio is going, for whoever is holding
    /// the button down.
    public var recognitionNote: String {
        isOnDevice
            ? "Listening — recognised on this device"
            : "Listening — sent to Apple for recognition"
    }

    /// Called with the finished question. The chat screen sends it.
    public var onAsk: ((String) -> Void)?

    private let recogniser = SFSpeechRecognizer(locale: Locale.current)
    private let audio = AVAudioEngine()
    private let synthesiser = AVSpeechSynthesizer()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?

    public init(speaksReplies: Bool = true) {
        self.session = VoiceSession(speaksReplies: speaksReplies)
        super.init()
        synthesiser.delegate = self
    }

    public var speaksReplies: Bool {
        get { session.speaksReplies }
        set { perform(session.apply(.speechEnabled(newValue))) }
    }

    public var state: VoiceState { session.state }

    // MARK: - The button

    public func press() {
        guard permissionProblem == nil else { return }
        perform(session.apply(.pressed))
    }

    /// The button went down before this app may listen: ask, and listen to nothing.
    ///
    /// Answering the system's question is a tap somewhere else, so by the time it is
    /// answered the finger that pressed is gone. This press was the question; the next
    /// one listens. Returns the problem to show, when there is one.
    public func askForPermission() async -> String? {
        perform(session.apply(.pressedWithoutPermission))
        await requestPermissions()
        perform(session.apply(.permissionAnswered(permissionProblem)))
        return permissionProblem
    }

    public func release() {
        perform(session.apply(.released))
    }

    /// The reply came back. Spoken only if the person is still waiting for it — the
    /// session decides, because they may already be asking the next question.
    public func answered(_ text: String) {
        perform(session.apply(.answered(text)))
    }

    /// The screen went away, or something else took the audio.
    public func interrupt() {
        perform(session.apply(.interrupted))
    }

    // MARK: - Permissions

    /// Asked for when the person first holds the button, not at launch: a permission
    /// sheet before anybody has pressed anything is how an app gets refused.
    public func requestPermissions() async {
        let speech = await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0) }
        }
        guard speech == .authorized else {
            permissionProblem =
                "Silicon Buddy can't use speech recognition. Turn it on in "
                + "Settings → Silicon Buddy."
            return
        }
        let microphone = await withCheckedContinuation { continuation in
            AVAudioApplication.requestRecordPermission { continuation.resume(returning: $0) }
        }
        guard microphone else {
            permissionProblem =
                "Silicon Buddy can't use the microphone. Turn it on in "
                + "Settings → Silicon Buddy."
            return
        }
        permissionProblem = nil
        isOnDevice = recogniser?.supportsOnDeviceRecognition ?? false
    }

    public var isAuthorized: Bool {
        SFSpeechRecognizer.authorizationStatus() == .authorized
            && AVAudioApplication.shared.recordPermission == .granted
    }

    // MARK: - Doing what the session decided

    private func perform(_ effects: [VoiceEffect]) {
        for effect in effects {
            switch effect {
            case .startListening: startListening()
            case .stopListening: stopListening()
            case .send(let text): onAsk?(text)
            case .speak(let text): speak(text)
            case .stopSpeaking: synthesiser.stopSpeaking(at: .immediate)
            }
        }
    }

    private func startListening() {
        guard let recogniser, recogniser.isAvailable else {
            perform(session.apply(.failed("Speech recognition isn't available right now.")))
            return
        }
        do {
            let audioSession = AVAudioSession.sharedInstance()
            // `.duckOthers` rather than interrupting: music turns down while the person
            // talks and comes back, which is what every other dictation button does.
            try audioSession.setCategory(
                .playAndRecord, mode: .spokenAudio,
                options: [.duckOthers, .defaultToSpeaker, .allowBluetooth]
            )
            try audioSession.setActive(true, options: .notifyOthersOnDeactivation)

            let request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            // On-device where the phone can: the question is being sent to the owner's
            // own Mac, and routing the audio through Apple's servers on the way would
            // undo the point of the whole app.
            request.requiresOnDeviceRecognition = recogniser.supportsOnDeviceRecognition
            isOnDevice = recogniser.supportsOnDeviceRecognition
            self.request = request

            let input = audio.inputNode
            let format = input.outputFormat(forBus: 0)
            input.removeTap(onBus: 0)
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
                request.append(buffer)
            }
            audio.prepare()
            try audio.start()

            task = recogniser.recognitionTask(with: request) { [weak self] result, error in
                Task { @MainActor in
                    guard let self else { return }
                    if let result {
                        self.perform(
                            self.session.apply(.heard(result.bestTranscription.formattedString))
                        )
                    }
                    if error != nil, self.session.state.isListening {
                        // A recogniser that gives up mid-phrase is common and not worth
                        // a red banner: the button simply stops listening.
                        self.perform(self.session.apply(.released))
                    }
                }
            }
        } catch {
            perform(session.apply(.failed("The microphone couldn't be started.")))
        }
    }

    private func stopListening() {
        audio.inputNode.removeTap(onBus: 0)
        if audio.isRunning { audio.stop() }
        request?.endAudio()
        task?.finish()
        task = nil
        request = nil
    }

    private func speak(_ text: String) {
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: Locale.preferredLanguages.first)
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        try? AVAudioSession.sharedInstance().setActive(true)
        synthesiser.speak(utterance)
    }
}

extension VoiceController: AVSpeechSynthesizerDelegate {
    nonisolated public func speechSynthesizer(
        _ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance
    ) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.perform(self.session.apply(.finishedSpeaking))
            try? AVAudioSession.sharedInstance().setActive(
                false, options: .notifyOthersOnDeactivation
            )
        }
    }

    nonisolated public func speechSynthesizer(
        _ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance
    ) {
        Task { @MainActor [weak self] in
            self?.perform(self?.session.apply(.finishedSpeaking) ?? [])
        }
    }
}
