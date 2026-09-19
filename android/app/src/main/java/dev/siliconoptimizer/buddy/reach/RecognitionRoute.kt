package dev.siliconoptimizer.buddy.reach

/**
 * Where the audio of a spoken question actually goes.
 *
 * This is a privacy claim before it is a caption. "Recognised on this phone" is the app
 * promising that a recording of the owner's voice did not leave the device, and there
 * are three separate ways to get that promise wrong:
 *
 * - claiming it from the SDK version, which is what shipped and what this replaced;
 * - claiming it because `isOnDeviceRecognitionAvailable` said yes, when the engine can
 *   still fail to build one;
 * - claiming it because `EXTRA_PREFER_OFFLINE` was *asked* for, which is a hint an
 *   engine may ignore without saying so.
 *
 * So all three inputs are named and the rule is one expression. Kept away from
 * `SpeechRecognizer` so it can be tested: the hardware half has no test, so the decision
 * it obeys must have one.
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
        /** Below this there is no on-device recogniser to create at all. */
        const val MINIMUM_SDK = android.os.Build.VERSION_CODES.TIRAMISU

        /**
         * @param sdkInt what this phone is running.
         * @param availabilityReported what `isOnDeviceRecognitionAvailable` answered.
         * @param createSucceeded whether `createOnDeviceSpeechRecognizer` actually
         *   returned one, and it is still the one in use. This is the load-bearing
         *   input; the other two are only permission to try.
         */
        fun of(
            sdkInt: Int,
            availabilityReported: Boolean,
            createSucceeded: Boolean,
        ): RecognitionRoute =
            if (sdkInt >= MINIMUM_SDK && availabilityReported && createSucceeded) {
                OnDevice
            } else {
                Network
            }
    }
}
