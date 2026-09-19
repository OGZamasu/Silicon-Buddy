package dev.siliconoptimizer.buddy

import android.view.MotionEvent
import dev.siliconoptimizer.buddy.agents.ObscuredTouches
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the approval card's buttons make of a touch. Android's flags, as constants, so this
 * runs on the JVM; the wiring from a real touch to the button is the instrumented test's.
 */
class ObscuredTouchesTest {

    private val down = MotionEvent.ACTION_DOWN
    private val up = MotionEvent.ACTION_UP

    @Test
    fun `a press through another window at the point pressed is refused`() {
        val touches = ObscuredTouches()
        touches.touched(down, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        touches.touched(up, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertTrue(touches.take())
    }

    /** A video call in a corner or a chat head overlaps the window, not the button. */
    @Test
    fun `a window overlapping some other part of the screen is not a reason to refuse`() {
        val touches = ObscuredTouches()
        touches.touched(down, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
        assertFalse(touches.take())
    }

    @Test
    fun `a clear press goes through`() {
        val touches = ObscuredTouches()
        touches.touched(down, 0)
        assertFalse(touches.take())
    }

    /**
     * Switch access, a keyboard and TalkBack answer without touching anything. One refused
     * press must not follow them: it is forgotten as it is refused.
     */
    @Test
    fun `a refused press does not refuse the next answer, which may not be a touch at all`() {
        val touches = ObscuredTouches()
        touches.touched(down, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertTrue(touches.take())
        assertFalse("an accessibility action after it", touches.take())
    }

    /** The first contact decides: a finger that slides out from under an overlay is still its press. */
    @Test
    fun `only the first contact of a press counts`() {
        val touches = ObscuredTouches()
        touches.touched(down, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        touches.touched(MotionEvent.ACTION_MOVE, 0)
        touches.touched(up, 0)
        assertTrue(touches.take())
    }
}
