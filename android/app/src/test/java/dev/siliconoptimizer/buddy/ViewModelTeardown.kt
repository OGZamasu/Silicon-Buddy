package dev.siliconoptimizer.buddy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.fail

/**
 * Stops everything a view model has in the air, and waits until it has stopped — for a
 * test's `@After`, before `Dispatchers.resetMain()`.
 *
 * A view model's work runs on Main, which the test has swapped for its own dispatcher, and
 * the conversation stores hop to `Dispatchers.IO` and come back. A read still on its IO
 * thread when Main is reset comes back to the real Main, which a JVM does not have, and
 * kotlinx-coroutines-test reports that against whichever test starts next, in whichever
 * class, as `UncaughtExceptionsBeforeTest`. Cancelling the scope stops what has not begun;
 * joining it waits out a read already under way, which cancelling cannot interrupt. A sleep
 * before `resetMain` only made the race rarer.
 *
 * For a Main that is an `UnconfinedTestDispatcher`, as in the chat tests: a coroutine coming
 * back from IO then finishes on the IO thread, so nothing here waits on the test thread.
 */
fun ViewModel.stopForTest(timeoutMs: Long = 10_000) {
    val scope = viewModelScope
    scope.cancel()
    val stopped = runBlocking { withTimeoutOrNull(timeoutMs) { scope.coroutineContext.job.join() } }
    if (stopped == null) fail("the view model was still running $timeoutMs ms after it was cancelled")
}
