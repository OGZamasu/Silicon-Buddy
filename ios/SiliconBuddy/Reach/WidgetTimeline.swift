import Foundation

/// What a widget draws, and where it came from.
///
/// A widget is not a screen: it gets a few seconds of background time at a cadence iOS
/// decides, and it has to show something the instant it is placed. So the snapshot the
/// app left behind is the answer, and a refresh improves it. A Mac that is asleep, off
/// the tailnet or simply not paired is a state worth drawing, not an error to swallow.
public struct BuddyWidgetEntry: Sendable, Equatable {
    public var date: Date
    public var snapshot: BuddySnapshot?
    public var isPaired: Bool
    /// One line explaining why there is nothing newer, when there is a reason.
    public var problem: String?
    /// The preset question this widget's button fires, as configured.
    public var quickPrompt: String
    /// The answer to that preset, when the widget has run it.
    ///
    /// There is no "asking" state to go with it, and there cannot be a useful one: an
    /// interactive widget redraws when its intent *returns*, so a spinner set on the
    /// way in is never rendered. The deadline is what stands in for it — the button
    /// either produces an answer or produces a sentence, within twenty seconds.
    public var quickAnswer: String?

    public init(
        date: Date = Date(), snapshot: BuddySnapshot? = nil, isPaired: Bool = false,
        problem: String? = nil, quickPrompt: String = QuickPrompt.default,
        quickAnswer: String? = nil
    ) {
        self.date = date
        self.snapshot = snapshot
        self.isPaired = isPaired
        self.problem = problem
        self.quickPrompt = quickPrompt
        self.quickAnswer = quickAnswer
    }

    /// The model line, or the reason there isn't one.
    public var headline: String {
        guard isPaired else { return "Not paired" }
        return snapshot?.modelLine ?? "Ask your Mac"
    }

    /// What the body of the widget shows: the preset's answer if it has one, otherwise
    /// the last thing the Mac said anywhere.
    public func body(limit: Int) -> String? {
        if let quickAnswer { return BuddySnapshot.trim(quickAnswer, to: limit) }
        return snapshot?.answerPreview(limit: limit)
    }
}

/// The preset question a widget can fire without opening the app.
public enum QuickPrompt {
    public static let `default` = "What should I do next?"

    /// The handful offered in the widget's own configuration sheet. Short, because the
    /// answer has to fit in a widget, and useful without any context — a widget has no
    /// transcript behind it.
    public static let presets: [String] = [
        "What should I do next?",
        "Summarise my day",
        "Give me one idea",
        "Explain what you are loaded for",
        "What is on your mind?",
    ]

    /// How long a widget may wait.
    ///
    /// Well under the extension's own budget, and nothing like the transport's own 900
    /// seconds: a widget process that is killed for overrunning leaves the last entry
    /// on screen, which looks like a button that does nothing. Twenty seconds, then a
    /// sentence saying the Mac was slow.
    public static let timeout: TimeInterval = 20

    public static func stored(in defaults: UserDefaults = BuddyShared.defaults) -> String {
        defaults.string(forKey: SharedConfiguration.Keys.quickPrompt) ?? `default`
    }

    public static func store(
        _ prompt: String, in defaults: UserDefaults = BuddyShared.defaults
    ) {
        defaults.set(prompt, forKey: SharedConfiguration.Keys.quickPrompt)
    }

    /// The last answer the widget's own button produced, and when.
    ///
    /// Separate from the snapshot's `lastAnswer`, which is whatever was said last
    /// anywhere: a widget that replaced its own answer with one from a chat the person
    /// had in the app would look like it had changed its mind.
    public static func answer(
        in defaults: UserDefaults = BuddyShared.defaults
    ) -> (text: String, at: Date)? {
        guard let text = defaults.string(forKey: SharedConfiguration.Keys.quickAnswer) else {
            return nil
        }
        let seconds = defaults.double(forKey: SharedConfiguration.Keys.quickAskedAt)
        return (text, seconds > 0 ? Date(timeIntervalSince1970: seconds) : Date())
    }

    public static func store(
        answer: String?, in defaults: UserDefaults = BuddyShared.defaults
    ) {
        guard let answer else {
            defaults.removeObject(forKey: SharedConfiguration.Keys.quickAnswer)
            defaults.removeObject(forKey: SharedConfiguration.Keys.quickAskedAt)
            return
        }
        defaults.set(answer, forKey: SharedConfiguration.Keys.quickAnswer)
        defaults.set(Date().timeIntervalSince1970, forKey: SharedConfiguration.Keys.quickAskedAt)
    }
}

/// Everything a widget's timeline provider does, with the transport handed in.
///
/// Pulled out of the provider so it can be run against the loopback stand-in in a test:
/// a `TimelineProvider` needs a `WidgetKit` context to exist at all, and none of the
/// interesting behaviour here is about `WidgetKit`.
public enum WidgetTimeline {

    /// How often the widget asks again when everything is working.
    public static let refreshInterval: TimeInterval = 15 * 60
    /// And when it is not: a Mac that is asleep will still be asleep in a minute, and
    /// the phone's battery is the thing being spent.
    public static let retryInterval: TimeInterval = 60 * 60

    /// One entry: the stored snapshot, improved by asking the Mac if it answers.
    public static func entry(
        using transport: (any ControlTransport)?,
        stored: BuddySnapshot?,
        quickPrompt: String,
        quickAnswer: String? = nil,
        now: Date = Date(),
        // Injected so a test can prove the deadline without spending it. Twenty
        // seconds of real waiting, twice, is forty seconds on every run of the suite.
        deadline: TimeInterval = QuickPrompt.timeout
    ) async -> BuddyWidgetEntry {
        guard let transport else {
            return BuddyWidgetEntry(
                date: now, snapshot: stored, isPaired: false,
                problem: "Open Silicon Buddy to pair with your Mac.",
                quickPrompt: quickPrompt, quickAnswer: quickAnswer
            )
        }
        do {
            guard let status = try await Self.withDeadline(deadline, {
                try await transport.status()
            }) else {
                return BuddyWidgetEntry(
                    date: now, snapshot: stored, isPaired: true,
                    problem: Self.tooSlow, quickPrompt: quickPrompt, quickAnswer: quickAnswer
                )
            }
            var snapshot = stored ?? BuddySnapshot()
            snapshot.state = status.state
            snapshot.loadedModelID = status.loadedModelID
            snapshot.loadedModelName = status.loadedModelName
            snapshot.updatedAt = now
            return BuddyWidgetEntry(
                date: now, snapshot: snapshot, isPaired: true,
                quickPrompt: quickPrompt, quickAnswer: quickAnswer
            )
        } catch {
            // The last snapshot is still the truth as far as anybody knows; it is just
            // older than it was. Saying when it was taken is the honest version of a
            // widget that cannot reach the Mac.
            return BuddyWidgetEntry(
                date: now, snapshot: stored, isPaired: true,
                problem: Self.problem(for: error),
                quickPrompt: quickPrompt, quickAnswer: quickAnswer
            )
        }
    }

    /// When to come back. Later after a failure, which is the whole policy.
    public static func nextRefresh(after now: Date, succeeded: Bool) -> Date {
        now.addingTimeInterval(succeeded ? refreshInterval : retryInterval)
    }

    /// What a widget says when the Mac is there but not answering in time. Its own
    /// sentence, because "not reachable" would be a lie: it answered, eventually.
    public static let tooSlow = "Your Mac is taking too long to answer."

    /// Runs `operation`, giving up after `seconds` and answering nil.
    ///
    /// The transport's own timeouts are sized for a person watching a model think —
    /// nine hundred seconds on `/chat`. A widget has a few. Rather than give the
    /// transport a second set of timeouts for one caller, the caller puts a deadline on
    /// the call it makes.
    static func withDeadline<T: Sendable>(
        _ seconds: TimeInterval,
        _ operation: @escaping @Sendable () async throws -> T
    ) async throws -> T? {
        try await withThrowingTaskGroup(of: T?.self) { group in
            group.addTask { try await operation() }
            group.addTask {
                try? await Task.sleep(for: .seconds(seconds))
                return nil
            }
            // Whichever finishes first decides; the loser is cancelled, which for the
            // request means the socket closes rather than the widget waiting on it.
            let first = try await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    static func problem(for error: Error) -> String {
        guard let transport = error as? TransportError else { return "Your Mac didn't answer." }
        switch transport {
        case .unauthorized:
            return "This device is no longer paired with your Mac."
        case .forbidden:
            return "Your Mac refused the request."
        default:
            return "Your Mac isn't reachable right now."
        }
    }

    /// Fires the configured preset and returns what the Mac said.
    ///
    /// One message, no history: a widget has no transcript, and attaching the phone's
    /// last conversation to a button on the Home Screen would answer a question nobody
    /// asked. The answer is written into the shared snapshot so the app and the Lock
    /// Screen show the same "last answer".
    public static func ask(
        _ prompt: String,
        using transport: (any ControlTransport)?,
        defaults: UserDefaults = BuddyShared.defaults,
        deadline: TimeInterval = QuickPrompt.timeout
    ) async -> AskResult {
        guard let transport else { return .problem("Not paired with a Mac.") }
        do {
            let request = try IntentMapping.chatRequest(
                prompt: prompt, maxTokens: IntentMapping.spokenMaxTokens
            )
            guard let response = try await withDeadline(deadline, {
                try await transport.chat(request)
            }) else {
                return .problem(tooSlow)
            }
            let answer = response.content.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !answer.isEmpty else { return .problem("Your Mac answered with nothing.") }
            SnapshotStore.note(question: prompt, answer: answer, to: defaults)
            return .answer(answer)
        } catch let refusal as IntentMapping.Refusal {
            return .problem(refusal.localizedDescription)
        } catch {
            return .problem(Self.problem(for: error))
        }
    }
}

/// What a widget's button got back: an answer, or a sentence about why not. Both are
/// text the widget shows, which is why neither is thrown.
public enum AskResult: Sendable, Equatable {
    case answer(String)
    case problem(String)

    public var text: String {
        switch self {
        case .answer(let text), .problem(let text): text
        }
    }
}
