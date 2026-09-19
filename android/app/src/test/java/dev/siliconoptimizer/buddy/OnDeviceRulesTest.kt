package dev.siliconoptimizer.buddy

import android.net.NetworkCapabilities
import dev.siliconoptimizer.buddy.ondevice.DownloadNetwork
import dev.siliconoptimizer.buddy.ondevice.DownloadState
import dev.siliconoptimizer.buddy.ondevice.FallbackDecision
import dev.siliconoptimizer.buddy.ondevice.FallbackPolicy
import dev.siliconoptimizer.buddy.ondevice.InstalledPhoneModel
import dev.siliconoptimizer.buddy.ondevice.MacState
import dev.siliconoptimizer.buddy.ondevice.ModelStore
import dev.siliconoptimizer.buddy.ondevice.NetworkNow
import dev.siliconoptimizer.buddy.ondevice.PartialDownload
import dev.siliconoptimizer.buddy.ondevice.PhoneHistory
import dev.siliconoptimizer.buddy.ondevice.OnDeviceNotices
import dev.siliconoptimizer.buddy.ondevice.ResourceGuard
import dev.siliconoptimizer.buddy.ondevice.ThinkingSplitter
import dev.siliconoptimizer.buddy.ondevice.backendSummary
import dev.siliconoptimizer.buddy.chat.ChatMessage
import dev.siliconoptimizer.buddy.reach.BuddyLink
import dev.siliconoptimizer.buddy.reach.BuddySnapshot
import dev.siliconoptimizer.buddy.reach.WidgetTimeline
import dev.siliconoptimizer.buddy.tile.BuddyTileService
import dev.siliconoptimizer.buddy.transport.ChatStreamEvent
import dev.siliconoptimizer.buddy.transport.PhoneModel
import dev.siliconoptimizer.buddy.transport.PhoneModelMeasured
import dev.siliconoptimizer.buddy.transport.PhoneModelOnMac
import dev.siliconoptimizer.buddy.transport.PhoneModelRecommended
import dev.siliconoptimizer.buddy.transport.PhoneModelSource
import dev.siliconoptimizer.buddy.transport.PhoneModelStream
import dev.siliconoptimizer.buddy.transport.PhoneModelThreadSample
import dev.siliconoptimizer.buddy.transport.Reachability
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The rules around the phone's own model, each as a table rather than a hope. */
class OnDeviceRulesTest {

    @get:Rule val folder = TemporaryFolder()

    private fun model(
        id: String,
        minFree: Long,
        isDefault: Boolean = false,
        size: Long = 1_000,
        threads: Pair<Int, Int> = 6 to 4,
    ) = InstalledPhoneModel(
        model = PhoneModel(
            id = id, label = id.uppercase(), isDefault = isDefault, sizeBytes = size, sha256 = "00",
            licence = "Apache-2.0", source = PhoneModelSource("r", "c", "f"),
            onMac = PhoneModelOnMac("ready"),
            recommended = PhoneModelRecommended(threads.first, threads.second, 4096, minFree, false),
            slowerOnPhone = false,
        ),
        fileName = "f", verifiedBytes = size, verifiedModifiedAt = 0, installedAt = 0,
    )

    private val qwen = model("qwen", 3_100_000_000, isDefault = true, size = 1_296_764_000)
    private val gemma = model("gemma", 4_700_000_000, size = 3_349_516_256, threads = 4 to 6)

    // MARK: - FallbackPolicy: the whole truth table

    @Test
    fun `the phone is offered exactly when the Mac is out of reach and a model is here and runnable`() {
        var offers = 0
        for (mac in MacState.entries) for (installed in listOf(emptyList(), listOf(qwen))) for (runtime in listOf(true, false))
            for (inPhone in listOf(true, false)) {
                val decision = FallbackPolicy.decide(mac, installed, runtime, inPhone)
                val expected = mac.isOutOfReach && installed.isNotEmpty() && runtime && !inPhone
                assertEquals("$mac installed=${installed.size} runtime=$runtime inPhone=$inPhone", expected, decision is FallbackDecision.Offer)
                if (expected) offers++
                // Why not, when not: the first reason that applies.
                if (!expected) {
                    val why = when {
                        inPhone -> FallbackDecision.AlreadyOnPhone
                        !mac.isOutOfReach -> FallbackDecision.MacAnswering
                        !runtime -> FallbackDecision.NotAvailable
                        else -> FallbackDecision.NoModel
                    }
                    assertEquals(why, decision)
                }
            }
        assertEquals("unreachable, not running, timed out, unpaired", 4, offers)
    }

    @Test
    fun `out of reach means these four and nothing else`() {
        assertEquals(
            setOf(MacState.Unreachable, MacState.AppNotRunning, MacState.TimedOut, MacState.Unpaired),
            MacState.entries.filter { it.isOutOfReach }.toSet(),
        )
        assertEquals(MacState.Unreachable, MacState.of(TransportError.Unreachable("h")))
        assertEquals(MacState.AppNotRunning, MacState.of(TransportError.AppNotRunning))
        assertEquals(MacState.TimedOut, MacState.of(TransportError.TimedOut))
        assertEquals(MacState.Unpaired, MacState.of(TransportError.Unauthorized))
        assertEquals(MacState.Unpaired, MacState.of(TransportError.NotConfigured))
        assertNull("a Stop is not a verdict on the Mac", MacState.of(TransportError.Cancelled))
        assertEquals(MacState.Refused, MacState.of(TransportError.BadRequest("No model is loaded.")))
        assertEquals(MacState.Refused, MacState.of(TransportError.Conflict("busy")))
        assertEquals(MacState.Unpaired, MacState.of(Reachability.Ready("1", null), paired = false))
        assertEquals(MacState.Unreachable, MacState.of(Reachability.Unreachable("h"), paired = true))
        assertEquals(MacState.AppNotRunning, MacState.of(Reachability.AppNotRunning, paired = true))
        assertEquals(MacState.Unpaired, MacState.of(Reachability.Unauthorized, paired = true))
        assertEquals(MacState.Answering, MacState.of(Reachability.Ready("1", null), paired = true))
    }

    @Test
    fun `the offered model is the owner's pick, else the Mac's default, else the smallest`() {
        val small = model("small", 1, size = 10)
        assertEquals("gemma", FallbackPolicy.choose(listOf(qwen, gemma, small), preferredID = "gemma")!!.id)
        assertEquals("qwen", FallbackPolicy.choose(listOf(gemma, qwen, small))!!.id)
        assertEquals("small", FallbackPolicy.choose(listOf(gemma, small))!!.id)
        assertEquals("a preference for a deleted model falls through", "qwen", FallbackPolicy.choose(listOf(qwen), "gone")!!.id)
        assertNull(FallbackPolicy.choose(emptyList()))
    }

    // MARK: - ResourceGuard: heat

    @Test
    fun `heat thins the threads, keeps answering when severe, and stops only when critical`() {
        val rec = qwen.model.recommended
        fun threads(status: Int) = (ResourceGuard.heat(status, rec) as ResourceGuard.Heat.Run).threads
        assertEquals(ResourceGuard.Threads(6, 4), threads(ResourceGuard.THERMAL_NONE))
        assertEquals(ResourceGuard.Threads(6, 4), threads(ResourceGuard.THERMAL_LIGHT))
        assertEquals(ResourceGuard.Threads(4, 3), threads(ResourceGuard.THERMAL_MODERATE))
        assertEquals(ResourceGuard.Threads(3, 2), threads(ResourceGuard.THERMAL_SEVERE))
        assertTrue((ResourceGuard.heat(ResourceGuard.THERMAL_MODERATE, rec) as ResourceGuard.Heat.Run).reduced)
        assertFalse((ResourceGuard.heat(ResourceGuard.THERMAL_LIGHT, rec) as ResourceGuard.Heat.Run).reduced)
        for (status in listOf(ResourceGuard.THERMAL_CRITICAL, ResourceGuard.THERMAL_EMERGENCY, ResourceGuard.THERMAL_SHUTDOWN)) {
            assertEquals(ResourceGuard.Heat.Stop, ResourceGuard.heat(status, rec))
        }
        // Gemma's recommendation is the other way round: more threads writing than reading.
        val gemmaRec = gemma.model.recommended
        assertEquals(ResourceGuard.Threads(3, 4), (ResourceGuard.heat(ResourceGuard.THERMAL_MODERATE, gemmaRec) as ResourceGuard.Heat.Run).threads)
        assertEquals(ResourceGuard.Threads(2, 3), (ResourceGuard.heat(ResourceGuard.THERMAL_SEVERE, gemmaRec) as ResourceGuard.Heat.Run).threads)
        // Never below two, and never more than asked for.
        val two = PhoneModelRecommended(2, 2, 4096, 1, false)
        assertEquals(ResourceGuard.Threads(2, 2), (ResourceGuard.heat(ResourceGuard.THERMAL_SEVERE, two) as ResourceGuard.Heat.Run).threads)
        val one = PhoneModelRecommended(1, 1, 4096, 1, false)
        assertEquals(ResourceGuard.Threads(1, 1), (ResourceGuard.heat(ResourceGuard.THERMAL_SEVERE, one) as ResourceGuard.Heat.Run).threads)
    }

    // MARK: - ResourceGuard: memory

    @Test
    fun `a model loads only with its minimum free, and a smaller one that fits is offered instead`() {
        assertEquals(ResourceGuard.Memory.Load, ResourceGuard.memory(qwen, 3_100_000_000, false, listOf(qwen)))
        val refused = ResourceGuard.memory(gemma, 4_000_000_000, false, listOf(qwen, gemma)) as ResourceGuard.Memory.Refuse
        assertEquals(4_700_000_000, refused.needed)
        assertEquals(4_000_000_000, refused.available)
        assertEquals("qwen", refused.alternative?.id)
        assertTrue(ResourceGuard.refusal(gemma, refused).contains("QWEN fits"))

        val none = ResourceGuard.memory(gemma, 3_000_000_000, false, listOf(qwen, gemma)) as ResourceGuard.Memory.Refuse
        assertNull("qwen does not fit in 3.0 GB either", none.alternative)
        assertTrue(ResourceGuard.refusal(gemma, none).contains("Close some apps"))

        val low = ResourceGuard.memory(qwen, 9_000_000_000, true, listOf(qwen)) as ResourceGuard.Memory.Refuse
        assertNull("Android already short of memory refuses whatever the number", low.alternative)
    }

    @Test
    fun `only a background trim unloads at once`() {
        assertFalse("UI_HIDDEN is every trip to the home screen", ResourceGuard.unloadsAtOnce(20))
        assertFalse(ResourceGuard.unloadsAtOnce(15))
        assertTrue(ResourceGuard.unloadsAtOnce(40))
        assertTrue(ResourceGuard.unloadsAtOnce(60))
        assertTrue(ResourceGuard.unloadsAtOnce(80))
        assertEquals(30_000L, ResourceGuard.BACKGROUND_GRACE_MS)
        assertEquals(300_000L, ResourceGuard.IDLE_MS)
    }

    // MARK: - Thinking

    @Test
    fun `a leading think block is folded away, split however the tokens fall`() {
        val splitter = ThinkingSplitter()
        val events = listOf("<th", "ink>Weigh", " it</th", "ink>\n\nThe answer", " is 4.").flatMap { splitter.feed(it) } + splitter.finish()
        assertEquals("Weigh it", events.filterIsInstance<ChatStreamEvent.Reasoning>().joinToString("") { it.text })
        assertEquals("The answer is 4.", events.filterIsInstance<ChatStreamEvent.Token>().joinToString("") { it.text })
    }

    @Test
    fun `an answer without thinking passes straight through, markup and all`() {
        val splitter = ThinkingSplitter()
        val events = listOf("<b>Bold</b>", " and plain.").flatMap { splitter.feed(it) } + splitter.finish()
        assertTrue(events.all { it is ChatStreamEvent.Token })
        assertEquals("<b>Bold</b> and plain.", events.joinToString("") { (it as ChatStreamEvent.Token).text })
    }

    @Test
    fun `thinking that never closes stays thinking`() {
        val splitter = ThinkingSplitter()
        val events = splitter.feed("<think>still going") + splitter.finish()
        assertTrue(events.all { it is ChatStreamEvent.Reasoning })
    }

    // MARK: - Wi-Fi

    private val wifi = NetworkCapabilities.TRANSPORT_WIFI
    private val cell = NetworkCapabilities.TRANSPORT_CELLULAR
    private val vpn = NetworkCapabilities.TRANSPORT_VPN
    private val ethernet = NetworkCapabilities.TRANSPORT_ETHERNET

    private fun on(vararg transports: Int, unmetered: Boolean = true, under: List<NetworkNow> = emptyList()) =
        NetworkNow(transports.toSet(), unmetered, under)

    @Test
    fun `Wi-Fi only, over the tailnet too, until the owner says mobile data`() {
        assertTrue(DownloadNetwork.allows(on(wifi), false))
        assertTrue(DownloadNetwork.allows(on(ethernet), false))
        assertTrue("Tailscale over Wi-Fi", DownloadNetwork.allows(on(vpn, wifi), false))
        assertFalse(DownloadNetwork.allows(on(cell), false))
        assertFalse("Tailscale over mobile data", DownloadNetwork.allows(on(vpn, cell), false))
        assertFalse("no network at all", DownloadNetwork.allows(NetworkNow(emptySet(), true), false))
        assertTrue(DownloadNetwork.allows(on(cell), true))
        assertTrue(DownloadNetwork.allows(on(vpn, cell), true))
    }

    @Test
    fun `Wi-Fi the owner pays for by the megabyte is not the Wi-Fi they meant`() {
        // A phone tethered to another phone, or a hotel network Android has marked
        // metered: Wi-Fi by transport, somebody's data allowance by the byte.
        assertFalse("metered Wi-Fi", DownloadNetwork.allows(on(wifi, unmetered = false), false))
        assertFalse("metered Wi-Fi under Tailscale", DownloadNetwork.allows(on(vpn, wifi, unmetered = false), false))
        assertTrue("and the owner can still say yes to it", DownloadNetwork.allows(on(wifi, unmetered = false), true))
    }

    @Test
    fun `a tunnel that says nothing about itself is judged by what it rides on`() {
        // Some builds hand out a VPN network with no underlying transports on it at all.
        val overWifi = on(vpn, under = listOf(on(wifi), on(cell, unmetered = false)))
        val overMobile = on(vpn, unmetered = false, under = listOf(on(cell, unmetered = false)))
        val overMeteredWifi = on(vpn, under = listOf(on(wifi, unmetered = false)))
        assertTrue("Tailscale with Wi-Fi underneath", DownloadNetwork.allows(overWifi, false))
        assertFalse("Tailscale with only mobile data underneath", DownloadNetwork.allows(overMobile, false))
        assertFalse("Tailscale over metered Wi-Fi", DownloadNetwork.allows(overMeteredWifi, false))
        assertFalse("nothing underneath and nothing said", DownloadNetwork.allows(on(vpn), false))
    }

    @Test
    fun `the phone's model reads the newest messages and says when it left some out`() {
        val short = (1..3).map { ChatMessage(role = ChatMessage.ROLE_USER, content = "a question") }
        assertEquals(3, PhoneHistory.cap(short).messages.size)
        assertFalse(PhoneHistory.cap(short).wasTrimmed)

        // Twenty turns of 400 words: far past a 1,500-token budget.
        val long = (1..20).map { ChatMessage(role = ChatMessage.ROLE_USER, content = "word ".repeat(400)) }
        val capped = PhoneHistory.cap(long)
        assertTrue("older turns were dropped", capped.wasTrimmed)
        assertEquals("all of them are the newest ones", long.takeLast(capped.messages.size), capped.messages)
        assertTrue(
            "and what is left fits the budget",
            capped.messages.sumOf { PhoneHistory.tokensIn(it.content) } <= PhoneHistory.BUDGET_TOKENS,
        )

        // The newest message is the question being asked, however long it is.
        val onlyOne = listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = "word ".repeat(4000)))
        assertEquals(1, PhoneHistory.cap(onlyOne).messages.size)
        assertFalse(PhoneHistory.cap(onlyOne).wasTrimmed)
    }

    @Test
    fun `a part-finished download and what a model needs to run are both said plainly`() {
        assertEquals(
            "38% of 1.00 GB is already here — paused",
            PartialDownload.line(380_000_000, 1_000_000_000),
        )
        assertNull("nothing here is not a row", PartialDownload.line(0, 1_000_000_000))
        assertNull(PartialDownload.line(null, 1_000_000_000))
        assertTrue(
            PartialDownload.memoryLine(3_100_000_000, 1_200_000_000)
                .startsWith("Needs 3.10 GB of free memory to run · 1.20 GB free now"),
        )
        assertEquals(
            "Needs 3.10 GB of free memory to run",
            PartialDownload.memoryLine(3_100_000_000, null),
        )
    }

    @Test
    fun `copied out of the app, a phone answer still says it came from the phone`() {
        assertEquals("Paris.", OnDeviceNotices.labelled("Paris.", null))
        assertEquals(
            "Paris.\n\n— On this phone · Qwen3.5 2B",
            OnDeviceNotices.labelled("Paris.", "Qwen3.5 2B"),
        )
    }

    // MARK: - What the tile, the widget and the link say

    @Test
    fun `the tile says the phone can stand in when the Mac did not answer`() {
        assertEquals(OnDeviceNotices.TILE_READY, BuddyTileService.subtitle(true, "Qwen3 4B", 0, macReachable = false, phoneReady = true))
        assertEquals("Mac unreachable · phone model ready", OnDeviceNotices.TILE_READY)
        assertEquals(OnDeviceNotices.MAC_UNREACHABLE, BuddyTileService.subtitle(true, "Qwen3 4B", 0, macReachable = false, phoneReady = false))
        assertEquals("Qwen3 4B", BuddyTileService.subtitle(true, "Qwen3 4B", 0, macReachable = true, phoneReady = true))
        assertEquals("approvals still come first", "1 approval waiting", BuddyTileService.subtitle(true, null, 1, false, true))
        assertEquals("Not paired", BuddyTileService.subtitle(false, null, 0, false, true))
    }

    @Test
    fun `the widget says Mac unreachable, offers the phone, and labels the phone's answers`() = runBlocking {
        val down = WidgetTimeline.entry(
            transport = object : HangingTransport() {
                override suspend fun status() = throw TransportError.Unreachable("100.64.0.9")
            },
            stored = BuddySnapshot(lastAnswer = "Paris.", lastAnswerOnPhone = "Qwen3.5 2B"),
            quickPrompt = "Hi", phoneReady = true,
        )
        assertTrue(down.macUnreachable)
        assertTrue(down.offersPhone)
        assertEquals(OnDeviceNotices.TILE_READY, down.statusLine)
        assertEquals("On this phone · Qwen3.5 2B", down.answerLabel)

        val noModel = down.copy(phoneReady = false)
        assertFalse(noModel.offersPhone)
        assertEquals("Mac unreachable", noModel.statusLine)

        val refusing = WidgetTimeline.entry(
            transport = object : HangingTransport() {
                override suspend fun status() = throw TransportError.Forbidden("no")
            },
            stored = null, quickPrompt = "Hi", phoneReady = true,
        )
        assertFalse("a Mac that refuses is not out of reach", refusing.offersPhone)
        assertNull("the Mac's own answers carry no label", BuddySnapshot(lastAnswer = "x").let {
            down.copy(snapshot = it).answerLabel
        })
    }

    @Test
    fun `the link can ask for the offer, and nothing more`() {
        assertEquals(BuddyLink.Compose(null, offerPhone = true), BuddyLink.parse("siliconbuddy", "ask", null, null, "phone"))
        assertEquals(BuddyLink.Compose(null, offerPhone = false), BuddyLink.parse("siliconbuddy", "ask", null, null, "something"))
        assertEquals(BuddyLink.Compose("hi", offerPhone = false), BuddyLink.parse("siliconbuddy", "ask", "hi", null))
        assertEquals("siliconbuddy://ask?offer=phone", BuddyLink.ASK_ON_PHONE_URI)
    }

    // MARK: - What Settings says

    @Test
    fun `download states read as sentences`() {
        assertEquals("Waiting for Wi-Fi that isn't metered", DownloadState.WaitingForWifi("Qwen").line)
        assertEquals("Your Mac is fetching it from Hugging Face · 34%", DownloadState.OnMac("Qwen", "fetching", 0.34).line)
        assertEquals("Your Mac is checking it", DownloadState.OnMac("Qwen", "checking", null).line)
        assertEquals("Copying from your Mac · 50% of 1.30 GB", DownloadState.Copying("Qwen", 648_382_000, 1_296_764_000).line)
        assertEquals(0.5, DownloadState.Copying("Qwen", 50, 100).progress!!, 1e-9)
        assertFalse(DownloadState.Done("Qwen").isActive)
    }

    @Test
    fun `what the Mac measured is said once, plainly`() {
        val entry = qwen.model.copy(
            measured = PhoneModelMeasured(
                "Galaxy S24 Ultra", "llama.cpp b11053, CPU", "phone hot and charging", 19.2,
                listOf(PhoneModelThreadSample(4, 19.2)), 122.9, 2.5, true, null, false, 2_586_836_992, 640,
            ),
        )
        assertEquals(
            "On a Galaxy S24 Ultra: about 2.5 s to the first word of a long question, then 19 tokens a second.",
            OnDeviceNotices.expectation(entry),
        )
        assertEquals("1.30 GB · Apache-2.0 · recommended", OnDeviceNotices.summary(entry))
        assertTrue(OnDeviceNotices.summary(gemma.model.copy(slowerOnPhone = true)).endsWith("slower on phones"))
    }

    @Test
    fun `the CPU variant is named the way a person reads it`() {
        assertEquals(
            "ARMv8.6 (i8mm, dot product, KleidiAI)",
            backendSummary("CPU libggml-cpu-android_armv8.6_1.so [NEON,ARM_FMA,FP16_VA,DOTPROD,MATMUL_INT8,KLEIDIAI,REPACK]"),
        )
        assertEquals("ARMv8.2 (dot product)", backendSummary("CPU libggml-cpu-android_armv8.2_2.so [NEON,DOTPROD]"))
    }

    @Test
    fun `Content-Range and entity tags are read the way the Mac writes them`() {
        assertEquals(100L to 1000L, PhoneModelStream.parseContentRange("bytes 100-999/1000"))
        assertEquals(null to 1000L, PhoneModelStream.parseContentRange("bytes */1000"))
        assertEquals(null to null, PhoneModelStream.parseContentRange(null))
        assertEquals("abc", PhoneModelStream.opaqueTag("\"abc\""))
        assertEquals("abc", PhoneModelStream.opaqueTag("W/\"abc\""))
        assertNull(PhoneModelStream.opaqueTag("\"\""))
    }

    // MARK: - The store

    @Test
    fun `the store keeps verified models, notices a changed file, and deletes everything`() {
        val store = ModelStore(folder.newFolder())
        val bytes = ByteArray(4096) { it.toByte() }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val listed = qwen.model.copy(sha256 = sha, sizeBytes = bytes.size.toLong())
        store.partFile(sha).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        store.noteReceived(sha, 1000)
        assertEquals(1000, store.receivedBytes(sha))
        val entry = store.install(listed)
        assertEquals(0, store.receivedBytes(sha))
        assertTrue(store.isIntact(entry))
        assertEquals(listOf("qwen"), store.installed().map { it.id })

        // Changed behind its back: not intact, and hashed again before use.
        store.file(entry).writeBytes(bytes.reversedArray())
        store.file(entry).setLastModified(entry.verifiedModifiedAt + 5_000)
        assertFalse(store.isIntact(entry))
        assertFalse("a changed file fails its checksum and is forgotten", store.reverify(entry))
        assertNull(store.installed("qwen"))
        assertFalse(store.file(entry).exists())

        store.partFile(sha).writeBytes(bytes)
        store.noteReceived(sha, 10)
        store.deleteEverything("qwen", sha)
        assertEquals(0, store.receivedBytes(sha))
        assertFalse(store.partFile(sha).exists())
    }

    @Test
    fun `a new pin replaces the old file instead of leaving a gigabyte behind`() {
        val store = ModelStore(folder.newFolder())
        fun put(fill: Byte): Pair<String, java.io.File> {
            val bytes = ByteArray(4096) { fill }
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            store.partFile(sha).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
            val entry = store.install(qwen.model.copy(sha256 = sha, sizeBytes = bytes.size.toLong()))
            return sha to store.file(entry)
        }

        val (_, first) = put(1)
        assertTrue(first.exists())
        val (_, second) = put(2)

        assertTrue("the model the Mac pins now", second.exists())
        assertFalse("and not the one it used to", first.exists())
        assertEquals(listOf("qwen"), store.installed().map { it.id })
    }

    @Test
    fun `a checksum that is not a checksum never becomes a file name`() {
        val store = ModelStore(folder.newFolder())
        assertFalse(ModelStore.isDigest("../../../../data/data/dev.siliconoptimizer.buddy/files/owned"))
        assertFalse(ModelStore.isDigest("zz".repeat(32)))
        assertFalse(ModelStore.isDigest("ab".repeat(31)))
        assertTrue(ModelStore.isDigest("AB".repeat(32)))
        // Asked for one anyway, it refuses rather than writing wherever the string said.
        try {
            store.partFile("../../owned")
            org.junit.Assert.fail("a path was accepted where a digest belongs")
        } catch (refused: IllegalArgumentException) {
            assertTrue(refused.message!!.contains("64 hex characters"))
        }
        assertEquals(0, store.receivedBytes("../../owned"))
        store.discardPartial("../../owned")
        store.deleteEverything("qwen", "../../owned")
        assertEquals(
            "nothing was written under a name that came off the wire",
            emptyList<String>(),
            store.directory.list().orEmpty().filterNot { it == "installed.json" },
        )
    }
}
