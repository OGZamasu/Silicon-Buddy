package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.agents.AgentNotices
import dev.siliconoptimizer.buddy.agents.AgentNotifications
import dev.siliconoptimizer.buddy.agents.AgentSession
import dev.siliconoptimizer.buddy.agents.Sync
import dev.siliconoptimizer.buddy.transport.AgentApproval
import dev.siliconoptimizer.buddy.transport.AgentEvent
import dev.siliconoptimizer.buddy.transport.AgentItem
import dev.siliconoptimizer.buddy.transport.AgentModelChoice
import dev.siliconoptimizer.buddy.transport.AgentScreening
import dev.siliconoptimizer.buddy.transport.AgentSessionDetail
import dev.siliconoptimizer.buddy.transport.AgentSessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The review's probes, kept as regression tests.
 *
 * Each one failed on the first cut of M4: a catch-up slice landing after rows that
 * streamed in while it was out (P1), a Mac relaunch looping reads (P2), this phone's own
 * answer credited to the Mac when the frame beat the HTTP reply (P3), and credential
 * shapes the masking missed (P4, P5).
 */
class CriticProbeTest {
    private val epoch = "E1"
    private val nextEpoch = "E2"

    private fun summary(turnActive: Boolean = false, itemCount: Int = 0, epoch: String = this.epoch, pending: Int = 0) =
        AgentSessionSummary(
            engine = "codex", state = "running", epoch = epoch, model = "m",
            modelChoices = listOf(AgentModelChoice("m", "M", "This Mac")),
            cwd = "~/x", approvalMode = "screened", sandbox = "workspace-write",
            turnActive = turnActive, pendingApprovals = pending, itemCount = itemCount,
            updatedAt = "2026-09-19T10:12:44Z",
        )

    private fun item(id: String) = AgentItem(id = id, kind = "assistant", text = id, at = "2026-09-19T10:12:31Z")

    private fun approval(id: String) = AgentApproval(
        id = id, kind = "command", summary = "swift test",
        screening = AgentScreening("confirm", "Jev: review"), requestedAt = "2026-09-19T10:12:36Z",
    )

    private fun detail(
        items: List<AgentItem>, seq: Long, complete: Boolean, itemCount: Int,
        epoch: String = this.epoch, approvals: List<AgentApproval> = emptyList(), turnActive: Boolean = false,
    ) = AgentSessionDetail(
        session = summary(turnActive, itemCount, epoch, approvals.size), items = items,
        approvals = approvals, seq = seq, epoch = epoch, complete = complete,
    )

    private fun itemFrame(item: AgentItem, seq: Long) =
        AgentEvent(engine = "codex", kind = "item", seq = seq, epoch = epoch, item = item)

    private fun turnFrame(active: Boolean, seq: Long) =
        AgentEvent(engine = "codex", kind = "turn", seq = seq, epoch = epoch, turnActive = active)

    private fun approvalFrame(a: AgentApproval, seq: Long, state: String) =
        AgentEvent(engine = "codex", kind = "approval", seq = seq, epoch = epoch, approval = a, state = state)

    /** Back from the background: the catch-up slice arrives after a newer row streamed in. */
    @Test
    fun `P1 a catch-up slice keeps transcript order`() {
        var s = AgentSession("codex").applying(detail(listOf(item("a"), item("b")), seq = 2, complete = true, itemCount = 2))
        s = s.applying(turnFrame(false, 2)).syncing()
        s = s.streamBroken() // EventFeed.start() on return emits Broken
        s = s.applying(turnFrame(true, 20)) // opening burst at the current seq
        assertEquals(Sync.CatchUp, s.needs)
        assertEquals(2L, s.cursor!!.since)
        s = s.syncing() // AgentsViewModel.schedule(): GET since=2 goes out
        s = s.applying(itemFrame(item("d"), 21)) // AgentsViewModel.apply(): frame lands while GET is out
        s = s.applying(detail(listOf(item("c"), item("d")), seq = 21, complete = false, itemCount = 4))
        assertEquals(listOf("a", "b", "c", "d"), s.transcript.map { it.id })
    }

    /** Mac relaunched (epoch changes, clock restarts) before the old stream noticed. */
    @Test
    fun `P2 an epoch change leaves no catch-up that never settles`() {
        var s = AgentSession("codex").applying(detail(listOf(item("a")), seq = 400, complete = true, itemCount = 1))
        s = s.applying(turnFrame(false, 400)).syncing() // stream open at 400
        s = s.needing(Sync.CatchUp).syncing() // onResume -> catchUpAll()
        // A foreign cursor is answered whole, under the new epoch, at seq 3.
        s = s.applying(detail(listOf(item("x")), seq = 3, complete = true, itemCount = 1, epoch = nextEpoch))
        var reads = 1
        while (s.needs != Sync.None && reads < 50) {
            val cursor = s.cursor
            s = s.syncing()
            s = if (cursor == null) {
                s.applying(detail(listOf(item("x")), seq = 3, complete = true, itemCount = 1, epoch = nextEpoch))
            } else {
                s.applying(detail(emptyList(), seq = 3, complete = false, itemCount = 1, epoch = nextEpoch))
            }
            reads++
        }
        assertEquals("GETs before the session settled (no delay between them in schedule())", 1, reads)
    }

    /** The phone's own accept: the Mac's frame beats the HTTP answer. */
    @Test
    fun `P3 this phone's answer is credited to this phone when the frame wins`() {
        var s = AgentSession("codex").applying(
            detail(listOf(item("a")), seq = 5, complete = true, itemCount = 1, approvals = listOf(approval("p1")), turnActive = true),
        )
        s = s.applying(turnFrame(true, 5)).syncing()
        s = s.applying(approvalFrame(approval("p1"), 6, "accepted"))
        s = s.answered("p1", "accepted", summary(turnActive = true, itemCount = 1))
        assertEquals("Accepted on this phone", AgentNotices.resolution("codex", s.resolutions.first()))
    }

    @Test
    fun `P4 credential-shaped strings are masked`() {
        val cases = listOf(
            "DB_PASSWORD=hunter2horse ./migrate" to "hunter2horse",
            "PGPASSWORD=hunter2horse psql -h db" to "hunter2horse",
            "OPENAI_API_KEY=abc123shortkey python run.py" to "abc123shortkey",
            "GITHUB_TOKEN=abc123shortkey gh pr list" to "abc123shortkey",
            "export AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY" to "bPxRfiCYEXAMPLEKEY",
            "git clone https://oauth2:glpat-AbCdEfGhIjKlMnOpQrSt@gitlab.example.com/x.git" to "glpat-AbCdEfGhIjKlMnOpQrSt",
            "psql postgres://admin:S3cr3tPass@db.internal/app" to "S3cr3tPass",
            "curl -u admin:S3cr3tPass https://example.com" to "S3cr3tPass",
            "mysql -uroot -pS3cr3tPass app" to "S3cr3tPass",
            "sshpass -p S3cr3tPass ssh deploy@host" to "S3cr3tPass",
            "tool --password S3cr3tPass" to "S3cr3tPass",
            "curl -H 'Authorization: Basic YWRtaW46cGFzc3dvcmQ='" to "YWRtaW46cGFzc3dvcmQ",
            "curl -H 'X-Api-Key: 8f14e45fceea167a5a36' https://api.example.com" to "8f14e45fceea167a5a36",
            "token: abc123shortkey" to "abc123shortkey",
            "Bearer abc.def.ghi" to "abc.def.ghi",
        )
        val report = cases.map { (text, secret) ->
            val masked = AgentNotifications.line(text, 140)
            (if (masked.contains(secret)) "LEAK " else "ok   ") + text + "  ->  " + masked
        }
        println(report.joinToString("\n"))
        val leaks = report.filter { it.startsWith("LEAK") }
        assertTrue("${leaks.size} of ${cases.size} leak:\n" + leaks.joinToString("\n"), leaks.isEmpty())
    }

    /**
     * The excerpt this probe caught is gone: a notification now carries none of the command.
     * What remains outside the transcript is the in-app line saying how a card came down,
     * which masks the block wherever in the text it starts.
     */
    @Test
    fun `P5 private key lines in an excerpt`() {
        val heredoc = "cat > ~/.ssh/id_ed25519 <<'EOF'\n-----BEGIN OPENSSH PRIVATE KEY-----\n" +
            "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW\n" +
            "QyNTUxOQAAACBx+q9m/Zb3k2Lr8XoTf1pQwErTyUiOpAsDfGhJkLzXcV==\nEOF"
        val approval = AgentApproval(
            id = "A5", kind = "command", summary = heredoc,
            screening = AgentScreening("confirm", "Jev: writes a key"), requestedAt = "2026-09-19T10:12:36Z",
        )
        val notice = AgentNotifications.notice("codex", approval, sdk = 34)
        val shown = listOf(
            notice.title, notice.text, notice.detail,
            AgentNotifications.line(heredoc, AgentNotifications.LINE_LIMIT),
            AgentNotifications.line(heredoc.substringAfter('\n'), AgentNotifications.LINE_LIMIT),
        )
        println(shown.joinToString("\n"))
        for (text in shown) {
            assertTrue(text, !text.contains("BEGIN OPENSSH PRIVATE KEY"))
            assertTrue(text, !text.contains("b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQ"))
        }
    }
}
