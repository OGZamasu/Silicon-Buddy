package dev.siliconoptimizer.buddy.ondevice

import dev.siliconoptimizer.buddy.transport.PhoneModel
import dev.siliconoptimizer.buddy.ui.Format
import java.util.Locale

/**
 * Every sentence the on-device model says about itself, in one place, so the chat, the
 * tile, the widget and Settings say the same thing the same way.
 */
object OnDeviceNotices {

    const val MAC_NOT_ANSWERING = "Your Mac isn't answering."
    const val ANSWER_ON_PHONE = "Answer on this phone"
    const val TRY_AGAIN = "Try again"

    /** The chip on every answer the phone wrote. */
    fun chip(label: String) = "On this phone · $label"

    /** The conversation list's section for conversations that never reached the Mac. */
    const val SECTION = "On this phone — not synced"

    const val TOO_HOT = "Stopped — phone too hot"
    const val TOO_HOT_TO_START =
        "Your phone is too hot to answer right now. Let it cool down, then try again."
    const val LEFT_APP = "Stopped when you left the app."
    const val STOPPED = "Stopped."
    const val UNLOADED_FOR_MEMORY = "Stopped — Android needed the memory back."

    /** The tile's second line, and the widget's, when the phone can stand in. */
    const val TILE_READY = "Mac unreachable · phone model ready"
    const val MAC_UNREACHABLE = "Mac unreachable"
    const val ASK_ON_PHONE = "Ask on this phone"

    const val MAC_IS_BACK = "Your Mac is back."
    const val NEW_MAC_CONVERSATION = "New Mac conversation"
    const val SEND_TO_MAC = "Send to Mac…"

    /** What "Send to Mac…" asks before anything leaves the phone. */
    fun sendToMacQuestion(messages: Int, macName: String): String =
        "Send ${if (messages == 1) "this message" else "these $messages messages"} to $macName? " +
            "They start a new conversation there, and your Mac answers from where this one ends. " +
            "The copy on this phone stays here."

    /** A chat-only phone asking for a model: the Mac's own sentence says how to fix it. */
    fun chatScope(macSentence: String?): String =
        "This phone is paired for chat only, so your Mac won't send it a model of its own." +
            (macSentence?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")

    const val CHAT_SCOPE_SETTINGS =
        "This phone is paired for chat only, so your Mac won't send it a model of its own. " +
            "Pair it again with full control from Settings → Silicon Buddy on the Mac."

    const val NO_MODEL_YET =
        "No model on this phone yet. While your Mac is reachable, get one in Settings → On this phone."

    /** What a model is, in the consent dialog and the Settings row. */
    fun summary(model: PhoneModel): String = buildString {
        append(Format.bytes(model.sizeBytes))
        append(" · ")
        append(model.licence)
        if (model.isDefault) append(" · recommended")
        if (model.slowerOnPhone) append(" · slower on phones")
    }

    /** What a real phone measured, said once, plainly. */
    fun expectation(model: PhoneModel): String? {
        val measured = model.measured ?: return null
        val first = String.format(Locale.US, "%.1f", measured.secondsToFirstWord300)
        val speed = String.format(Locale.US, "%.0f", measured.tokensPerSecond)
        val sustained = measured.sustainedTokensPerSecond?.let {
            String.format(Locale.US, ", settling to about %.0f on long answers", it)
        } ?: ""
        return "On a ${measured.device}: about $first s to the first word of a long question, " +
            "then $speed tokens a second$sustained."
    }
}
