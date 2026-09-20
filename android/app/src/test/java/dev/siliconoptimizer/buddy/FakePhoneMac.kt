package dev.siliconoptimizer.buddy

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A Mac serving one phone model over real HTTP, with every refusal the real one documents.
 *
 * It speaks `/ondevice/models` the way silicon-optimizer PR #48 does — a list, a prepare
 * that answers 202 until the file is ready and 200 after, and the file with `Range`,
 * `If-Range` compared strongly against the digest, `ETag` and `X-Content-SHA256` — and it
 * can misbehave on cue: drop a transfer part-way, corrupt one, ignore a range, refuse a
 * file as not ready, or have lost its drive.
 */
class FakePhoneMac(
    val bytes: ByteArray,
    val id: String = "qwen3.5-2b-q4_0",
    val label: String = "Qwen3.5 2B",
    /** How many list reads a prepare takes to become ready. 0: ready at once. */
    var fetchPolls: Int = 1,
    var scope: String = "full",
) : AutoCloseable {

    val sha256: String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // What it will do, set by the test.
    @Volatile var dropAfter: Long? = null
    @Volatile var corrupt = 0
    @Volatile var ignoreRange = 0
    @Volatile var notReady = 0
    @Volatile var driveMissing = false
    @Volatile var macFailure: Pair<String, String>? = null
    /** Whether a prepare clears [macFailure] — true for the failures a retry fixes. */
    @Volatile var prepareClearsFailure = true
    @Volatile var ready = false
    @Volatile var servedSize: Long? = null

    /** A digest to serve in `X-Content-SHA256` other than the real one, for a Mac serving
     * a different file than it listed. */
    @Volatile var servedDigest: String? = null

    /** A digest to *list*, for a Mac that answers with something that is not one. */
    @Volatile var listedDigest: String? = null

    private var pollsLeft = 0

    data class Request(val method: String, val path: String, val query: String, val headers: Map<String, String>)

    val requests = CopyOnWriteArrayList<Request>()
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    @Volatile private var running = true
    val port: Int get() = socket.localPort

    init {
        thread(isDaemon = true, name = "fake-phone-mac") {
            while (running) {
                val connection = try { socket.accept() } catch (error: Exception) { break }
                thread(isDaemon = true) { runCatching { serve(connection) } }
            }
        }
    }

    fun count(method: String, pathSuffix: String, query: String? = null): Int =
        requests.count { it.method == method && it.path.endsWith(pathSuffix) && (query == null || it.query.contains(query)) }

    override fun close() {
        running = false
        runCatching { socket.close() }
    }

    private fun entry(): String {
        val onMac = when {
            macFailure != null -> """{"state":"failed","failure":"${macFailure!!.first}","reason":"${macFailure!!.second}"}"""
            ready -> """{"state":"ready"}"""
            requests.any { it.path.endsWith("/prepare") } -> """{"state":"downloading","stage":"fetching","fraction":0.5}"""
            else -> """{"state":"absent"}"""
        }
        return """{"id":"$id","label":"$label","isDefault":true,"sizeBytes":${bytes.size},"sha256":"${listedDigest ?: sha256}",""" +
            """"licence":"Apache-2.0","source":{"repo":"test/repo","commit":"abc","file":"model.gguf"},""" +
            """"onMac":$onMac,"recommended":{"threadsPrompt":6,"threadsGenerate":4,"contextLength":4096,""" +
            """"minFreeMemoryBytes":3100000000,"thinking":false},"slowerOnPhone":false}"""
    }

    private fun serve(connection: Socket) = connection.use { open ->
        val input = open.getInputStream()
        val head = ByteArrayOutputStream()
        while (!head.toString("UTF-8").endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte < 0) return
            head.write(byte)
        }
        val lines = head.toString("UTF-8").split("\r\n").filter { it.isNotEmpty() }
        val (method, target) = lines.first().split(" ").let { it[0] to it[1] }
        val headers = lines.drop(1).associate { line ->
            line.substringBefore(':').lowercase() to line.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        repeat(length) { input.read() }
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        requests += Request(method, path, query, headers)
        val output = open.getOutputStream()

        fun reply(status: Int, body: String, extra: Map<String, String> = emptyMap()) {
            val data = body.toByteArray()
            val text = buildString {
                append("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${data.size}\r\n")
                extra.forEach { (k, v) -> append("$k: $v\r\n") }
                append("Connection: close\r\n\r\n")
            }
            output.write(text.toByteArray())
            output.write(data)
            output.flush()
        }

        if (headers["authorization"] != "Bearer test-token") return reply(401, """{"error":"Invalid or missing control token."}""")
        if (scope != "full") {
            return reply(
                403,
                """{"error":"This device is paired for chat only. Pair it again with full control from Settings → Silicon Buddy on the Mac."}""",
            )
        }
        when {
            method == "GET" && path == "/ondevice/models" -> {
                if (!ready && macFailure == null && requests.any { it.path.endsWith("/prepare") }) {
                    if (pollsLeft-- <= 0) ready = true
                }
                reply(200, """{"models":[${entry()}]}""")
            }
            method == "POST" && path == "/ondevice/models/$id/prepare" -> {
                if (driveMissing) return reply(503, """{"error":"The drive “Demo SSD” is not connected."}""")
                if (query.contains("verify=1")) {
                    ready = false
                    pollsLeft = fetchPolls
                    return reply(202, entry())
                }
                if (macFailure != null) {
                    if (!prepareClearsFailure) return reply(202, entry())
                    macFailure = null
                }
                if (ready) return reply(200, entry())
                pollsLeft = fetchPolls
                if (fetchPolls == 0) {
                    ready = true
                    return reply(200, entry())
                }
                reply(202, entry())
            }
            method == "GET" && path == "/ondevice/models/$id/file" -> serveFile(headers, output, ::reply)
            else -> reply(404, """{"error":"Unknown endpoint $method $path"}""")
        }
    }

    private fun serveFile(
        headers: Map<String, String>,
        output: java.io.OutputStream,
        reply: (Int, String, Map<String, String>) -> Unit,
    ) {
        if (driveMissing) return reply(503, """{"error":"The drive “Demo SSD” is not connected."}""", emptyMap())
        if (!ready || notReady > 0) {
            if (notReady > 0) notReady--
            return reply(409, """{"error":"This Mac does not have that model ready yet."}""", emptyMap())
        }
        val size = servedSize ?: bytes.size.toLong()
        var start: Long? = headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull()
        headers["if-range"]?.let { if (it.trim('"') != sha256) start = null }
        if (start != null && ignoreRange > 0) {
            ignoreRange--
            start = null
        }
        if (start != null && start!! >= size) {
            return reply(416, """{"error":"That byte range is not inside this file."}""", mapOf("Content-Range" to "bytes */$size"))
        }
        val from = start ?: 0L
        val body = bytes.copyOfRange(from.toInt(), size.toInt())
        val text = buildString {
            append(if (start != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/octet-stream\r\nContent-Length: ${size - from}\r\n")
            append("ETag: \"$sha256\"\r\nX-Content-SHA256: ${servedDigest ?: sha256}\r\nAccept-Ranges: bytes\r\n")
            if (start != null) append("Content-Range: bytes $from-${size - 1}/$size\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(text.toByteArray())
        val sending = body.copyOf()
        if (corrupt > 0 && sending.isNotEmpty()) {
            corrupt--
            sending[sending.size / 2] = (sending[sending.size / 2].toInt() xor 0xff).toByte()
        }
        val cut = dropAfter
        if (cut != null) {
            dropAfter = null
            output.write(sending, 0, minOf(cut.toInt(), sending.size))
            output.flush()
            return
        }
        output.write(sending)
        output.flush()
    }
}
