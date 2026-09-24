package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.transport.Health
import dev.siliconoptimizer.buddy.transport.PairedMacCheck
import dev.siliconoptimizer.buddy.transport.Reachability
import dev.siliconoptimizer.buddy.transport.Status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reachability check of the paired Mac takes seconds, and the phone can be re-paired or
 * told to forget its Mac while one is out. Its answer is about the Mac it asked, and used to
 * be written anyway: the old Mac's model went into the snapshot the widget and the tile
 * read, and "Connected" onto a phone that had just forgotten its Mac.
 */
class ReachabilityRefreshTest {

    /** A Mac that answers when the test says so. */
    private class Slow : HangingTransport() {
        val answer = CompletableDeferred<Unit>()
        override suspend fun health(): Health {
            answer.await()
            return Health(status = "ok", appVersion = "0.5.0")
        }
        override suspend fun status(): Status = Status(state = "running", loadedModelName = "Old Mac's model")
    }

    @Test
    fun `an answer from a Mac that is no longer paired is dropped`() = runTest {
        var generation = 1
        val asked = generation
        val reachabilities = mutableListOf<Reachability>()
        val statuses = mutableListOf<Status?>()
        val mac = Slow()
        val check = launch {
            PairedMacCheck.run(
                mac,
                stillPaired = { generation == asked },
                reachability = { reachabilities += it },
                status = { statuses += it },
            )
        }
        testScheduler.advanceUntilIdle()

        generation = 2 // Forget, or another Mac paired, while the check is out.
        mac.answer.complete(Unit)
        check.join()

        assertTrue("reachability written for the wrong Mac: $reachabilities", reachabilities.isEmpty())
        assertTrue("status written for the wrong Mac: $statuses", statuses.isEmpty())
    }

    @Test
    fun `an answer from the Mac still paired is written`() = runTest {
        val reachabilities = mutableListOf<Reachability>()
        val statuses = mutableListOf<Status?>()
        val mac = Slow()
        mac.answer.complete(Unit)

        PairedMacCheck.run(
            mac,
            stillPaired = { true },
            reachability = { reachabilities += it },
            status = { statuses += it },
        )

        assertEquals(listOf<Reachability>(Reachability.Ready("0.5.0", "Old Mac's model")), reachabilities)
        assertEquals("Old Mac's model", statuses.single()?.loadedModelName)
    }
}
