package dev.siliconoptimizer.buddy.transport

/** One server-sent event: a name, a body, and an optional id. */
data class SseEvent(
    /** The `event:` field, or "message" when the stream did not name one. */
    val name: String = "message",
    /** The `data:` fields joined with newlines, as the spec requires. */
    val data: String,
    val id: String? = null,
    /** A `retry:` field, in milliseconds. */
    val retry: Int? = null,
)

/**
 * A line-at-a-time parser for `text/event-stream`.
 *
 * Written as something that takes lines rather than something that owns a socket,
 * because the interesting cases — multi-line data, comments, a stream that ends
 * mid-event — are worth testing without a server, and because the same parser then
 * serves both `/chat/stream` and `/events`.
 */
class SseParser {
    private var name: String? = null
    private val data = mutableListOf<String>()
    private var id: String? = null
    private var retry: Int? = null
    private val pending = StringBuilder()

    /** Comment lines (`: heartbeat`) seen so far: proof a quiet stream is alive. */
    var sawComment: Boolean = false
        private set

    /** Feeds one line without its terminator; returns an event on the blank line. */
    fun consume(rawLine: String): SseEvent? {
        val line = rawLine.removeSuffix("\r")

        if (line.isEmpty()) {
            // A block with no data fields dispatches nothing, per the spec.
            if (data.isEmpty()) {
                reset()
                return null
            }
            val event = SseEvent(name ?: "message", data.joinToString("\n"), id, retry)
            reset()
            return event
        }

        if (line.startsWith(":")) {
            sawComment = true
            return null
        }

        val colon = line.indexOf(':')
        val field: String
        var value: String
        if (colon >= 0) {
            field = line.substring(0, colon)
            value = line.substring(colon + 1)
            // Exactly one leading space is stripped.
            if (value.startsWith(" ")) value = value.substring(1)
        } else {
            field = line
            value = ""
        }

        when (field) {
            "event" -> name = value
            "data" -> data.add(value)
            "id" -> if (value.none { it.code == 0 }) id = value
            "retry" -> value.toIntOrNull()?.let { retry = it }
            else -> Unit // Unknown fields are ignored, not an error.
        }
        return null
    }

    /** Feeds a chunk that may hold any number of lines, keeping a partial one back. */
    fun consumeChunk(chunk: String): List<SseEvent> {
        pending.append(chunk)
        val events = mutableListOf<SseEvent>()
        while (true) {
            val breakIndex = pending.indexOf("\n")
            if (breakIndex < 0) break
            val line = pending.substring(0, breakIndex)
            pending.delete(0, breakIndex + 1)
            consume(line)?.let { events.add(it) }
        }
        return events
    }

    /** Whatever is left when the stream ends, if the server hung up after the data. */
    fun finish(): SseEvent? {
        if (pending.isNotEmpty()) {
            val line = pending.toString()
            pending.clear()
            consume(line)?.let { return it }
        }
        if (data.isEmpty()) return null
        val event = SseEvent(name ?: "message", data.joinToString("\n"), id, retry)
        reset()
        return event
    }

    private fun reset() {
        name = null
        data.clear()
        // `id` and `retry` persist across events, as the spec says.
    }
}
