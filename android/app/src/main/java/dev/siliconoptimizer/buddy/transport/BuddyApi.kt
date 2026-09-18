package dev.siliconoptimizer.buddy.transport

import kotlinx.serialization.Serializable

/**
 * The routes the Mac grows in M0, which this app already speaks.
 *
 * Every one is optional at runtime: a Mac without them answers 404 and the client falls
 * back — streaming to plain `POST /chat`, conversations to the on-device store, pairing
 * to the advanced form.
 */
@Serializable
data class PairRequest(
    val code: String,
    val deviceName: String,
    val platform: String,
)

@Serializable
data class PairResponse(
    val deviceID: String,
    val token: String,
    val macName: String,
    val port: Int,
)

@Serializable
data class ConversationSummary(
    val id: String,
    val title: String,
    /** ISO-8601 on the wire; kept as text because only ordering and display need it. */
    val updatedAt: String,
    val messageCount: Int,
)

@Serializable
data class NewConversation(val title: String? = null)

@Serializable
data class StoredMessage(
    val role: String,
    val content: String,
    val createdAt: String,
    val images: List<String>? = null,
    val reasoning: String? = null,
)

@Serializable
data class ConversationDetail(
    val id: String,
    val title: String,
    val messages: List<StoredMessage>,
)

@Serializable
data class ChatMetrics(
    val promptTokens: Int = 0,
    val generatedTokens: Int = 0,
    val tokensPerSecond: Double = 0.0,
)

/** What `POST /chat/stream` sends, one per SSE `event:` name. */
sealed interface ChatStreamEvent {
    data class Token(val text: String) : ChatStreamEvent
    data class Reasoning(val text: String) : ChatStreamEvent
    data class Finished(val metrics: ChatMetrics) : ChatStreamEvent
    data class Failed(val message: String) : ChatStreamEvent
}

@Serializable
data class DownloadProgress(
    val modelID: String,
    val receivedBytes: Long,
    val totalBytes: Long? = null,
    val state: String,
    val detail: String? = null,
) {
    val fraction: Double?
        get() = totalBytes?.takeIf { it > 0 }?.let {
            minOf(1.0, receivedBytes.toDouble() / it)
        }
}

@Serializable
data class JobProgress(
    val id: String,
    val kind: String,
    val state: String,
    val progress: Double? = null,
    val detail: String? = null,
)

/** `GET /events`: everything the Mac wants to push without being asked. */
sealed interface ServerEvent {
    data class StatusChanged(val status: Status) : ServerEvent
    data class Download(val progress: DownloadProgress) : ServerEvent
    data class Job(val progress: JobProgress) : ServerEvent
    data object Heartbeat : ServerEvent
}
