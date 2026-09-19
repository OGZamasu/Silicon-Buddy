package dev.siliconoptimizer.buddy.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Where the Mac is and how to prove we may talk to it. */
data class ServerConfig(
    val host: String,
    val port: Int,
    val token: String,
    /** What the Mac calls itself, once pairing or `/v1/node` has told us. */
    val macName: String? = null,
    /** The id the Mac minted for this device at pairing, when it did. */
    val deviceID: String? = null,
    /** What the owner granted this device. An early Mac says nothing, which means full. */
    val scope: DeviceScope = DeviceScope.Full,
) {
    /** Whether this device may change what the Mac is running. */
    val canControl: Boolean get() = scope.canControl

    val displayAddress: String get() = "$host:$port"

    fun url(path: String, query: Map<String, String> = emptyMap()): URL {
        val suffix = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") {
            "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        }
        return URL("http://$host:$port$path$suffix")
    }

    /** Never prints the token. */
    override fun toString(): String = "ServerConfig($displayAddress, token: <redacted>)"
}

/**
 * Every call this app makes to a Mac.
 *
 * One interface so screens can be driven by a stub in tests without a Mac anywhere near
 * them, and so a relay transport could be slid underneath later.
 */
interface ControlTransport {
    suspend fun health(): Health
    suspend fun status(): Status
    suspend fun profile(): Profile
    suspend fun metrics(): Metrics
    suspend fun installed(): List<InstalledModel>
    suspend fun catalog(category: String? = null, onlyRunnable: Boolean = false): List<CatalogModel>
    suspend fun swarm(): SwarmView
    suspend fun node(): NodeAdvertisement
    suspend fun videoModels(): List<VideoModel>
    suspend fun imageModels(): List<ImageModel>
    suspend fun load(request: LoadRequest): Status
    suspend fun install(request: LoadRequest): String
    suspend fun unload()
    suspend fun chat(request: ChatRequest): ChatResponse

    // M0-pending. Each of these throws RouteUnavailable on a Mac without it.
    suspend fun pair(code: String, deviceName: String, platform: String): PairResponse
    fun chatStream(request: ChatRequest): Flow<ChatStreamEvent>
    fun events(): Flow<ServerEvent>
    suspend fun conversations(): List<ConversationSummary>
    suspend fun createConversation(title: String?): ConversationSummary
    suspend fun conversation(id: String): ConversationDetail
    fun sendMessage(
        conversationID: String,
        message: ChatMessageWire,
        maxTokens: Int? = null,
    ): Flow<ChatStreamEvent>

    // M3: the media routes and the render queue. Defaulted rather than abstract, so a
    // transport written for one surface — the widget's, a test's — does not have to
    // answer for routes it will never call.
    suspend fun meshModels(): List<MeshModel> = unsupported("/mesh/models")
    suspend fun videoQueue(): VideoQueueView = unsupported("/video/queue")
    suspend fun enqueueVideos(request: VideoQueueRequest): VideoQueueView =
        unsupported("/video/queue")

    suspend fun controlVideoQueue(request: VideoQueueControlRequest): VideoQueueView =
        unsupported("/video/queue/control")

    suspend fun generateVideo(request: VideoGenerateRequest): VideoResponse =
        unsupported("/video/generate")

    suspend fun planImage(request: ImageRequest): ImagePlan = unsupported("/image/plan")
    suspend fun generateImage(request: ImageRequest): ImageResponse =
        unsupported("/image/generate")

    suspend fun planMesh(request: MeshRequest): MeshPlan = unsupported("/mesh/plan")
    suspend fun generateMesh(request: MeshRequest): MeshResponse = unsupported("/mesh/generate")

    /** `GET /jev`, read for one fact: whether this Mac routes media requests itself. */
    suspend fun jev(): JevView = unsupported("/jev")

    private fun unsupported(path: String): Nothing = throw TransportError.RouteUnavailable(path)
}

/** The real thing: HttpURLConnection against the Mac's tiny HTTP server. */
class ControlClient(private val config: ServerConfig) : ControlTransport {

    companion object {
        /** One tag for everything this client says, so `logcat -s SiliconBuddy` is enough. */
        const val LOG = "SiliconBuddy"

        /** A connection that lasted this long counts as having worked. */
        const val STEADY_CONNECTION_MS = 30_000L

        /** The delay before opening the stream again: 1, 2, 4… seconds, capped. */
        fun reconnectDelayMillis(attempt: Int): Long =
            if (attempt <= 0) 0 else minOf(30_000L, 1000L shl (minOf(attempt, 6) - 1))

        /**
         * The next attempt number. A stream that stayed up starts the count again; one
         * that dropped immediately does not, however many events it managed first.
         */
        fun nextAttempt(attempt: Int, connectedForMs: Long): Int =
            if (connectedForMs >= STEADY_CONNECTION_MS) 0 else minOf(attempt + 1, 6)

        /** An id is data, not a path: a conversation called `../status` stays an id. */
        fun pathComponent(id: String): String =
            java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    // MARK: - Plumbing

    private fun open(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: String? = null,
        authorized: Boolean = true,
        accept: String = "application/json",
        readTimeoutMs: Int = 30_000,
    ): HttpURLConnection {
        // The one place every request passes through. A host that got into a
        // ServerConfig some other way still never gets dialled.
        if (!TailnetHost.isAllowed(config.host)) {
            // Worth a line: this is the gate that makes a scanned QR safe, and "the app
            // refused to dial that host" and "the host did not answer" look identical
            // from the outside. The route only, though — not the address that was
            // refused, and not the rest of the path, because `/conversations/<id>` is
            // the owner's data and a host is the owner's tailnet.
            android.util.Log.w(
                LOG,
                "refusing $method /${path.trimStart('/').substringBefore('/')}: " +
                    "host is not on the tailnet",
            )
            throw TransportError.Forbidden(TailnetHost.EXPLANATION)
        }
        if (authorized && config.token.isEmpty()) throw TransportError.NotConfigured
        val connection = config.url(path, query).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 8_000
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("User-Agent", "SiliconBuddy-Android/0.1")
        if (authorized) {
            connection.setRequestProperty("Authorization", "Bearer ${config.token}")
        }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        return connection
    }

    private suspend fun send(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: String? = null,
        authorized: Boolean = true,
        readTimeoutMs: Int = 30_000,
    ): String = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        var watchdog: kotlinx.coroutines.DisposableHandle? = null
        try {
            connection = open(method, path, query, body, authorized, readTimeoutMs = readTimeoutMs)
            // A render holds this connection open for minutes, and reading from a socket
            // blocks in the kernel where a cancelled coroutine cannot reach it. So
            // cancellation disconnects the socket from another thread, which is what
            // makes the read return — the same trick the event streams use, and what
            // makes "stop waiting" on a render possible at all.
            val open = connection
            watchdog = coroutineContext[Job]?.invokeOnCompletion {
                if (it != null) runCatching { open.disconnect() }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            coroutineContext.ensureActive()
            TransportError.from(status, text, path)?.let { throw it }
            text
        } catch (error: IOException) {
            coroutineContext.ensureActive()
            throw TransportError.from(error, config.host)
        } finally {
            watchdog?.dispose()
            connection?.disconnect()
        }
    }

    private inline fun <reified T> decode(text: String, path: String): T =
        try {
            json.decodeFromString<T>(text)
        } catch (error: Exception) {
            throw TransportError.Decoding("$path: ${error.message}")
        }

    // MARK: - Routes that exist today

    override suspend fun health(): Health =
        decode(send("GET", "/health", authorized = false, readTimeoutMs = 8_000), "/health")

    override suspend fun status(): Status =
        decode(send("GET", "/status", readTimeoutMs = 15_000), "/status")

    override suspend fun profile(): Profile = decode(send("GET", "/profile"), "/profile")

    override suspend fun metrics(): Metrics =
        decode(send("GET", "/metrics", readTimeoutMs = 15_000), "/metrics")

    override suspend fun installed(): List<InstalledModel> =
        decode(send("GET", "/installed"), "/installed")

    override suspend fun catalog(category: String?, onlyRunnable: Boolean): List<CatalogModel> {
        val query = buildMap {
            put("onlyRunnable", if (onlyRunnable) "true" else "false")
            category?.let { put("category", it) }
        }
        return decode(send("GET", "/catalog", query, readTimeoutMs = 45_000), "/catalog")
    }

    override suspend fun swarm(): SwarmView = decode(send("GET", "/swarm"), "/swarm")

    override suspend fun node(): NodeAdvertisement = decode(send("GET", "/v1/node"), "/v1/node")

    override suspend fun videoModels(): List<VideoModel> =
        decode(send("GET", "/video/models"), "/video/models")

    override suspend fun imageModels(): List<ImageModel> =
        decode(send("GET", "/image/models", readTimeoutMs = 45_000), "/image/models")

    // MARK: - The media routes

    override suspend fun meshModels(): List<MeshModel> =
        decode(send("GET", "/mesh/models", readTimeoutMs = 45_000), "/mesh/models")

    override suspend fun videoQueue(): VideoQueueView =
        decode(send("GET", "/video/queue", readTimeoutMs = 20_000), "/video/queue")

    override suspend fun enqueueVideos(request: VideoQueueRequest): VideoQueueView = decode(
        send("POST", "/video/queue", body = json.encodeToString(request), readTimeoutMs = 30_000),
        "/video/queue",
    )

    override suspend fun controlVideoQueue(request: VideoQueueControlRequest): VideoQueueView =
        decode(
            send(
                "POST", "/video/queue/control",
                body = json.encodeToString(request), readTimeoutMs = 30_000,
            ),
            "/video/queue/control",
        )

    /**
     * The one route that holds a connection open for the length of a render. The
     * queue is the better path for anything real; this exists for a single short clip,
     * and the Mac answers 429 rather than making a ninth caller wait.
     */
    override suspend fun generateVideo(request: VideoGenerateRequest): VideoResponse = decode(
        send(
            "POST", "/video/generate",
            body = json.encodeToString(request),
            readTimeoutMs = RenderBudget.IDLE_SECONDS * 1000,
        ),
        "/video/generate",
    )

    override suspend fun planImage(request: ImageRequest): ImagePlan = decode(
        send("POST", "/image/plan", body = json.encodeToString(request), readTimeoutMs = 60_000),
        "/image/plan",
    )

    override suspend fun generateImage(request: ImageRequest): ImageResponse = decode(
        send(
            "POST", "/image/generate",
            body = json.encodeToString(request),
            readTimeoutMs = RenderBudget.IDLE_SECONDS * 1000,
        ),
        "/image/generate",
    )

    override suspend fun planMesh(request: MeshRequest): MeshPlan = decode(
        send("POST", "/mesh/plan", body = json.encodeToString(request), readTimeoutMs = 60_000),
        "/mesh/plan",
    )

    override suspend fun generateMesh(request: MeshRequest): MeshResponse = decode(
        send(
            "POST", "/mesh/generate",
            body = json.encodeToString(request),
            readTimeoutMs = RenderBudget.IDLE_SECONDS * 1000,
        ),
        "/mesh/generate",
    )

    override suspend fun jev(): JevView = decode(send("GET", "/jev", readTimeoutMs = 20_000), "/jev")

    override suspend fun load(request: LoadRequest): Status = decode(
        send("POST", "/load", body = json.encodeToString(request), readTimeoutMs = 900_000),
        "/load",
    )

    override suspend fun install(request: LoadRequest): String = decode<StatusMessage>(
        send("POST", "/install", body = json.encodeToString(request), readTimeoutMs = 60_000),
        "/install",
    ).status

    override suspend fun unload() {
        send("POST", "/unload", body = "{}", readTimeoutMs = 120_000)
    }

    override suspend fun chat(request: ChatRequest): ChatResponse = decode(
        send("POST", "/chat", body = json.encodeToString(request), readTimeoutMs = 900_000),
        "/chat",
    )

    // MARK: - Routes the Mac grows in M0

    override suspend fun pair(code: String, deviceName: String, platform: String): PairResponse {
        val body = json.encodeToString(PairRequest(code, deviceName, platform))
        return try {
            decode(
                send("POST", "/buddy/pair", body = body, authorized = false, readTimeoutMs = 20_000),
                "/buddy/pair",
            )
        } catch (error: TransportError.Unauthorized) {
            // A Mac without this route rejects the unauthenticated request before it
            // ever looks at the path: unknown routes answer 401 to a caller with no
            // token, and 404 only to one with a good one. A Mac that *has* /buddy/pair
            // can never answer 401 to it, so this is "that Mac is too old".
            throw TransportError.RouteUnavailable("/buddy/pair")
        }
    }

    override fun chatStream(request: ChatRequest): Flow<ChatStreamEvent> =
        chatEvents("/chat/stream", json.encodeToString(request))

    override fun sendMessage(
        conversationID: String,
        message: ChatMessageWire,
        maxTokens: Int?,
    ): Flow<ChatStreamEvent> = chatEvents(
        "/conversations/${pathComponent(conversationID)}/messages",
        json.encodeToString(
            NewMessageRequest(
                content = message.content, images = message.images, maxTokens = maxTokens,
            ),
        ),
    )

    private fun chatEvents(path: String, body: String): Flow<ChatStreamEvent> =
        stream("POST", path, body).let { events ->
            kotlinx.coroutines.flow.flow {
                events.collect { event ->
                    when (event.name) {
                        "token" -> emit(ChatStreamEvent.Token(textOf(event)))
                        "reasoning" -> emit(ChatStreamEvent.Reasoning(textOf(event)))
                        "finished" -> emit(
                            ChatStreamEvent.Finished(
                                runCatching { json.decodeFromString<ChatMetrics>(event.data) }
                                    .getOrElse { ChatMetrics() },
                            ),
                        )
                        "error" -> emit(ChatStreamEvent.Failed(textOf(event)))
                        // A name this build has never heard of — `verdict` is the first.
                        // The Mac adds them, and an upgrade there must not break a phone
                        // that has not been rebuilt.
                        else -> Unit
                    }
                }
            }
        }

    /**
     * `GET /events`, kept open.
     *
     * A long-lived stream is a stream that will drop: a sleeping phone, a tailnet blip,
     * a Mac that restarts. So this reconnects with a growing delay rather than ending,
     * and carries `Last-Event-ID` back so a Mac that numbers its events can resume. It
     * ends for one reason only — the route is not there — which is what tells the caller
     * to go back to polling.
     */
    override fun events(): Flow<ServerEvent> = kotlinx.coroutines.flow.flow {
        var lastEventID: String? = null
        var attempt = 0
        while (true) {
            // How long this attempt stayed up decides the next delay. Counting events
            // instead would be worse than counting nothing: a Mac that sends one
            // heartbeat and drops would pin the delay at a second and this client would
            // knock twenty times a minute, forever.
            val openedAt = System.currentTimeMillis()
            var summary: String? = null
            // The open is logged as well as the drop. A stream that is up says nothing
            // on its own, so without this line "connected and quiet" and "never dialled"
            // look identical in logcat — and those are the two answers anyone reading it
            // is trying to tell apart.
            //
            // Whether it is resuming, not from where: the id is the Mac's own text and
            // nothing server-supplied goes in a log line.
            android.util.Log.i(
                LOG,
                "/events opening (attempt $attempt" +
                    (if (lastEventID != null) ", resuming" else "") + ")",
            )
            try {
                stream(
                    "GET", "/events", null, readTimeoutMs = 0, lastEventID = lastEventID,
                ).collect { event ->
                    event.id?.let { lastEventID = it }
                    when (event.name) {
                        "status" -> runCatching { json.decodeFromString<Status>(event.data) }
                            .getOrNull()?.let { emit(ServerEvent.StatusChanged(it)) }
                        "download" -> runCatching {
                            json.decodeFromString<DownloadProgress>(event.data)
                        }.getOrNull()?.let { emit(ServerEvent.Download(it)) }
                        "verdict" -> runCatching { json.decodeFromString<Verdict>(event.data) }
                            .getOrNull()?.let { emit(ServerEvent.Checked(it)) }
                        "job" -> runCatching { json.decodeFromString<JobProgress>(event.data) }
                            .getOrNull()?.let { emit(ServerEvent.Job(it)) }
                        "heartbeat", "ping" -> emit(
                            ServerEvent.Beat(
                                runCatching { json.decodeFromString<Heartbeat>(event.data) }
                                    .getOrNull()?.at,
                            ),
                        )
                        else -> Unit
                    }
                }
            } catch (error: TransportError) {
                if (error.isMissingRoute || error is TransportError.Unauthorized) throw error
                // `logSummary`, never `message`: `Unreachable` puts the Mac's tailnet
                // address in its message by construction, and this value is on its way
                // to logcat and to a field called `summary` for the same reason.
                summary = error.logSummary
            }
            attempt = nextAttempt(attempt, System.currentTimeMillis() - openedAt)
            val retryIn = reconnectDelayMillis(attempt)
            // Logged as well as emitted: when the question is "did the stream come back
            // after the tunnel flapped", the answer has to be findable in logcat
            // afterwards, not only on a screen nobody was watching at the time.
            android.util.Log.i(
                LOG,
                "/events dropped after ${System.currentTimeMillis() - openedAt}ms " +
                    "(attempt $attempt, retrying in ${retryIn}ms)" +
                    (summary?.let { ": $it" } ?: ""),
            )
            emit(ServerEvent.Disconnected(attempt, retryIn, summary))
            kotlinx.coroutines.delay(retryIn)
        }
    }

    /**
     * The shared body of every SSE call: check the status line, then run the lines
     * through [SseParser]. A read timeout of 0 means "wait as long as the Mac wants",
     * which is what an event stream is.
     */
    private fun stream(
        method: String,
        path: String,
        body: String?,
        readTimeoutMs: Int = 900_000,
        lastEventID: String? = null,
    ): Flow<SseEvent> = kotlinx.coroutines.flow.flow {
        var connection: HttpURLConnection? = null
        try {
            connection = open(
                method, path, body = body,
                accept = "text/event-stream", readTimeoutMs = readTimeoutMs,
            )
            lastEventID?.let { connection.setRequestProperty("Last-Event-ID", it) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val text = connection.errorStream?.bufferedReader()
                    ?.use(BufferedReader::readText).orEmpty()
                throw TransportError.from(status, text, path)
                    ?: TransportError.Server(status, text)
            }
            val parser = SseParser()
            // `readLine` blocks in the kernel; a cancelled coroutine cannot interrupt
            // it, and the stream would be held open until the Mac said something. So
            // cancellation disconnects the socket, which is what makes the read return.
            val open = connection
            val watchdog = coroutineContext[Job]?.invokeOnCompletion {
                runCatching { open.disconnect() }
            }
            try {
                readLines(connection.inputStream) { line ->
                    parser.consume(line)?.let { emit(it) }
                }
            } finally {
                watchdog?.dispose()
            }
            parser.finish()?.let { emit(it) }
        } catch (error: IOException) {
            throw TransportError.from(error, config.host)
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Reads an event stream a line at a time.
     *
     * Not `BufferedReader.readLine`: it treats CR, LF and CRLF alike and then tells you
     * nothing about which it saw, and — worse for SSE — a reader cannot distinguish the
     * blank line that ends a block from a stream that has simply paused. This reads
     * bytes, splits on LF, keeps the empty lines, and decodes each line as UTF-8 only
     * once it is whole, so a multi-byte character split across two reads survives.
     */
    private suspend inline fun readLines(
        stream: java.io.InputStream,
        crossinline onLine: suspend (String) -> Unit,
    ) {
        val line = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            coroutineContext.ensureActive()
            val read = stream.read(buffer)
            if (read < 0) break
            for (index in 0 until read) {
                val byte = buffer[index]
                if (byte == '\n'.code.toByte()) {
                    onLine(line.toString("UTF-8"))
                    line.reset()
                } else {
                    line.write(byte.toInt())
                }
            }
        }
        if (line.size() > 0) onLine(line.toString("UTF-8"))
    }

    // MARK: - Reconnection

    /**
     * A token event may be a bare string or `{"text": "…"}`; accept both, because the
     * Mac's shape is not frozen until M0 lands.
     */
    private fun textOf(event: SseEvent): String {
        if (!event.data.startsWith("{")) return event.data
        return runCatching {
            val element = json.parseToJsonElement(event.data)
            val fields = element as? kotlinx.serialization.json.JsonObject ?: return event.data
            listOf("text", "content", "delta", "error")
                .firstNotNullOfOrNull { key ->
                    (fields[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
                } ?: event.data
        }.getOrElse { event.data }
    }

    override suspend fun conversations(): List<ConversationSummary> =
        decode(send("GET", "/conversations", readTimeoutMs = 20_000), "/conversations")

    override suspend fun createConversation(title: String?): ConversationSummary = decode(
        send(
            "POST", "/conversations",
            body = json.encodeToString(NewConversation(title)), readTimeoutMs = 20_000,
        ),
        "/conversations",
    )

    override suspend fun conversation(id: String): ConversationDetail {
        val path = "/conversations/${pathComponent(id)}"
        return try {
            decode(send("GET", path, readTimeoutMs = 20_000), path)
        } catch (error: TransportError.RouteUnavailable) {
            // This route exists on any Mac that has conversations at all; a 404 here is
            // about the conversation, not about the Mac.
            throw error.asNotFound()
        }
    }
}
