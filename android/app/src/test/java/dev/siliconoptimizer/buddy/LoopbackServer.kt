package dev.siliconoptimizer.buddy

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A real HTTP server on 127.0.0.1, for tests that are about what goes over a socket.
 *
 * The transport is the part of this app that talks to something else, and until now
 * nothing exercised it: reverting the SSE blank-line fix left every test green. So this
 * binds a port, speaks the little of HTTP the Mac speaks, answers whatever the test
 * sets, and records what arrived — including how long between connections, which is how
 * a reconnect policy gets checked rather than assumed.
 */
class LoopbackServer : AutoCloseable {

    data class Reply(
        val status: Int,
        val body: ByteArray,
        val contentType: String = "application/json",
        /** Sent in pieces, the way an event stream arrives. */
        val chunks: List<ByteArray>? = null,
        /** Close the connection straight after answering. */
        val closeAfter: Boolean = true,
    )

    data class Received(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
        val atMillis: Long,
    )

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val repliesByPath = mutableMapOf<String, Reply>()
    private val received = CopyOnWriteArrayList<Received>()
    private val lock = Object()
    @Volatile private var running = true

    val port: Int get() = socket.localPort

    init {
        thread(isDaemon = true, name = "loopback-server") {
            while (running) {
                val connection = try {
                    socket.accept()
                } catch (error: Exception) {
                    break
                }
                thread(isDaemon = true) { serve(connection) }
            }
        }
    }

    // MARK: - What it answers

    fun reply(path: String, status: Int, body: String) {
        synchronized(lock) {
            repliesByPath[path] = Reply(status, body.toByteArray())
        }
    }

    /** An event stream, optionally delivered in pieces to split lines across reads. */
    fun events(path: String, body: String, splitAt: Int? = null) {
        val bytes = body.toByteArray()
        val chunks = splitAt?.let {
            listOf(bytes.copyOfRange(0, it), bytes.copyOfRange(it, bytes.size))
        }
        synchronized(lock) {
            repliesByPath[path] = Reply(200, bytes, "text/event-stream", chunks)
        }
    }

    /** An event stream sent as raw bytes, for CRLF and multi-byte cases. */
    fun eventBytes(path: String, chunks: List<ByteArray>) {
        synchronized(lock) {
            repliesByPath[path] = Reply(
                200, chunks.fold(ByteArray(0)) { all, part -> all + part },
                "text/event-stream", chunks,
            )
        }
    }

    // MARK: - What it saw

    val requests: List<Received> get() = received.toList()

    fun request(path: String): Received? = received.lastOrNull { it.path == path }

    fun requestCount(path: String): Int = received.count { it.path == path }

    /** The gaps between consecutive requests to a path, in milliseconds. */
    fun gaps(path: String): List<Long> {
        val times = received.filter { it.path == path }.map { it.atMillis }
        return times.zipWithNext { earlier, later -> later - earlier }
    }

    /** Waits until a path has been asked for this many times, or gives up. */
    fun awaitRequests(path: String, count: Int, timeoutMs: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (requestCount(path) >= count) return true
            Thread.sleep(25)
        }
        return false
    }

    override fun close() {
        running = false
        runCatching { socket.close() }
    }

    // MARK: - The little of HTTP the Mac speaks

    private fun serve(connection: Socket) {
        connection.use { open ->
            val input = open.getInputStream()
            val header = ByteArrayOutputStream()
            var previous = 0
            var blankLines = 0
            while (blankLines < 1) {
                val byte = input.read()
                if (byte < 0) return
                header.write(byte)
                if (byte == '\n'.code && previous == '\r'.code && header.size() >= 4) {
                    val text = header.toString("UTF-8")
                    if (text.endsWith("\r\n\r\n")) blankLines++
                }
                previous = byte
            }
            val headerText = header.toString("UTF-8")
            val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
            val requestLine = lines.first().split(" ")
            val headers = lines.drop(1).mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null
                else line.substring(0, colon).lowercase() to line.substring(colon + 1).trim()
            }.toMap()

            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = input.read(body, read, length - read)
                if (count < 0) break
                read += count
            }

            val target = requestLine.getOrElse(1) { "/" }
            val path = target.substringBefore('?')
            received.add(
                Received(
                    method = requestLine.firstOrNull() ?: "GET",
                    path = path,
                    headers = headers,
                    body = String(body, Charsets.UTF_8),
                    atMillis = System.currentTimeMillis(),
                ),
            )

            val reply = synchronized(lock) {
                repliesByPath[path] ?: Reply(404, """{"error":"Unknown endpoint"}""".toByteArray())
            }
            val output = open.getOutputStream()
            val head = buildString {
                append("HTTP/1.1 ${reply.status} ${if (reply.status == 200) "OK" else "Error"}\r\n")
                append("Content-Type: ${reply.contentType}\r\n")
                append("Content-Length: ${reply.body.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(head.toByteArray())
            output.flush()
            val pieces = reply.chunks ?: listOf(reply.body)
            for ((index, piece) in pieces.withIndex()) {
                output.write(piece)
                output.flush()
                if (index < pieces.lastIndex) Thread.sleep(30)
            }
        }
    }
}
