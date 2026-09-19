package dev.siliconoptimizer.buddy.voice

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import dev.siliconoptimizer.buddy.reach.RecognitionRoute
import dev.siliconoptimizer.buddy.reach.SnapshotStore
import dev.siliconoptimizer.buddy.reach.VoiceEffect
import dev.siliconoptimizer.buddy.reach.VoiceEvent
import dev.siliconoptimizer.buddy.reach.VoiceSession
import dev.siliconoptimizer.buddy.reach.VoiceState
import java.util.Locale

/**
 * Push-to-talk, and the answer read back.
 *
 * The decisions live in `VoiceSession`, which has no microphone in it and is tested on
 * its own. This is the half that cannot be tested without hardware: the recogniser, the
 * synthesiser and the permission. Keeping the two apart is the only reason the awkward
 * cases — press while speaking, release before anything was heard — are covered at all.
 */
class VoiceController(application: Application) : AndroidViewModel(application) {

    private val session = VoiceSession(SnapshotStore(application).speaksReplies)
    private var recogniser: SpeechRecognizer? = null
    private var speech: TextToSpeech? = null
    private var speechReady = false

    /** Mirrors of the session, so Compose redraws when they change. */
    var state by mutableStateOf<VoiceState>(VoiceState.Idle)
        private set
    var partial by mutableStateOf<String?>(null)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    /**
     * Where the audio is going, decided by which recogniser was actually created.
     *
     * Below API 33 there is no on-device recogniser to create, and `EXTRA_PREFER_OFFLINE`
     * is a preference the engine is free to ignore — so "offline" cannot be claimed,
     * only asked for. See [RecognitionRoute].
     */
    var route by mutableStateOf(RecognitionRoute.Network)
        private set

    /** True when this phone recognises speech without sending the audio anywhere. */
    val isOnDevice: Boolean get() = route.keepsAudioOnDevice

    /**
     * What the caption says about where the audio is going, for whoever is holding the
     * button down.
     */
    val recognitionNote: String get() = route.note

    /** Called with the finished question. The chat screen sends it. */
    var onAsk: ((String) -> Unit)? = null

    var speaksReplies: Boolean
        get() = session.speaksReplies
        set(value) {
            SnapshotStore(getApplication()).speaksReplies = value
            perform(session.apply(VoiceEvent.SpeechEnabled(value)))
        }

    val buttonLabel: String get() = session.buttonLabel

    // MARK: - The button

    fun press() {
        if (problem != null) return
        perform(session.apply(VoiceEvent.Pressed))
    }

    fun release() = perform(session.apply(VoiceEvent.Released))

    /**
     * The reply came back. Spoken only if the person is still waiting for it — the
     * session decides, because they may already be asking the next question.
     */
    fun answered(text: String) = perform(session.apply(VoiceEvent.Answered(text)))

    /** The screen went away, or something else took the audio. */
    fun interrupt() = perform(session.apply(VoiceEvent.Interrupted))

    fun permissionRefused() {
        problem = "Silicon Buddy can't use the microphone. Turn it on in Android's " +
            "app settings for Silicon Buddy."
    }

    fun permissionGranted() {
        problem = null
    }

    fun clearProblem() {
        problem = null
        if (state is VoiceState.Failed) state = VoiceState.Idle
    }

    // MARK: - Doing what the session decided

    private fun perform(effects: List<VoiceEffect>) {
        for (effect in effects) {
            when (effect) {
                VoiceEffect.StartListening -> startListening()
                VoiceEffect.StopListening -> stopListening()
                is VoiceEffect.Send -> onAsk?.invoke(effect.text)
                is VoiceEffect.Speak -> speak(effect.text)
                VoiceEffect.StopSpeaking -> speech?.stop()
            }
        }
        publish()
    }

    private fun publish() {
        state = session.state
        partial = session.partial
    }

    private fun startListening() {
        val context = getApplication<Application>()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            perform(session.apply(VoiceEvent.Failed("Speech recognition isn't available on this phone.")))
            return
        }
        recogniser?.destroy()
        // The on-device recogniser where there is one: the question is going to the
        // owner's own Mac, and routing the audio through Google's servers on the way
        // would undo the point of the app. `createOnDeviceSpeechRecognizer` is the only
        // way to be sure, because `EXTRA_PREFER_OFFLINE` is a hint the engine may
        // ignore without saying so.
        val offersOnDevice =
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
                    .getOrDefault(false)
        // Which recogniser we ended up with, not which one we hoped for: a phone that
        // advertises the feature can still fail to build one, and the caption below the
        // button is a promise about where a recording of the owner's voice goes.
        var builtOnDevice = false
        val recogniser = if (offersOnDevice) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
                .onSuccess { builtOnDevice = true }
                .getOrElse { SpeechRecognizer.createSpeechRecognizer(context) }
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        route = RecognitionRoute.of(builtOnDevice)
        this.recogniser = recogniser
        recogniser.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                heard(partialResults)
            }

            override fun onResults(results: Bundle?) {
                heard(results)
            }

            override fun onError(error: Int) {
                // A recogniser that gives up mid-phrase is common and not worth a red
                // banner: the button simply stops listening and whatever was heard is
                // sent, exactly as if the person had let go.
                if (session.isListening) perform(session.apply(VoiceEvent.Released))
            }

            private fun heard(bundle: Bundle?) {
                bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.let { perform(session.apply(VoiceEvent.Heard(it))) }
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            // On-device where the phone can. The question is going to the owner's own
            // Mac, and routing the audio through Google's servers on the way would undo
            // the point of the whole app. Ignored by older recognisers, which is why it
            // is a preference and not a requirement.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        recogniser.startListening(intent)
    }

    private fun stopListening() {
        recogniser?.stopListening()
    }

    private fun speak(text: String) {
        val context = getApplication<Application>()
        if (speech == null) {
            speech = TextToSpeech(context) { status ->
                speechReady = status == TextToSpeech.SUCCESS
                if (speechReady) {
                    speech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) {
                            perform(session.apply(VoiceEvent.FinishedSpeaking))
                        }

                        @Deprecated("Replaced by onError(String, Int)")
                        override fun onError(utteranceId: String?) {
                            perform(session.apply(VoiceEvent.FinishedSpeaking))
                        }
                    })
                    say(text)
                }
            }
            return
        }
        say(text)
    }

    private fun say(text: String) {
        speech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE)
    }

    override fun onCleared() {
        super.onCleared()
        recogniser?.destroy()
        recogniser = null
        speech?.stop()
        speech?.shutdown()
        speech = null
    }

    companion object {
        private const val UTTERANCE = "buddy-reply"
    }
}
