package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.modelsui.ModelsViewModel
import dev.siliconoptimizer.buddy.transport.CatalogModel
import dev.siliconoptimizer.buddy.transport.InstalledModel
import dev.siliconoptimizer.buddy.transport.LoadFailure
import dev.siliconoptimizer.buddy.transport.LoadRequest
import dev.siliconoptimizer.buddy.transport.Status
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the Models screen says when the Mac says no (#12): every operation's failure reaches
 * [ModelsViewModel.problem] with the Mac's own sentence, a dismissal clears it, a new
 * attempt starts clean, and a list that fails to arrive is neither emptied nor passed off
 * as an empty library.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelsErrorsTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val catalogModel = CatalogModel(
        id = "test-model", name = "Test Model", author = "Test", license = "MIT",
        summary = "A model for the error tests", category = "chat", parameters = "2B",
        isMoE = false, capabilities = listOf("chat"), rating = 1, maxContext = 4096,
        quantizations = listOf("Q4_K_M"),
    )
    private val onDisk = InstalledModel("test-model@Q4_K_M", "Test Model", "Q4_K_M", 100, false, false)

    /** A Mac whose every route can be told to fail with a given error, and then to recover. */
    private inner class FlakyMac : HangingTransport() {
        var installedError: TransportError? = null
        var catalogError: TransportError? = null
        var loadError: TransportError? = null
        var unloadError: TransportError? = null
        var installError: TransportError? = null
        var state = Status("Not loaded")
        var loads = 0

        override suspend fun installed(): List<InstalledModel> {
            installedError?.let { throw it }
            return listOf(onDisk)
        }

        override suspend fun catalog(category: String?, onlyRunnable: Boolean): List<CatalogModel> {
            catalogError?.let { throw it }
            return listOf(catalogModel)
        }

        override suspend fun status(): Status = state

        override suspend fun load(request: LoadRequest): Status {
            loads++
            loadError?.let { throw it }
            state = Status("Ready", loadedModelID = request.modelID, loadedModelName = "Test Model")
            return state
        }

        override suspend fun unload() {
            unloadError?.let { throw it }
            state = Status("Not loaded")
        }

        override suspend fun install(request: LoadRequest): String {
            installError?.let { throw it }
            return "Downloading…"
        }
    }

    private val driveGone = TransportError.Unavailable("The model drive is disconnected.")

    @Test
    fun `a refresh that fails keeps the list and says why, with a retry`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val mac = FlakyMac()
        try {
            model.refresh(mac)
            runCurrent()
            assertEquals(listOf(onDisk), model.installed)
            assertNull(model.problem)

            mac.installedError = TransportError.TimedOut
            mac.catalogError = TransportError.TimedOut
            model.refresh(mac)
            runCurrent()

            val problem = model.problem
            assertNotNull(problem)
            problem!!
            assertEquals("Couldn't read the model list", problem.title)
            assertEquals("The Mac took too long to answer.", model.error)
            assertEquals(ModelsViewModel.Operation.Refresh, problem.retry)
            assertEquals("the list the phone already had stays on screen", listOf(onDisk), model.installed)
            assertEquals(listOf(catalogModel), model.catalog)
            assertTrue(model.installedFailed)

            mac.installedError = null
            mac.catalogError = null
            model.retry(mac)
            runCurrent()
            assertNull("a refresh that worked takes its own complaint away", model.problem)
            assertFalse(model.installedFailed)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `a first read that fails is not an empty library`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val mac = FlakyMac().apply { installedError = TransportError.AppNotRunning }
        try {
            model.refresh(mac)
            runCurrent()
            assertTrue(model.installed.isEmpty())
            assertTrue("the screen must be able to tell 'failed' from 'empty'", model.installedFailed)
            assertFalse(model.catalogFailed)
            assertEquals("Couldn't read the models on the Mac", model.problem?.title)
            assertEquals(TransportError.AppNotRunning.message, model.error)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `a load the Mac refuses says the Mac's words and offers to try again`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val mac = FlakyMac().apply { loadError = driveGone }
        try {
            model.refresh(mac)
            runCurrent()
            model.load(onDisk.id, transport = mac)
            runCurrent()

            assertNull("the progress row goes", model.job)
            assertEquals("The model drive is disconnected.", model.error)
            assertEquals("Couldn't load Test Model", model.problem?.title)
            assertEquals(ModelsViewModel.Operation.Load(onDisk.id, null), model.problem?.retry)

            // The Mac is back: Retry is a new attempt, and the old sentence goes with it.
            mac.loadError = null
            model.retry(mac)
            runCurrent()
            assertNull(model.problem)
            assertTrue(model.isLoaded(onDisk.id))
            assertEquals(2, mac.loads)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `an install and an unload that fail say so too`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val mac = FlakyMac().apply { installError = driveGone }
        try {
            model.install(catalogModel, transport = mac)
            runCurrent()
            assertNull(model.job)
            assertEquals("Couldn't install Test Model", model.problem?.title)
            assertEquals("The model drive is disconnected.", model.error)
            assertTrue(model.problem?.retry is ModelsViewModel.Operation.Install)

            mac.state = Status("Ready", loadedModelID = onDisk.id, loadedModelName = "Test Model")
            model.refresh(mac)
            runCurrent()
            mac.unloadError = TransportError.Forbidden("This device is paired for chat only.")
            model.unload(mac)
            runCurrent()
            assertNull(model.job)
            assertEquals("Couldn't unload Test Model", model.problem?.title)
            assertEquals("This device is paired for chat only.", model.error)
            assertEquals(ModelsViewModel.Operation.Unload, model.problem?.retry)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `dismissing clears the error, and a new operation starts clean`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val mac = FlakyMac().apply { loadError = driveGone }
        try {
            model.load(onDisk.id, transport = mac)
            runCurrent()
            assertNotNull(model.problem)
            model.clearError()
            assertNull(model.problem)
            assertNull(model.error)

            model.load(onDisk.id, transport = mac)
            runCurrent()
            assertNotNull(model.problem)
            // A different operation: the load's failure is not left standing over it.
            model.install(catalogModel, transport = mac)
            assertNull("stale text goes the moment something new starts", model.problem)
            runCurrent()
            assertNull(model.problem)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `the refresh after a failed operation does not wipe the reason`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val mac = FlakyMac().apply { unloadError = driveGone }
        try {
            model.unload(mac)
            runCurrent()
            assertEquals("The model drive is disconnected.", model.error)
            model.refresh(mac)
            runCurrent()
            assertEquals("a working refresh is not an answer to a failed unload",
                "The model drive is disconnected.", model.error)

            // And a failing one does not replace it either: the operation's reason is the
            // one the person was reading.
            mac.installedError = TransportError.TimedOut
            model.refresh(mac)
            runCurrent()
            assertEquals("The model drive is disconnected.", model.error)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `a load the Mac lost carries its log from the status`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val sentence = "llama-server was killed (signal 9) after 8 seconds, which usually means " +
            "the system reclaimed its memory."
        val mac = FlakyMac().apply {
            // What the Mac answers a load that fails before it stops waiting: `ControlHostError
            // .loadFailed(state)`, a 400 whose sentence ends with the status line.
            loadError = TransportError.BadRequest("The model failed to load: $sentence")
            state = Status(
                sentence,
                failure = LoadFailure(
                    reason = "killed", detail = "load_tensors: loading model tensors",
                    runtime = "llama.cpp", signal = 9, at = "2026-09-19T11:04:38Z",
                ),
            )
        }
        try {
            model.refresh(mac)
            runCurrent()
            model.clearError()
            model.load(onDisk.id, transport = mac)
            runCurrent()
            assertEquals("Couldn't load Test Model", model.problem?.title)
            assertEquals("the line itself, not the Mac's prefix around it", sentence, model.error)
            assertEquals("load_tensors: loading model tensors", model.problem?.detail)
            assertNull("the banner already says it; the list does not say it twice", model.standingFailure)

            // Put away, the Mac's standing failure is still the Mac's state, and is shown as such.
            model.clearError()
            assertEquals("killed", model.standingFailure?.reason)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `a refusal that is not about the standing failure does not borrow its log`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val refusal = "Context length must be between 1 and 4096 tokens for this model."
        val mac = FlakyMac().apply {
            loadError = TransportError.BadRequest(refusal)
            state = Status(
                "llama-server was killed (signal 9) after 8 seconds.",
                failure = LoadFailure(reason = "killed", detail = "an older log", at = "2026-09-19T11:04:38Z"),
            )
        }
        try {
            model.load(onDisk.id, transport = mac)
            runCurrent()
            assertEquals(refusal, model.error)
            assertNull("that log belongs to another load", model.problem?.detail)
            assertEquals("and the older failure is still said, once, in the list", "killed", model.standingFailure?.reason)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `the Mac's error is matched to its status line, and nothing looser`() {
        val line = "llama-server never answered in 10 minutes."
        assertTrue(ModelsViewModel.describes(line, line))
        assertTrue(ModelsViewModel.describes("The model failed to load: $line", line))
        assertFalse(ModelsViewModel.describes("Something else. $line", line))
        assertFalse(ModelsViewModel.describes("The model failed to load: ", ""))
        assertFalse(ModelsViewModel.describes(null, line))
    }

    @Test
    fun `an operation with no Mac to ask does nothing`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        model.load("x", transport = null)
        model.install(catalogModel, transport = null)
        model.unload(null)
        model.refresh(null)
        advanceTimeBy(10_000)
        runCurrent()
        assertNull(model.job)
        assertNull(model.problem)
    }
}
