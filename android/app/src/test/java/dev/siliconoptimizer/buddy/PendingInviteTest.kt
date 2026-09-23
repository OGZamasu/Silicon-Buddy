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

        waiting.spending(invite)
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
        waiting.spending(invite)
        waiting.offer(null)
        assertNull(waiting.invite)
        assertTrue(saved.keys().isEmpty())
    }

    /**
     * The critic's case: link W, Pair, and W delivered again — a second tap, or a messenger
     * redelivering it — while its code is with the Mac. It was saved again, so when the Mac
     * refused the code and the process later died, W came back with its refused code and
     * without the refusal.
     */
    @Test
    fun `the same link coming back while its code is spent, or after a refusal, is not saved again`() {
        val saved = SavedStateHandle()
        val waiting = PendingInvite(saved)
        waiting.offer(invite)
        waiting.spending(invite)

        waiting.offer(invite)
        assertEquals("somebody opened it, so it is asked about", invite, waiting.invite)
        assertNull(PendingInvite(afterProcessDeath(saved)).invite)
        // And after the Mac has refused it, the same again.
        waiting.offer(null)
        waiting.offer(invite)
        assertNull(PendingInvite(afterProcessDeath(saved)).invite)
    }

    @Test
    fun `a typed code being spent is not saved when a link for it arrives`() {
        val saved = SavedStateHandle()
        val waiting = PendingInvite(saved)
        waiting.spending(invite)
        waiting.offer(invite)
        assertNull(PendingInvite(afterProcessDeath(saved)).invite)
    }

    @Test
    fun `declined, its link is asked about again but not saved`() {
        val saved = SavedStateHandle()
        val waiting = PendingInvite(saved)
        waiting.offer(invite)
        waiting.decline()
        assertNull(waiting.invite)
        assertTrue(saved.keys().isEmpty())

        waiting.offer(invite)
        assertEquals(invite, waiting.invite)
        assertNull(PendingInvite(afterProcessDeath(saved)).invite)
    }

    @Test
    fun `another link arriving while one code is spent is still saved`() {
        val saved = SavedStateHandle()
        val waiting = PendingInvite(saved)
        waiting.offer(invite)
        waiting.spending(invite)
        val other = PairingInvite("100.64.0.10", 8788, "135790")
        waiting.offer(other)
        assertEquals("nobody has answered it", other, PendingInvite(afterProcessDeath(saved)).invite)
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
