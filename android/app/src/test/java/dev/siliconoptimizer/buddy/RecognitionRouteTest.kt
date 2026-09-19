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
 * lives here, with all three of its inputs named, where it can be held to it.
 */
class RecognitionRouteTest {

    private val tiramisu = RecognitionRoute.MINIMUM_SDK
    private val androidSixteen = 36

    /** The only combination that earns the claim. */
    @Test
    fun `all three have to hold`() {
        assertEquals(
            RecognitionRoute.OnDevice,
            RecognitionRoute.of(androidSixteen, availabilityReported = true, createSucceeded = true),
        )
    }

    /**
     * The original bug, pinned: a new SDK on its own says nothing about where the audio
     * goes. These are the cases that used to return OnDevice.
     */
    @Test
    fun `a new SDK alone is not on-device`() {
        assertEquals(
            RecognitionRoute.Network,
            RecognitionRoute.of(androidSixteen, availabilityReported = false, createSucceeded = false),
        )
        assertEquals(
            RecognitionRoute.Network,
            RecognitionRoute.of(androidSixteen, availabilityReported = true, createSucceeded = false),
        )
    }

    /**
     * `isOnDeviceRecognitionAvailable` answering true is not the same as
     * `createOnDeviceSpeechRecognizer` returning something, and the fallback in that
     * case is the network recogniser. Claiming local recognition there would be the
     * original bug wearing a different hat.
     */
    @Test
    fun `availability reported but creation failed is the network`() {
        val route = RecognitionRoute.of(
            androidSixteen, availabilityReported = true, createSucceeded = false,
        )
        assertEquals(RecognitionRoute.Network, route)
        assertFalse(route.keepsAudioOnDevice)
    }

    /** Below API 33 there is nothing to create, whatever anything else claims. */
    @Test
    fun `an old phone is never on-device`() {
        for (available in listOf(true, false)) {
            for (created in listOf(true, false)) {
                assertEquals(
                    "sdk=${tiramisu - 1} available=$available created=$created",
                    RecognitionRoute.Network,
                    RecognitionRoute.of(tiramisu - 1, available, created),
                )
            }
        }
    }

    /** The boundary itself, so the comparison cannot drift off by one. */
    @Test
    fun `the on-device recogniser starts existing at API 33`() {
        assertEquals(
            RecognitionRoute.Network,
            RecognitionRoute.of(tiramisu - 1, availabilityReported = true, createSucceeded = true),
        )
        assertEquals(
            RecognitionRoute.OnDevice,
            RecognitionRoute.of(tiramisu, availabilityReported = true, createSucceeded = true),
        )
    }

    /** Seven of the eight combinations are the network. Exhaustively, so none drifts. */
    @Test
    fun `only one of the eight combinations keeps the audio here`() {
        val onDevice = buildList {
            for (sdk in listOf(tiramisu - 1, tiramisu)) {
                for (available in listOf(true, false)) {
                    for (created in listOf(true, false)) {
                        if (RecognitionRoute.of(sdk, available, created).keepsAudioOnDevice) {
                            add(Triple(sdk, available, created))
                        }
                    }
                }
            }
        }
        assertEquals(listOf(Triple(tiramisu, true, true)), onDevice)
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
}
