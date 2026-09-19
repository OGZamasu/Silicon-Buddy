package dev.siliconoptimizer.buddy.chat

import android.content.Context
import dev.siliconoptimizer.buddy.transport.ChatMessageWire
import dev.siliconoptimizer.buddy.transport.ChatMetrics
import dev.siliconoptimizer.buddy.transport.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    /** The model's thinking, shown collapsed. */
    val reasoning: String? = null,
    /** Base64 `data:` URLs sent with the message. */
    val images: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** Set while a streamed reply is still arriving. */
    val isStreaming: Boolean = false,
    /** The Mac's own numbers for this reply. */
    val metrics: ChatMetrics? = null,
    /** Set when the generation failed, with the Mac's words. */
    val failure: String? = null,
    /**
     * What the Mac's answer checking made of this reply, if it checked it. It arrives
     * after the answer is finished and is never waited for.
     */
    val verdict: Verdict? = null,
    /**
     * Who wrote this reply when it was not the Mac: `phone:<model id>`. Set on every answer
     * the phone's own model wrote — it is what the chip under the bubble reads — and never on
     * one from the Mac.
     */
    val origin: String? = null,
    /** The phone model's name when it wrote this, so the chip outlives the model. */
    val originLabel: String? = null,
) {
    val wire: ChatMessageWire get() = ChatMessageWire(role, content, images)

    /** Written by the phone's own model rather than the Mac. */
    val isFromPhone: Boolean get() = origin?.startsWith(PHONE_ORIGIN) == true

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_SYSTEM = "system"

        /** The prefix of [origin] for an answer the phone wrote: `phone:qwen3.5-2b-q4_0`. */
        const val PHONE_ORIGIN = "phone:"
    }
}

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = DEFAULT_TITLE,
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
    /** What the Mac says, for a conversation it keeps and this device has not opened. */
    val remoteMessageCount: Int? = null,
    /**
     * Answered by the phone's own model, and kept only on the phone. Its id starts with
     * `OnDeviceIds.PREFIX`, and nothing that talks to the Mac will take it.
     */
    val onDevice: Boolean = false,
    /** Which of the phone's models answers here. */
    val phoneModelID: String? = null,
) {
    val messageCount: Int
        get() = if (messages.isEmpty() && remoteMessageCount != null) {
            remoteMessageCount
        } else {
            messages.count { it.role != ChatMessage.ROLE_SYSTEM }
        }

    /** The first thing the person said makes a better title than "New conversation". */
    fun titledFromFirstMessage(): Conversation {
        if (title != DEFAULT_TITLE) return this
        val first = messages.firstOrNull { it.role == ChatMessage.ROLE_USER }?.content?.trim()
        if (first.isNullOrEmpty()) return this
        val short = first.take(48) + if (first.length > 48) "…" else ""
        return copy(title = short)
    }

    companion object {
        const val DEFAULT_TITLE = "New conversation"
    }
}

/**
 * Conversations on the device.
 *
 * This is the fallback for a Mac without `/conversations`, and it is also what makes
 * the app usable on a train: the transcript is here, not only there. One JSON file,
 * because a handful of conversations is not a database.
 */
class ConversationStore(val directory: File) {

    constructor(context: Context) : this(context.filesDir)

    private val file = File(directory, "conversations.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun all(): List<Conversation> = withContext(Dispatchers.IO) { read() }

    suspend fun conversation(id: String): Conversation? =
        withContext(Dispatchers.IO) { read().firstOrNull { it.id == id } }

    suspend fun save(conversation: Conversation): Conversation = withContext(Dispatchers.IO) {
        val updated = conversation.titledFromFirstMessage()
            .copy(updatedAt = System.currentTimeMillis())
        val others = read().filterNot { it.id == updated.id }
        write((others + updated).sortedByDescending { it.updatedAt })
        updated
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        write(read().filterNot { it.id == id })
    }

    private fun read(): List<Conversation> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<Conversation>>(file.readText())
                .sortedByDescending { it.updatedAt }
        }.getOrElse { emptyList() }
    }

    private fun write(conversations: List<Conversation>) {
        runCatching {
            directory.mkdirs()
            file.writeText(json.encodeToString(conversations))
        }
    }
}
