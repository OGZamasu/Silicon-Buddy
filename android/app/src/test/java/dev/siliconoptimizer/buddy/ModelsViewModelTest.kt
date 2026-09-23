package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.modelsui.ModelsViewModel
import dev.siliconoptimizer.buddy.transport.CatalogModel
import dev.siliconoptimizer.buddy.transport.InstalledModel
import dev.siliconoptimizer.buddy.transport.LoadRequest
import dev.siliconoptimizer.buddy.transport.Status
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val catalogModel = CatalogModel(
        id = "test-model", name = "Test model", author = "Test", license = "MIT",
        summary = "A model for the polling tests", category = "chat", parameters = "2B",
        isMoE = false, capabilities = listOf("chat"), rating = 1, maxContext = 4096,
        quantizations = listOf("Q4_K_M"),
    )

    private class DownloadMac : HangingTransport() {
        var size: Long? = 100
        var installedReads = 0
        var statusReads = 0
        var catalogReads = 0

        override suspend fun install(request: LoadRequest) = "Downloading…"
        override suspend fun installed(): List<InstalledModel> {
            installedReads++
            return size?.let {
                listOf(InstalledModel("test-model@Q4_K_M", "Test model", "Q4_K_M", it, false, false))
            } ?: emptyList()
        }

        override suspend fun status(): Status {
            statusReads++
            return Status("Idle")
        }

        override suspend fun catalog(category: String?, onlyRunnable: Boolean): List<CatalogModel> {
            catalogReads++
            return emptyList()
        }
    }

    @Test
    fun `a settled install clears progress refreshes once and stops polling`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val mac = DownloadMac()
        try {
            model.install(catalogModel, transport = mac)
            runCurrent()
            advanceTimeBy(6_000)
            runCurrent()

            assertNull("two unchanged readings should finish the install job", model.job)
            assertEquals(1, mac.catalogReads)
            assertEquals(4, mac.installedReads) // Three polls and the final refresh.
            assertEquals(4, mac.statusReads)
            assertEquals("test-model@Q4_K_M", model.installed.single().id)

            advanceTimeBy(60_000)
            runCurrent()
            assertEquals("completed downloads must not keep asking the Mac", 4, mac.installedReads)
            assertEquals(4, mac.statusReads)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `growing again requires two more unchanged readings`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val mac = DownloadMac()
        try {
            model.install(catalogModel, transport = mac)
            runCurrent()
            advanceTimeBy(4_000)
            runCurrent()
            assertNotNull(model.job)

            mac.size = 200
            advanceTimeBy(4_000)
            runCurrent()
            assertNotNull("one unchanged reading after growth is not enough", model.job)
            advanceTimeBy(2_000)
            runCurrent()
            assertNull(model.job)
            assertEquals(200L, model.installed.single().sizeOnDiskBytes)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `an absent model does not count as a settled download`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val mac = DownloadMac().apply { size = null }
        try {
            model.install(catalogModel, transport = mac)
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertNotNull(model.job)
            assertEquals(0, mac.catalogReads)
        } finally {
            model.reset()
        }
    }

    /** A Mac that takes the install and then never answers a poll, or a refresh. */
    private class Unanswering : HangingTransport() {
        override suspend fun install(request: LoadRequest) = "Downloading…"
        override suspend fun status(): Status = Status("Idle")
    }

    @Test
    fun `a re-pair while a poll is in flight leaves no install behind`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        model.install(catalogModel, transport = Unanswering())
        runCurrent()
        advanceTimeBy(2_001) // The first poll is out, and hangs in /installed.
        runCurrent()
        assertNotNull(model.job)

        model.reset()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertNull("no phantom install on the next Mac's screen, and Unload is not held off", model.job)
        assertEquals(emptyList<InstalledModel>(), model.installed)
    }

    @Test
    fun `a re-pair while a refresh is in flight leaves the next Mac's lists alone`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val old = object : HangingTransport() {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            override suspend fun installed(): List<InstalledModel> {
                gate.await()
                return listOf(InstalledModel("old@Q4_K_M", "Old", "Q4_K_M", 1, false, false))
            }
            override suspend fun catalog(category: String?, onlyRunnable: Boolean) = emptyList<CatalogModel>()
            override suspend fun status() = Status("Ready", loadedModelID = "old@Q4_K_M")
        }
        model.refresh(old)
        runCurrent()
        assertTrue(model.isLoading)

        model.reset()
        old.gate.complete(Unit)
        runCurrent()
        assertTrue("the old Mac's disk is not the new one's", model.installed.isEmpty())
        assertNull(model.status)
        assertFalse(model.isLoading)
    }

    @Test
    fun `reset stops following the previous Mac`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val mac = DownloadMac()
        model.install(catalogModel, transport = mac)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        model.reset()
        advanceTimeBy(60_000)
        runCurrent()
        assertNull(model.job)
        assertEquals(emptyList<InstalledModel>(), model.installed)
        assertEquals(1, mac.installedReads)
        assertEquals(0, mac.catalogReads)
    }
}
