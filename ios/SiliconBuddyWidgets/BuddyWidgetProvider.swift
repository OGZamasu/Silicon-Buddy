import AppIntents
import WidgetKit

/// The timeline for the configurable Home Screen widget.
///
/// All of the thinking is in `WidgetTimeline`, which knows nothing about WidgetKit and
/// can therefore be run against a stand-in Mac in a test. This is the adapter.
struct BuddyProvider: AppIntentTimelineProvider {

    func placeholder(in context: Context) -> BuddyWidgetEntry {
        BuddyWidgetEntry(
            snapshot: BuddySnapshot(
                macName: "Your Mac", state: "Ready",
                loadedModelName: "Qwen3 4B", lastAnswer: "Ready when you are."
            ),
            isPaired: true
        )
    }

    func snapshot(
        for configuration: QuickPromptConfiguration, in context: Context
    ) async -> BuddyWidgetEntry {
        // The gallery preview. Never a network call: the widget picker shows dozens of
        // these at once and a tailnet round trip apiece would stall it.
        BuddyWidgetEntry(
            snapshot: SnapshotStore.read() ?? BuddySnapshot(
                macName: "Your Mac", state: "Ready", loadedModelName: "Qwen3 4B",
                lastAnswer: "Ask, and the answer appears here."
            ),
            isPaired: SharedConfiguration.load() != nil,
            quickPrompt: configuration.prompt
        )
    }

    func timeline(
        for configuration: QuickPromptConfiguration, in context: Context
    ) async -> Timeline<BuddyWidgetEntry> {
        configuration.remember()
        let stored = SnapshotStore.read()
        let answer = QuickPrompt.answer()
        var entry = await WidgetTimeline.entry(
            using: SharedConfiguration.transport(),
            stored: stored,
            quickPrompt: configuration.prompt,
            quickAnswer: answer?.text
        )
        entry.quickPrompt = configuration.prompt
        return Timeline(
            entries: [entry],
            policy: .after(
                WidgetTimeline.nextRefresh(after: entry.date, succeeded: entry.problem == nil)
            )
        )
    }
}

/// The Lock Screen accessory: what is loaded, and nothing else.
///
/// No configuration and no button. An accessory has room for one fact and the Lock
/// Screen is not the place to start a generation, so this only ever reads.
struct LoadedModelProvider: TimelineProvider {

    func placeholder(in context: Context) -> BuddyWidgetEntry {
        BuddyWidgetEntry(snapshot: BuddySnapshot(loadedModelName: "Qwen3 4B"), isPaired: true)
    }

    func getSnapshot(in context: Context, completion: @escaping (BuddyWidgetEntry) -> Void) {
        completion(
            BuddyWidgetEntry(
                snapshot: SnapshotStore.read() ?? BuddySnapshot(loadedModelName: "Qwen3 4B"),
                isPaired: SharedConfiguration.load() != nil
            )
        )
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<BuddyWidgetEntry>) -> Void) {
        // `TimelineProvider` predates Swift concurrency: its completion is a plain
        // escaping closure, and asking the Mac means leaving this function before it
        // can be called. WidgetKit calls it exactly once and does not care which queue
        // from, which is what the box below asserts and the comment records.
        let handback = TimelineHandback(call: completion)
        Task {
            let entry = await WidgetTimeline.entry(
                using: SharedConfiguration.transport(),
                stored: SnapshotStore.read(),
                quickPrompt: QuickPrompt.stored()
            )
            handback.call(
                Timeline(
                    entries: [entry],
                    policy: .after(
                        WidgetTimeline.nextRefresh(
                            after: entry.date, succeeded: entry.problem == nil
                        )
                    )
                )
            )
        }
    }
}

/// WidgetKit's completion handler, carried across one `await`.
private struct TimelineHandback: @unchecked Sendable {
    let call: (Timeline<BuddyWidgetEntry>) -> Void
}

extension BuddyWidgetEntry: TimelineEntry {}
