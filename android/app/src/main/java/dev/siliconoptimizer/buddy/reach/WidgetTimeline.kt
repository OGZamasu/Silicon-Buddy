package dev.siliconoptimizer.buddy.reach

import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a widget draws, and where it came from.
 *
 * A widget is not a screen: it gets a few seconds of background time at a cadence the
 * system decides, and it has to show something the instant it is placed. So the
 * snapshot the app left behind is the answer, and a refresh improves it. A Mac that is
 * asleep, off the tailnet or simply not paired is a state worth drawing, not an error
 * to swallow — a widget has nowhere to put an exception.
 */
data class BuddyWidgetEntry(
    val snapshot: BuddySnapshot? = null,
    val isPaired: Boolean = false,
    /** One line explaining why there is nothing newer, when there is a reason. */
    val problem: String? = null,
    /** The preset question this widget's button fires. */
    val quickPrompt: String = QuickPrompt.DEFAULT,
    /** The answer to that preset, when the widget has run it. */
    val quickAnswer: String? = null,
    /** The Mac did not answer the widget's own look just now. */
    val macUnreachable: Boolean = false,
    /** A verified model is on this phone, and this phone can run it. */
    val phoneReady: Boolean = false,
) {
    /** The model line, or the reason there isn't one. */
    val headline: String
        get() = if (!isPaired) "Not paired" else snapshot?.modelLine ?: "Ask your Mac"

    /**
     * With the Mac out of reach and a model on the phone: say so, and offer "Ask on this
     * phone" — which opens the app with the offer showing. The widget never answers on the
     * phone by itself.
     */
    val offersPhone: Boolean get() = isPaired && macUnreachable && phoneReady

    val statusLine: String?
        get() = when {
            offersPhone -> dev.siliconoptimizer.buddy.ondevice.OnDeviceNotices.TILE_READY
            macUnreachable -> dev.siliconoptimizer.buddy.ondevice.OnDeviceNotices.MAC_UNREACHABLE
            else -> null
        }

    /** "On this phone · Qwen3.5 2B" over an answer the phone wrote; nothing over the Mac's. */
    val answerLabel: String?
        get() = if (quickAnswer == null) {
            snapshot?.lastAnswerOnPhone?.let { dev.siliconoptimizer.buddy.ondevice.OnDeviceNotices.chip(it) }
        } else {
            null
        }

    /**
     * What the body of the widget shows: the preset's answer if it has one, otherwise
     * the last thing the Mac said anywhere.
     */
    fun body(limit: Int): String? =
        quickAnswer?.let { BuddySnapshot.trim(it, limit) } ?: snapshot?.answerPreview(limit)
}

/** What a widget's button got back: an answer, or a sentence about why not. */
sealed interface AskResult {
    /** The words to put on the widget either way — an answer is not a special case. */
    val text: String

    data class Answer(override val text: String) : AskResult
    data class Problem(override val text: String) : AskResult
}

/** Everything a widget does about content, with the transport handed in. */
object WidgetTimeline {

    /** One entry: the stored snapshot, improved by asking the Mac if it answers. */
    suspend fun entry(
        transport: ControlTransport?,
        stored: BuddySnapshot?,
        quickPrompt: String,
        quickAnswer: String? = null,
        now: Long = System.currentTimeMillis(),
        phoneReady: Boolean = false,
    ): BuddyWidgetEntry {
        if (transport == null) {
            return BuddyWidgetEntry(
                snapshot = stored,
                isPaired = false,
                problem = "Open Silicon Buddy to pair with your Mac.",
                quickPrompt = quickPrompt,
                quickAnswer = quickAnswer,
            )
        }
        return try {
            val status = withTimeoutOrNull(QuickPrompt.TIMEOUT_MS) { transport.status() }
                ?: return BuddyWidgetEntry(
                    snapshot = stored,
                    isPaired = true,
                    problem = TOO_SLOW,
                    quickPrompt = quickPrompt,
                    quickAnswer = quickAnswer,
                    macUnreachable = true,
                    phoneReady = phoneReady,
                )
            BuddyWidgetEntry(
                snapshot = (stored ?: BuddySnapshot()).copy(
                    state = status.state,
                    loadedModelID = status.loadedModelID,
                    loadedModelName = status.loadedModelName,
                    updatedAt = now,
                ),
                isPaired = true,
                quickPrompt = quickPrompt,
                quickAnswer = quickAnswer,
            )
        } catch (error: Exception) {
            // The last snapshot is still the truth as far as anybody knows; it is just
            // older than it was. Saying when it was taken is the honest version of a
            // widget that cannot reach the Mac.
            BuddyWidgetEntry(
                snapshot = stored,
                isPaired = true,
                problem = problem(error),
                quickPrompt = quickPrompt,
                quickAnswer = quickAnswer,
                macUnreachable = isOutOfReach(error),
                phoneReady = phoneReady,
            )
        }
    }

    /** The failures that mean the Mac is not there, rather than that it said no. */
    fun isOutOfReach(error: Throwable): Boolean =
        error is TransportError.Unreachable || error is TransportError.AppNotRunning ||
            error is TransportError.TimedOut

    /**
     * What a widget says when the Mac is there but not answering in time. Its own
     * sentence, because "not reachable" would be a lie: it answered, eventually.
     */
    const val TOO_SLOW = "Your Mac is taking too long to answer."

    fun problem(error: Throwable): String = when (error) {
        is TransportError.Unauthorized -> "This device is no longer paired with your Mac."
        is TransportError.Forbidden -> "Your Mac refused the request."
        else -> "Your Mac isn't reachable right now."
    }

    /**
     * Fires the configured preset and returns what the Mac said.
     *
     * One message, no history: a widget has no transcript, and attaching the phone's
     * last conversation to a button on the home screen would answer a question nobody
     * asked.
     */
    suspend fun ask(
        prompt: String,
        transport: ControlTransport?,
        snapshots: SnapshotStore? = null,
    ): AskResult {
        if (transport == null) return AskResult.Problem("Not paired with a Mac.")
        return try {
            val request = IntentMapping.chatRequest(prompt, maxTokens = IntentMapping.SPOKEN_MAX_TOKENS)
            val answer = withTimeoutOrNull(QuickPrompt.TIMEOUT_MS) {
                transport.chat(request).content.trim()
            } ?: return AskResult.Problem(TOO_SLOW)
            if (answer.isEmpty()) {
                AskResult.Problem("Your Mac answered with nothing.")
            } else {
                snapshots?.note(prompt, answer)
                AskResult.Answer(answer)
            }
        } catch (refusal: IntentMapping.Refusal) {
            AskResult.Problem(refusal.message ?: "That couldn't be asked.")
        } catch (error: Exception) {
            AskResult.Problem(problem(error))
        }
    }
}
