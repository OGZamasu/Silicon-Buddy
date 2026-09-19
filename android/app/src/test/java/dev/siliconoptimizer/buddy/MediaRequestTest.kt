package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.media.ImageRequestBuilder
import dev.siliconoptimizer.buddy.media.MeshRequestBuilder
import dev.siliconoptimizer.buddy.media.VideoLane
import dev.siliconoptimizer.buddy.media.VideoRequest
import dev.siliconoptimizer.buddy.transport.ImageModel
import dev.siliconoptimizer.buddy.transport.ImagePlan
import dev.siliconoptimizer.buddy.transport.MeshModel
import dev.siliconoptimizer.buddy.transport.VideoModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Create tab is allowed to ask the Mac for.
 *
 * These are the rules that decide whether a render can happen at all: a lane that
 * renders five and ten second clips must never be sent eight, and "auto" must not be
 * offered by a phone that has not been told the Mac routes media itself. Every one of
 * them is a sentence somebody could get wrong in a screen and never notice.
 */
class MediaRequestTest {

    private val h3 = VideoModel(
        id = "hailuo-h3", name = "Hailuo H3", summary = "Text and image to video.",
        typicalDuration = "4 minutes", supportsImageInput = true,
        supportedSeconds = listOf(5, 10), available = true, node = "silicon-node",
    )

    private val offline = VideoModel(
        id = "ltx-2", name = "LTX 2", summary = "Fast local video.",
        typicalDuration = "2 minutes", supportsImageInput = false,
        supportedSeconds = listOf(3, 5), available = false, node = null,
    )

    // MARK: - Lanes

    @Test
    fun `auto appears only when the Mac says it routes media`() {
        assertEquals(
            listOf("Hailuo H3", "LTX 2"),
            VideoRequest.lanes(listOf(h3, offline), routesMedia = false).map { it.label },
        )
        val advertised = VideoRequest.lanes(listOf(h3), routesMedia = true)
        assertEquals(VideoLane.Auto, advertised.first())
        assertNull("Auto names no model: the Mac picks one", advertised.first().id)
    }

    @Test
    fun `a lane with no machine behind it cannot be rendered on`() {
        assertTrue(VideoRequest.isAvailable(VideoLane.On(h3)))
        assertFalse(VideoRequest.isAvailable(VideoLane.On(offline)))
        assertNull(
            "An unavailable lane must not produce a request",
            VideoRequest.enqueue("A tram climbing Alfama", VideoLane.On(offline)),
        )
    }

    // MARK: - Seconds

    @Test
    fun `the seconds offered are the lane's own`() {
        assertEquals(listOf(5, 10), VideoRequest.secondsChoices(VideoLane.On(h3)))
        // Auto has no model yet, so the wire contract's own picker is all there is.
        assertEquals(VideoRequest.FALLBACK_SECONDS, VideoRequest.secondsChoices(VideoLane.Auto))
    }

    @Test
    fun `a duration the lane does not support becomes one it does`() {
        val lane = VideoLane.On(h3)
        assertEquals(5, VideoRequest.seconds(lane, 7))
        assertEquals(10, VideoRequest.seconds(lane, 8))
        assertEquals(10, VideoRequest.seconds(lane, 12))
        assertEquals(5, VideoRequest.seconds(lane, null))
        assertTrue(VideoRequest.seconds(lane, 900) in h3.supportedSeconds)
        assertTrue(VideoRequest.seconds(lane, -4) in h3.supportedSeconds)
    }

    @Test
    fun `a lane that advertises nothing outside one to fifteen is trusted, the rest is dropped`() {
        val odd = h3.copy(supportedSeconds = listOf(0, 5, 99))
        assertEquals(listOf(5), VideoRequest.secondsChoices(VideoLane.On(odd)))
    }

    // MARK: - Variations

    @Test
    fun `variations stay inside what the Mac's queue accepts`() {
        assertEquals(1, VideoRequest.variations(0))
        assertEquals(1, VideoRequest.variations(-3))
        assertEquals(20, VideoRequest.variations(200))
        assertEquals(4, VideoRequest.variations(4))
    }

    // MARK: - The bodies

    @Test
    fun `a queued clip carries the lane, the duration and the takes`() {
        val request = VideoRequest.enqueue(
            prompt = "  A tram climbing Alfama at dawn  ",
            lane = VideoLane.On(h3),
            title = " Lisbon ",
            seconds = 10,
            variations = 2,
        )
        assertNotNull(request)
        assertEquals(listOf("A tram climbing Alfama at dawn"), request!!.prompts)
        assertEquals("Lisbon", request.title)
        assertEquals(2, request.variations)
        assertEquals("hailuo-h3", request.modelID)
        assertEquals(10, request.seconds)
    }

    @Test
    fun `auto sends no model and no duration, because the Mac chooses both`() {
        val request = VideoRequest.enqueue("A tram at dawn", VideoLane.Auto, seconds = 10)
        assertNotNull(request)
        assertNull(request!!.modelID)
        assertNull("A duration checked against no model is a guess", request.seconds)
    }

    @Test
    fun `an empty prompt is not a render`() {
        assertNull(VideoRequest.enqueue("   ", VideoLane.On(h3)))
        assertNull(VideoRequest.generate("", VideoLane.On(h3)))
    }

    @Test
    fun `rendering one now names the lane it was asked for`() {
        val request = VideoRequest.generate("A tram at dawn", VideoLane.On(h3), seconds = 8)
        assertEquals("hailuo-h3", request!!.modelID)
        assertEquals("8 seconds is not on offer; 10 is the nearest that is", 10, request.seconds)
    }

    // MARK: - Image

    private val flux = ImageModel(
        id = "flux2-klein", name = "FLUX.2 klein", author = "Black Forest Labs",
        license = "Apache-2.0", summary = "Fast local text-to-image.", parameters = "4B",
        blocks = 19, defaultSteps = 8, isGated = false,
        recommendation = ImagePlan(
            width = 1024, height = 1024, steps = 8, quantization = "8-bit",
            peakBytes = 13958643712, peakPhase = "Decode", budgetBytes = 29200000000,
            verdict = "fits", phases = emptyList(), suggestions = emptyList(),
            notes = emptyList(),
        ),
    )

    @Test
    fun `an image request takes the model's own defaults`() {
        assertEquals(8, ImageRequestBuilder.defaultSteps(flux))
        assertEquals(1024, ImageRequestBuilder.defaultSize(flux))
        val request = ImageRequestBuilder.build("A tram at dawn", flux, 1024, 8)!!
        assertEquals("flux2-klein", request.modelID)
        assertEquals(1024, request.width)
        assertEquals(1024, request.height)
        assertEquals(8, request.steps)
    }

    @Test
    fun `steps and sizes stay inside what a picker offers`() {
        assertEquals(1, ImageRequestBuilder.steps(0))
        assertEquals(50, ImageRequestBuilder.steps(5000))
        // A size nothing offers falls back to the model's own rather than being sent.
        assertEquals(1024, ImageRequestBuilder.build("A tram", flux, 999, 8)!!.width)
    }

    @Test
    fun `an image needs a prompt and a model`() {
        assertNull(ImageRequestBuilder.build("", flux, 1024, 8))
        assertNull(ImageRequestBuilder.build("A tram", null, 1024, 8))
    }

    // MARK: - Mesh

    private val hunyuan = MeshModel(
        id = "hunyuan3d-2", name = "Hunyuan3D 2", author = "Tencent",
        summary = "Image to textured mesh.", outputs = "GLB, OBJ",
        typicalDuration = "5 minutes", peakBytes = 13958643712, weightsBytes = 6442450944,
        isInstalled = true, installDetail = "Installed",
    )

    @Test
    fun `a mesh is asked for by a path on the Mac`() {
        val request = MeshRequestBuilder.build("/Users/you/Pictures/kettle.png", hunyuan, 2048)!!
        assertEquals("/Users/you/Pictures/kettle.png", request.imagePath)
        assertEquals("hunyuan3d-2", request.modelID)
        assertEquals(2048, request.textureSize)
    }

    @Test
    fun `a phone's own picture is not a path the Mac can read`() {
        assertFalse(MeshRequestBuilder.isMacPath("content://media/external/images/media/42"))
        assertFalse(MeshRequestBuilder.isMacPath("kettle.png"))
        assertFalse(MeshRequestBuilder.isMacPath(""))
        assertTrue(MeshRequestBuilder.isMacPath(" /Users/you/Pictures/kettle.png "))
        assertNull(MeshRequestBuilder.build("content://media/42", hunyuan, 2048))
    }

    /**
     * A path from this phone is absolute and looks like a file, and is still nothing
     * the Mac can open. Sending one buys a render that fails minutes later.
     */
    @Test
    fun `a path only this phone has is refused before it is sent`() {
        listOf(
            "/storage/emulated/0/DCIM/Camera/IMG_0042.jpg",
            "/sdcard/Download/kettle.png",
            "/data/user/0/dev.siliconoptimizer.buddy/files/kettle.png",
            "/mnt/media_rw/kettle.png",
        ).forEach {
            assertFalse(it, MeshRequestBuilder.isMacPath(it))
            assertNull(MeshRequestBuilder.build(it, hunyuan, 2048))
        }
        assertTrue(MeshRequestBuilder.isMacPath("/Volumes/Work/kettle.png"))
    }

    @Test
    fun `a texture size nothing offers becomes one that is offered`() {
        assertEquals(2048, MeshRequestBuilder.build("/tmp/a.png", hunyuan, 777)!!.textureSize)
    }
}
