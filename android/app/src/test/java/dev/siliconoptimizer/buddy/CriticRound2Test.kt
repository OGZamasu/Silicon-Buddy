package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.agents.AgentNotices
import dev.siliconoptimizer.buddy.agents.AgentNotifications
import dev.siliconoptimizer.buddy.agents.AgentSession
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
 * The second review's probes, kept as regression tests.
 *
 * R1 failed on the first review round's cut: a card the owner declined at the Mac while
 * this phone's Accept was out came down reading "Declined on this phone". R2 was the
 * reviewer's report of what the masking let through; it asserts now.
 */
class CriticRound2Test {
    private val epoch = "E1"

    private fun summary(turnActive: Boolean = true, itemCount: Int = 1, pending: Int = 1) = AgentSessionSummary(
        engine = "codex", state = "running", epoch = epoch, model = "m",
        modelChoices = listOf(AgentModelChoice("m", "M", "This Mac")),
        cwd = "~/x", approvalMode = "screened", sandbox = "workspace-write",
        turnActive = turnActive, pendingApprovals = pending, itemCount = itemCount,
        updatedAt = "2026-09-19T10:12:44Z",
    )

    private val card = AgentApproval(
        id = "A1", kind = "command", summary = "rm -rf .build",
        screening = AgentScreening("confirm", "Jev: review"), requestedAt = "2026-09-19T10:12:36Z",
    )

    private fun loaded(): AgentSession = AgentSession("codex").applying(
        AgentSessionDetail(
            session = summary(), items = listOf(AgentItem(id = "a", kind = "assistant", text = "a", at = "2026-09-19T10:12:31Z")),
            approvals = listOf(card), seq = 5, epoch = epoch, complete = true,
        ),
    ).applying(AgentEvent(engine = "codex", kind = "turn", seq = 5, epoch = epoch, turnActive = true)).syncing()

    /**
     * The owner declines at the Mac a moment before this phone's Accept arrives: the Mac's
     * frame (declined) lands while the POST is out, then the POST comes back 409. This phone
     * pressed Accept; nothing it sent was applied.
     */
    @Test
    fun `R1 a card answered at the Mac first is not credited to this phone`() {
        var s = loaded()
        // AgentsViewModel.answer: AgentAnswers.note(...) before sending; apply() remembers it.
        s = s.remembering(mapOf("A1" to "accepted"))
            .applying(AgentEvent(engine = "codex", kind = "approval", seq = 6, epoch = epoch, approval = card, state = "declined"))
        // The 409 comes back: AgentsViewModel forgets the note and records the conflict.
        s = s.unanswering("A1").approvalConflict("A1", "That was answered at the Mac before this arrived.")
        val said = AgentNotices.resolution("codex", s.resolutions.single())
        println("resolution: $said")
        assertEquals("Declined on the Mac", said)
    }

    /** Masking of the in-app resolution line (notifications no longer carry it). */
    @Test
    fun `R2 masking extras`() {
        val cases = listOf(
            "SECRET_KEY=hunter2horse ./manage.py" to "hunter2horse",
            "STRIPE_KEY=sk_test_abc123 node app" to "sk_test_abc123",
            "export ENCRYPTION_KEY=hunter2horse" to "hunter2horse",
            "curl 'https://api.example.com/v1?key=hunter2horse'" to "hunter2horse",
        )
        val report = cases.map { (text, secret) ->
            val masked = AgentNotifications.line(text, 140)
            (if (masked.contains(secret)) "LEAK " else "ok   ") + text + "  ->  " + masked
        }
        println(report.joinToString("\n"))
        val leaks = report.filter { it.startsWith("LEAK") }
        assertTrue("${leaks.size} of ${cases.size} leak:\n" + leaks.joinToString("\n"), leaks.isEmpty())
        // And an ordinary word ending in "key" is not a secret.
        assertEquals("monkey=banana", AgentNotifications.line("monkey=banana", 140))
    }
}
