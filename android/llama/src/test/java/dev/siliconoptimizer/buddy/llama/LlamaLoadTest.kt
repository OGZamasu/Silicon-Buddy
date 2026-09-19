package dev.siliconoptimizer.buddy.llama

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What happens to a model that is still arriving when nobody wants it any more.
 *
 * A phone model is a gigabyte and takes the better part of a minute to read in. In that
 * minute the owner can leave the app, close the conversation, or press Stop — and the
 * coroutine that asked for it is cancelled. What must not happen is llama.cpp finishing the
 * load into memory no one holds a reference to: the phone then carries a gigabyte it cannot
 * free until the process dies.
 */
class LlamaLoadTest {

    @get:Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(20)

    private val started = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private val cancelled = AtomicBoolean(false)
    private val freed = CopyOnWriteArrayList<Long>()

    /** llama.cpp, as slow as the real one and as obedient about giving up. */
    private val loads = object : LlamaSession.Loads {
        override fun load(path: String, settings: LlamaSession.Settings): Long {
            started.complete(Unit)
            runBlocking { release.await() }
            return HANDLE
        }

        override fun cancelLoad() {
            cancelled.set(true)
            // The real one stops at its next progress report and throws; this one finishes,
            // which is the worse case: a whole model, loaded, with nobody expecting it.
            release.complete(Unit)
        }

        override fun unload(handle: Long) {
            freed += handle
        }
    }

    private lateinit var real: LlamaSession.Loads

    @Before
    fun setUp() {
        real = LlamaSession.loads
        LlamaSession.loads = loads
    }

    @After
    fun tearDown() {
        LlamaSession.loads = real
    }

    @Test
    fun `a load whose caller is cancelled is stopped and freed, not left in memory`() = runBlocking {
        var threw = false
        val caller = launch(Dispatchers.Default) {
            try {
                LlamaSession.open("model.gguf", LlamaSession.Settings())
            } catch (stopped: CancellationException) {
                threw = true
            }
        }
        started.await()
        caller.cancel()
        caller.join()

        assertTrue("the caller is told it was cancelled", threw)
        assertTrue("llama.cpp was asked to give up", cancelled.get())
        // The load had already finished by then, so what it made has to be freed here.
        val deadline = System.currentTimeMillis() + 10_000
        while (freed.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("the model was freed", listOf(HANDLE), freed.toList())
    }

    @Test
    fun `a load nobody cancelled is kept, and freed when it is closed`() = runBlocking {
        release.complete(Unit)
        val session = LlamaSession.open("model.gguf", LlamaSession.Settings())
        assertFalse(session.isClosed)
        assertTrue("nothing was cancelled", !cancelled.get())
        assertTrue("and nothing was freed behind its back", freed.isEmpty())
        session.close()
        assertTrue(session.isClosed)
        assertEquals(listOf(HANDLE), freed.toList())
    }

    private companion object {
        const val HANDLE = 7L
    }
}
