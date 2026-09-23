package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.pairing.MAC_TOO_OLD_FOR_CODES
import dev.siliconoptimizer.buddy.pairing.PairingExchange
import dev.siliconoptimizer.buddy.pairing.PairingInvite
import dev.siliconoptimizer.buddy.transport.ServerConfig
import dev.siliconoptimizer.buddy.transport.TransportError
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A code being spent is AppState's, not the screen's. The pairing sheet ran
 * `POST /buddy/pair` in its own coroutine scope, so closing the sheet mid-request cancelled
 * it — after the Mac may already have spent the code and made a device record for a token
 * the phone then dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingExchangeTest {

    private val invite = PairingInvite("100.64.0.9", 8788, "418203")
    private val paired = ServerConfig("100.64.0.9", 8788, "device-token", macName = "Test Mac")

    @Test
    fun `the Mac answers after the screen that asked has gone, and its token is kept`() = runTest {
        val exchange = PairingExchange(CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()))
        val answer = CompletableDeferred<ServerConfig>()
        var stored: ServerConfig? = null

        // The sheet: a coroutine scope of its own, which asks and is then closed.
        val sheet = Job()
        launch(sheet) {
            exchange.start(invite, exchange = { answer.await() }, store = { stored = it })
            awaitCancellation()
        }
        runCurrent()
        assertTrue(exchange.isWorking)
        sheet.cancel()
        runCurrent()

        answer.complete(paired)
        advanceUntilIdle()
        assertEquals(paired, stored)
        assertEquals(PairingExchange.State.Idle, exchange.state)
    }

    /**
     * The same over a real socket, with the owner's scope cancelled too — the activity
     * finishing, Back out of the app — after the Mac has the request and before it answers.
     * The client closes its connection when it is cancelled, which is what cut off the
     * answer before; here the answer arrives and is stored.
     */
    @Test
    fun `a request the Mac already has is not cut off when the app is left`() {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val requestArrived = CountDownLatch(1)
        val mayAnswer = CountDownLatch(1)
        val mac = kotlin.concurrent.thread(isDaemon = true) {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                var length = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("content-length:", ignoreCase = true)) {
                        length = line.substringAfter(':').trim().toInt()
                    }
                }
                val body = CharArray(length)
                var read = 0
                while (read < length) {
                    val count = reader.read(body, read, length - read)
                    if (count < 0) break
                    read += count
                }
                // The code is spent and the device is made; now the phone's side goes away.
                requestArrived.countDown()
                mayAnswer.await(10, TimeUnit.SECONDS)
                val answer = """{"deviceID":"D7","token":"kept-token","macName":"Test Mac","port":8788}"""
                runCatching {
                    socket.getOutputStream().write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${answer.length}\r\nConnection: close\r\n\r\n$answer")
                            .toByteArray(),
                    )
                }
            }
        }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val exchange = PairingExchange(owner)
        val stored = CompletableDeferred<ServerConfig>()
        try {
            val local = PairingInvite("127.0.0.1", server.localPort, "418203")
            assertTrue(
                exchange.start(
                    local,
                    exchange = { it.exchange("Pixel", "android") },
                    store = { stored.complete(it) },
                ),
            )
            assertTrue("the request never reached the Mac", requestArrived.await(10, TimeUnit.SECONDS))
            owner.cancel()
            mayAnswer.countDown()
            val config = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(10_000) { stored.await() }
            }
            assertEquals("the Mac's answer was dropped", "kept-token", config?.token)
            assertEquals("D7", config?.deviceID)
        } finally {
            mayAnswer.countDown()
            mac.join(5_000)
            server.close()
        }
    }

    @Test
    fun `a refusal no screen is left to show waits until one has shown it`() = runTest {
        val exchange = PairingExchange(this)
        var stored: ServerConfig? = null
        exchange.start(
            invite,
            exchange = { throw TransportError.Forbidden("That code has expired.") },
            store = { stored = it },
        )
        advanceUntilIdle()
        assertNull(stored)
        val failed = exchange.state as PairingExchange.State.Failed
        assertEquals(invite, failed.invite)
        assertEquals("That code has expired.", failed.message)
        assertFalse(failed.macTooOld)
        // Still there for whichever screen comes next, until it says it has shown it.
        advanceUntilIdle()
        assertEquals(failed, exchange.state)
        exchange.acknowledge()
        assertEquals(PairingExchange.State.Idle, exchange.state)
    }

    @Test
    fun `a Mac without buddy pair ends it as too old, in the words the sheet uses`() = runTest {
        val exchange = PairingExchange(this)
        exchange.start(
            invite,
            exchange = { throw TransportError.RouteUnavailable("/buddy/pair") },
            store = {},
        )
        advanceUntilIdle()
        val failed = exchange.state as PairingExchange.State.Failed
        assertTrue(failed.macTooOld)
        assertEquals(MAC_TOO_OLD_FOR_CODES, failed.message)
    }

    @Test
    fun `an answer that cannot be stored is a failure too`() = runTest {
        val exchange = PairingExchange(this)
        exchange.start(
            invite,
            exchange = { paired },
            store = { throw TransportError.Forbidden("Not a tailnet address.") },
        )
        advanceUntilIdle()
        assertEquals("Not a tailnet address.", (exchange.state as PairingExchange.State.Failed).message)
    }

    @Test
    fun `one code at a time`() = runTest {
        val exchange = PairingExchange(this)
        val answer = CompletableDeferred<ServerConfig>()
        var dialled = 0
        assertTrue(exchange.start(invite, exchange = { dialled++; answer.await() }, store = {}))
        runCurrent()
        val other = PairingInvite("100.64.0.10", 8788, "135790")
        assertFalse(exchange.start(other, exchange = { dialled++; paired }, store = {}))
        runCurrent()
        assertEquals(1, dialled)
        assertEquals(PairingExchange.State.Working(invite), exchange.state)
        answer.complete(paired)
        advanceUntilIdle()
        // Done, the next may go.
        assertTrue(exchange.start(other, exchange = { dialled++; paired }, store = {}))
        advanceUntilIdle()
        assertEquals(2, dialled)
    }
}
