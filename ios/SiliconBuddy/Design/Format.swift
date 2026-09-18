import Foundation

/// Numbers the way this app says them: bytes as GB, utilisation as whole percent,
/// token rates with one decimal. Centralised so the dashboard and the model list never
/// disagree about what 5,946,648,928 is.
public enum Format {
    public static func bytes(_ value: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        formatter.allowedUnits = [.useGB, .useMB, .useKB]
        return formatter.string(fromByteCount: value)
    }

    public static func bytes(_ value: Int64?) -> String {
        value.map(bytes) ?? "—"
    }

    public static func gigabytes(_ value: Double) -> String {
        String(format: "%.1f GB", value)
    }

    public static func percent(_ fraction: Double) -> String {
        "\(Int((fraction * 100).rounded()))%"
    }

    public static func rate(_ tokensPerSecond: Double?) -> String {
        guard let tokensPerSecond, tokensPerSecond > 0 else { return "—" }
        return String(format: "%.1f tok/s", tokensPerSecond)
    }

    public static func seconds(_ value: Double?) -> String {
        guard let value else { return "—" }
        if value < 60 { return String(format: "%.0f s ago", value) }
        return String(format: "%.0f min ago", value / 60)
    }

    public static func relative(_ date: Date, now: Date = Date()) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: now)
    }
}
