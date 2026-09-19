package dev.siliconoptimizer.buddy.reach

/**
 * Where the audio of a spoken question actually goes.
 *
 * This is a privacy claim before it is a caption. "Recognised on this phone" is the app
 * promising that a recording of the owner's voice did not leave the device, and the only
 * honest basis for that promise is that the on-device recogniser was really created —
 * not that the SDK is new enough to have one, and not that the engine was *asked* for
 * offline recognition. `EXTRA_PREFER_OFFLINE` is a hint an engine may ignore without
 * saying so, and `createOnDeviceSpeechRecognizer` can fail on a phone that reports the
 * feature as available.
 *
 * Kept away from `SpeechRecognizer` so the rule can be tested: the hardware half has no
 * test, so the decision it obeys must have one.
 */
enum class RecognitionRoute {
    /** The recogniser runs here and the audio never leaves the phone. */
    OnDevice,

    /** The recording goes to Google to be turned into text. */
    Network,
    ;

    val keepsAudioOnDevice: Boolean get() = this == OnDevice

    /** What the composer says while the button is held down. */
    val note: String
        get() = when (this) {
            OnDevice -> "Listening — recognised on this phone"
            Network -> "Listening — sent to Google for recognition"
        }

    companion object {
        /**
         * [onDeviceCreated] is the only input, deliberately: availability was asked for
         * earlier and may have been answered optimistically, so the route is decided by
         * what was built, not by what was offered.
         */
        fun of(onDeviceCreated: Boolean): RecognitionRoute =
            if (onDeviceCreated) OnDevice else Network
    }
}
