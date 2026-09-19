package dev.siliconoptimizer.buddy.chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.siliconoptimizer.buddy.transport.ChatMetrics
import dev.siliconoptimizer.buddy.transport.ChatRequest
import dev.siliconoptimizer.buddy.transport.ChatStreamEvent
import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The chat screen's state and the one piece of logic worth testing here: how a reply
 * arrives, whether it streams or not.
 */
class ChatViewModel(
    application: Application,
    private val store: ConversationStore,
) : AndroidViewModel(application) {

    /**
     * The constructor the default ViewModel factory looks for. A default argument would
     * not do: the factory reflects for exactly `(Application)` and a synthesised
     * constructor is not it — which crashed the app on its first launch.
     */
    constructor(application: Application) : this(application, ConversationStore(application))

    val conversations = mutableStateListOf<Conversation>()
    var current by mutableStateOf<Conversation?>(null)
        private set
    var isSending by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var draft by mutableStateOf("")
    val attachments = mutableStateListOf<String>()

    /** When the current reply was asked for, so the transcript can say how long. */
    var sendingSince by mutableStateOf<Long?>(null)
        private set

    /** Whether the Mac answered `/chat/stream` and `/conversations`, learned by asking. */
    var usesStreaming by mutableStateOf(true)
        private set
    var usesRemoteConversations by mutableStateOf(false)
        private set
    private var askedAboutConversations = false

    /** How much history to send when the phone is the one keeping it. */
    var historyLimit = 24

    /**
     * A ceiling on the answer. Without one, a reasoning model asked a small question can
     * think for ten minutes and the phone shows a spinner the whole time.
     */
    var maxTokens = 2048

    private var sendJob: Job? = null

    // MARK: - Conversations

    fun loadConversations(transport: ControlTransport?) {
        viewModelScope.launch { loadConversationsNow(transport) }
    }

    private suspend fun loadConversationsNow(transport: ControlTransport?) {
            if (transport != null && (usesRemoteConversations || !askedAboutConversations)) {
                askedAboutConversations = true
                try {
                    val remote = transport.conversations()
                    usesRemoteConversations = true
                    conversations.clear()
                    conversations.addAll(
                        remote.map {
                            Conversation(
                                id = it.id,
                                title = it.title,
                                updatedAt = System.currentTimeMillis(),
                                remoteMessageCount = it.messageCount,
                            )
                        },
                    )
                    return
                } catch (failure: TransportError) {
                    if (failure.isMissingRoute) {
                        // This Mac has no /conversations at all; the device keeps them.
                        usesRemoteConversations = false
                    } else {
                        // A timeout, a dropped tailnet, a Mac mid-restart. The Mac still
                        // owns these conversations — moving them to the device over a bad
                        // minute would fork the transcript, and nothing would merge it back.
                        error = failure.message
                        if (usesRemoteConversations) return
                    }
                }
            }
            val stored = store.all()
            conversations.clear()
            conversations.addAll(stored)
            // A conversation started but not yet sent to is not in the store.
            current?.let { open ->
                if (conversations.none { it.id == open.id }) conversations.add(0, open)
            }
    }

    fun newConversation(transport: ControlTransport? = null) {
        if (usesRemoteConversations && transport != null) {
            viewModelScope.launch {
                try {
                    val created = transport.createConversation(null)
                    current = Conversation(id = created.id, title = created.title)
                    loadConversationsNow(transport)
                } catch (failure: TransportError) {
                    if (failure.isMissingRoute) {
                        usesRemoteConversations = false
                        startLocalConversation()
                    } else {
                        // The Mac keeps these; a failure now is a failure to say so.
                        error = failure.message
                    }
                }
            }
            return
        }
        startLocalConversation()
    }

    private fun startLocalConversation() {
        val fresh = Conversation()
        current = fresh
        conversations.add(0, fresh)
    }

    fun open(id: String, transport: ControlTransport?) {
        viewModelScope.launch {
            if (usesRemoteConversations && transport != null) {
                try {
                    val detail = transport.conversation(id)
                    isConversationBusy = detail.isGenerating
                    current = Conversation(
                        id = detail.id,
                        title = detail.title,
                        messages = detail.messages.map {
                            ChatMessage(
                                role = it.role,
                                content = it.content,
                                reasoning = it.reasoning,
                                images = it.images.orEmpty(),
                            )
                        },
                    )
                    return@launch
                } catch (failure: TransportError) {
                    if (failure.isMissingRoute) {
                        // No /conversations on this Mac at all.
                        usesRemoteConversations = false
                    } else {
                        // Including "no such conversation": the Mac still owns the rest.
                        error = failure.message
                        conversations.removeAll { it.id == id }
                        current = null
                        return@launch
                    }
                }
            }
            current = store.conversation(id) ?: conversations.firstOrNull { it.id == id }
                    ?: Conversation(id = id)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            store.delete(id)
            conversations.removeAll { it.id == id }
            if (current?.id == id) current = null
        }
    }

    // MARK: - Sending

    fun send(transport: ControlTransport?) {
        val text = draft.trim()
        if (text.isEmpty() && attachments.isEmpty()) return
        if (transport == null) {
            error = TransportError.NotConfigured.message
            return
        }
        SendLimits.problem(attachments, text)?.let {
            error = it
            return
        }
        val images = attachments.toList()
        draft = ""
        attachments.clear()

        var conversation = current ?: Conversation()
        val placeholderID = java.util.UUID.randomUUID().toString()
        conversation = conversation.copy(
            messages = conversation.messages +
                ChatMessage(role = ChatMessage.ROLE_USER, content = text, images = images) +
                ChatMessage(
                    id = placeholderID,
                    role = ChatMessage.ROLE_ASSISTANT,
                    content = "",
                    isStreaming = true,
                ),
        ).titledFromFirstMessage()
        current = conversation
        isSending = true
        sendingSince = System.currentTimeMillis()
        isConversationBusy = false
        error = null

        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            run(placeholderID, transport)
            isSending = false
            sendingSince = null
        }
    }

    fun cancel() {
        sendJob?.cancel()
        sendJob = null
        isSending = false
        sendingSince = null
        finishStreaming("Stopped.")
    }

    private enum class Outcome { Answered, MissingRoute, Busy, Failed, Stopped }

    /** True while the Mac is answering the open conversation, ours or anyone's. */
    var isConversationBusy by mutableStateOf(false)
        private set

    private suspend fun run(messageID: String, transport: ControlTransport) {
        val history = (current?.messages ?: emptyList())
            .filter { it.role != ChatMessage.ROLE_ASSISTANT || it.content.isNotEmpty() }
            .takeLast(historyLimit)
            .map { it.wire }
        val request = ChatRequest(history, maxTokens = maxTokens)

        if (usesStreaming) {
            // First choice: the conversation route, so the Mac keeps the transcript.
            val id = current?.id
            val last = history.lastOrNull()
            if (usesRemoteConversations && id != null && last != null) {
                when (consume(transport.sendMessage(id, last, maxTokens), messageID)) {
                    Outcome.Answered -> {
                        finishStreaming(null); persist(transport); return
                    }
                    // Only this route is missing; plain streaming may still be there.
                    Outcome.MissingRoute -> usesRemoteConversations = false
                    Outcome.Busy -> {
                        // The Mac is still answering the previous message here. Sending
                        // it again would only be refused again.
                        isConversationBusy = true
                        persist(transport); return
                    }
                    Outcome.Stopped -> {
                        finishStreaming("Stopped."); return
                    }
                    Outcome.Failed -> {
                        persist(transport); return
                    }
                }
            }

            // Second: streaming without a stored conversation.
            when (consume(transport.chatStream(request), messageID)) {
                Outcome.Answered -> {
                    finishStreaming(null); persist(transport); return
                }
                Outcome.MissingRoute -> usesStreaming = false
                Outcome.Busy -> {
                    isConversationBusy = true
                    persist(transport); return
                }
                Outcome.Stopped -> {
                    finishStreaming("Stopped."); return
                }
                Outcome.Failed -> {
                    persist(transport); return
                }
            }
        }

        // Last: one request, one answer. Every Mac has this.
        try {
            val response = transport.chat(request)
            update(messageID) {
                it.copy(
                    content = response.content,
                    reasoning = response.reasoning,
                    metrics = ChatMetrics(
                        response.promptTokens,
                        response.generatedTokens,
                        response.tokensPerSecond,
                    ),
                    isStreaming = false,
                    failure = truncationNote(response.content, response.generatedTokens, maxTokens),
                )
            }
        } catch (failure: Exception) {
            val description = failure.message ?: "The Mac didn't answer."
            finishStreaming(description)
            error = description
        }
        persist(transport)
    }

    /** Drains one SSE stream into the placeholder message. */
    private suspend fun consume(stream: Flow<ChatStreamEvent>, messageID: String): Outcome {
        var sawAnything = false
        try {
            stream.collect { event ->
                sawAnything = true
                when (event) {
                    is ChatStreamEvent.Token -> update(messageID) {
                        it.copy(content = it.content + event.text)
                    }
                    is ChatStreamEvent.Reasoning -> update(messageID) {
                        it.copy(reasoning = (it.reasoning ?: "") + event.text)
                    }
                    is ChatStreamEvent.Finished -> update(messageID) {
                        it.copy(
                            metrics = event.metrics,
                            isStreaming = false,
                            failure = truncationNote(
                                it.content, event.metrics.generatedTokens, maxTokens,
                            ),
                        )
                    }
                    is ChatStreamEvent.Failed -> {
                        update(messageID) { it.copy(failure = event.message, isStreaming = false) }
                        error = event.message
                    }
                }
            }
        } catch (failure: TransportError) {
            if (failure.isMissingRoute) return Outcome.MissingRoute
            if (failure is TransportError.Cancelled) return Outcome.Stopped
            if (failure is TransportError.Conflict) {
                finishStreaming(failure.message)
                error = failure.message
                return Outcome.Busy
            }
            finishStreaming(failure.message)
            error = failure.message
            return Outcome.Failed
        } catch (failure: kotlinx.coroutines.CancellationException) {
            return Outcome.Stopped
        } catch (failure: Exception) {
            finishStreaming(failure.message)
            error = failure.message
            return Outcome.Failed
        }
        // A stream that ends without one event is not an answer; try the next thing.
        return if (sawAnything) Outcome.Answered else Outcome.MissingRoute
    }

    private fun update(messageID: String, change: (ChatMessage) -> ChatMessage) {
        val conversation = current ?: return
        current = conversation.copy(
            messages = conversation.messages.map { if (it.id == messageID) change(it) else it },
        )
    }

    private fun finishStreaming(failure: String?) {
        val conversation = current ?: return
        val index = conversation.messages.indexOfLast { it.isStreaming }
        if (index < 0) return
        val message = conversation.messages[index]
        val updated = message.copy(
            isStreaming = false,
            failure = if (failure != null && message.content.isEmpty()) failure else message.failure,
        )
        current = conversation.copy(
            messages = conversation.messages.toMutableList().also { it[index] = updated },
        )
    }

    private suspend fun persist(transport: ControlTransport? = null) {
        if (usesRemoteConversations) {
            // The Mac keeps the transcript, so the list it publishes is the one worth
            // showing: the title and the count are its answers, not ours.
            loadConversationsNow(transport)
            return
        }
        val conversation = current ?: return
        val saved = store.save(conversation)
        current = saved
        val index = conversations.indexOfFirst { it.id == saved.id }
        if (index >= 0) conversations[index] = saved else conversations.add(0, saved)
    }

    fun clearError() {
        error = null
    }

    /**
     * Called when the paired Mac changes. What this Mac supports is a fact about that
     * Mac, and so is its transcript: neither survives a re-pair.
     */
    fun macChanged() {
        sendJob?.cancel()
        sendJob = null
        isSending = false
        sendingSince = null
        isConversationBusy = false
        usesStreaming = true
        usesRemoteConversations = false
        askedAboutConversations = false
        conversations.clear()
        current = null
        error = null
    }

    /** Where the transcript came from, said plainly so nobody wonders. */
    val storageNote: String
        get() = if (usesRemoteConversations) {
            "Synced with the Mac."
        } else {
            "Kept on this device — the Mac doesn't store conversations yet."
        }

    /** Adds a picture, or says why it cannot be added. The cap is the Mac's. */
    fun attach(dataUrl: String) {
        if (attachments.size >= SendLimits.MAX_ATTACHMENTS) {
            error = "The Mac takes at most ${SendLimits.MAX_ATTACHMENTS} pictures a message."
            return
        }
        if (SendLimits.encodedBytes(dataUrl) > SendLimits.MAX_IMAGE_BYTES) {
            error = "That picture is still too large after shrinking. Try a smaller one."
            return
        }
        attachments.add(dataUrl)
    }

    companion object {
        /**
         * A reasoning model can spend its whole budget thinking and answer nothing. An
         * empty bubble would look like a bug; saying what happened is the honest version.
         */
        fun truncationNote(content: String, generatedTokens: Int, limit: Int): String? =
            if (content.isEmpty() && generatedTokens >= limit) {
                "The model spent all $limit tokens thinking and never got to an answer. " +
                    "Ask again more narrowly, or load a model that thinks less."
            } else {
                null
            }
    }
}
