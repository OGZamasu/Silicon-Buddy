import SwiftUI

/// The small amount of shared look the app needs: a card, a labelled stat, a bar, a
/// pill. Everything else is stock SwiftUI, which is the point — it should feel like an
/// Apple app, not like a web page wearing a phone costume.
public enum Theme {
    public static let cardCorner: CGFloat = 14
    public static let tight: CGFloat = 6
    public static let gap: CGFloat = 12
}

/// A titled block on the dashboard.
public struct Card<Content: View>: View {
    let title: String
    let systemImage: String
    let footnote: String?
    @ViewBuilder var content: Content

    public init(
        _ title: String, systemImage: String, footnote: String? = nil,
        @ViewBuilder content: () -> Content
    ) {
        self.title = title
        self.systemImage = systemImage
        self.footnote = footnote
        self.content = content()
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: Theme.gap) {
            HStack(spacing: Theme.tight) {
                Image(systemName: systemImage)
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.secondary)
                Spacer(minLength: 0)
                if let footnote {
                    Text(footnote)
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                        .monospacedDigit()
                }
            }
            content
        }
        .padding(Theme.gap + 2)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.background.secondary, in: RoundedRectangle(cornerRadius: Theme.cardCorner))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(title)
    }
}

/// One number with its name under it.
public struct Stat: View {
    let label: String
    let value: String
    var tint: Color = .primary

    public init(_ label: String, _ value: String, tint: Color = .primary) {
        self.label = label
        self.value = value
        self.tint = tint
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(.title3.weight(.medium))
                .monospacedDigit()
                .foregroundStyle(tint)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityValue(value)
    }
}

/// A labelled proportion. Uses a Gauge's accessibility semantics rather than drawing a
/// rectangle and hoping VoiceOver guesses.
public struct MeterRow: View {
    let label: String
    let detail: String
    let fraction: Double
    var tint: Color = .accentColor

    public init(label: String, detail: String, fraction: Double, tint: Color = .accentColor) {
        self.label = label
        self.detail = detail
        self.fraction = fraction
        self.tint = tint
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(label).font(.subheadline)
                Spacer()
                Text(detail)
                    .font(.subheadline.weight(.medium))
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
            }
            ProgressView(value: min(max(fraction, 0), 1))
                .progressViewStyle(.linear)
                .tint(tint)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityValue(detail)
    }
}

/// A small status word — "Ready", "Loaded", "Vision".
public struct Pill: View {
    let text: String
    var tint: Color = .secondary
    var filled = false

    public init(_ text: String, tint: Color = .secondary, filled: Bool = false) {
        self.text = text
        self.tint = tint
        self.filled = filled
    }

    public var body: some View {
        Text(text)
            .font(.caption2.weight(.medium))
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(
                filled ? AnyShapeStyle(tint.opacity(0.18)) : AnyShapeStyle(.quaternary),
                in: Capsule()
            )
            .foregroundStyle(filled ? tint : .secondary)
    }
}

/// The empty state used everywhere something is missing rather than broken.
public struct Placeholder: View {
    let title: String
    let message: String
    let systemImage: String

    public init(title: String, message: String, systemImage: String) {
        self.title = title
        self.message = message
        self.systemImage = systemImage
    }

    public var body: some View {
        ContentUnavailableView {
            Label(title, systemImage: systemImage)
        } description: {
            Text(message)
        }
    }
}
