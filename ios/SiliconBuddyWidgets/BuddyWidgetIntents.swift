import AppIntents
import WidgetKit

/// What the widget's own button does.
///
/// An interactive widget runs this inside the widget extension, not in the app: the
/// point is an answer without a launch. It asks once, with no history, and writes what
/// came back where the timeline provider will find it — WidgetKit redraws as soon as
/// `perform` returns.
struct AskQuickPromptIntent: AppIntent {
    static let title: LocalizedStringResource = "Ask the quick prompt"
    static let description = IntentDescription(
        "Asks your Mac the question this widget is set to, and shows the answer."
    )
    /// Not offered in Shortcuts: "Ask my Mac" is the action for that, and it takes the
    /// question as a parameter rather than reading it out of a widget's settings.
    static let isDiscoverable: Bool = false

    @Parameter(title: "Question")
    var prompt: String

    init() { self.prompt = QuickPrompt.default }
    init(prompt: String) { self.prompt = prompt }

    func perform() async throws -> some IntentResult {
        let transport = SharedConfiguration.transport()
        // Both outcomes are shown: a widget that silently does nothing when the Mac is
        // asleep is indistinguishable from a widget that is broken.
        QuickPrompt.store(answer: await WidgetTimeline.ask(prompt, using: transport).text)
        return .result()
    }
}

/// Clears the answer so the widget goes back to showing what is loaded.
struct ClearQuickAnswerIntent: AppIntent {
    static let title: LocalizedStringResource = "Clear the widget's answer"
    static let isDiscoverable: Bool = false

    func perform() async throws -> some IntentResult {
        QuickPrompt.store(answer: nil)
        return .result()
    }
}

/// The widget's own settings: which question its button asks.
struct QuickPromptConfiguration: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "Quick prompt"
    static let description = IntentDescription(
        "The question the widget's button asks your Mac. Long-press the widget to change it."
    )

    @Parameter(title: "Question", default: "What should I do next?")
    var prompt: String

    init() { self.prompt = QuickPrompt.default }
    init(prompt: String) { self.prompt = prompt }

    /// Kept where the extension's other code can read it, so a timeline built before
    /// the configuration arrives still shows the right question.
    func remember() { QuickPrompt.store(prompt) }
}
