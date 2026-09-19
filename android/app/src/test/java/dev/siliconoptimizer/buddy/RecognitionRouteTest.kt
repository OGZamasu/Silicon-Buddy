package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.reach.RecognitionRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caption under the push-to-talk button is a privacy claim.
 *
 * The bug this covers shipped: `VoiceController` worked out the route correctly from
 * which recogniser it had built, and then overwrote the answer with
 * `SDK_INT >= TIRAMISU` — so every phone on Android 13 or later was told its voice was
 * recognised locally, including the ones streaming the audio to Google. The decision now
 * lives here, where it can be held to it.
 */
class RecognitionRouteTest {

    @Test
    fun `the route follows the recogniser that was built, not the one that was wanted`() {
        assertEquals(RecognitionRoute.OnDevice, RecognitionRoute.of(onDeviceCreated = true))
        assertEquals(RecognitionRoute.Network, RecognitionRoute.of(onDeviceCreated = false))
    }

    /**
     * `isOnDeviceRecognitionAvailable` answering true is not the same as
     * `createOnDeviceSpeechRecognizer` returning something, and the fallback in that
     * case is the network recogniser. Claiming local recognition there would be the
     * original bug wearing a different hat.
     */
    @Test
    fun `an on-device recogniser that could not be built is not claimed as on-device`() {
        assertEquals(RecognitionRoute.Network, RecognitionRoute.of(onDeviceCreated = false))
        assertFalse(RecognitionRoute.of(onDeviceCreated = false).keepsAudioOnDevice)
    }

    @Test
    fun `only the on-device route promises the audio stays here`() {
        assertTrue(RecognitionRoute.OnDevice.keepsAudioOnDevice)
        assertFalse(RecognitionRoute.Network.keepsAudioOnDevice)
    }

    /**
     * The words matter as much as the flag: the network case has to say where the
     * recording goes, not merely decline to claim otherwise.
     */
    @Test
    fun `the caption names the destination when the audio leaves the phone`() {
        assertEquals("Listening — recognised on this phone", RecognitionRoute.OnDevice.note)
        assertEquals("Listening — sent to Google for recognition", RecognitionRoute.Network.note)
    }

    /** Nothing may default to the reassuring answer. */
    @Test
    fun `the safe default is the network route`() {
        assertEquals(RecognitionRoute.Network, RecognitionRoute.values().first { !it.keepsAudioOnDevice })
        assertFalse(RecognitionRoute.of(false).keepsAudioOnDevice)
    }
}
