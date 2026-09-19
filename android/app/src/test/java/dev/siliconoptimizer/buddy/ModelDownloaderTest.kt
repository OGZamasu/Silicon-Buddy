package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.ondevice.DownloadFailure
import dev.siliconoptimizer.buddy.ondevice.DownloadStep
import dev.siliconoptimizer.buddy.ondevice.ModelDownloader
import dev.siliconoptimizer.buddy.ondevice.ModelStore
import dev.siliconoptimizer.buddy.ondevice.SpaceReserver
import dev.siliconoptimizer.buddy.transport.ControlClient
import dev.siliconoptimizer.buddy.transport.ServerConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * The phone's model downloader against a Mac over a real socket.
 *
 * Every answer the Mac documents for `/ondevice/models/{id}/file` — 206 for the rest, 200
 * meaning "not the file you started", 416 past the end, 409 while not ready, 503 with its
 * drive gone — and the phone's own checksum: a mismatch deletes the bytes, asks the Mac to
 * verify its copy once, and fetches once more; a second mismatch stops.
 */
class ModelDownloaderTest {

    @get:Rule val folder = TemporaryFolder()

    /** A download that hangs is a failure to report, not a build to wait on. */
    @get:Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(30)

    private val bytes = Random(7).nextBytes(3 * 1024 * 1024 + 17)
    private lateinit var mac: FakePhoneMac
    private lateinit var store: ModelStore

    @Before
    fun setUp() {
        mac = FakePhoneMac(bytes)
        store = ModelStore(folder.newFolder("models"))
    }

    @After
    fun tearDown() = mac.close()

    private fun downloader(space: SpaceReserver = SpaceReserver.Plain) = ModelDownloader(
        mac = ControlClient(ServerConfig("127.0.0.1", mac.port, "test-token")),
        store = store,
        space = space,
        pollMillis = 10,
        retryDelays = listOf(10, 10, 10),
        progressEveryBytes = 256 * 1024,
    )

    private fun installedBytes(): ByteArray = store.file(store.installed(mac.id)!!).readBytes()

    @Test
    fun `the Mac fetches it first, then the phone copies and verifies it`() = runBlocking {
        val steps = mutableListOf<DownloadStep>()
        val entry = downloader().download(mac.id) { steps += it }

        assertEquals(mac.sha256, entry.model.sha256)
        assertArrayEquals(bytes, installedBytes())
        assertTrue("the Mac's fetch is followed", steps.any { it is DownloadStep.OnMac })
        assertTrue("bytes arriving are reported", steps.any { it is DownloadStep.Copying })
        assertEquals(DownloadStep.Verifying, steps.last())
        assertEquals(1, mac.count("POST", "/prepare"))
        assertEquals("nothing half-done is left", setOf("installed.json", "${mac.sha256}.gguf"), store.directory.list()!!.toSet())
        assertTrue(store.isIntact(entry))
    }

    @Test
    fun `a transfer cut at 40 percent resumes with Range and If-Range, and gets a 206`() = runBlocking {
        mac.ready = true
        val cut = (bytes.size * 0.4).toLong()
        mac.dropAfter = cut
        downloader().download(mac.id)

        assertArrayEquals(bytes, installedBytes())
        val files = mac.requests.filter { it.path.endsWith("/file") }
        assertEquals(2, files.size)
        assertNull("the first asks for the whole file", files[0].headers["range"])
        assertEquals("bytes=$cut-", files[1].headers["range"])
        assertEquals("the resume is conditional on the same file", "\"${mac.sha256}\"", files[1].headers["if-range"])
    }

    @Test
    fun `a 200 to a ranged request means the partial is another file's, so it is discarded`() = runBlocking {
        mac.ready = true
        // Part of something else on disk, recorded as 1 MB received.
        val part = store.partFile(mac.sha256)
        part.parentFile!!.mkdirs()
        part.writeBytes(ByteArray(1024 * 1024) { 0x55 })
        store.noteReceived(mac.sha256, 1024 * 1024)
        mac.ignoreRange = 1

        downloader().download(mac.id)

        assertArrayEquals("the old bytes are gone, and all of the new ones are here", bytes, installedBytes())
        assertEquals("one request, answered whole", 1, mac.count("GET", "/file"))
    }

    @Test
    fun `a partial that is already complete is checked, not fetched again`() = runBlocking {
        mac.ready = true
        val part = store.partFile(mac.sha256)
        part.parentFile!!.mkdirs()
        part.writeBytes(bytes)
        // The phone was killed between the last byte and the checksum.
        store.noteReceived(mac.sha256, bytes.size.toLong())

        val entry = downloader().download(mac.id)
        assertEquals(mac.sha256, entry.model.sha256)
        assertEquals("nothing was fetched: it was all here", 0, mac.count("GET", "/file"))
    }

    @Test
    fun `a 416 means the partial does not fit the Mac's file, so it goes and the phone starts over`() = runBlocking {
        mac.ready = true
        // The Mac's file is shorter than the phone's partial says it got to.
        val short = bytes.size - 1000L
        mac.servedSize = short
        val part = store.partFile(mac.sha256)
        part.parentFile!!.mkdirs()
        part.writeBytes(bytes.copyOf(short.toInt()))
        store.noteReceived(mac.sha256, short)

        try {
            downloader().download(mac.id)
            fail("a Mac serving a different size than it listed is not a file to install")
        } catch (failure: DownloadFailure) {
            assertTrue(failure.message!!, failure.message!!.contains("not the"))
            assertTrue("worth trying again later", failure.transient)
        }
        val files = mac.requests.filter { it.path.endsWith("/file") }
        assertEquals("bytes=$short-", files.first().headers["range"])
        assertNull("after the 416, the whole file is asked for", files[1].headers["range"])
        assertNull(store.installed(mac.id))
    }

    @Test
    fun `a 409 while the Mac's copy is not ready goes back to waiting for it`() = runBlocking {
        mac.ready = true
        mac.notReady = 2
        downloader().download(mac.id)

        assertArrayEquals(bytes, installedBytes())
        assertTrue("it asked the Mac again", mac.count("POST", "/prepare") >= 2)
    }

    @Test
    fun `a 503 says the Mac's drive is missing and stops`() = runBlocking {
        mac.driveMissing = true
        try {
            downloader().download(mac.id)
            fail("a missing drive is not something to retry blindly")
        } catch (failure: DownloadFailure) {
            assertTrue(failure.message!!, failure.message!!.contains("Demo SSD"))
            assertFalse(failure.transient)
        }
    }

    @Test
    fun `a checksum mismatch deletes the file, asks the Mac to verify once, and fetches once more`() = runBlocking {
        mac.ready = true
        mac.corrupt = 1
        downloader().download(mac.id)

        assertArrayEquals(bytes, installedBytes())
        assertEquals("verify=1, exactly once", 1, mac.count("POST", "/prepare", "verify=1"))
        val files = mac.requests.filter { it.path.endsWith("/file") }
        assertEquals(2, files.size)
        assertNull("the second fetch is from zero, not a resume of bad bytes", files[1].headers["range"])
    }

    @Test
    fun `a second mismatch stops, keeps nothing, and says so`() = runBlocking {
        mac.ready = true
        mac.corrupt = 2
        try {
            downloader().download(mac.id)
            fail("two bad copies in a row is not something to loop on")
        } catch (failure: DownloadFailure) {
            assertTrue(failure.message!!, failure.message!!.contains("checksum"))
            assertFalse(failure.transient)
        }
        assertEquals(1, mac.count("POST", "/prepare", "verify=1"))
        assertNull(store.installed(mac.id))
        assertFalse("no partial is left to resume from", store.partFile(mac.sha256).exists())
    }

    @Test
    fun `a chat-only phone is told why, in the Mac's words`() = runBlocking {
        mac.scope = "chat"
        try {
            downloader().download(mac.id)
            fail()
        } catch (failure: DownloadFailure) {
            assertTrue(failure.message!!, failure.message!!.contains("paired for chat only"))
            assertFalse(failure.transient)
        }
    }

    @Test
    fun `no room on the phone is found out before the first byte`() = runBlocking {
        mac.ready = true
        val tight = object : SpaceReserver {
            override fun allocatableBytes(directory: File) = 1024L
            override fun reserve(file: File, totalBytes: Long) = fail("nothing is reserved without room")
        }
        try {
            downloader(tight).download(mac.id)
            fail()
        } catch (failure: DownloadFailure) {
            assertTrue(failure.message!!, failure.message!!.contains("free"))
        }
        assertFalse(store.partFile(mac.sha256).exists())
    }

    @Test
    fun `the whole file's room is reserved up front`() = runBlocking {
        mac.ready = true
        var reserved = 0L
        val recording = object : SpaceReserver {
            override fun allocatableBytes(directory: File) = Long.MAX_VALUE
            override fun reserve(file: File, totalBytes: Long) {
                reserved = totalBytes
                SpaceReserver.Plain.reserve(file, totalBytes)
            }
        }
        downloader(recording).download(mac.id)
        assertEquals(bytes.size.toLong(), reserved)
    }

    @Test
    fun `a Mac that failed for want of room says so in its own words, and it is not retried`() = runBlocking {
        mac.macFailure = "diskFull" to "The Mac needs 1.3 GB free for Qwen3.5 2B."
        mac.prepareClearsFailure = false
        try {
            downloader().download(mac.id)
            fail()
        } catch (failure: DownloadFailure) {
            assertTrue(failure.message!!, failure.message!!.contains("1.3 GB free"))
            assertFalse(failure.transient)
        }
        assertEquals("asked once: a full disk is not fixed by asking again", 1, mac.count("POST", "/prepare"))
    }

    @Test
    fun `a cancelled download keeps what arrived, so the next one carries on`() = runBlocking {
        mac.ready = true
        mac.dropAfter = (bytes.size / 2).toLong()
        val first = async {
            runCatching {
                ModelDownloader(
                    mac = ControlClient(ServerConfig("127.0.0.1", mac.port, "test-token")),
                    store = store, pollMillis = 10, retryDelays = listOf(5_000),
                    progressEveryBytes = 256 * 1024,
                ).download(mac.id)
            }
        }
        // The drop sends it into its retry delay; cancel it there.
        while (mac.count("GET", "/file") < 1 || store.receivedBytes(mac.sha256) == 0L) delay(10)
        delay(100)
        first.cancel()
        val kept = store.receivedBytes(mac.sha256)
        assertTrue("half the file is kept: $kept", kept >= bytes.size / 2 - 1)

        downloader().download(mac.id)
        assertArrayEquals(bytes, installedBytes())
        assertEquals("bytes=$kept-", mac.requests.last { it.path.endsWith("/file") }.headers["range"])
    }

    @Test
    fun `a Mac serving a different file than it listed is stopped at the first header`() = runBlocking {
        mac.ready = true
        // The listing says one digest; the file comes with another. Nothing is kept and
        // nothing is hashed: 3 MB is cheap to notice here and expensive to notice later.
        mac.servedDigest = "9".repeat(64)
        try {
            downloader().download(mac.id)
            fail("a file the Mac itself says is a different one was accepted")
        } catch (stopped: DownloadFailure) {
            assertTrue(stopped.message!!.contains("serving a different file"))
            assertTrue("worth trying again: the Mac may be mid-swap", stopped.transient)
        }
        assertNull("nothing was installed", store.installed(mac.id))
        assertEquals("and nothing was left on the phone", 0, store.receivedBytes(mac.sha256))
    }

    @Test
    fun `a Mac that lists something which is not a checksum is refused before a byte moves`() = runBlocking {
        mac.ready = true
        mac.listedDigest = "../../../../data/data/dev.siliconoptimizer.buddy/files/owned"
        val before = mac.requests.size
        try {
            downloader().download(mac.id)
            fail("a path where a digest should be was used")
        } catch (refused: DownloadFailure) {
            assertTrue(refused.message!!.contains("without a proper checksum"))
            assertFalse("this is not a network problem", refused.transient)
        }
        assertEquals("only the list was read", before + 1, mac.requests.size)
        assertEquals("nothing was written anywhere", 0, store.directory.list()!!.size)
    }

    @Test
    fun `a model already here and intact is not fetched again`() = runBlocking {
        mac.ready = true
        downloader().download(mac.id)
        val requests = mac.requests.size
        downloader().download(mac.id)
        assertEquals("only the list was read", requests + 1, mac.requests.size)
    }
}
