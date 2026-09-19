package dev.siliconoptimizer.buddy.reach

/**
 * Push-to-talk, as a state machine with no microphone in it.
 *
 * The awkward parts of voice are not the recogniser or the synthesiser, they are the
 * order things happen in: a person who presses the button while the phone is still
 * talking wants to interrupt it, not to queue behind it; a release before anything was
 * heard should send nothing rather than an empty prompt; an answer that arrives after
 * the person has already started their next question must not be spoken over them. All
 * of that is here, where a test can drive it in a microsecond.
 */
sealed interface VoiceState {
    /** Nothing happening. */
    object Idle : VoiceState

    /** The microphone is open. Carries what has been heard so far. */
    data class Listening(val heard: String) : VoiceState

    /** The button is up and the Mac is answering. */
    data class Thinking(val prompt: String) : VoiceState

    /** The answer is being read out. */
    data class Speaking(val text: String) : VoiceState

    /** Something went wrong, in the words to show. */
    data class Failed(val message: String) : VoiceState
}

/** Everything that can happen to a voice session. */
sealed interface VoiceEvent {
    object Pressed : VoiceEvent
    data class Heard(val text: String) : VoiceEvent
    object Released : VoiceEvent
    data class Answered(val text: String) : VoiceEvent
    object FinishedSpeaking : VoiceEvent
    object Interrupted : VoiceEvent
    data class Failed(val message: String) : VoiceEvent
    data class SpeechEnabled(val enabled: Boolean) : VoiceEvent
}

/** What the app should do about it. The session decides; the caller performs. */
sealed interface VoiceEffect {
    object StartListening : VoiceEffect
    object StopListening : VoiceEffect
    data class Send(val text: String) : VoiceEffect
    data class Speak(val text: String) : VoiceEffect
    object StopSpeaking : VoiceEffect
}

/** The session itself: mutable, small, and driven entirely by [apply]. */
class VoiceSession(speaksReplies: Boolean = true) {

    var state: VoiceState = VoiceState.Idle
        private set

    /** Whether an answer is read out loud. The toggle in Settings sets this. */
    var speaksReplies: Boolean = speaksReplies
        private set

    fun apply(event: VoiceEvent): List<VoiceEffect> {
        val current = state
        return when {
            event is VoiceEvent.SpeechEnabled -> {
                speaksReplies = event.enabled
                if (!event.enabled && current is VoiceState.Speaking) {
                    // Turning it off mid-sentence stops the sentence. Anything else
                    // would be the app ignoring the switch it just showed being flipped.
                    state = VoiceState.Idle
                    listOf(VoiceEffect.StopSpeaking)
                } else {
                    emptyList()
                }
            }

            // Pressing while the phone is talking is an interruption, and it is the
            // most common one: the answer is long, the person has heard enough, they
            // press to ask the next thing. Stop the voice and open the microphone.
            event is VoiceEvent.Pressed && current is VoiceState.Speaking -> {
                state = VoiceState.Listening("")
                listOf(VoiceEffect.StopSpeaking, VoiceEffect.StartListening)
            }

            // Pressing while the Mac is still answering abandons that answer. The
            // person has moved on, and an answer to the previous question arriving on
            // top of the next one is worse than no answer.
            event is VoiceEvent.Pressed && current !is VoiceState.Listening -> {
                state = VoiceState.Listening("")
                listOf(VoiceEffect.StartListening)
            }

            event is VoiceEvent.Pressed -> emptyList()

            event is VoiceEvent.Heard && current is VoiceState.Listening -> {
                state = VoiceState.Listening(event.text)
                emptyList()
            }

            // The recogniser's last words, arriving after the button came up. The
            // prompt has already gone; adding to it now would change the question after
            // it was asked.
            event is VoiceEvent.Heard -> emptyList()

            event is VoiceEvent.Released && current is VoiceState.Listening -> {
                val text = current.heard.trim()
                if (text.length < MINIMUM_CHARACTERS) {
                    state = VoiceState.Idle
                    listOf(VoiceEffect.StopListening)
                } else {
                    state = VoiceState.Thinking(text)
                    listOf(VoiceEffect.StopListening, VoiceEffect.Send(text))
                }
            }

            event is VoiceEvent.Released -> emptyList()

            event is VoiceEvent.Answered && current is VoiceState.Thinking -> {
                if (speaksReplies && event.text.isNotBlank()) {
                    state = VoiceState.Speaking(event.text)
                    listOf(VoiceEffect.Speak(event.text))
                } else {
                    state = VoiceState.Idle
                    emptyList()
                }
            }

            // An answer to a question the person has already moved on from. Shown in
            // the transcript by whoever asked for it, never spoken over them.
            event is VoiceEvent.Answered -> emptyList()

            event is VoiceEvent.FinishedSpeaking && current is VoiceState.Speaking -> {
                state = VoiceState.Idle
                emptyList()
            }

            event is VoiceEvent.FinishedSpeaking -> emptyList()

            event is VoiceEvent.Interrupted -> {
                state = VoiceState.Idle
                when (current) {
                    is VoiceState.Listening -> listOf(VoiceEffect.StopListening)
                    is VoiceState.Speaking -> listOf(VoiceEffect.StopSpeaking)
                    else -> emptyList()
                }
            }

            event is VoiceEvent.Failed -> {
                state = VoiceState.Failed(event.message)
                when (current) {
                    is VoiceState.Listening -> listOf(VoiceEffect.StopListening)
                    is VoiceState.Speaking -> listOf(VoiceEffect.StopSpeaking)
                    else -> emptyList()
                }
            }

            else -> emptyList()
        }
    }

    /** What has been heard so far, for the live caption under the button. */
    val partial: String? get() = (state as? VoiceState.Listening)?.heard

    val isListening: Boolean get() = state is VoiceState.Listening

    /** What the button says it will do if pressed now. */
    val buttonLabel: String
        get() = when (state) {
            is VoiceState.Listening -> "Listening — let go to ask"
            is VoiceState.Thinking -> "Asking the Mac"
            is VoiceState.Speaking -> "Press to interrupt"
            else -> "Hold to talk"
        }

    companion object {
        /**
         * The shortest thing worth sending. A tap that catches one syllable of room
         * noise should not wake a 27B model.
         */
        const val MINIMUM_CHARACTERS = 2
    }
}
