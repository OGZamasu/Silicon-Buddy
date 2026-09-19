import SwiftUI
import UIKit

/// The share extension's entry point.
///
/// A share extension is a `UIViewController` whatever the UI is written in, so this is
/// the two dozen lines that host SwiftUI and hand back control when the sheet closes.
@objc(ShareViewController)
final class ShareViewController: UIViewController {

    private let model = ShareModel()

    override func viewDidLoad() {
        super.viewDidLoad()
        let composer = ShareComposerView(model: model) { [weak self] in self?.finish() }
        let hosting = UIHostingController(rootView: composer)
        addChild(hosting)
        hosting.view.frame = view.bounds
        hosting.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(hosting.view)
        hosting.didMove(toParent: self)

        let items = (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
        Task { await model.start(with: items) }
    }

    /// Always completes rather than cancels: the sheet is dismissed either way, and a
    /// cancellation tells the sending app the share failed, which it did not.
    private func finish() {
        extensionContext?.completeRequest(returningItems: nil)
    }
}
