package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.dashboard.DashboardViewModel
import dev.siliconoptimizer.buddy.transport.Metrics
import dev.siliconoptimizer.buddy.transport.NodeAdvertisement
import dev.siliconoptimizer.buddy.transport.Profile
import dev.siliconoptimizer.buddy.transport.Status
import dev.siliconoptimizer.buddy.transport.SwarmView
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The dashboard's polling, when the Mac stops knowing this phone. A 401 is the same answer
 * however often it is asked, so after it nothing on this screen asks again until the phone
 * is paired anew.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A Mac that has forgotten this phone: every route answers 401, and each ask is counted. */
    private class Forgotten : HangingTransport() {
        var asked = 0
        private fun refuse(): Nothing {
            asked++
            throw TransportError.Unauthorized
        }
        override suspend fun metrics(): Metrics = refuse()
        override suspend fun status(): Status = refuse()
        override suspend fun profile(): Profile = refuse()
        override suspend fun swarm(): SwarmView = refuse()
        override suspend fun node(): NodeAdvertisement = refuse()
    }

    @Test
    fun `a refused token ends the live readings for good`() = runTest(dispatcher) {
        val model = DashboardViewModel()
        val mac = Forgotten()
        model.pollsStatus = true
        model.startLiveUpdates(mac, seconds = 4)
        advanceTimeBy(4_001)
        runCurrent()
        assertEquals("the first tick asks once, and is refused", 1, mac.asked)
        assertTrue(model.unpaired)
        assertEquals(DashboardViewModel.UNPAIRED, model.error)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("neither /metrics nor /status is asked again", 1, mac.asked)

        // Leaving the screen and coming back does not start it again, nor does asking.
        model.pauseLiveUpdates()
        model.resumeLiveUpdates()
        model.startLiveUpdates(mac, seconds = 4)
        model.refresh(mac)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, mac.asked)
    }

    @Test
    fun `a refused refresh says so and polls nothing after it`() = runTest(dispatcher) {
        val model = DashboardViewModel()
        val mac = Forgotten()
        model.refresh(mac)
        runCurrent()
        assertTrue(model.unpaired)
        assertFalse(model.isLoading)
        val afterRefresh = mac.asked
        model.startLiveUpdates(mac, seconds = 4)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(afterRefresh, mac.asked)
    }

    /** Paired again: a new Mac, as far as this screen knows, and it reads again. */
    @Test
    fun `pairing again starts the readings afresh`() = runTest(dispatcher) {
        val model = DashboardViewModel()
        val mac = Forgotten()
        model.markUnpaired()
        model.reset()
        assertFalse(model.unpaired)
        model.startLiveUpdates(mac, seconds = 4)
        advanceTimeBy(4_001)
        runCurrent()
        assertEquals(1, mac.asked)
    }
}
