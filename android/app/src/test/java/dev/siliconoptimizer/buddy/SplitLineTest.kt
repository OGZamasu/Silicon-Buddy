package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.ui.sideBySide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A label and its reading share a line only when both fit on it whole. At 200% font the
 * dashboard's weighted rows gave the reading all it asked for and the label the rest, and
 * showed "Memor / y" beside "38.65 GB of 137.44 GB", and "render / -node" beside its URL.
 *
 * Pixels of a 411 dp phone at 420 dpi (2.625 px a dp): a dashboard card leaves 331 dp, 869 px,
 * for the line, and the 12 dp gap is 32 px. Text widths are about what Roboto draws.
 */
class SplitLineTest {

    private val line = 869
    private val gap = 32

    @Test
    fun `at the usual size Memory and its reading share the line`() {
        // "Memory" is about 55 dp at 14 sp, "38.65 GB of 137.44 GB" about 150 dp.
        val memory = 144
        val reading = 394
        assertEquals(line - gap - reading, sideBySide(line, gap, memory, reading))
    }

    @Test
    fun `at 200 percent the reading goes under Memory rather than squeezing it`() {
        // Twice as wide: 288 + 32 + 788 is more than the line, so they stack. The Row gave
        // "Memory" the 49 px that were left, which is two or three letters a line.
        assertNull(sideBySide(line, gap, 288, 788))
    }

    @Test
    fun `a peer's name is never narrowed to fit its URL beside it`() {
        // A status dot, "render-node" and "http://100.64.0.7:8765", at 200% on a 384 dp phone
        // (304 dp for the line, 798 px).
        assertNull(sideBySide(798, gap, 420, 520))
        // At the usual size they share it.
        assertEquals(798 - gap - 260, sideBySide(798, gap, 210, 260))
    }

    @Test
    fun `exactly enough room is enough`() {
        assertEquals(300, sideBySide(600, 20, 300, 280))
        assertNull(sideBySide(600, 20, 301, 280))
    }

    @Test
    fun `whenever they share the line the start gets all of itself and the end fits beside it`() {
        for (width in 100..1200 step 37) {
            for (start in 0..1200 step 41) {
                for (end in 0..1200 step 43) {
                    val given = sideBySide(width, gap, start, end) ?: continue
                    assertTrue("start $start given $given of $width", given >= start)
                    assertTrue("end $end does not fit beside $given in $width", given + gap + end <= width)
                }
            }
        }
        // And they do share it whenever that is possible.
        assertNotNull(sideBySide(1000, gap, 400, 400))
    }
}
