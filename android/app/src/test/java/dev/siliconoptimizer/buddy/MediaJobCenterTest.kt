package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.media.MediaJobCenter
import dev.siliconoptimizer.buddy.transport.ImageRequest
import dev.siliconoptimizer.buddy.transport.MeshRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * How a request the service held ended, as the rest of the app needs to know it: with the
 * Mac's answer — said by the service, and the stream's copy of it the service's — or given up
 * on, in which case the Mac's own ending, whenever it comes, is still news.
 */
class MediaJobCenterTest {

    @Before
    fun setUp() = MediaJobCenter.forget()

    @After
    fun tearDown() = MediaJobCenter.forget()

    private fun image() = MediaJobCenter.Work.Image(ImageRequest(prompt = "A tram at dawn"), summary = "")
    private fun mesh() = MediaJobCenter.Work.Mesh(MeshRequest(uploadID = "0B7D"), summary = "")

    @Test
    fun `a request the Mac answered keeps its kind claimed for a moment, and gives nothing up`() {
        val work = image()
        MediaJobCenter.began(work)
        MediaJobCenter.ended(work, MediaJobCenter.Outcome(kind = "image", headline = "Your image is ready"))

        assertTrue("the stream's copy of the ending is still the service's", MediaJobCenter.isRendering("image"))
        assertEquals(0, MediaJobCenter.gaveUp("image"))
    }

    @Test
    fun `a request given up on is counted for its kind, and claims nothing`() {
        for (outcome in listOf(
            MediaJobCenter.Outcome(kind = "mesh", headline = "Still rendering on the Mac", failed = true, answered = false),
            MediaJobCenter.Outcome(kind = "mesh", headline = "Stopped waiting", failed = true, answered = false),
        )) {
            val work = mesh()
            MediaJobCenter.began(work)
            MediaJobCenter.ended(work, outcome)
        }

        assertEquals(2, MediaJobCenter.gaveUp("mesh"))
        assertEquals(0, MediaJobCenter.gaveUp("image"))
        assertFalse("the Mac's own ending, whenever it comes, is not the service's", MediaJobCenter.isRendering("mesh"))
    }
}
