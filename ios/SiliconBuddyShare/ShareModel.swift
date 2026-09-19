import Foundation
import Observation
import UIKit
import UniformTypeIdentifiers

/// The share sheet's state: what arrived, what will be asked, and what came back.
@MainActor
@Observable
final class ShareModel {

    enum Phase: Equatable {
        case reading
        case ready
        case sending
        case answered(String)
        case failed(String)
    }

    private(set) var phase: Phase = .reading
    private(set) var draft = SharedDraft(prompt: "", quoted: "", images: [], summary: "")
    private(set) var storedOnMac = false
    /// The answer as it streams in, so the sheet shows writing rather than a spinner.
    private(set) var streamed = ""

    var prompt: String = ""

    /// Whether there is a Mac to ask at all, learned once when the sheet opens.
    private(set) var isPaired = false

    private var transport: (any ControlTransport)?

    func start(with items: [NSExtensionItem]) async {
        transport = SharedConfiguration.transport()
        isPaired = transport != nil
        let payload = await SharedItemReader.payload(from: items)
        draft = ShareNormaliser.draft(for: payload)
        prompt = draft.prompt
        phase = draft.isEmpty
            ? .failed("There was nothing in that to send.")
            : .ready
    }

    func send() {
        guard case .ready = phase else { return }
        guard isPaired else {
            phase = .failed(OneShotAsk.Failure.notPaired.localizedDescription)
            return
        }
        var outgoing = draft
        outgoing.prompt = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        streamed = ""
        phase = .sending
        let transport = transport
        // Strong, deliberately: a share sheet lives for the length of one question, and
        // a model released mid-answer would leave the sheet showing a spinner forever.
        Task { @MainActor in
            do {
                let outcome = try await OneShotAsk.send(
                    message: outgoing.message,
                    images: outgoing.images,
                    // Titled so the Mac's own conversation list says where it came
                    // from, rather than showing an untitled thread nobody recognises.
                    title: "Shared: " + BuddySnapshot.trim(outgoing.prompt, to: 40),
                    using: transport,
                    onToken: { piece in
                        Task { @MainActor in self.append(piece) }
                    }
                )
                self.storedOnMac = outcome.storedOnMac
                self.phase = .answered(outcome.answer)
            } catch {
                self.phase = .failed(
                    (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                )
            }
        }
    }

    private func append(_ piece: String) { streamed += piece }

    func editAgain() {
        guard case .failed = phase else { return }
        phase = draft.isEmpty ? phase : .ready
    }
}

/// Turns the extension's attachments into a payload.
///
/// Every provider is asked for the representation we actually want, and anything that
/// refuses is skipped rather than failing the share: a sending app that offers a picture
/// in five formats and a URL should not be able to stop the other four working.
///
/// `NSItemProvider` hands its result back on a queue of its own choosing, as an `Any`.
/// Nothing of that shape may cross into Swift concurrency, so each callback narrows the
/// value to one of three `Sendable` cases before it is resumed — which is also the only
/// place that has to know a "text" provider may hand over bytes rather than a string.
@MainActor
enum SharedItemReader {

    /// The three shapes worth taking from a share sheet, already Sendable.
    enum Loaded: Sendable {
        case text(String)
        case url(URL)
        case data(Data)
    }

    static func payload(from items: [NSExtensionItem]) async -> SharePayload {
        var found: [SharePayload.Item] = []
        for item in items {
            for provider in item.attachments ?? [] {
                if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier),
                   case .data(let data)? = await load(provider, as: UTType.image.identifier),
                   let image = UIImage(data: data),
                   let attachment = ImagePreparation.attachment(from: image) {
                    found.append(.image(attachment.jpeg))
                    continue
                }
                if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier),
                   case .url(let url)? = await load(provider, as: UTType.url.identifier) {
                    found.append(.url(url))
                    continue
                }
                for identifier in [UTType.plainText.identifier, UTType.text.identifier] {
                    guard provider.hasItemConformingToTypeIdentifier(identifier),
                          case .text(let text)? = await load(provider, as: identifier)
                    else { continue }
                    found.append(.text(text))
                    break
                }
            }
            // Some apps put the selection in the item's own attributed content rather
            // than in an attachment — Notes does — and dropping it would make the
            // sheet look broken for no reason the person can see.
            if let attributed = item.attributedContentText?.string,
               !attributed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                found.append(.text(attributed))
            }
        }
        return SharePayload(items: found)
    }

    private static func load(_ provider: NSItemProvider, as identifier: String) async -> Loaded? {
        await withCheckedContinuation { continuation in
            provider.loadItem(forTypeIdentifier: identifier, options: nil) { value, _ in
                continuation.resume(returning: narrow(value, wanting: identifier))
            }
        }
    }

    /// Runs on whatever queue `NSItemProvider` chose, and is the boundary: everything
    /// past it is `Sendable`.
    nonisolated private static func narrow(_ value: Any?, wanting identifier: String) -> Loaded? {
        let wantsImage = UTType(identifier)?.conforms(to: .image) ?? false
        switch value {
        case let url as URL:
            // A picture arrives as a file URL as often as it arrives as bytes.
            if wantsImage, let data = try? Data(contentsOf: url) { return .data(data) }
            return .url(url)
        case let text as String:
            return .text(text)
        case let data as Data:
            if wantsImage { return .data(data) }
            return String(data: data, encoding: .utf8).map(Loaded.text)
        case let image as UIImage:
            return image.jpegData(compressionQuality: 0.95).map(Loaded.data)
        case let attributed as NSAttributedString:
            return .text(attributed.string)
        default:
            return nil
        }
    }
}
