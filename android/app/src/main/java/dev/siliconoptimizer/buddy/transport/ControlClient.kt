package dev.siliconoptimizer.buddy.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.siliconoptimizer.buddy.ui.Format
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

    /**
     * `POST /uploads`: a picture this phone has, given to the Mac so a render can start
     * from it. Answers the two ids that stand in for a path.
     */
    suspend fun upload(bytes: ByteArray, contentType: String, filename: String?): UploadResponse =
        unsupported("/uploads")

    /**
     * `GET /media/{id}`, the one route that answers bytes rather than JSON. Written
     * into [sink] as it arrives — a clip is not something to hold in memory — and the
     * content type the Mac put on it is returned.
     */
    suspend fun media(id: String, sink: java.io.OutputStream): String? = unsupported("/media")

    /** `GET /swarm/peers/{name}/status`: one node asked now, adapter and all. */
    suspend fun peerStatus(name: String): PeerNodeStatus = unsupported("/swarm/peers")

    // M4: the Mac's agent sessions. Full scope, every one: each of them runs commands on
    // the owner's Mac.

    /** `GET /agent/sessions`: every engine, running or not. */
    suspend fun agentSessions(): AgentSessionList = unsupported("/agent/sessions")

    /**
     * `GET /agent/sessions/{engine}`. With [since] and [epoch] — one cursor in two halves —
     * only what changed after it; `complete` in the answer says whether it is a slice to
     * merge or a transcript to replace the one on screen with. A cursor from another
     * transcript is answered whole.
     */
    suspend fun agentSession(engine: String, since: Long? = null, epoch: String? = null): AgentSessionDetail =
        unsupported("/agent/sessions")

    suspend fun startAgent(engine: String): AgentSessionSummary = unsupported("/agent/sessions")
    suspend fun newAgentThread(engine: String): AgentSessionSummary =
        unsupported("/agent/sessions")

    suspend fun stopAgent(engine: String): AgentSessionSummary = unsupported("/agent/sessions")
    suspend fun interruptAgent(engine: String): AgentSessionSummary =
        unsupported("/agent/sessions")

    /** 202: the row the send became. What the engine does arrives on `/events`. */
    suspend fun sendAgentMessage(engine: String, request: AgentMessageRequest): AgentMessageAccepted =
        unsupported("/agent/sessions")

    /**
     * Answers a held call. A 404 comes back as `NotFound` — answered already, or gone —
     * and a 409 as `Conflict` carrying the Mac's own sentence: answered at the Mac first.
     */
    suspend fun answerAgentApproval(engine: String, id: String, decision: String): AgentApprovalResult =
        unsupported("/agent/sessions")

    // M5: the small models the Mac keeps for this phone. Full scope, every one; a chat-only
    // device is answered 403 and told why.

    /** `GET /ondevice/models`: what this phone could run, and the Mac's copy of each. */
    suspend fun phoneModels(): PhoneModelList = unsupported("/ondevice/models")

    /**
     * `POST /ondevice/models/{id}/prepare`: have the Mac fetch and verify the pinned file.
     * [verify] asks it to hash a ready copy again — once, after this phone's own check of
     * what it fetched failed.
     */
    suspend fun preparePhoneModel(id: String, verify: Boolean = false): PhoneModelPreparation =
        unsupported("/ondevice/models")

    /** `DELETE /ondevice/models/{id}`: the Mac's copy and any partial, gone. */
    suspend fun removePhoneModel(id: String): PhoneModel = unsupported("/ondevice/models")

    /**
     * `GET /ondevice/models/{id}/file` from byte [from] on. With [ifRange] — the tag the
     * partial was fetched under — a 200 means the partial belongs to another file.
     */
    suspend fun openPhoneModelFile(id: String, from: Long, ifRange: String?): PhoneModelStream =
        unsupported("/ondevice/models")

    private fun unsupported(path: String): Nothing = throw TransportError.RouteUnavailable(path)
}

/** The real thing: HttpURLConnection against the Mac's tiny HTTP server. */
class ControlClient(
    private val config: ServerConfig,
    /**
     * What to do with a connection whose caller has given up.
     *
     * On Android this is OkHttp behind the `HttpURLConnection` name, where closing the
     * connection from another thread is what makes a blocked read return. A test on the
     * JVM gets the platform's own implementation, which ignores it — so this is a seam,
     * and what the tests check is that it is reached the moment the caller gives up
     * rather than after the read it is meant to interrupt.
     */
    private val abandon: (HttpURLConnection) -> Unit = { runCatching { it.disconnect() } },
) : ControlTransport {

    companion object {
        /** One tag for everything this client says, so `logcat -s SiliconBuddy` is enough. */
        const val LOG = "SiliconBuddy"

        /**
         * How long `/events` may say nothing at all before this phone stops believing
         * in it.
         *
         * The Mac heartbeats every fifteen seconds, so silence is not a quiet Mac — it
         * is a socket that is no longer there. It happens: a Mac restarts while the
         * phone is asleep, a tailnet address moves, a NAT drops a mapping it has not
         * seen traffic on. Read with no timeout at all, the phone waits forever on a
         * half-open connection, saying "streaming from /events" while nothing arrives
         * and never reconnecting — which is how a render that finished an hour ago is
         * still "rendering" on the screen. Three heartbeats' grace, then reconnect.
         */
        const val SILENT_STREAM_MS = 45_000

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
        contentType: String? = null,
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
        contentType?.let { connection.setRequestProperty("Content-Type", it) }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType ?: "application/json")
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
    ): String = exchange(method, path, query, body, authorized, readTimeoutMs).second

    /** [send], and the status it answered with — for the routes where 200 and 202 differ. */
    private suspend fun exchange(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: String? = null,
        authorized: Boolean = true,
        readTimeoutMs: Int = 30_000,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        // A render holds this connection open for minutes, and reading from a socket
        // blocks in the kernel, where a cancelled coroutine cannot reach it. A child
        // suspended in `awaitCancellation` is cancelled at once, on another thread, and
        // closing the connection from there is what makes the read return.
        //
        // `invokeOnCompletion` is no good for this: a job that is cancelling but still
        // inside a blocking read has not *completed*, so the handler would run after
        // the read it was meant to interrupt. And the watcher has to exist before the
        // connection does — a child started in a scope that is already cancelled never
        // runs at all, which is what happens when the caller gives up while the request
        // is still being opened.
        val socket = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                socket.get()?.let(abandon)
            }
        }
        try {
            val connection = open(
                method, path, query, body, authorized, readTimeoutMs = readTimeoutMs,
            )
            socket.set(connection)
            // Given up on while it was connecting: do not start a read that nothing is
            // waiting for and that only a timeout would end.
            coroutineContext.ensureActive()
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            coroutineContext.ensureActive()
            TransportError.from(status, text, path)?.let { throw it }
            status to text
        } catch (error: IOException) {
            coroutineContext.ensureActive()
            throw TransportError.from(error, config.host)
        } finally {
            watcher.cancel()
            socket.get()?.disconnect()
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

    override suspend fun peerStatus(name: String): PeerNodeStatus {
        val path = "/swarm/peers/${pathComponent(name)}/status"
        return decode(send("GET", path, readTimeoutMs = 30_000), path)
    }

    // MARK: - The models the Mac keeps for this phone

    override suspend fun phoneModels(): PhoneModelList =
        decode(send("GET", "/ondevice/models", readTimeoutMs = 20_000), "/ondevice/models")

    override suspend fun preparePhoneModel(id: String, verify: Boolean): PhoneModelPreparation {
        val path = "/ondevice/models/${pathComponent(id)}/prepare"
        return try {
            // No body, but a length: `{}`, the way the agent verbs and `/unload` send one.
            val (status, text) = exchange(
                "POST", path,
                query = if (verify) mapOf("verify" to "1") else emptyMap(),
                body = "{}", readTimeoutMs = 60_000,
            )
            PhoneModelPreparation(decode(text, path), wasReady = status == 200)
        } catch (error: TransportError.RouteUnavailable) {
            // The list came from this Mac, so the route is there: a 404 is about the id.
            throw error.asNotFound()
        }
    }

    override suspend fun removePhoneModel(id: String): PhoneModel {
        val path = "/ondevice/models/${pathComponent(id)}"
        return try {
            decode(send("DELETE", path, readTimeoutMs = 60_000), path)
        } catch (error: TransportError.RouteUnavailable) {
            throw error.asNotFound()
        }
    }

    /**
     * Opens the file for reading from [from]. The caller reads the body and closes it; a
     * status other than 200 or 206 is thrown as the error it maps to — 409 while the Mac's
     * copy is not verified yet, 416 for a range past the end, 503 while its drive is gone.
     */
    override suspend fun openPhoneModelFile(
        id: String,
        from: Long,
        ifRange: String?,
    ): PhoneModelStream = withContext(Dispatchers.IO) {
        val path = "/ondevice/models/${pathComponent(id)}/file"
        val socket = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                socket.get()?.let(abandon)
            }
        }
        var handedOver = false
        try {
            // A read timeout rather than none: a Mac that stops sending mid-file is a
            // stalled download to resume later, not a notification stuck at 40% forever.
            val connection = open("GET", path, accept = "application/octet-stream", readTimeoutMs = 60_000)
                .also { socket.set(it) }
            // Byte ranges only, and never a compressed body: the offsets are the file's.
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (from > 0) {
                connection.setRequestProperty("Range", "bytes=$from-")
                ifRange?.let { connection.setRequestProperty("If-Range", "\"$it\"") }
            }
            coroutineContext.ensureActive()
            val status = connection.responseCode
            if (status != 200 && status != 206) {
                val text = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                val failure = when (val mapped = TransportError.from(status, text, path)) {
                    // The Mac listed this id; a 404 is about the model, not the route.
                    is TransportError.RouteUnavailable -> mapped.asNotFound()
                    null -> TransportError.Server(status, text)
                    else -> mapped
                }
                throw failure
            }
            val (start, total) = PhoneModelStream.parseContentRange(connection.getHeaderField("Content-Range"))
            val length = connection.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1L
            val digest = connection.getHeaderField("X-Content-SHA256")?.trim()?.lowercase()
                ?: PhoneModelStream.opaqueTag(connection.getHeaderField("ETag"))?.lowercase()
            val stream = PhoneModelStream(
                status = status,
                contentLength = length,
                rangeStart = if (status == 206) start else null,
                totalBytes = if (status == 206) total else length.takeIf { it >= 0 },
                sha256 = digest,
                body = connection.inputStream,
                release = { runCatching { connection.disconnect() } },
            )
            // Handed over: from here the caller reads it and closes it. The watcher below
            // lets go of it first, or its own cleanup would hang up on the caller.
            handedOver = true
            socket.set(null)
            stream
        } catch (error: IOException) {
            coroutineContext.ensureActive()
            throw TransportError.from(error, config.host)
        } finally {
            watcher.cancel()
            if (!handedOver) socket.get()?.disconnect()
        }
    }

    // MARK: - Agent sessions

    override suspend fun agentSessions(): AgentSessionList =
        decode(send("GET", "/agent/sessions", readTimeoutMs = 20_000), "/agent/sessions")

    override suspend fun agentSession(engine: String, since: Long?, epoch: String?): AgentSessionDetail {
        val path = "/agent/sessions/${pathComponent(engine)}"
        // Both halves or neither: a sequence number means nothing outside the transcript
        // that issued it.
        val query = if (since != null && epoch != null) {
            mapOf("since" to since.toString(), "epoch" to epoch)
        } else {
            emptyMap()
        }
        return decode(send("GET", path, query, readTimeoutMs = 30_000), path)
    }

    override suspend fun startAgent(engine: String): AgentSessionSummary =
        agentVerb(engine, "start")

    override suspend fun newAgentThread(engine: String): AgentSessionSummary =
        agentVerb(engine, "new")

    override suspend fun interruptAgent(engine: String): AgentSessionSummary =
        agentVerb(engine, "interrupt")

    override suspend fun stopAgent(engine: String): AgentSessionSummary {
        val path = "/agent/sessions/${pathComponent(engine)}"
        return decode(send("DELETE", path, readTimeoutMs = 30_000), path)
    }

    /**
     * `start`, `new` and `interrupt` take no body. An empty object goes anyway, the way
     * `POST /unload` sends one, so the request always carries a length.
     */
    private suspend fun agentVerb(engine: String, verb: String): AgentSessionSummary {
        val path = "/agent/sessions/${pathComponent(engine)}/$verb"
        return decode(send("POST", path, body = "{}", readTimeoutMs = 30_000), path)
    }

    override suspend fun sendAgentMessage(
        engine: String,
        request: AgentMessageRequest,
    ): AgentMessageAccepted {
        val path = "/agent/sessions/${pathComponent(engine)}/messages"
        return decode(
            send("POST", path, body = json.encodeToString(request), readTimeoutMs = 30_000),
            path,
        )
    }

    override suspend fun answerAgentApproval(
        engine: String,
        id: String,
        decision: String,
    ): AgentApprovalResult {
        val path = "/agent/sessions/${pathComponent(engine)}/approvals/${pathComponent(id)}"
        return try {
            decode(
                send(
                    "POST", path,
                    body = json.encodeToString(AgentApprovalDecision(decision)),
                    readTimeoutMs = 30_000,
                ),
                path,
            )
        } catch (error: TransportError.RouteUnavailable) {
            // The route exists on any Mac that listed this approval; a 404 is about the
            // approval — answered already, or gone with the engine — not about the Mac.
            throw error.asNotFound()
        }
    }

    /**
     * Sends the bytes themselves.
     *
     * Raw rather than multipart: the Mac reads both, and the type it records is the one
     * it reads off the first bytes either way, so the envelope buys nothing. The
     * filename rides in `X-Filename` because a name is worth keeping and is not worth
     * believing.
     */
    override suspend fun upload(
        bytes: ByteArray,
        contentType: String,
        filename: String?,
    ): UploadResponse = withContext(Dispatchers.IO) {
        if (bytes.size > Uploads.MAXIMUM_BYTES) {
            throw TransportError.TooLarge(
                "That picture is ${Format.megabytes(bytes.size.toLong())}. This Mac takes " +
                    "${Format.megabytes(Uploads.MAXIMUM_BYTES)} at most.",
            )
        }
        val socket = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                socket.get()?.let(abandon)
            }
        }
        var connection: HttpURLConnection? = null
        try {
            connection = open(
                "POST", "/uploads", readTimeoutMs = 120_000, contentType = contentType,
            ).also { socket.set(it) }
            filename?.let { connection.setRequestProperty("X-Filename", it) }
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.doOutput = true
            connection.outputStream.use { it.write(bytes) }
            coroutineContext.ensureActive()
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            TransportError.from(status, text, "/uploads")?.let { throw it }
            decode<UploadResponse>(text, "/uploads")
        } catch (error: IOException) {
            coroutineContext.ensureActive()
            throw TransportError.from(error, config.host)
        } finally {
            watcher.cancel()
            socket.get()?.disconnect()
        }
    }

    /**
     * Copies a result out of the Mac.
     *
     * The only route that answers bytes, so the only one that does not decode: it is
     * written straight into whatever the caller opened — a row in the phone's own
     * photo library, usually — because a clip is megabytes and holding it in memory to
     * hand it on would be a second copy for nothing.
     */
    override suspend fun media(id: String, sink: java.io.OutputStream): String? =
        withContext(Dispatchers.IO) {
            val path = "/media/${pathComponent(id)}"
            val socket = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    socket.get()?.let(abandon)
                }
            }
            var connection: HttpURLConnection? = null
            try {
                connection = open("GET", path, accept = "*/*", readTimeoutMs = 120_000)
                    .also { socket.set(it) }
                coroutineContext.ensureActive()
                val status = connection.responseCode
                if (status !in 200..299) {
                    val text = connection.errorStream?.bufferedReader()
                        ?.use(BufferedReader::readText).orEmpty()
                    throw TransportError.from(status, text, path)
                        ?: TransportError.Server(status, text)
                }
                val type = connection.contentType
                connection.inputStream.use { source ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                    }
                }
                sink.flush()
                type
            } catch (error: IOException) {
                coroutineContext.ensureActive()
                throw TransportError.from(error, config.host)
            } finally {
                watcher.cancel()
                socket.get()?.disconnect()
            }
        }

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
    ): Flow<ChatStreamEvent> = if (OnDeviceIds.isOnDevice(conversationID)) {
        // A conversation the phone answered itself never reaches the Mac by its id.
        kotlinx.coroutines.flow.flow { throw TransportError.Forbidden(OnDeviceIds.REFUSAL) }
    } else chatEvents(
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
                    "GET", "/events", null,
                    readTimeoutMs = SILENT_STREAM_MS, lastEventID = lastEventID,
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
                        // A frame this build cannot read is still a frame the sessions missed,
                        // and dropping it quietly would let the cursor walk past it: say it as
                        // a gap, so they fetch from before it.
                        "agent" -> emit(
                            runCatching { json.decodeFromString<AgentEvent>(event.data) }
                                .getOrNull()?.let { ServerEvent.Agent(it) }
                                ?: ServerEvent.Resync(null),
                        )
                        // The Mac dropped frames for this phone rather than wait for it.
                        // Said even when the body does not parse: the gap is the news.
                        "resync" -> emit(
                            ServerEvent.Resync(
                                runCatching { json.decodeFromString<ResyncEvent>(event.data) }
                                    .getOrNull()?.dropped,
                            ),
                        )
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
        coroutineScope {
            // A stream is a read that blocks in the kernel until the Mac says something —
            // a heartbeat, seconds away. Cancelled, it has to let go now: the watcher that
            // stops following when a turn ends, and the app leaving the screen, both mean
            // "hang up", not "hang up after the next heartbeat". So, as in `send`, a child
            // waits on cancellation and closes the connection from its own thread, which is
            // what makes the blocked read return. Not `invokeOnCompletion`: a job still
            // inside that read has not completed, so the handler would only run after it.
            val socket = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    socket.get()?.let(abandon)
                }
            }
            try {
                val connection = open(
                    method, path, body = body,
                    accept = "text/event-stream", readTimeoutMs = readTimeoutMs,
                )
                socket.set(connection)
                lastEventID?.let { connection.setRequestProperty("Last-Event-ID", it) }
                ensureActive()
                val status = connection.responseCode
                if (status !in 200..299) {
                    val text = connection.errorStream?.bufferedReader()
                        ?.use(BufferedReader::readText).orEmpty()
                    throw TransportError.from(status, text, path)
                        ?: TransportError.Server(status, text)
                }
                val parser = SseParser()
                readLines(connection.inputStream) { line ->
                    parser.consume(line)?.let { emit(it) }
                }
                parser.finish()?.let { emit(it) }
            } catch (error: IOException) {
                // Closed under it because it was given up on: that is the cancellation,
                // not a Mac that dropped the line.
                ensureActive()
                throw TransportError.from(error, config.host)
            } finally {
                watcher.cancel()
                socket.get()?.disconnect()
            }
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
        if (OnDeviceIds.isOnDevice(id)) throw TransportError.Forbidden(OnDeviceIds.REFUSAL)
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
