import AppIntents
import Foundation

/// "Hey Siri, ask my Mac…"
///
/// Three actions, all of which run without opening the app: the whole point is a
/// question answered while the phone is in a pocket. They read the paired Mac out of
/// the shared container, so they work whether or not the app has been launched since
/// the phone rebooted.
struct AskMyMacIntent: AppIntent {
    static let title: LocalizedStringResource = "Ask my Mac"
    static let description = IntentDescription(
        "Asks the model loaded on your Mac a question and reads the answer back.",
        categoryName: "Chat"
    )
    /// No app launch: the answer is the result, and opening the app on top of whatever
    /// the person was doing would be the wrong kind of helpful.
    static let openAppWhenRun: Bool = false

    @Parameter(title: "Question", requestValueDialog: "What should I ask your Mac?")
    var question: String

    /// How long an answer may be. A spoken answer is not a transcript.
    @Parameter(title: "Longest answer", default: 512, inclusiveRange: (64, 4096))
    var maxTokens: Int

    static var parameterSummary: some ParameterSummary {
        Summary("Ask my Mac \(\.$question)") {
            \.$maxTokens
        }
    }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> & ProvidesDialog {
        guard let transport = SharedConfiguration.transport() else {
            throw IntentMapping.Refusal.notPaired
        }
        let outcome = try await OneShotAsk.send(
            message: question, title: "Asked from Shortcuts",
            maxTokens: maxTokens, using: transport
        )
        return .result(value: outcome.answer, dialog: IntentDialog(stringLiteral: outcome.answer))
    }
}

/// "Load Gemma on my Mac."
struct LoadModelIntent: AppIntent {
    static let title: LocalizedStringResource = "Load a model"
    static let description = IntentDescription(
        "Loads one of the models installed on your Mac.",
        categoryName: "Models"
    )
    static let openAppWhenRun: Bool = false

    @Parameter(title: "Model", requestValueDialog: "Which model should I load?")
    var model: String

    static var parameterSummary: some ParameterSummary {
        Summary("Load \(\.$model) on my Mac")
    }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> & ProvidesDialog {
        guard let config = SharedConfiguration.load() else {
            throw IntentMapping.Refusal.notPaired
        }
        // Checked here rather than left to the Mac's 403: a device paired for chat is
        // not allowed to spend the machine, and saying so is faster and clearer than
        // a refusal that arrives after a round trip.
        guard config.scope.canControl else { throw IntentMapping.Refusal.notAllowed }

        let transport = ControlClient(config: config)
        let installed = try await transport.installed()
        let request = try IntentMapping.loadRequest(
            named: model, in: installed, scope: config.scope
        )
        let status = try await transport.load(request)
        SnapshotStore.note(status: status, macName: config.macName)
        let sentence = IntentMapping.loadedSummary(status, macName: config.macName)
        return .result(value: sentence, dialog: IntentDialog(stringLiteral: sentence))
    }
}

/// "What is loaded on my Mac?"
struct WhatIsLoadedIntent: AppIntent {
    static let title: LocalizedStringResource = "What is loaded on my Mac"
    static let description = IntentDescription(
        "Says which model your Mac has loaded right now.",
        categoryName: "Models"
    )
    static let openAppWhenRun: Bool = false

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> & ProvidesDialog {
        guard let config = SharedConfiguration.load() else {
            throw IntentMapping.Refusal.notPaired
        }
        let transport = ControlClient(config: config)
        do {
            let status = try await transport.status()
            SnapshotStore.note(status: status, macName: config.macName)
            let sentence = IntentMapping.loadedSummary(status, macName: config.macName)
            return .result(value: sentence, dialog: IntentDialog(stringLiteral: sentence))
        } catch {
            // The last thing the app saw is better than "I don't know", as long as it
            // is offered as what it is.
            guard let snapshot = SnapshotStore.read(), let name = snapshot.loadedModelName else {
                throw IntentMapping.Refusal.notPaired
            }
            let sentence = "Your Mac isn't answering. It last had \(name) loaded."
            return .result(value: sentence, dialog: IntentDialog(stringLiteral: sentence))
        }
    }
}

/// The phrases Siri listens for without anybody opening Shortcuts.
struct BuddyShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: AskMyMacIntent(),
            phrases: [
                "Ask my Mac with \(.applicationName)",
                "Ask \(.applicationName)",
                "\(.applicationName) ask my Mac",
            ],
            shortTitle: "Ask my Mac",
            systemImageName: "bubble.left.and.text.bubble.right"
        )
        AppShortcut(
            intent: WhatIsLoadedIntent(),
            phrases: [
                "What is loaded on my Mac in \(.applicationName)",
                "\(.applicationName) what is loaded",
            ],
            shortTitle: "What is loaded",
            systemImageName: "cpu"
        )
        AppShortcut(
            intent: LoadModelIntent(),
            phrases: [
                "Load a model with \(.applicationName)",
                "\(.applicationName) load a model",
            ],
            shortTitle: "Load a model",
            systemImageName: "square.stack.3d.up"
        )
    }
}
