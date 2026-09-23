package dev.siliconoptimizer.buddy

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A link is read once. An activity recreated for a new font size or display density, or
 * reopened from Recents, is handed the intent it was first opened with again — and a
 * pairing link read the second time asked to pair with a code already spent.
 */
class LinkArrivalTest {

    @Test
    fun `a link is read when it arrives`() {
        assertTrue(MainActivity.arrivesFresh(restored = false, flags = 0))
        assertTrue(
            MainActivity.arrivesFresh(
                restored = false,
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
        )
    }

    @Test
    fun `a recreated activity does not read its intent again`() {
        assertFalse(MainActivity.arrivesFresh(restored = true, flags = 0))
        assertFalse(MainActivity.arrivesFresh(restored = true, flags = Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Test
    fun `an activity reopened from Recents does not read it either`() {
        assertFalse(
            MainActivity.arrivesFresh(
                restored = false,
                flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY or Intent.FLAG_ACTIVITY_NEW_TASK,
            ),
        )
    }
}
