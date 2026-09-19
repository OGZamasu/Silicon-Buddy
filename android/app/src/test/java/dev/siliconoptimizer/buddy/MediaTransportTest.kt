package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.media.VideoLane
import dev.siliconoptimizer.buddy.media.VideoRequest
import dev.siliconoptimizer.buddy.transport.ControlClient
import dev.siliconoptimizer.buddy.transport.ImageRequest
import dev.siliconoptimizer.buddy.transport.MeshRequest
import dev.siliconoptimizer.buddy.transport.ServerConfig
import dev.siliconoptimizer.buddy.transport.TransportError
import dev.siliconoptimizer.buddy.transport.Uploads
import dev.siliconoptimizer.buddy.transport.VideoGenerateRequest
import dev.siliconoptimizer.buddy.transport.VideoModel
import dev.siliconoptimizer.buddy.transport.VideoQueueControlRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The media calls, over a real socket.
 *
 * What goes out matters more here than anywhere else in the app: these are the requests
 * that spend minutes of somebody's GPU. So this checks the bodies rather than the
 * round trip — that an absent option is absent rather than null, that the snake_case
 * fields keep their spelling, and that a Mac already rendering eight clips reaches a
 * person as "busy" and not as a number.
 */
class MediaTransportTest {

    private lateinit var server: LoopbackServer
    private lateinit var client: ControlClient

    private val queue = """
        {"paused":false,"activeID":"9C2F-0001","items":[]}
    """.trimIndent()

    @Before
    fun setUp() {
        server = LoopbackServer()
        client = ControlClient(ServerConfig("127.0.0.1", server.port, token = "device-token"))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun body(path: String): JsonObject =
        Json.parseToJsonElement(server.request(path)!!.body) as JsonObject

    @Test
    fun `queueing sends the prompt, the lane and the takes, and nothing it was not given`() = runTest {
        server.reply("/video/queue", 200, queue)
        val h3 = VideoModel(
            id = "hailuo-h3", name = "Hailuo H3", summary = "", typicalDuration = "",
            supportsImageInput = true, supportedSeconds = listOf(5, 10), available = true,
            node = "silicon-node",
        )
        client.enqueueVideos(
            VideoRequest.enqueue("A tram at dawn", VideoLane.On(h3), title = "Lisbon", seconds = 10, variations = 3)!!,
        )
        val sent = body("/video/queue")
        assertEquals("A tram at dawn", (sent.getValue("prompts") as kotlinx.serialization.json.JsonArray)[0].let { (it as JsonPrimitive).content })
        assertEquals("Lisbon", (sent["title"] as JsonPrimitive).content)
        assertEquals("3", (sent["variations"] as JsonPrimitive).content)
        assertEquals("hailuo-h3", (sent["modelID"] as JsonPrimitive).content)
        assertEquals("10", (sent["seconds"] as JsonPrimitive).content)
        assertNull("An option nobody chose must not be sent as null", sent["seed"])
        assertNull(sent["h3_turbo"])
        // Every request this app makes carries a length; the Mac answers 411 otherwise.
        assertEquals(
            server.request("/video/queue")!!.body.toByteArray().size.toString(),
            server.request("/video/queue")!!.headers["content-length"],
        )
    }

    @Test
    fun `the queue's own vocabulary goes out unchanged`() = runTest {
        server.reply("/video/queue/control", 200, queue)
        client.controlVideoQueue(
            VideoQueueControlRequest(VideoQueueControlRequest.RETRY, "9C2F-0001", confirmNewRender = true),
        )
        val sent = body("/video/queue/control")
        assertEquals("retry", (sent.getValue("action") as JsonPrimitive).content)
        assertEquals("9C2F-0001", (sent.getValue("id") as JsonPrimitive).content)
        assertEquals("true", (sent.getValue("confirmNewRender") as JsonPrimitive).content)
    }

    @Test
    fun `the snake_case renderer controls keep their spelling`() = runTest {
        server.reply("/video/generate", 200, """{"file":"/Users/you/a.mp4","node":"n","model":"m","elapsedSeconds":1.0}""")
        client.generateVideo(
            VideoGenerateRequest(prompt = "A tram", modelID = "hailuo-h3", seconds = 10, h3Turbo = false, h3Steps = 30),
        )
        val sent = body("/video/generate")
        assertEquals("false", (sent.getValue("h3_turbo") as JsonPrimitive).content)
        assertEquals("30", (sent.getValue("h3_steps") as JsonPrimitive).content)
        assertNull("h3Turbo is not a field the Mac reads", sent["h3Turbo"])
    }

    @Test
    fun `a Mac already rendering eight clips says so in words`() = runTest {
        server.reply(
            "/video/generate", 429,
            """{"error":"Too many synchronous video requests. No clip was added. Use POST /video/queue to save work without holding a connection, then GET /video/queue to follow it."}""",
        )
        val error = runCatching {
            client.generateVideo(VideoGenerateRequest(prompt = "A tram"))
        }.exceptionOrNull()
        assertTrue(error is TransportError.Busy)
        assertTrue(error!!.message!!.contains("POST /video/queue"))
    }

    @Test
    fun `a chat-only phone is told why it may not render`() = runTest {
        server.reply(
            "/image/generate", 403,
            """{"error":"This device is paired for chat only. Pair it again with full control from Settings → Silicon Buddy on the Mac."}""",
        )
        val error = runCatching {
            client.generateImage(ImageRequest(prompt = "A tram", modelID = "flux2-klein"))
        }.exceptionOrNull()
        assertTrue(error is TransportError.Forbidden)
        assertTrue(error!!.message!!.contains("chat only"))
    }

    @Test
    fun `a Mac without the media router is not an error to show`() = runTest {
        server.reply("/jev", 403, """{"error":"This device is paired for chat only."}""")
        assertTrue(
            runCatching { client.jev() }.exceptionOrNull() is TransportError.Forbidden,
        )
    }

    @Test
    fun `planning a mesh asks about a path and gets phases back`() = runTest {
        server.reply(
            "/mesh/plan", 200,
            """{"model":"Hunyuan3D 2","peakBytes":13958643712,"peakPhase":"Texture bake","budgetBytes":29200000000,"verdict":"fits","isRemote":false,"phases":[{"name":"Texture bake","detail":"2048px","residentBytes":13958643712}],"suggestions":[],"notes":[]}""",
        )
        val plan = client.planMesh(MeshRequest("/Users/you/Pictures/kettle.png", "hunyuan3d-2", textureSize = 2048))
        assertEquals("Texture bake", plan.peakPhase)
        assertEquals(1, plan.phases.size)
        assertEquals(
            "/Users/you/Pictures/kettle.png",
            (body("/mesh/plan").getValue("imagePath") as JsonPrimitive).content,
        )
    }

    @Test
    fun `an older Mac without the mesh routes says which route is missing`() = runTest {
        server.reply("/mesh/models", 404, """{"error":"Unknown endpoint GET /mesh/models"}""")
        val error = runCatching { client.meshModels() }.exceptionOrNull()
        assertTrue(error is TransportError.RouteUnavailable)
        assertTrue(error!!.message!!.contains("/mesh/models"))
    }

    /**
     * Stopping the wait has to reach the socket *while* the read is blocked.
     *
     * A render holds the connection for minutes, and a read blocks in the kernel where
     * a cancelled coroutine cannot touch it; only closing the connection from another
     * thread gets it back. The first version of this hung the handler off
     * `invokeOnCompletion`, which fires when a coroutine has *finished* — that is,
     * after the read it was supposed to interrupt. So what is checked here is the
     * timing: the moment the caller gives up, not a moment later.
     */
    @Test
    fun `giving up on a render reaches the connection at once`() {
        val silent = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val accepted = java.util.concurrent.LinkedBlockingQueue<java.net.Socket>()
        val abandoned = java.util.concurrent.CountDownLatch(1)
        val listener = kotlin.concurrent.thread(isDaemon = true) {
            runCatching { accepted.put(silent.accept()) }
        }
        // Its own scope, so a client left blocked on a socket the platform will not
        // interrupt cannot hold the test suite open.
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        try {
            val waiting = ControlClient(
                ServerConfig("127.0.0.1", silent.localPort, token = "device-token"),
                abandon = { abandoned.countDown(); runCatching { it.disconnect() } },
            )
            val call = scope.launch {
                runCatching {
                    waiting.generateImage(ImageRequest(prompt = "A tram", modelID = "flux2-klein"))
                }
            }
            assertNotNull(
                "the request never left",
                accepted.poll(10, java.util.concurrent.TimeUnit.SECONDS),
            )
            call.cancel()
            assertTrue(
                "A cancelled render must reach its connection, not wait for the read",
                abandoned.await(5, java.util.concurrent.TimeUnit.SECONDS),
            )
        } finally {
            scope.cancel()
            listener.interrupt()
            runCatching { accepted.poll()?.close() }
            silent.close()
        }
    }

    // MARK: - The routes that move bytes

    @Test
    fun `an upload sends the bytes, the type and the name, and no more than the ceiling`() = runTest {
        server.reply(
            "/uploads", 200,
            """{"uploadID":"0B7D","mediaID":"bWVk","bytes":12,"contentType":"image/png","mediaURL":"/media/bWVk","expiresAt":"2026-09-25T09:41:00Z"}""",
        )
        val answer = client.upload("a picture".toByteArray(), "image/png", "kettle.png")
        assertEquals("0B7D", answer.uploadID)
        val sent = server.request("/uploads")!!
        assertEquals("image/png", sent.headers["content-type"])
        assertEquals("kettle.png", sent.headers["x-filename"])
        assertEquals("a picture", sent.body)

        // Refused here rather than after sending 40 MB to be told about it.
        val tooBig = runCatching {
            client.upload(ByteArray(Uploads.MAXIMUM_BYTES.toInt() + 1), "image/png", "big.png")
        }.exceptionOrNull()
        assertTrue(tooBig is TransportError.TooLarge)
        assertEquals("nothing left this phone", 1, server.requestCount("/uploads"))
    }

    @Test
    fun `a file the Mac will not keep says which ones it will`() = runTest {
        server.reply(
            "/uploads", 415,
            """{"error":"That upload is not an image or a short video this Mac will keep. Send a PNG, JPEG, GIF, WebP, MP4, MOV or WebM."}""",
        )
        val error = runCatching {
            client.upload("not a picture".toByteArray(), "text/plain", "notes.txt")
        }.exceptionOrNull()
        assertTrue(error is TransportError.UnsupportedMedia)
        assertTrue(error!!.message!!.contains("WebM"))
    }

    @Test
    fun `fetching a result writes the bytes out as they arrive`() = runTest {
        server.reply("/media/bWVk", 200, "the clip itself")
        val sink = java.io.ByteArrayOutputStream()
        client.media("bWVk", sink)
        assertEquals("the clip itself", sink.toString("UTF-8"))
        assertNotNull(server.request("/media/bWVk"))
    }

    @Test
    fun `a chat-only phone is told why it may not pull a render`() = runTest {
        server.reply(
            "/media/bWVk", 403,
            """{"error":"This device is paired for chat only, so it may fetch preview images but not the renders themselves. Pair it again with full control from Settings → Silicon Buddy on the Mac."}""",
        )
        val error = runCatching { client.media("bWVk", java.io.ByteArrayOutputStream()) }
            .exceptionOrNull()
        assertTrue(error is TransportError.Forbidden)
        assertTrue(error!!.message!!.contains("preview images"))
    }

    @Test
    fun `a media id is data, not a path`() = runTest {
        server.reply("/media/..%2Fstatus", 404, """{"error":"No file with that media id."}""")
        runCatching { client.media("../status", java.io.ByteArrayOutputStream()) }
        assertNotNull(
            "an id that looks like a path stays an id",
            server.request("/media/..%2Fstatus"),
        )
    }

    @Test
    fun `asking one node goes to that node's own route`() = runTest {
        server.reply(
            "/swarm/peers/silicon-node/status", 200,
            """{"name":"silicon-node","baseURL":"http://silicon-node:8790","reachable":true,"capabilities":[],"gguf":{"running":true,"model":"qwen.gguf","adapter":"a.lora.gguf","installedModels":[],"adapters":[]}}""",
        )
        val peer = client.peerStatus("silicon-node")
        assertEquals("a.lora.gguf", peer.gguf!!.adapter)
    }

    /**
     * A stream that has gone quiet is a stream that has gone.
     *
     * The Mac heartbeats every fifteen seconds. Read with no timeout, a half-open
     * socket — a Mac restarted while the phone slept, a NAT that dropped its mapping —
     * leaves the phone waiting forever, saying "streaming from /events" while nothing
     * arrives and never reconnecting. The screens that read the stream then show a
     * render that finished an hour ago as still running.
     */
    @Test
    fun `the event stream does not wait forever on a socket that says nothing`() {
        assertTrue(
            "three heartbeats' grace, and not much more",
            ControlClient.SILENT_STREAM_MS in 30_000..90_000,
        )
    }
}
