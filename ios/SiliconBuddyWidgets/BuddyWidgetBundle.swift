import SwiftUI
import WidgetKit

@main
struct SiliconBuddyWidgets: WidgetBundle {
    var body: some Widget {
        BuddyWidget()
        LoadedModelWidget()
    }
}

/// Home Screen, small and medium, with a preset question the owner picks.
struct BuddyWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(
            kind: "dev.siliconoptimizer.buddy.widget",
            intent: QuickPromptConfiguration.self,
            provider: BuddyProvider()
        ) { entry in
            BuddyWidgetView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Silicon Buddy")
        .description("What your Mac has loaded, the last answer, and a question you can fire with one tap.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

/// Lock Screen, and the iPad's today view.
struct LoadedModelWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(
            kind: "dev.siliconoptimizer.buddy.widget.loaded",
            provider: LoadedModelProvider()
        ) { entry in
            LoadedModelView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Loaded model")
        .description("Which model your Mac has loaded right now.")
        .supportedFamilies([.accessoryInline, .accessoryCircular, .accessoryRectangular])
    }
}
