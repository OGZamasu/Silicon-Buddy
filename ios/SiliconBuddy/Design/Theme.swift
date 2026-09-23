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

/// `Stat`s side by side, as many to a row as fit whole: three on a phone at the usual text
/// size, fewer as Dynamic Type grows. No column is narrower than the widest stat's value on
/// one line, less the little a `Stat` may shrink it by, so a large size moves a stat to the
/// next row rather than shrinking "402.65 GB" until its unit is cut off.
public struct StatRow: Layout {
    var spacing: CGFloat = 12

    /// How far a value may be shrunk to keep a row together — "402.65 GB" in a third of an
    /// iPhone at the default size is about 0.9 — before a stat moves down instead.
    private static let shrink: CGFloat = 0.85

    public init(spacing: CGFloat = 12) {
        self.spacing = spacing
    }

    public func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard !subviews.isEmpty else { return .zero }
        let width = available(proposal, subviews)
        let grid = arrange(subviews, width: width)
        let height = grid.heights.reduce(0, +) + spacing * CGFloat(grid.heights.count - 1)
        return CGSize(width: width, height: height)
    }

    public func placeSubviews(
        in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()
    ) {
        guard !subviews.isEmpty else { return }
        let grid = arrange(subviews, width: bounds.width)
        var y = bounds.minY
        for (row, height) in grid.heights.enumerated() {
            for column in 0..<grid.columns {
                let index = row * grid.columns + column
                guard index < subviews.count else { break }
                subviews[index].place(
                    at: CGPoint(x: bounds.minX + CGFloat(column) * (grid.cell + spacing), y: y),
                    anchor: .topLeading,
                    proposal: ProposedViewSize(width: grid.cell, height: height)
                )
            }
            y += height + spacing
        }
    }

    /// The width offered, or — asked for its ideal — one row of every stat at its own width.
    private func available(_ proposal: ProposedViewSize, _ subviews: Subviews) -> CGFloat {
        if let width = proposal.width, width.isFinite { return width }
        let ideal = subviews.map { $0.sizeThatFits(.unspecified).width }
        return ideal.reduce(0, +) + spacing * CGFloat(ideal.count - 1)
    }

    private func arrange(_ subviews: Subviews, width: CGFloat) -> (columns: Int, cell: CGFloat, heights: [CGFloat]) {
        let widest = (subviews.map { $0.sizeThatFits(.unspecified).width }.max() ?? 0) * Self.shrink
        let fit = Int(((width + spacing) / (widest + spacing)).rounded(.down))
        let columns = min(max(fit, 1), subviews.count)
        let cell = (width - spacing * CGFloat(columns - 1)) / CGFloat(columns)
        let heights = stride(from: 0, to: subviews.count, by: columns).map { start in
            subviews[start..<min(start + columns, subviews.count)]
                .map { $0.sizeThatFits(ProposedViewSize(width: cell, height: nil)).height }
                .max() ?? 0
        }
        return (columns, cell, heights)
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
