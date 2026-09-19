package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.agents.AgentBoard
import dev.siliconoptimizer.buddy.agents.AgentNotices
import dev.siliconoptimizer.buddy.agents.AgentNotifications
import dev.siliconoptimizer.buddy.agents.AgentNotifier
import dev.siliconoptimizer.buddy.agents.Resolution
import dev.siliconoptimizer.buddy.reach.SnapshotStore
import dev.siliconoptimizer.buddy.tile.BuddyTileService
import dev.siliconoptimizer.buddy.transport.AgentApproval
import dev.siliconoptimizer.buddy.transport.AgentApprovalDecision
import dev.siliconoptimizer.buddy.transport.AgentScreening
import dev.siliconoptimizer.buddy.transport.AgentSessionDetail
import dev.siliconoptimizer.buddy.transport.AgentSessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an approval notification says, and who may press its buttons.
 *
 * Its buttons run commands on somebody's Mac and it is drawn on a locked screen, so the
 * rules are: one per approval and never two; nothing for a session this phone is not
 * following; both buttons behind the owner's fingerprint or PIN where Android can demand
 * it, and opening the app instead where it cannot; and no text that carries a secret or
 * a file.
 */
class AgentNotificationTest {

    private fun approval(id: String, summary: String = "swift test --filter Lisbon", kind: String = "command") =
        AgentApproval(
            id = id, kind = kind, summary = summary,
            screening = AgentScreening(AgentScreening.CONFIRM, "Jev: review"),
            requestedAt = "2026-09-19T10:12:36Z",
        )

    private fun summary(engine: String, mode: String = AgentSessionSummary.APPROVALS_SCREENED) = AgentSessionSummary(
        engine = engine, state = "running", epoch = "E1", model = "local/qwen3-coder-30b",
        modelChoices = emptyList(), cwd = "~/Developer/lisbon", approvalMode = mode,
        sandbox = "workspace-write", turnActive = true, pendingApprovals = 0, itemCount = 0,
        updatedAt = "2026-09-19T10:12:44Z",
    )

    private fun board(vararg waiting: Pair<String, List<AgentApproval>>): AgentBoard =
        waiting.fold(AgentBoard.empty) { board, (engine, approvals) ->
            board.updating(engine) {
                it.applying(
                    AgentSessionDetail(summary(engine), emptyList(), approvals, seq = 3, epoch = "E1", complete = true),
                )
            }
        }

    // MARK: - One per approval

    @Test
    fun `one notification per approval waiting in a watched session`() {
        val board = board(
            "codex" to listOf(approval("C1"), approval("C2", "git push origin main")),
            "pi" to listOf(approval("P1", "bash ls notes/", kind = "tool")),
        )
        val codexOnly = AgentNotifications.notices(board, setOf("codex"), sdk = 36)
        assertEquals(listOf("C1", "C2"), codexOnly.map { it.approvalID })
        assertEquals(2, codexOnly.map { it.notificationID }.toSet().size)

        val both = AgentNotifications.notices(board, setOf("codex", "pi"), sdk = 36)
        assertEquals(3, both.size)
        assertEquals(3, both.map { it.notificationID }.toSet().size)
    }

    @Test
    fun `nothing rings for a session this phone never opened`() {
        val board = board("codex" to listOf(approval("C1")))
        assertTrue(AgentNotifications.notices(board, setOf("pi"), sdk = 36).isEmpty())
        assertTrue(AgentNotifications.notices(board, emptySet(), sdk = 36).isEmpty())
    }

    @Test
    fun `the same approval is never two notifications`() {
        val board = board("codex" to listOf(approval("C1"), approval("C1")))
        assertEquals(1, AgentNotifications.notices(board, setOf("codex"), sdk = 36).size)
    }

    @Test
    fun `an approval keeps its notification number, and two approvals never share one`() {
        assertEquals(
            AgentNotifications.notificationID("codex", "C1"),
            AgentNotifications.notificationID("codex", "C1"),
        )
        assertNotEquals(
            AgentNotifications.notificationID("codex", "C1"),
            AgentNotifications.notificationID("codex", "C2"),
        )
        assertNotEquals(
            "the confirmation must survive the approval's own notification being taken down",
            AgentNotifications.notificationID("codex", "C1"),
            AgentNotifications.settledID("codex", "C1"),
        )
    }

    @Test
    fun `the notification names the engine and the thing being decided`() {
        val notice = AgentNotifications.notice("codex", approval("C1"), sdk = 36)
        assertEquals("Codex wants to run a command", notice.title)
        assertEquals("swift test --filter Lisbon", notice.text)
        assertEquals(
            "Pi wants to use a tool",
            AgentNotifications.notice("pi", approval("P1", kind = "tool"), sdk = 36).title,
        )
        assertEquals(
            "Codex wants to change files",
            AgentNotifications.notice("codex", approval("F1", kind = "fileChange"), sdk = 36).title,
        )
    }

    @Test
    fun `the guardrail's verdict rides along in the expanded text`() {
        val screened = approval("C1").copy(
            reason = "Codex asks before running a command in this folder.",
            screening = AgentScreening(AgentScreening.CONFIRM, "Jev: review: destructive"),
        )
        val detail = AgentNotifications.notice("codex", screened, sdk = 36).detail
        assertTrue(detail.contains("Codex asks before running a command"))
        assertTrue(detail.contains("Jev: review: destructive"))
    }

    @Test
    fun `a verdict reads as what the guardrail did, and not screened is never a pass`() {
        assertEquals("Screened: safe", AgentNotices.verdict(AgentScreening.ACT))
        assertEquals("Screened: asks you", AgentNotices.verdict(AgentScreening.CONFIRM))
        assertEquals("Screened: would block", AgentNotices.verdict(AgentScreening.BLOCK))
        assertEquals("Not screened", AgentNotices.verdict(AgentScreening.UNAVAILABLE))
        assertEquals("no sandbox", AgentNotices.sandbox(AgentSessionSummary.SANDBOX_NONE))
        assertEquals("sandbox: workspace-write", AgentNotices.sandbox("workspace-write"))
    }

    // MARK: - Who may press the buttons

    @Test
    fun `on Android 12 and later both buttons demand the phone be unlocked`() {
        val notice = AgentNotifications.notice("codex", approval("C1"), sdk = 31)
        assertEquals(listOf("Decline", "Accept"), notice.actions.map { it.label })
        assertEquals(
            listOf(AgentApprovalDecision.DECLINE, AgentApprovalDecision.ACCEPT),
            notice.actions.map { it.decision },
        )
        assertTrue(notice.actions.all { it.authenticationRequired && !it.opensApp })
    }

    /**
     * The flag on the real `NotificationCompat.Action`, not only on the plan: this is the
     * object Android reads when somebody presses the button on a locked phone.
     */
    @Test
    fun `the built actions carry the authentication flag`() {
        val actions = AgentNotifier.actions(null, AgentNotifications.notice("codex", approval("C1"), sdk = 36))
        assertEquals(2, actions.size)
        assertTrue(actions.all { it.isAuthenticationRequired })
        assertTrue("an answer from the shade does not open the app", actions.none { it.showsUserInterface })
    }

    @Test
    fun `before Android 12 the buttons open the app instead of answering`() {
        val notice = AgentNotifications.notice("codex", approval("C1"), sdk = 30)
        assertTrue(notice.actions.all { it.opensApp && !it.authenticationRequired })
        val actions = AgentNotifier.actions(null, notice)
        assertTrue(actions.all { it.showsUserInterface })
        assertTrue(actions.none { it.isAuthenticationRequired })
    }

    // MARK: - What the text may say

    @Test
    fun `a bearer token never reaches the lock screen`() {
        val secret = "sk-live-4f1d2c3b4a5968778695a4b3c2d1e0f9"
        val notice = AgentNotifications.notice(
            "codex",
            approval("C1", """curl -H "Authorization: Bearer $secret" https://api.example.com/v1/deploy"""),
            sdk = 36,
        )
        assertFalse(notice.text.contains(secret))
        assertFalse(notice.detail.contains(secret))
        assertTrue(notice.text.contains("Bearer •••"))
    }

    @Test
    fun `key-shaped values and long opaque strings are masked`() {
        assertEquals(
            "export API_KEY=•••",
            AgentNotifications.redact("export API_KEY=abc123def456"),
        )
        assertEquals("password: •••", AgentNotifications.redact("password: hunter2hunter2"))
        val masked = AgentNotifications.redact("git push https://ghp_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789@github.example/you/lisbon")
        assertFalse(masked.contains("ghp_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"))
        // A path is not a secret, and stays readable.
        assertEquals("Sources/Lisbon/Itinerary.swift", AgentNotifications.redact("Sources/Lisbon/Itinerary.swift"))
    }

    /** A tool call can carry a whole file as an argument. The notification never does. */
    @Test
    fun `a file's contents never ride along whole`() {
        val file = (1..60).joinToString("\n") { "line $it of a file the agent wants to write" }
        val notice = AgentNotifications.notice(
            "pi", approval("P1", "write {\"path\":\"notes/lisbon.md\"}\n$file", kind = "tool"), sdk = 36,
        )
        assertEquals("one line in the shade", 1, notice.text.lines().size)
        assertTrue(notice.text.length <= AgentNotifications.LINE_LIMIT + 2)
        assertTrue(notice.detail.lines().size <= AgentNotifications.DETAIL_LINES + 1)
        assertTrue(notice.detail.length <= AgentNotifications.DETAIL_LIMIT + 120)
        assertFalse(notice.detail.contains("line 60"))
    }

    @Test
    fun `a long single line is cut on a word`() {
        val long = "rm -rf " + (1..60).joinToString(" ") { "build/output-$it" }
        val line = AgentNotifications.line(long, 80)
        assertTrue(line.endsWith("…"))
        assertTrue(line.length <= 81)
    }

    @Test
    fun `a locked screen is told that something waits, and not what`() {
        val public = AgentNotifications.PUBLIC_TITLE + " " + AgentNotifications.PUBLIC_TEXT
        assertFalse(public.contains("Codex"))
        assertFalse(public.contains("Pi "))
        assertFalse(public.contains("swift"))
    }

    // MARK: - The watcher's own line

    @Test
    fun `the watching notification counts what is waiting`() {
        assertEquals("Watching Codex and Pi on your Mac", AgentNotifications.watchingTitle(listOf("pi", "codex")))
        assertEquals("1 approval is waiting for you.", AgentNotifications.watchingText(1))
        assertEquals("3 approvals are waiting for you.", AgentNotifications.watchingText(3))
    }

    // MARK: - How a card came down, in words

    @Test
    fun `a resolution says who answered and which way`() {
        val card = approval("C1")
        assertEquals("Accepted on the Mac", AgentNotices.resolution("codex", Resolution(card, "accepted", false, null)))
        assertEquals("Declined on this phone", AgentNotices.resolution("codex", Resolution(card, "declined", true, null)))
        assertTrue(
            AgentNotices.resolution("codex", Resolution(card, null, false, "That was answered at the Mac before this arrived."))
                .contains("answered at the Mac"),
        )
    }

    @Test
    fun `only unattended is a warning`() {
        fun mode(value: String) = summary("pi", value)
        assertTrue(mode(AgentSessionSummary.APPROVALS_UNATTENDED).runsWithoutAsking)
        assertFalse(mode(AgentSessionSummary.APPROVALS_SCREENED).runsWithoutAsking)
        assertFalse(mode(AgentSessionSummary.APPROVALS_ASKED).runsWithoutAsking)
        assertFalse("a word this build does not know is not a warning", mode("sometimes").runsWithoutAsking)
        assertTrue(AgentNotices.approvalMode(mode(AgentSessionSummary.APPROVALS_UNATTENDED))!!.startsWith("Runs without asking"))
        assertEquals(null, AgentNotices.approvalMode(mode("sometimes")))
    }

    // MARK: - The tile

    @Test
    fun `the tile says how many approvals are waiting before anything else`() {
        assertEquals("2 approvals waiting", BuddyTileService.subtitle(true, "Qwen3 4B", 2))
        assertEquals("1 approval waiting", BuddyTileService.subtitle(true, "Qwen3 4B", 1))
        assertEquals("Qwen3 4B", BuddyTileService.subtitle(true, "Qwen3 4B", 0))
        assertEquals("Not paired", BuddyTileService.subtitle(false, "Qwen3 4B", 2))
    }

    @Test
    fun `a count of waiting approvals goes stale rather than lingering`() {
        val at = 1_000_000L
        assertEquals(2, SnapshotStore.pendingApprovals(2, at, at + 60_000))
        assertEquals(0, SnapshotStore.pendingApprovals(2, at, at + SnapshotStore.APPROVALS_FRESH_MS + 1))
        assertEquals(0, SnapshotStore.pendingApprovals(0, at, at))
        assertEquals("a clock that went backwards is not fresh", 0, SnapshotStore.pendingApprovals(2, at, at - 1))
    }
}
