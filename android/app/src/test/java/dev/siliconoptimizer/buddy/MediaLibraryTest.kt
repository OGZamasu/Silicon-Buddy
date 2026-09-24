package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.media.MediaLibrary
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Saving a render onto the phone: the order of it, apart from MediaStore.
 *
 * "Save to Photos" runs in a view model's coroutine, where nothing catches. A failure has to
 * come back as the save's result — a Mac that answers 404 for a render it has since removed,
 * a tailnet that drops halfway, a phone that runs out of room — because thrown from there it
 * closes the app.
 */
class MediaLibraryTest {

    private fun failing(failure: Throwable): suspend (java.io.OutputStream) -> Unit = { throw failure }

    private suspend fun save(fetch: suspend (java.io.OutputStream) -> Unit, discarded: MutableList<String>) =
        MediaLibrary.written(
            insert = { "row-1" },
            open = { ByteArrayOutputStream() },
            publish = { fail("a save that failed must not become visible") },
            discard = { discarded += it },
            fetch = fetch,
        )

    @Test
    fun `a Mac that no longer has the file is a failed save, not a crash`() = runBlocking {
        val discarded = mutableListOf<String>()
        val result = save(failing(TransportError.NotFound("That render is not on the Mac any more.")), discarded)

        assertTrue(result.exceptionOrNull() is TransportError.NotFound)
        assertEquals("nothing half-made is left in the gallery", listOf("row-1"), discarded)
    }

    @Test
    fun `a dropped connection and a full disk are failed saves too`() = runBlocking {
        for (failure in listOf(TransportError.Unreachable("100.64.0.9"), TransportError.Unauthorized, IOException("ENOSPC"))) {
            val discarded = mutableListOf<String>()
            val result = save(failing(failure), discarded)
            assertEquals(failure, result.exceptionOrNull())
            assertEquals(listOf("row-1"), discarded)
        }
    }

    @Test
    fun `a save that is stopped is stopped, not failed`() = runBlocking {
        val discarded = mutableListOf<String>()
        try {
            save(failing(CancellationException("left the screen")), discarded)
            fail("a cancelled save is not a result")
        } catch (expected: CancellationException) {
            assertEquals(listOf("row-1"), discarded)
        }
    }

    @Test
    fun `a save that works is published`() = runBlocking {
        val published = mutableListOf<String>()
        val result = MediaLibrary.written(
            insert = { "row-1" },
            open = { ByteArrayOutputStream() },
            publish = { published += it },
            discard = { fail("a finished save is not taken away") },
            fetch = { it.write(byteArrayOf(1, 2, 3)) },
        )

        assertEquals("row-1", result.getOrNull())
        assertEquals(listOf("row-1"), published)
    }

    // MARK: - Where a render goes

    /**
     * A mesh is not a picture. MediaStore's image collection refuses a `.glb` on Android 11
     * and later — the insert throws before a byte is fetched — so "Save the file" on a mesh
     * could never work on the phone this app is for. It goes to Downloads, which takes any
     * file, typed so that a 3D viewer is what opens it.
     */
    @Test
    fun `a mesh goes to Downloads as the model it is`() {
        val glb = MediaLibrary.destination("mesh", MediaLibrary.nameFor("mesh", "Kettle", "/Users/you/out/kettle.glb"))
        assertEquals(MediaLibrary.Destination.Collection.Downloads, glb.collection)
        assertEquals("model/gltf-binary", glb.mimeType)

        val obj = MediaLibrary.destination("mesh", "kettle-1758000000.obj")
        assertEquals(MediaLibrary.Destination.Collection.Downloads, obj.collection)
        assertEquals("model/obj", obj.mimeType)

        assertEquals(
            "a mesh without a path is still a mesh",
            "glb",
            MediaLibrary.nameFor("mesh", "Kettle", null).substringAfterLast('.'),
        )
    }

    @Test
    fun `pictures and clips still go where a gallery finds them`() {
        assertEquals(
            MediaLibrary.Destination.Collection.Images,
            MediaLibrary.destination("image", "Lisbon-1758000000.png").collection,
        )
        assertEquals(
            MediaLibrary.Destination.Collection.Video,
            MediaLibrary.destination("video", "Lisbon-1758000000.mp4").collection,
        )
    }
}
