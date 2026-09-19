import Foundation

/// The part of "Hey Siri, load Gemma on my Mac" that is worth testing.
///
/// An intent parameter is a phrase a person said or typed, and the Mac wants an id. The
/// mapping between the two is where a shortcut either works or quietly loads the wrong
/// model, so it is here, in one place, with no Siri anywhere near it.
public enum IntentMapping {

    /// Why an intent could not be carried out, in words that Siri can read aloud.
    public enum Refusal: Error, Equatable, LocalizedError {
        case notPaired
        case notAllowed
        case emptyPrompt
        case noSuchModel(String)
        case ambiguous(String, [String])

        public var errorDescription: String? {
            switch self {
            case .notPaired:
                "Silicon Buddy isn't paired with a Mac yet. Open the app and pair first."
            case .notAllowed:
                BuddyAPI.DeviceScope.chat.explanation
            case .emptyPrompt:
                "There was nothing to ask."
            case .noSuchModel(let name):
                "No model on your Mac is called \"\(name)\"."
            case .ambiguous(let name, let candidates):
                "\"\(name)\" matches \(candidates.count) models: "
                    + candidates.prefix(3).joined(separator: ", ")
                    + ". Say more of the name."
            }
        }
    }

    /// How much an intent may generate. Smaller than the chat screen's on purpose: a
    /// spoken answer that runs for two thousand tokens is not an answer, it is a
    /// monologue, and Siri cuts it off anyway.
    public static let spokenMaxTokens = 512

    /// "Ask my Mac <text>" — one question, no history. A Shortcuts action has no
    /// transcript behind it, and pretending otherwise would attach whatever the phone
    /// last talked about to an unrelated question.
    public static func chatRequest(
        prompt: String, images: [String] = [], maxTokens: Int = spokenMaxTokens
    ) throws -> ControlAPI.ChatRequest {
        let text = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty || !images.isEmpty else { throw Refusal.emptyPrompt }
        return ControlAPI.ChatRequest(
            messages: [ControlAPI.ChatRequest.Message(role: "user", content: text, images: images)],
            maxTokens: maxTokens
        )
    }

    /// "Load <model>" — a phrase, against what the Mac actually has installed.
    ///
    /// Exact id first, then exact name, then a unique prefix, then a unique containment.
    /// Each step is narrower than the next, and a step that matches more than one model
    /// refuses rather than picking: loading the wrong 27B model costs minutes and a
    /// gigabyte of memory, and the person is standing there listening.
    public static func resolveModel(
        named phrase: String, in installed: [ControlAPI.InstalledModel], scope: BuddyAPI.DeviceScope
    ) throws -> ControlAPI.InstalledModel {
        guard scope.canControl else { throw Refusal.notAllowed }
        let needle = normalise(phrase)
        guard !needle.isEmpty else { throw Refusal.noSuchModel(phrase) }

        if let exact = installed.first(where: { normalise($0.id) == needle }) { return exact }

        let byName = installed.filter { normalise($0.name) == needle }
        if byName.count == 1 { return byName[0] }
        if byName.count > 1 { throw Refusal.ambiguous(phrase, byName.map(\.name)) }

        // The bare model id without its quantization — "gemma-3-12b-it" for
        // "gemma-3-12b-it@Q4_K_M" — which is how a person says it and how the catalog
        // prints it.
        let byBaseID = installed.filter {
            normalise($0.id.split(separator: "@").first.map(String.init) ?? $0.id) == needle
        }
        if byBaseID.count == 1 { return byBaseID[0] }
        if byBaseID.count > 1 { throw Refusal.ambiguous(phrase, byBaseID.map(\.name)) }

        let byPrefix = installed.filter { normalise($0.name).hasPrefix(needle) }
        if byPrefix.count == 1 { return byPrefix[0] }
        if byPrefix.count > 1 { throw Refusal.ambiguous(phrase, byPrefix.map(\.name)) }

        let byContains = installed.filter {
            normalise($0.name).contains(needle) || normalise($0.id).contains(needle)
        }
        if byContains.count == 1 { return byContains[0] }
        if byContains.count > 1 { throw Refusal.ambiguous(phrase, byContains.map(\.name)) }

        throw Refusal.noSuchModel(phrase)
    }

    public static func loadRequest(
        named phrase: String, in installed: [ControlAPI.InstalledModel], scope: BuddyAPI.DeviceScope
    ) throws -> ControlAPI.LoadRequest {
        // An installed model carries its quantization in its id, so the request needs
        // nothing else: the Mac already knows what it has on disk.
        ControlAPI.LoadRequest(modelID: try resolveModel(named: phrase, in: installed, scope: scope).id)
    }

    /// "What is loaded on my Mac" — one sentence, spoken.
    public static func loadedSummary(_ status: ControlAPI.Status, macName: String?) -> String {
        let mac = macName ?? "your Mac"
        guard let name = status.loadedModelName ?? status.loadedModelID else {
            return "Nothing is loaded on \(mac) right now."
        }
        var sentence = "\(mac) has \(name) loaded"
        if let context = status.contextLength, context > 0 {
            sentence += ", with a \(contextPhrase(context)) context"
        }
        if let rate = status.lastGenerationTokensPerSecond, rate > 0 {
            sentence += String(format: ", last answering at %.0f tokens a second", rate)
        }
        return sentence + "."
    }

    /// "65,536" spoken as "64K", because that is how anybody says it.
    static func contextPhrase(_ tokens: Int) -> String {
        guard tokens >= 1024 else { return "\(tokens) token" }
        let thousands = Double(tokens) / 1024
        return thousands == thousands.rounded()
            ? "\(Int(thousands))K" : String(format: "%.1fK", thousands)
    }

    /// Case, spacing and punctuation are not how a person distinguishes two models.
    static func normalise(_ text: String) -> String {
        text.lowercased()
            .replacingOccurrences(of: "_", with: "-")
            .replacingOccurrences(of: " ", with: "-")
            .trimmingCharacters(in: CharacterSet(charactersIn: "-.,!? "))
    }
}
