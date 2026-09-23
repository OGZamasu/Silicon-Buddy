import Foundation
import Observation

/// The pairing code being spent, owned by `AppModel` rather than by the sheet that asked
/// for it.
///
/// `POST /buddy/pair` spends the code and makes this device a device on the Mac. Each pair
/// button used to start its own `Task`, which a closing sheet did not cancel — but nothing
/// stopped a second one either. Close the sheet while code X was being spent, pair Y, and
/// X's answer, landing later, silently replaced Y: whichever exchange *landed* last won,
/// not the one chosen last, and no one was asked. A failure that landed after its sheet
/// had closed was said nowhere.
///
/// Here there is one exchange at a time. It runs to its end whatever becomes of the screen
/// that started it, and what it brings back is stored. A second code waits, and when it
/// can go, replacing the Mac the first one paired asks first, as replacing any Mac does. A
/// failure that no screen is left to show stays in `state` until one has shown it.
@MainActor
@Observable
public final class PairingExchange {

    public enum State: Equatable, Sendable {
        case idle
        case working(PairingInvite)
        /// `message` is for the person; `macTooOld` is a Mac without `/buddy/pair`.
        case failed(PairingInvite, message: String, macTooOld: Bool)
    }

    /// What a screen asking about one invite shows of the exchange.
    public enum Phase: Equatable, Sendable {
        /// Nothing is being spent: this invite may go.
        case ready
        /// This invite is being spent.
        case spending
        /// Another code is being spent, and this one waits for it. Declining is still fine.
        case waiting(for: PairingInvite)
        /// This invite was refused, and may be tried again.
        case failed(String, macTooOld: Bool)
        /// Another code was refused, and nothing has said so yet. This one may go.
        case otherFailed(PairingInvite, String)
    }

    /// What a Mac without `POST /buddy/pair` gets told, however its code was spent.
    public static let macTooOldForCodes =
        "That Mac is too old for pairing codes. Update Silicon Optimizer on it, then pair again."

    public private(set) var state: State = .idle

    /// The exchange in flight, for a test to wait on. Nothing else needs it: it is never
    /// cancelled.
    @ObservationIgnored private var running: Task<Void, Never>?

    public init() {}

    public var isWorking: Bool {
        if case .working = state { return true }
        return false
    }

    /// The code being spent now, when one is.
    public var spending: PairingInvite? {
        if case .working(let invite) = state { return invite }
        return nil
    }

    /// A code that was refused, or that never reached its Mac.
    public struct Failure: Equatable, Sendable {
        public var invite: PairingInvite
        public var message: String
        public var macTooOld: Bool
    }

    /// How the last code ended, when it failed and nothing has shown that yet.
    public var failure: Failure? {
        if case .failed(let invite, let message, let macTooOld) = state {
            return Failure(invite: invite, message: message, macTooOld: macTooOld)
        }
        return nil
    }

    public func phase(of invite: PairingInvite) -> Phase {
        switch state {
        case .idle:
            return .ready
        case .working(let spent):
            return spent == invite ? .spending : .waiting(for: spent)
        case .failed(let spent, let message, let macTooOld):
            return spent == invite ? .failed(message, macTooOld: macTooOld) : .otherFailed(spent, message)
        }
    }

    /// Spends `invite` with `exchange` and hands what the Mac answered to `store`. False,
    /// with nothing dialled, while another code is still being spent: one pairing at a time.
    @discardableResult
    public func start(
        _ invite: PairingInvite,
        exchange: @escaping @Sendable (PairingInvite) async throws -> ServerConfig,
        store: @escaping @MainActor (ServerConfig) throws -> Void
    ) -> Bool {
        guard !isWorking else { return false }
        state = .working(invite)
        // Not the caller's task, and never cancelled: a request cut off after it was sent
        // is one the Mac may already have answered — the code spent, a device record made,
        // and its token dropped by a device that then says it isn't paired.
        running = Task {
            do {
                try store(try await exchange(invite))
                state = .idle
            } catch let error as TransportError where error.isMissingRoute {
                state = .failed(invite, message: Self.macTooOldForCodes, macTooOld: true)
            } catch {
                state = .failed(invite, message: error.localizedDescription, macTooOld: false)
            }
        }
        return true
    }

    /// A failure has been shown to the person, so it need not be shown again.
    public func acknowledge() {
        if case .failed = state { state = .idle }
    }

    /// Returns once the exchange in flight, if any, has ended.
    func settled() async {
        await running?.value
    }
}
