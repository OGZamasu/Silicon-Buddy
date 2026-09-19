package dev.siliconoptimizer.buddy.reach

import dev.siliconoptimizer.buddy.transport.ChatMessageWire
import dev.siliconoptimizer.buddy.transport.ChatRequest
import dev.siliconoptimizer.buddy.transport.ChatStreamEvent
import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.flow.Flow

/**
 * One question, one answer, from somewhere that has no chat screen.
 *
 * The share target, a widget's button and an Assistant shortcut all want the same
 * thing: ask the Mac once and get text back. What they must not do is invent a second
 * way of talking to it, so this is the one — and it prefers the conversation route,
 * because a question asked from a share sheet belongs in the same history as one asked
 * in the app. A Mac that does not store conversations gets the plain route instead, and
 * the outcome says which happened.
 */
object OneShotAsk {

    data class Outcome(
        val answer: String,
        /** The Mac's conversation this was filed under, when it filed it. */
        val conversationID: String? = null,
        /**
         * False when this Mac has no `/conversations` and the exchange lives nowhere
         * but the screen it was asked from.
         */
        val storedOnMac: Boolean = false,
    )

    class Failure(message: String) : Exception(message)

    /**
     * Asks, streaming when the Mac streams, and returns the whole answer. [onToken] is
     * called as text arrives so a sheet can show the answer being written rather than a
     * spinner; it is never required.
     */
    suspend fun send(
        message: String,
        images: List<String> = emptyList(),
        title: String? = null,
        maxTokens: Int = 1024,
        transport: ControlTransport?,
        snapshots: SnapshotStore? = null,
        onToken: ((String) -> Unit)? = null,
    ): Outcome {
        if (transport == null) {
            throw Failure(
                "Silicon Buddy isn't paired with a Mac. Open the app and pair first.",
            )
        }
        val text = message.trim()
        if (text.isEmpty() && images.isEmpty()) throw IntentMapping.Refusal.EmptyPrompt

        // First choice: a conversation on the Mac, so this turns up in the app's list
        // and on the Mac's own screen rather than only here.
        val conversation = runCatching { transport.createConversation(title) }.getOrNull()
        if (conversation != null) {
            val answer = drain(
                transport.sendMessage(conversation.id, ChatMessageWire("user", text, images), maxTokens),
                onToken,
            )
            if (answer != null) {
                snapshots?.note(text, answer)
                return Outcome(answer, conversation.id, storedOnMac = true)
            }
        }

        val request = ChatRequest(
            messages = listOf(ChatMessageWire("user", text, images)),
            maxTokens = maxTokens,
        )
        drain(transport.chatStream(request), onToken)?.let {
            snapshots?.note(text, it)
            return Outcome(it)
        }

        // Last: one request, one answer. Every Mac has this.
        try {
            val answer = transport.chat(request).content.trim()
            if (answer.isEmpty()) throw Failure("Your Mac answered with nothing.")
            onToken?.invoke(answer)
            snapshots?.note(text, answer)
            return Outcome(answer)
        } catch (error: TransportError) {
            throw Failure(error.message ?: "Your Mac refused the request.")
        }
    }

    /**
     * Reads a stream to its end and returns the text, or null when the route was not
     * there at all. `finished` ends the answer: a `verdict` frame after it is a
     * decoration, never something to wait for.
     */
    private suspend fun drain(
        stream: Flow<ChatStreamEvent>,
        onToken: ((String) -> Unit)?,
    ): String? {
        val answer = StringBuilder()
        var sawAnything = false
        var finished = false
        try {
            stream.collect { event ->
                sawAnything = true
                when (event) {
                    is ChatStreamEvent.Token -> {
                        answer.append(event.text)
                        onToken?.invoke(event.text)
                    }
                    is ChatStreamEvent.Finished -> {
                        finished = true
                        throw Done
                    }
                    is ChatStreamEvent.Failed -> throw Failure(event.message)
                    is ChatStreamEvent.Reasoning -> Unit
                }
            }
        } catch (stop: Done) {
            return answer.toString().ifEmpty { null }
        } catch (error: TransportError) {
            if (error.isMissingRoute) return null
            throw Failure(error.message ?: "Your Mac refused the request.")
        }
        if (!sawAnything && !finished) return null
        return answer.toString().ifEmpty { null }
    }

    /** Thrown to leave `collect` the moment `finished` arrives; a flow has no `break`. */
    private object Done : kotlinx.coroutines.CancellationException("finished")
}
