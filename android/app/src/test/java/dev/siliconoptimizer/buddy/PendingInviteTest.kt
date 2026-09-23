package dev.siliconoptimizer.buddy

import androidx.lifecycle.SavedStateHandle
import dev.siliconoptimizer.buddy.pairing.PairingInvite
import dev.siliconoptimizer.buddy.pairing.PendingInvite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Pair with this Mac?" survives the process dying under it, and only while it is unanswered.
 * The link that brought it is read once — a recreated activity never reads its intent again,
 * or a spent code would be offered back (LinkArrivalTest) — so an invite that lived only in
 * the view model was simply gone when the person came back.
 */
class PendingInviteTest {

    private val invite = PairingInvite("100.64.0.9", 8788, "418203")

    /** What Android does when the process dies: the saved values, restored into a new handle. */
    private fun afterProcessDeath(saved: SavedStateHandle): SavedStateHandle =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    @Test
    fun `an invite nobody answered is asked about again after the process dies`() {
        val saved = SavedStateHandle()
        PendingInvite(saved).offer(invite)

        val restored = afterProcessDeath(saved)
        assertEquals(invite, PendingInvite(restored).invite)
        // And again if the process dies a second time before anyone answers: it is still
        // the one unanswered question.
        assertEquals(invite, PendingInvite(afterProcessDeath(restored)).invite)
    }

    @Test
    fun `answered no, it is not asked about again`() {
        val saved = SavedStateHandle()
        PendingInvite(saved).offer(invite)
        val restored = afterProcessDeath(saved)
        val back = PendingInvite(restored)
        assertEquals(invite, back.invite)

        back.offer(null)
        assertNull(back.invite)
        assertNull(PendingInvite(afterProcessDeath(restored)).invite)
    }

    @Test
    fun `being spent, it stays on screen but is never offered again`() {
        val saved = SavedStateHandle()
        val waiting = PendingInvite(saved)
        waiting.offer(invite)

        waiting.spending()
        assertEquals("the dialog still shows it while the Mac answers", invite, waiting.invite)
        assertNull(
            "a code that may be spent is not offered after a restart",
            PendingInvite(afterProcessDeath(saved)).invite,
        )
    }

    @Test
    fun `paired, nothing is left to ask about`() {
        val saved = SavedStateHandle()
        val waiting = PendingInvite(saved)
        waiting.offer(invite)
        waiting.spending()
        waiting.offer(null)
        assertNull(waiting.invite)
        assertTrue(saved.keys().isEmpty())
    }

    @Test
    fun `a newer link replaces the one waiting`() {
        val saved = SavedStateHandle()
        val waiting = PendingInvite(saved)
        waiting.offer(invite)
        val newer = PairingInvite("100.64.0.10", 8788, "135790")
        waiting.offer(newer)
        assertEquals(newer, PendingInvite(afterProcessDeath(saved)).invite)
    }

    @Test
    fun `what comes back is held to the rules a scanned code is`() {
        // Saved state is the app's own, but what is read back is parsed again, strictly: a
        // host off the tailnet is not asked about.
        val saved = SavedStateHandle(
            mapOf(PendingInvite.KEY to "siliconbuddy://pair?host=evil.example.com&port=8788&code=418203"),
        )
        assertNull(PendingInvite(saved).invite)
        assertNull(PendingInvite(SavedStateHandle(mapOf(PendingInvite.KEY to "not a link"))).invite)
    }

    @Test
    fun `a fresh start asks about nothing`() {
        assertNull(PendingInvite(SavedStateHandle()).invite)
    }
}
