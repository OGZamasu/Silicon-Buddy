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
    /** What the owner granted. Absent on an early Mac, which means full. */
    val scope: String? = null,
)

/**
 * What a device may do. The Mac decides this at pairing; the app's job is to stop
 * offering what this device cannot have, rather than to let it be refused later.
 */
enum class DeviceScope {
    /** Everything: loading, installing, rendering, the device list. */
    Full,

    /** Reading and asking: status, metrics, catalog, chat and conversations. */
    Chat;

    val canControl: Boolean get() = this == Full

    val explanation: String
        get() = when (this) {
            Full -> "This device has full control of the Mac."
            Chat -> "This device is paired for chat only. Loading, installing and " +
                "rendering are hidden. Pair it again with full control from Settings, " +
                "Silicon Buddy on the Mac."
        }

    companion object {
        fun from(wire: String?): DeviceScope =
            entries.firstOrNull { it.name.equals(wire, ignoreCase = true) } ?: Full
    }
}

/** One row of `GET /buddy/devices`. Only the Mac's own token may read this. */
@Serializable
data class PairedDevice(
    val id: String,
    val name: String,
    val platform: String,
    val scope: String,
    val pairedAt: String,
    val lastSeen: String? = null,
    /**
     * True when this device's token predates something the Mac now requires and it has
     * to pair again.
     */
    val needsRepair: Boolean? = null,
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
    /**
     * The Mac's id for this message. What makes a `verdict` event land on the reply it
     * is about rather than on the newest one.
     */
    val id: String? = null,
    val role: String,
    val content: String,
    val createdAt: String,
    val images: List<String>? = null,
    val reasoning: String? = null,
    /** What the Mac's answer checking made of this reply, kept with it. */
    val verification: Verdict? = null,
)

@Serializable
data class ConversationDetail(
    val id: String,
    val title: String,
    val updatedAt: String,
    /**
     * True while the Mac is still answering the last message here. Sending another
     * would be answered 409, so the composer closes instead.
     */
    val isGenerating: Boolean = false,
    val messages: List<StoredMessage>,
)

/**
 * `POST /conversations/{id}/messages`. One message, not a transcript: the Mac already
 * has the history, which is the whole point of storing it there.
 */
@Serializable
data class NewMessageRequest(
    val content: String,
    val images: List<String> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)

@Serializable
data class ChatMetrics(
    val promptTokens: Int = 0,
    val generatedTokens: Int = 0,
    val tokensPerSecond: Double = 0.0,
    /** How long the Mac took to say anything at all. */
    val timeToFirstToken: Double? = null,
)

/** A `token` or `reasoning` event: one piece of text. */
@Serializable
data class TokenEvent(val text: String)

/**
 * What the Mac's answer checking made of a reply once it was written.
 *
 * It arrives after the answer — on `GET /events`, and sometimes on the chat stream too
 * — so it is never something to wait for: the reply is finished at `finished` and this
 * decorates it afterwards, or never.
 */
@Serializable
data class Verdict(
    val conversationID: String? = null,
    val messageID: String? = null,
    /**
     * The Mac's own word: `annotate`, `escalate`. Rendered, not interpreted: the
     * vocabulary is Jev's and it is not frozen.
     */
    val verdict: String,
    /** Why, in the Mac's sentences. */
    val reasons: List<String>? = null,
    /**
     * What to do about it, when the Mac has advice — usually which model to send the
     * question to instead. Absent on `/events`, present on the chat streams.
     */
    val suggestion: String? = null,
    /** The model the Mac handed it to instead, when it re-ran the answer itself. */
    val escalatedTo: String? = null,
) {
    /**
     * One line for the message footer. An unrecognised verdict is shown as itself
     * rather than swallowed, because the Mac will grow more of them.
     */
    val summary: String
        get() = when {
            !escalatedTo.isNullOrEmpty() -> "Checked — asked $escalatedTo instead"
            else -> when (verdict.lowercase()) {
                "ok", "pass", "answered" -> "Checked — answers the question"
                "annotate" -> "Checked — worth a second look"
                "escalate" -> "Checked — worth asking a stronger model"
                else -> "Checked — $verdict"
            }
        }
}

/**
 * What `POST /chat/stream` sends, one per SSE `event:` name.
 *
 * Not a closed set. The Mac grows event names — `verdict` arrived with Jev's answer
 * checking — and a client that treated an unknown one as an error would break on the
 * upgrade rather than on the downgrade. Anything not listed here is skipped, and
 * `finished` ends the reply whatever follows it.
 */
sealed interface ChatStreamEvent {
    data class Token(val text: String) : ChatStreamEvent
    data class Reasoning(val text: String) : ChatStreamEvent
    data class Finished(val metrics: ChatMetrics) : ChatStreamEvent
    data class Failed(val message: String) : ChatStreamEvent
}

/** A model coming down, as `GET /events` reports it. */
@Serializable
data class DownloadProgress(
    /** The model id, which is what the Models list matches rows on. */
    val id: String,
    val name: String,
    val bytesReceived: Long,
    val bytesExpected: Long? = null,
    val bytesPerSecond: Double? = null,
    /** The Mac's own 0–1, which knows about resumed downloads and this does not. */
    val fraction: Double? = null,
) {
    val progress: Double?
        get() = fraction?.coerceIn(0.0, 1.0)
            ?: bytesExpected?.takeIf { it > 0 }?.let {
                minOf(1.0, bytesReceived.toDouble() / it)
            }
}

/** A render in flight: video, image or mesh. */
@Serializable
data class JobProgress(
    val id: String,
    val kind: String,
    val status: String,
    val fraction: Double? = null,
    val title: String? = null,
)

/** The Mac's keep-alive, with its clock. */
@Serializable
data class Heartbeat(val at: String? = null)

/** `GET /events`: everything the Mac wants to push without being asked. */
sealed interface ServerEvent {
    data class StatusChanged(val status: Status) : ServerEvent
    data class Download(val progress: DownloadProgress) : ServerEvent
    data class Job(val progress: JobProgress) : ServerEvent

    /** An answer the Mac has since checked. */
    data class Checked(val verdict: Verdict) : ServerEvent
    data class Beat(val at: String?) : ServerEvent
}
