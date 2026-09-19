import Foundation

/// The Mac's agent sessions: the Chat tab's Codex and Pi engines, mirrored.
///
/// Types only, for now. The Android app drives these sessions from the phone as of M4;
/// the iPad catches up afterwards, and until then these exist so `ContractTests` holds
/// this app to the same shapes the Mac exports — a field the Mac adds fails a build here
/// rather than a screen later.
///
/// There is one session per engine and it is the session on the Mac, not a copy, so the
/// session id is the engine id. Every route is full scope: each one runs commands on the
/// owner's Mac. Timestamps stay the Mac's ISO-8601 text, since nothing here computes with
/// them yet.
public enum AgentAPI {

    /// One gateway model as a picker needs it. `where` is for reading — "This Mac" or a
    /// peer's name — never for routing: `id` is what a turn is sent with.
    public struct ModelChoice: Codable, Sendable, Equatable, Identifiable {
        public var id: String
        public var label: String
        public var `where`: String

        public init(id: String, label: String, where location: String) {
            self.id = id
            self.label = label
            self.where = location
        }
    }

    /// A session as a list shows it — present even when the engine is stopped, so a phone
    /// can offer to start it.
    public struct SessionSummary: Codable, Sendable, Equatable, Identifiable {
        /// "codex" or "pi", and the session id.
        public var engine: String
        public var id: String { engine }
        /// `stopped`, `starting`, `running` or `failed`.
        public var state: String
        /// Codex mints one at `thread/start`; Pi has none to give.
        public var threadID: String?
        /// Which transcript this is: it changes with a new thread, a restart and every
        /// launch of the Mac's app, and a `since` is only a cursor together with it.
        public var epoch: String
        /// The model the next turn uses: the session's, not one turn's.
        public var model: String
        public var modelChoices: [ModelChoice]
        /// Home-relative (`~/…`). Chosen on the Mac, never by a device; absent for Codex
        /// until the owner has picked one.
        public var cwd: String?
        /// `screened`, `asked` or `unattended`: whether anything asks before the agent acts.
        public var approvals: String
        /// Codex's sandbox for the current thread, or `none` for Pi.
        public var sandbox: String
        public var turnActive: Bool
        /// Zero unless the engine is running.
        public var pendingApprovals: Int
        public var itemCount: Int
        public var updatedAt: String
        /// Why it is `failed`, in the engine's words. Never set otherwise.
        public var failure: String?

        public init(
            engine: String, state: String, threadID: String? = nil, epoch: String,
            model: String, modelChoices: [ModelChoice], cwd: String? = nil, approvals: String,
            sandbox: String, turnActive: Bool, pendingApprovals: Int, itemCount: Int,
            updatedAt: String, failure: String? = nil
        ) {
            self.engine = engine
            self.state = state
            self.threadID = threadID
            self.epoch = epoch
            self.model = model
            self.modelChoices = modelChoices
            self.cwd = cwd
            self.approvals = approvals
            self.sandbox = sandbox
            self.turnActive = turnActive
            self.pendingApprovals = pendingApprovals
            self.itemCount = itemCount
            self.updatedAt = updatedAt
            self.failure = failure
        }
    }

    /// `GET /agent/sessions`.
    public struct SessionList: Codable, Sendable, Equatable {
        public var sessions: [SessionSummary]
        public init(sessions: [SessionSummary]) { self.sessions = sessions }
    }

    /// One transcript row, in the vocabulary both engines are mapped onto: `user`,
    /// `assistant`, `reasoning`, `command`, `fileChange`, `tool`, `notice`, `error`.
    public struct Item: Codable, Sendable, Equatable, Identifiable {
        public var id: String
        public var kind: String
        /// What the row is about: the prose, the command line, the tool.
        public var text: String
        /// What came back, where the engine keeps it apart: at most its last 8,192 characters.
        public var output: String?
        /// True when `output` is the tail of something longer.
        public var truncated: Bool?
        /// `running`, `completed`, `failed` or `declined`, where the row has a lifecycle.
        public var status: String?
        /// The model the turn was sent with, on the row that was the sending.
        public var model: String?
        public var at: String

        public init(
            id: String, kind: String, text: String, output: String? = nil,
            truncated: Bool? = nil, status: String? = nil, model: String? = nil, at: String
        ) {
            self.id = id
            self.kind = kind
            self.text = text
            self.output = output
            self.truncated = truncated
            self.status = status
            self.model = model
            self.at = at
        }
    }

    /// What the Mac's guardrail made of a held call, as the Mac's own card says it.
    public struct Screening: Codable, Sendable, Equatable {
        /// `act`, `confirm`, `block` or `unavailable` — and `unavailable` is never a pass.
        public var verdict: String
        /// "Jev: review: destructive".
        public var summary: String

        public init(verdict: String, summary: String) {
            self.verdict = verdict
            self.summary = summary
        }
    }

    /// A call the agent is holding for a person — listed only once the guardrail has spoken.
    public struct Approval: Codable, Sendable, Equatable, Identifiable {
        public var id: String
        /// `command`, `fileChange` or `tool`.
        public var kind: String
        /// The command line, the paths, or the tool and its arguments.
        public var summary: String
        public var reason: String?
        public var screening: Screening
        public var requestedAt: String

        public init(
            id: String, kind: String, summary: String, reason: String? = nil,
            screening: Screening, requestedAt: String
        ) {
            self.id = id
            self.kind = kind
            self.summary = summary
            self.reason = reason
            self.screening = screening
            self.requestedAt = requestedAt
        }
    }

    /// `GET /agent/sessions/{engine}`, with or without `?since=`.
    public struct SessionDetail: Codable, Sendable, Equatable {
        public var session: SessionSummary
        public var items: [Item]
        /// Always the whole set waiting, never a slice.
        public var approvals: [Approval]
        /// The next `?since=`.
        public var seq: Int
        /// The transcript `seq` belongs to; the other half of the cursor.
        public var epoch: String
        /// True when `items` replaces what is held: replace rather than merge.
        public var complete: Bool
        /// Rows left out by `limit`: the oldest ones, still on the Mac.
        public var omitted: Int

        public init(
            session: SessionSummary, items: [Item], approvals: [Approval], seq: Int,
            epoch: String, complete: Bool, omitted: Int = 0
        ) {
            self.session = session
            self.items = items
            self.approvals = approvals
            self.seq = seq
            self.epoch = epoch
            self.complete = complete
            self.omitted = omitted
        }
    }

    /// `POST /agent/sessions/{engine}/messages`. `model` must be one of the session's
    /// `modelChoices`, and it sticks as that engine's model on the Mac.
    public struct MessageRequest: Codable, Sendable, Equatable {
        public var text: String
        public var model: String?

        public init(text: String, model: String? = nil) {
            self.text = text
            self.model = model
        }
    }

    /// 202: the row the send became.
    public struct MessageAccepted: Codable, Sendable, Equatable {
        public var itemID: String
        public init(itemID: String) { self.itemID = itemID }
    }

    /// `POST /agent/sessions/{engine}/approvals/{id}`: `accept` or `decline`.
    public struct ApprovalDecision: Codable, Sendable, Equatable {
        public var decision: String
        public init(decision: String) { self.decision = decision }
    }

    /// The decision as applied — `accepted` or `declined` — and the session after it.
    public struct ApprovalResult: Codable, Sendable, Equatable {
        public var id: String
        public var decision: String
        public var session: SessionSummary

        public init(id: String, decision: String, session: SessionSummary) {
            self.id = id
            self.decision = decision
            self.session = session
        }
    }

    /// The `agent` frame on `GET /events`: `reset` (the transcript was replaced), `state`,
    /// `turn`, `item` (a row whole) or `approval` (with `state` saying `pending`,
    /// `accepted` or `declined`). Frames arrive in `seq` order.
    public struct Event: Codable, Sendable, Equatable {
        public var engine: String
        public var kind: String
        public var seq: Int
        public var epoch: String
        public var threadID: String?
        public var item: Item?
        public var approval: Approval?
        public var turnActive: Bool?
        public var state: String?

        public init(
            engine: String, kind: String, seq: Int, epoch: String, threadID: String? = nil,
            item: Item? = nil, approval: Approval? = nil, turnActive: Bool? = nil,
            state: String? = nil
        ) {
            self.engine = engine
            self.kind = kind
            self.seq = seq
            self.epoch = epoch
            self.threadID = threadID
            self.item = item
            self.approval = approval
            self.turnActive = turnActive
            self.state = state
        }
    }

    /// `resync` on `GET /events`: the Mac dropped frames for a subscriber that fell behind.
    public struct Resync: Codable, Sendable, Equatable {
        public var dropped: Int
        public init(dropped: Int) { self.dropped = dropped }
    }
}
