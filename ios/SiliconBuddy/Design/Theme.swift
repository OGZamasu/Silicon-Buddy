import SwiftUI

/// Shared surfaces and spacing, with native controls and Dynamic Type on every screen.
public enum Theme {
    public static let cardCorner: CGFloat = 24
    public static let tight: CGFloat = 6
    public static let gap: CGFloat = 16
    public static var canvas: Color { Color("Canvas") }
    public static var surface: Color { Color("CardSurface") }
    public static var border: Color { Color("CardBorder") }
    public static var onAccent: Color { Color("OnAccent") }
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
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.accentColor)
                    .padding(9)
                    .background(Color.accentColor.opacity(0.10), in: RoundedRectangle(cornerRadius: 10))
                    .accessibilityHidden(true)
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
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
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: Theme.cardCorner))
        .overlay {
            RoundedRectangle(cornerRadius: Theme.cardCorner).strokeBorder(Theme.border, lineWidth: 1)
        }
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
                .font(.title2.weight(.semibold))
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
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
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
            VStack(spacing: 16) {
                Image(systemName: systemImage)
                    .font(.title)
                    .foregroundStyle(Color.accentColor)
                    .padding(20)
                    .background(Color.accentColor.opacity(0.10), in: RoundedRectangle(cornerRadius: 22))
                    .accessibilityHidden(true)
                Text(title).font(.title2.weight(.semibold))
            }
        } description: {
            Text(message)
        }
    }
}
