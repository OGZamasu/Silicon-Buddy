import Foundation

/// What another app handed us, before anything has been decided about it.
///
/// The share sheet receives whatever the sending app felt like attaching: a selection of
/// text, a link, a photograph, or several at once, in any order. Turning that into one
/// message for a model is the only part worth testing, so it lives here rather than in
/// the view controller.
public struct SharePayload: Sendable, Equatable {

    public enum Item: Sendable, Equatable {
        case text(String)
        case url(URL)
        /// JPEG bytes, already scaled to the Mac's per-image cap by the caller.
        case image(Data)
    }

    public var items: [Item]

    public init(items: [Item]) { self.items = items }

    public var isEmpty: Bool { items.isEmpty }
}

/// One message, ready for the composer: what to ask, what was shared, and the pictures.
public struct SharedDraft: Sendable, Equatable {
    /// The editable question, prefilled. The person can replace it entirely.
    public var prompt: String
    /// The shared material, quoted under the question. Empty when only pictures came.
    public var quoted: String
    /// Base64 `data:` URLs within the Mac's caps.
    public var images: [String]
    /// One line naming what arrived, for the sheet's header.
    public var summary: String
    /// Said out loud when something had to be dropped, rather than dropped quietly.
    public var note: String?

    public init(
        prompt: String, quoted: String, images: [String], summary: String, note: String? = nil
    ) {
        self.prompt = prompt
        self.quoted = quoted
        self.images = images
        self.summary = summary
        self.note = note
    }

    /// What actually goes to the Mac: the question, then the material under it.
    public var message: String {
        quoted.isEmpty ? prompt : prompt + "\n\n" + quoted
    }

    /// True when nothing actually arrived. The prompt does not count: it is this app's
    /// suggestion, not the other app's contribution, and a sheet offering to summarise
    /// nothing is a sheet that should not have opened.
    public var isEmpty: Bool { quoted.isEmpty && images.isEmpty }
}

/// Turns what arrived into what to send.
public enum ShareNormaliser {

    /// How much shared text to carry. A share sheet is handed whole web pages, and a
    /// message that fills the model's context leaves nothing for the answer — so the
    /// text is cut here, visibly, rather than by the Mac on arrival.
    public static let maximumQuotedCharacters = 8_000

    public static func draft(for payload: SharePayload) -> SharedDraft {
        var texts: [String] = []
        var urls: [URL] = []
        var images: [Data] = []
        for item in payload.items {
            switch item {
            case .text(let text):
                let tidy = text.trimmingCharacters(in: .whitespacesAndNewlines)
                if !tidy.isEmpty { texts.append(tidy) }
            case .url(let url):
                urls.append(url)
            case .image(let data):
                images.append(data)
            }
        }

        var notes: [String] = []

        // A URL that also came through as text — which is what Safari sends — is one
        // thing shared, not two. Quoting it twice would waste context and read oddly.
        texts.removeAll { text in urls.contains { $0.absoluteString == text } }

        var accepted = images
        if accepted.count > SendLimits.maximumAttachments {
            notes.append(
                "Only the first \(SendLimits.maximumAttachments) pictures were kept — "
                    + "that is all the Mac takes in one message."
            )
            accepted = Array(accepted.prefix(SendLimits.maximumAttachments))
        }
        let oversized = accepted.filter { $0.count > SendLimits.maximumImageBytes }
        if !oversized.isEmpty {
            notes.append(
                oversized.count == 1
                    ? "One picture was too large to send even after shrinking."
                    : "\(oversized.count) pictures were too large to send even after shrinking."
            )
            accepted = accepted.filter { $0.count <= SendLimits.maximumImageBytes }
        }

        var body = texts
        body.append(contentsOf: urls.map(\.absoluteString))
        var quoted = body.joined(separator: "\n\n")
        if quoted.count > maximumQuotedCharacters {
            quoted = String(quoted.prefix(maximumQuotedCharacters))
            notes.append("The text was cut to the first \(maximumQuotedCharacters) characters.")
        }

        // Eight pictures each inside the per-image cap can still be three times the
        // body the Mac accepts, and eight legal pictures adding up to an illegal
        // message is exactly the shape a share sheet produces — select-all in Photos,
        // share. So the last cap is the one on the whole thing, and pictures come off
        // the end until it fits rather than the Mac answering 413 after the upload.
        let dropped = fit(&accepted, alongside: quoted)
        if dropped > 0 {
            notes.append(
                dropped == 1
                    ? "One picture was left out to keep the message under the 4 MB the Mac takes."
                    : "\(dropped) pictures were left out to keep the message under the 4 MB the Mac takes."
            )
        }

        let nothingArrived = quoted.isEmpty && accepted.isEmpty
        return SharedDraft(
            prompt: nothingArrived
                ? "" : prompt(hasImages: !accepted.isEmpty, hasText: !quoted.isEmpty),
            quoted: quoted,
            images: accepted.map { "data:image/jpeg;base64," + $0.base64EncodedString() },
            summary: summary(textCount: texts.count, urls: urls, images: accepted.count),
            note: notes.isEmpty ? nil : notes.joined(separator: " ")
        )
    }

    /// Drops pictures from the end until the encoded message fits, and says how many.
    ///
    /// The arithmetic is the composer's: base64 costs a third on top, plus the JSON
    /// around each one, plus the text and a little slack.
    static func fit(_ images: inout [Data], alongside text: String) -> Int {
        func size() -> Int {
            images.reduce(0) { $0 + ($1.count * 4 / 3) + 64 } + text.utf8.count + 512
        }
        var dropped = 0
        while !images.isEmpty, size() > SendLimits.maximumBodyBytes {
            images.removeLast()
            dropped += 1
        }
        return dropped
    }

    /// The question that is already in the box. A picture is nearly always "what is
    /// this?" and a page is nearly always "summarise this"; anything else is a word or
    /// two of typing away.
    static func prompt(hasImages: Bool, hasText: Bool) -> String {
        switch (hasImages, hasText) {
        case (true, true): "What is this, and what does the text say about it?"
        case (true, false): "What is this?"
        default: "Summarise this"
        }
    }

    static func summary(textCount: Int, urls: [URL], images: Int) -> String {
        var parts: [String] = []
        if images == 1 { parts.append("a picture") } else if images > 1 {
            parts.append("\(images) pictures")
        }
        if let first = urls.first {
            parts.append(urls.count == 1 ? (first.host ?? "a link") : "\(urls.count) links")
        }
        if textCount == 1 { parts.append("some text") } else if textCount > 1 {
            parts.append("\(textCount) pieces of text")
        }
        guard !parts.isEmpty else { return "Nothing to send" }
        if parts.count == 1 { return "Shared: " + parts[0] }
        return "Shared: " + parts.dropLast().joined(separator: ", ") + " and " + parts[parts.count - 1]
    }
}
