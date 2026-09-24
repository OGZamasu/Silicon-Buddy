package dev.siliconoptimizer.buddy

import android.app.Application
import androidx.compose.runtime.snapshots.Snapshot
import dev.siliconoptimizer.buddy.chat.ChatMessage
import dev.siliconoptimizer.buddy.chat.ChatViewModel
import dev.siliconoptimizer.buddy.chat.ConversationStore
import dev.siliconoptimizer.buddy.ondevice.InstalledPhoneModel
import dev.siliconoptimizer.buddy.ondevice.MacState
import dev.siliconoptimizer.buddy.ondevice.OnDeviceChat
import dev.siliconoptimizer.buddy.ondevice.OnDeviceNotices
import dev.siliconoptimizer.buddy.ondevice.Preflight
import dev.siliconoptimizer.buddy.transport.CatalogModel
import dev.siliconoptimizer.buddy.transport.ChatMessageWire
import dev.siliconoptimizer.buddy.transport.ChatMetrics
import dev.siliconoptimizer.buddy.transport.ChatRequest
import dev.siliconoptimizer.buddy.transport.ChatResponse
import dev.siliconoptimizer.buddy.transport.ChatStreamEvent
import dev.siliconoptimizer.buddy.transport.ControlClient
import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.ConversationDetail
import dev.siliconoptimizer.buddy.transport.ConversationSummary
import dev.siliconoptimizer.buddy.transport.Health
import dev.siliconoptimizer.buddy.transport.ImageModel
import dev.siliconoptimizer.buddy.transport.InstalledModel
import dev.siliconoptimizer.buddy.transport.LoadRequest
import dev.siliconoptimizer.buddy.transport.Metrics
import dev.siliconoptimizer.buddy.transport.NodeAdvertisement
import dev.siliconoptimizer.buddy.transport.OnDeviceIds
import dev.siliconoptimizer.buddy.transport.PairResponse
import dev.siliconoptimizer.buddy.transport.PhoneModel
import dev.siliconoptimizer.buddy.transport.PhoneModelOnMac
import dev.siliconoptimizer.buddy.transport.PhoneModelRecommended
import dev.siliconoptimizer.buddy.transport.PhoneModelSource
import dev.siliconoptimizer.buddy.transport.Profile
import dev.siliconoptimizer.buddy.transport.Reachability
import dev.siliconoptimizer.buddy.transport.ServerConfig
import dev.siliconoptimizer.buddy.transport.ServerEvent
import dev.siliconoptimizer.buddy.transport.Status
import dev.siliconoptimizer.buddy.transport.SwarmView
import dev.siliconoptimizer.buddy.transport.TransportError
import dev.siliconoptimizer.buddy.transport.VideoModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The chat with the phone's own model in it: when it is offered, what tapping it does, and
 * — the part that matters most — that a conversation the phone answered never reaches the
 * Mac. Every call the chat makes to the Mac is counted, and for a phone conversation the
 * count stays at zero.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnDeviceChatTest {

    @get:Rule val folder = TemporaryFolder()
    @get:Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(30)

    /** A Mac that records every call and can be out of reach, or refuse, on cue. */
    class RecordingMac : ControlTransport {
        val calls = CopyOnWriteArrayList<String>()
        @Volatile var failure: TransportError? = null
        /** A Mac that answers everything else and falls over on this one route. */
        @Volatile var conversationsFailure: TransportError? = null
        @Volatile var keepsConversations = false
        val sent = CopyOnWriteArrayList<Pair<String, ChatMessageWire>>()

        private fun record(call: String) {
            calls += call
        }

        private fun fail(): Nothing? = failure?.let { throw it }

        override suspend fun health() = Health("ok", "test").also { record("health") }
        override suspend fun status() = Status("Ready").also { record("status") }
        override suspend fun profile(): Profile = throw TransportError.RouteUnavailable("/profile")
        override suspend fun metrics(): Metrics = throw TransportError.RouteUnavailable("/metrics")
        override suspend fun installed(): List<InstalledModel> = emptyList()
        override suspend fun catalog(category: String?, onlyRunnable: Boolean): List<CatalogModel> = emptyList()
        override suspend fun swarm(): SwarmView = SwarmView(emptyList())
        override suspend fun node(): NodeAdvertisement = throw TransportError.RouteUnavailable("/v1/node")
        override suspend fun videoModels(): List<VideoModel> = emptyList()
        override suspend fun imageModels(): List<ImageModel> = emptyList()
        override suspend fun load(request: LoadRequest): Status = throw TransportError.RouteUnavailable("/load")
        override suspend fun install(request: LoadRequest): String = ""
        override suspend fun unload() = Unit
        override suspend fun chat(request: ChatRequest): ChatResponse {
            record("chat")
            fail()
            return ChatResponse("From the Mac.", promptTokens = 3, generatedTokens = 3, tokensPerSecond = 30.0)
        }

        override suspend fun pair(code: String, deviceName: String, platform: String): PairResponse =
            throw TransportError.RouteUnavailable("/buddy/pair")

        override fun chatStream(request: ChatRequest): Flow<ChatStreamEvent> = flow {
            record("chatStream")
            fail()
            emit(ChatStreamEvent.Token("From the Mac."))
            emit(ChatStreamEvent.Finished(ChatMetrics(3, 3, 30.0)))
        }

        override fun events(): Flow<ServerEvent> = flow { }

        override suspend fun conversations(): List<ConversationSummary> {
            record("conversations")
            conversationsFailure?.let { throw it }
            fail()
            if (!keepsConversations) throw TransportError.RouteUnavailable("/conversations")
            return listOf(ConversationSummary("mac-1", "On the Mac", "2026-09-19T00:00:00Z", 2))
        }

        /** The ids this Mac has actually heard of — it 404s on anything else, as a Mac does. */
        val known = java.util.concurrent.CopyOnWriteArraySet(listOf("mac-1"))

        override suspend fun createConversation(title: String?): ConversationSummary {
            record("createConversation:$title")
            fail()
            known += "mac-new"
            return ConversationSummary("mac-new", title ?: "New conversation", "2026-09-19T00:00:00Z", 0)
        }

        override suspend fun conversation(id: String): ConversationDetail {
            record("conversation:$id")
            fail()
            if (keepsConversations && id !in known) {
                throw TransportError.NotFound("That conversation isn't on your Mac any more.")
            }
            return ConversationDetail(id, "On the Mac", "2026-09-19T00:00:00Z", messages = emptyList())
        }

        override fun sendMessage(conversationID: String, message: ChatMessageWire, maxTokens: Int?): Flow<ChatStreamEvent> = flow {
            record("sendMessage:$conversationID")
            fail()
            if (keepsConversations && conversationID !in known) {
                throw TransportError.NotFound("That conversation isn't on your Mac any more.")
            }
            sent += conversationID to message
            emit(ChatStreamEvent.Token("From the Mac."))
            emit(ChatStreamEvent.Finished(ChatMetrics(3, 3, 30.0)))
        }
    }

    /** The phone's side: models on record, and an answer that is always the same. */
    inner class FakePhone(var models: List<InstalledPhoneModel>) : OnDeviceChat {
        override val conversations = ConversationStore(folder.newFolder())
        override fun installed() = models
        override var couldRun = true
        override var preferredID: String? = null
        var preflightAnswer: (InstalledPhoneModel) -> Preflight = { Preflight.Ready }
        val answered = CopyOnWriteArrayList<Pair<String, List<ChatMessage>>>()

        /** Held open to stand for the minute a real model takes to load. */
        @Volatile var loading: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val preflights = java.util.concurrent.atomic.AtomicInteger(0)

        override suspend fun preflight(model: InstalledPhoneModel): Preflight {
            preflights.incrementAndGet()
            loading?.await()
            return preflightAnswer(model)
        }

        val loadsCancelled = java.util.concurrent.atomic.AtomicInteger(0)

        /** Set to leave every answer half written, the model still going. */
        @Volatile var keepsWriting = false

        override fun answer(model: InstalledPhoneModel, history: List<ChatMessage>, maxTokens: Int) = flow {
            answered += model.id to history
            emit(ChatStreamEvent.Token("Hello "))
            if (keepsWriting) kotlinx.coroutines.awaitCancellation()
            emit(ChatStreamEvent.Token("from the phone."))
            emit(ChatStreamEvent.Finished(ChatMetrics(12, 4, 18.5, timeToFirstToken = 0.4)))
        }

        override fun cancelLoading() {
            loadsCancelled.incrementAndGet()
        }
    }

    private fun phoneModel(id: String, label: String, minFree: Long, isDefault: Boolean) = InstalledPhoneModel(
        model = PhoneModel(
            id = id, label = label, isDefault = isDefault, sizeBytes = 1_000, sha256 = "ab".repeat(32),
            licence = "Apache-2.0", source = PhoneModelSource("r", "c", "f.gguf"),
            onMac = PhoneModelOnMac("ready"),
            recommended = PhoneModelRecommended(6, 4, 4096, minFree, false),
            slowerOnPhone = !isDefault,
        ),
        fileName = "x.gguf", verifiedBytes = 1_000, verifiedModifiedAt = 0, installedAt = 0,
    )

    private val qwen by lazy { phoneModel("qwen3.5-2b-q4_0", "Qwen3.5 2B", 3_100_000_000, true) }
    private val gemma by lazy { phoneModel("gemma-4-e2b-q4_0", "Gemma 4 E2B", 4_700_000_000, false) }

    private lateinit var mac: RecordingMac
    private lateinit var phone: FakePhone
    private lateinit var chat: ChatViewModel
    private lateinit var store: ConversationStore

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mac = RecordingMac()
        phone = FakePhone(listOf(qwen))
        store = ConversationStore(folder.newFolder())
        chat = ChatViewModel(Application(), store, phone)
    }

    @After
    fun tearDown() {
        // The conversation stores read and write on Dispatchers.IO and come back to Main;
        // one still in the air at `resetMain` fails whichever test runs next. See
        // `stopForTest`.
        chat.stopForTest()
        Dispatchers.resetMain()
    }

    private fun await(what: String, condition: () -> Boolean) {
        // Twenty seconds, not ten: these wait on real coroutines on real dispatchers, and
        // the whole suite runs in one JVM — once in about ten full runs, a wait that takes
        // milliseconds on an idle machine did not get there in ten seconds on a busy one.
        // The class's own 30-second rule is still what catches a test that truly hangs.
        val deadline = System.currentTimeMillis() + 20_000
        while (!holds(condition)) {
            if (System.currentTimeMillis() > deadline) fail("never: $what")
            Thread.sleep(10)
        }
    }

    /**
     * [condition], read in a snapshot of its own. The view model changes its message
     * lists on the main dispatcher while this thread polls them, and iterating a
     * `SnapshotStateList` that changes underneath throws (`IndexOutOfBoundsException`,
     * `ConcurrentModificationException`) instead of answering. A read-only snapshot sees
     * one consistent state; the next poll sees the next.
     */
    private fun holds(condition: () -> Boolean): Boolean {
        val snapshot = Snapshot.takeSnapshot()
        try {
            return snapshot.enter(condition)
        } finally {
            snapshot.dispose()
        }
    }

    private fun ask(text: String, transport: ControlTransport?) {
        chat.draft = text
        chat.send(transport)
        await("the send finishes") { !chat.isSending }
    }

    private fun macCallsAbout(id: String) = mac.calls.filter { it.contains(id) }

    // MARK: - When the phone is offered

    @Test
    fun `a Mac out of reach offers the phone, and nothing is answered until the tap`() {
        mac.failure = TransportError.Unreachable("100.64.0.9")
        ask("What is the capital of France?", mac)

        val offer = chat.offer
        assertNotNull("the banner is up", offer)
        assertEquals("What is the capital of France?", offer!!.question)
        assertEquals(qwen.id, offer.model.id)
        assertEquals(MacState.Unreachable, chat.macState)
        val failed = chat.current!!.messages.last()
        assertEquals("the failed reply carries the same button", failed.id, offer.failedMessageID)
        assertNotNull(failed.failure)
        assertTrue("never silent, never automatic: the phone has not answered", phone.answered.isEmpty())
    }

    @Test
    fun `each way of being out of reach offers it, and nothing else does`() {
        val offering = listOf(
            TransportError.Unreachable("100.64.0.9"), TransportError.AppNotRunning,
            TransportError.TimedOut, TransportError.Unauthorized,
        )
        val notOffering = listOf(
            TransportError.BadRequest("No model is loaded."), TransportError.Conflict("Still answering."),
            TransportError.Server(500, "Boom"), TransportError.Forbidden("Not allowed."),
        )
        for (error in offering + notOffering) {
            setUp()
            mac.failure = error
            ask("Hello?", mac)
            assertEquals("${error.logSummary} offers the phone?", error in offering, chat.offer != null)
        }
    }

    @Test
    fun `no model on the phone, or a phone that cannot run one, means no offer`() {
        mac.failure = TransportError.Unreachable("100.64.0.9")
        phone.models = emptyList()
        ask("Hello?", mac)
        assertNull(chat.offer)

        setUp()
        mac.failure = TransportError.Unreachable("100.64.0.9")
        phone.couldRun = false
        ask("Hello?", mac)
        assertNull(chat.offer)
    }

    @Test
    fun `with no Mac paired at all, a model on the phone is still offered`() {
        chat.draft = "Hello?"
        chat.send(null)
        assertEquals(MacState.Unpaired, chat.macState)
        assertEquals("Hello?", chat.offer?.question)
    }

    @Test
    fun `the probe finding the Mac out of reach shows the offer before anything is sent, and it goes when the Mac is back`() {
        chat.noteReachability(Reachability.Unreachable("100.64.0.9"), paired = true)
        assertNotNull(chat.offer)
        assertNull("nothing to answer yet", chat.offer!!.question)
        chat.noteReachability(Reachability.Ready("1.0", "Qwen3 4B"), paired = true)
        assertNull(chat.offer)
    }

    // MARK: - What the tap does

    @Test
    fun `answering on the phone starts a conversation that lives only on the phone, and labels the answer`() {
        mac.failure = TransportError.Unreachable("100.64.0.9")
        ask("What is the capital of France?", mac)
        val before = mac.calls.size

        chat.answerOnPhone()
        await("the phone answers") { !chat.isSending && chat.current?.onDevice == true }

        val conversation = chat.current!!
        assertTrue(OnDeviceIds.isOnDevice(conversation.id))
        assertEquals(qwen.id, conversation.phoneModelID)
        val (question, answer) = conversation.messages
        assertEquals("What is the capital of France?", question.content)
        assertEquals("Hello from the phone.", answer.content)
        assertEquals("phone:${qwen.id}", answer.origin)
        assertEquals("Qwen3.5 2B", answer.originLabel)
        assertTrue(answer.isFromPhone)
        assertEquals(4, answer.metrics?.generatedTokens)
        assertEquals("the Mac was not asked anything", before, mac.calls.size)
        assertTrue(chat.phoneConversations.any { it.id == conversation.id })
        assertTrue(chat.conversations.none { it.id == conversation.id })
        runBlocking { assertNotNull("kept on the phone", phone.conversations.conversation(conversation.id)) }
        assertNull(chat.offer)
    }

    /**
     * A second question while the phone is still writing the first answer. The first send is
     * cancelled, and used to end as though its model had finished: the new question's reply
     * was closed before a word of it arrived, and the words then went on arriving into a
     * reply the screen said was done.
     */
    @Test
    fun `a question asked while the phone is answering keeps its own reply going`() {
        phone.keepsWriting = true
        chat.noteReachability(Reachability.Unreachable("100.64.0.9"), paired = true)
        chat.answerOnPhone()
        chat.draft = "First question"
        chat.send(mac)
        await("the phone is writing") { chat.current?.messages?.lastOrNull()?.content == "Hello " }

        chat.draft = "Second question"
        chat.send(mac)
        await("the second answer has begun") {
            phone.answered.size == 2 && chat.current?.messages?.getOrNull(3)?.content == "Hello "
        }
        // Every chance for the cancelled send to run its ending over this one.
        Thread.sleep(300)

        assertTrue("the second question is still being answered", holds { chat.isSending })
        val messages = chat.current!!.messages
        assertTrue("its reply is still arriving", messages[3].isStreaming)
        assertNull(messages[3].failure)
        assertFalse("the first reply is over, not left spinning", messages[1].isStreaming)
        assertEquals("Hello ", messages[1].content)
    }

    @Test
    fun `a phone conversation is opened, continued and deleted without the Mac hearing of it`() {
        mac.keepsConversations = true
        chat.loadConversations(mac)
        await("the Mac's list") { chat.usesRemoteConversations }
        chat.noteReachability(Reachability.Unreachable("100.64.0.9"), paired = true)
        chat.answerOnPhone()
        chat.draft = "First question"
        chat.send(mac)
        await("answered") { !chat.isSending && chat.current?.messages?.size == 2 }
        val id = chat.current!!.id
        val before = mac.calls.size

        chat.open(id, mac)
        await("reopened") { chat.current?.id == id }
        chat.draft = "A second question"
        chat.send(mac)
        await("answered again") { !chat.isSending && chat.current?.messages?.size == 4 }
        assertEquals("the whole conversation went to the phone's model", 3, phone.answered.last().second.size)
        chat.delete(id)
        await("deleted") { chat.phoneConversations.none { it.id == id } }

        assertEquals("zero Mac calls", before, mac.calls.size)
        assertTrue("the id never reached the Mac", macCallsAbout(OnDeviceIds.PREFIX).isEmpty())
    }

    @Test
    fun `Try again sends the same question to the Mac in place of the reply that failed`() {
        mac.failure = TransportError.Unreachable("100.64.0.9")
        ask("Still there?", mac)
        mac.failure = null
        chat.retryOnMac(mac)
        await("answered") { !chat.isSending }
        val messages = chat.current!!.messages
        assertEquals("no duplicate question, no leftover failure", 2, messages.size)
        assertEquals("From the Mac.", messages.last().content)
        assertNull(chat.offer)
        assertEquals(MacState.Answering, chat.macState)
    }

    @Test
    fun `not enough memory names the smaller model, and using it answers with that one`() {
        phone.models = listOf(gemma, qwen)
        phone.preferredID = gemma.id
        phone.preflightAnswer = { model ->
            if (model.id == gemma.id) Preflight.Refused("Not enough free memory for Gemma 4 E2B.", qwen) else Preflight.Ready
        }
        chat.noteReachability(Reachability.AppNotRunning, paired = true)
        chat.answerOnPhone()
        chat.draft = "Summarise this."
        chat.send(null)
        await("refused") { !chat.isSending && chat.refusal != null }
        assertEquals(qwen.id, chat.refusal!!.alternative?.id)
        assertTrue("nothing was answered by the model that did not fit", phone.answered.isEmpty())

        chat.useAlternative()
        await("answered") { !chat.isSending && chat.current!!.messages.lastOrNull()?.content?.isNotEmpty() == true }
        assertEquals(qwen.id, phone.answered.single().first)
        assertEquals(qwen.id, chat.current!!.phoneModelID)
        assertEquals("the refused exchange was replaced, not kept beside", 2, chat.current!!.messages.size)
    }

    @Test
    fun `a phone conversation takes no pictures`() {
        chat.noteReachability(Reachability.Unreachable("100.64.0.9"), paired = true)
        chat.answerOnPhone()
        chat.attachments.add("data:image/jpeg;base64,AAAA")
        chat.draft = "What is this?"
        chat.send(mac)
        assertTrue(chat.error!!.contains("text only"))
        assertTrue(phone.answered.isEmpty())
    }

    // MARK: - Back to the Mac

    @Test
    fun `the Mac is back only when something has heard from it since, not because an old probe said so`() {
        mac.failure = TransportError.Unreachable("100.64.0.9")
        ask("Hello?", mac)
        chat.answerOnPhone()
        await("answered on the phone") { !chat.isSending && chat.current?.onDevice == true }
        assertFalse("the Mac just failed: it is not back", chat.macIsBack)
        chat.noteStreamLive()
        assertTrue("the event stream opening again is news", chat.macIsBack)
        chat.noteReachability(Reachability.AppNotRunning, paired = true)
        assertFalse(chat.macIsBack)
    }

    @Test
    fun `Send to Mac carries the whole exchange in one message to a new Mac conversation, and the phone keeps its copy`() {
        mac.keepsConversations = true
        chat.loadConversations(mac)
        await("the Mac's list") { chat.usesRemoteConversations }
        chat.noteReachability(Reachability.Unreachable("100.64.0.9"), paired = true)
        chat.answerOnPhone()
        chat.draft = "Plan a day in Porto."
        chat.send(mac)
        await("answered") { !chat.isSending && chat.current?.messages?.size == 2 }
        val phoneID = chat.current!!.id
        assertEquals(2, chat.sendableCount)

        chat.sendToMac(mac)
        await("sent") { !chat.isSending && chat.current?.id == "mac-new" }

        assertEquals(1, mac.calls.count { it.startsWith("createConversation:") })
        val (to, message) = mac.sent.single()
        assertEquals("mac-new", to)
        assertTrue(message.content.contains("**Me:** Plan a day in Porto."))
        assertTrue(message.content.contains("**Phone (Qwen3.5 2B):** Hello from the phone."))
        assertTrue(macCallsAbout(OnDeviceIds.PREFIX).isEmpty())
        runBlocking { assertNotNull("the phone's copy stays", phone.conversations.conversation(phoneID)) }
    }

    @Test
    fun `a conversation whose model the Mac has replaced falls back to one that is here`() {
        // The Mac's catalogue changes — a smaller model is added, an old id retired — and a
        // conversation from before names something this phone no longer has.
        chat.noteReachability(Reachability.Unreachable("100.64.0.9"), paired = true)
        chat.answerOnPhone()
        chat.current!!.let { conversation ->
            runBlocking { phone.conversations.save(conversation.copy(phoneModelID = "qwen4-0.5b-q4_0")) }
        }
        chat.open(chat.current!!.id, mac)

        chat.draft = "Still there?"
        chat.send(mac)
        await("answered") { !chat.isSending && chat.current!!.messages.lastOrNull()?.content?.isNotEmpty() == true }
        assertEquals("answered by a model that is actually here", qwen.id, phone.answered.single().first)
        assertNull("and no error about an id nobody knows", chat.error)
    }

    // MARK: - A phone with little memory to spare

    @Test
    fun `a warning is shown before the answer, not instead of it`() {
        phone.preflightAnswer = {
            Preflight.Warned("Your phone is low on memory: Qwen3.5 2B runs best with 3.10 GB free and this phone has 2.62 GB. Other apps may close, and answers may be slower.")
        }
        chat.noteReachability(Reachability.Unreachable("100.64.0.9"), paired = true)
        chat.answerOnPhone()
        chat.draft = "What is the capital of France?"
        chat.send(mac)
        await("answered") { !chat.isSending }

        assertEquals("Hello from the phone.", chat.current!!.messages.last().content)
        assertNotNull("the warning is up", chat.memoryWarning)
        assertTrue(chat.memoryWarning!!.message.contains("Other apps may close"))
        assertEquals("and it knows which model it is about", qwen.id, chat.memoryWarning!!.model.id)
        assertNull("it is not a refusal", chat.refusal)

        chat.dismissMemoryWarning()
        assertNull(chat.memoryWarning)
    }

    @Test
    fun `Try again after making room asks the same model the same question`() {
        phone.preflightAnswer = { Preflight.Refused("Not enough free memory for Qwen3.5 2B.") }
        chat.noteReachability(Reachability.Unreachable("100.64.0.9"), paired = true)
        chat.answerOnPhone()
        chat.draft = "What is the capital of France?"
        chat.send(mac)
        await("refused") { !chat.isSending && chat.refusal != null }
        assertEquals("the sheet knows whose numbers to show", qwen.id, chat.refusal!!.model?.id)

        // Room is made, and the same question goes again to the same model.
        phone.preflightAnswer = { Preflight.Ready }
        chat.retryOnPhone()
        await("answered") { !chat.isSending && chat.current!!.messages.lastOrNull()?.content?.isNotEmpty() == true }
        assertEquals(qwen.id, phone.answered.single().first)
        assertEquals("What is the capital of France?", chat.current!!.messages.first().content)
        assertEquals("the refused exchange was replaced, not kept beside", 2, chat.current!!.messages.size)
        assertNull(chat.refusal)
    }

    // MARK: - While the phone is working on it

    @Test
    fun `the question is on the phone's disk before the model is even loaded`() {
        chat.noteReachability(Reachability.Unreachable("100.64.0.9"), paired = true)
        chat.answerOnPhone()
        phone.loading = kotlinx.coroutines.CompletableDeferred()
        chat.draft = "What is the capital of France?"
        chat.send(mac)
        await("the phone is asked") { phone.preflights.get() > 0 }

        // Loading a model is the longest minute in this app, and the likeliest moment for
        // Android to take the process for memory. The question has to survive that.
        val id = chat.current!!.id
        runBlocking {
            val saved = phone.conversations.conversation(id)
            assertNotNull("saved before the wait, not after it", saved)
            assertEquals("What is the capital of France?", saved!!.messages.first().content)
        }
        assertTrue("and the screen stays awake while it works", chat.keepsScreenOn)

        phone.loading!!.complete(Unit)
        await("answered") { !chat.isSending }
        assertFalse("and not a moment longer", chat.keepsScreenOn)
        runBlocking {
            assertEquals("the answer is saved too", 2, phone.conversations.conversation(id)!!.messages.size)
        }
    }

    @Test
    fun `waiting for the Mac does not keep the phone's screen on`() {
        chat.draft = "Hello?"
        chat.send(mac)
        await("the send finishes") { !chat.isSending }
        assertFalse(chat.keepsScreenOn)
    }

    @Test
    fun `Stop while the model is still loading stops the load too`() {
        chat.noteReachability(Reachability.Unreachable("100.64.0.9"), paired = true)
        chat.answerOnPhone()
        phone.loading = kotlinx.coroutines.CompletableDeferred()
        chat.draft = "Hello?"
        chat.send(mac)
        await("the phone is asked") { phone.preflights.get() > 0 }

        chat.cancel()
        assertEquals("llama.cpp is told, not just the coroutine", 1, phone.loadsCancelled.get())
        phone.loading!!.complete(Unit)
        assertFalse(chat.isSending)
        val id = chat.current!!.id
        await("what was asked and the stopped answer are both kept") {
            runBlocking { phone.conversations.conversation(id)?.messages?.size == 2 }
        }
    }

    @Test
    fun `nothing goes to the Mac while the phone is still writing the answer`() {
        mac.keepsConversations = true
        chat.loadConversations(mac)
        await("the Mac's list") { chat.usesRemoteConversations }
        chat.noteReachability(Reachability.Unreachable("100.64.0.9"), paired = true)
        chat.answerOnPhone()
        phone.loading = kotlinx.coroutines.CompletableDeferred()
        chat.draft = "Half an answer"
        chat.send(mac)
        await("the phone is asked") { phone.preflights.get() > 0 }
        val before = mac.calls.size

        chat.sendToMac(mac)
        assertEquals("half an answer is not what anybody meant to send", before, mac.calls.size)
        phone.loading!!.complete(Unit)
        await("answered") { !chat.isSending }
    }

    // MARK: - How much of a conversation the phone's model reads

    @Test
    fun `a long conversation goes to the phone's model newest-first, and the answer says so`() {
        chat.noteReachability(Reachability.Unreachable("100.64.0.9"), paired = true)
        chat.answerOnPhone()
        // Twelve turns of about 500 tokens each: far past what a 4k context should be given.
        repeat(6) { turn ->
            chat.draft = "Question $turn ${"word ".repeat(500)}"
            chat.send(mac)
            await("answered $turn") { !chat.isSending }
        }
        val sent = phone.answered.last().second
        assertTrue("the oldest turns were left out", sent.size < chat.current!!.messages.size - 1)
        assertTrue("the newest question went", sent.last().content.startsWith("Question 5"))
        assertTrue(
            "and it fits the budget",
            sent.sumOf { dev.siliconoptimizer.buddy.ondevice.PhoneHistory.tokensIn(it.content) } <=
                dev.siliconoptimizer.buddy.ondevice.PhoneHistory.BUDGET_TOKENS,
        )
        assertTrue("the answer says what it did not read", chat.current!!.messages.last().trimmedHistory)
        assertEquals(
            "Only the most recent messages were sent to the model on this phone.",
            OnDeviceNotices.HISTORY_TRIMMED,
        )
    }

    @Test
    fun `a short conversation says nothing about trimming, because nothing was trimmed`() {
        chat.noteReachability(Reachability.Unreachable("100.64.0.9"), paired = true)
        chat.answerOnPhone()
        chat.draft = "Hello?"
        chat.send(mac)
        await("answered") { !chat.isSending }
        assertFalse(chat.current!!.messages.last().trimmedHistory)
    }

    // MARK: - Opening what is already open

    @Test
    fun `opening the conversation that is already open leaves what is on the screen alone`() {
        chat.noteReachability(Reachability.Unreachable("100.64.0.9"), paired = true)
        chat.answerOnPhone()
        chat.draft = "Hello?"
        chat.send(mac)
        await("answered") { !chat.isSending && chat.current?.messages?.size == 2 }
        val open = chat.current!!

        // Something older on disk under the same id — a save that lost a race, an earlier
        // version of the conversation. Re-opening must not put it on the screen.
        runBlocking { phone.conversations.save(open.copy(messages = open.messages.take(1))) }
        chat.open(open.id, mac)
        Thread.sleep(50)
        assertEquals("what was on the screen is still there", 2, chat.current!!.messages.size)
        assertEquals(open.messages.last().content, chat.current!!.messages.last().content)
    }

    // MARK: - A Mac that was out of reach when the app started

    @Test
    fun `a Mac unreachable at launch is asked again when it comes back, before anything is sent to it`() {
        mac.keepsConversations = true
        // The floor between asks is about a Mac that keeps failing; this one comes back.
        chat.conversationAskFloorMillis = 0
        mac.failure = TransportError.Unreachable("100.64.0.9")
        chat.loadConversations(mac)
        await("the ask failed") { chat.error != null }
        assertFalse("nothing was learned, so nothing is assumed", chat.askedAboutConversations)
        assertFalse(chat.usesRemoteConversations)
        await("the ask settled") { !chat.conversationAskInFlight }

        chat.noteReachability(Reachability.Unreachable("100.64.0.9"), paired = true)
        chat.answerOnPhone()
        chat.draft = "Plan a day in Porto."
        chat.send(mac)
        await("the phone answered") { !chat.isSending && chat.current?.messages?.size == 2 }

        // The Mac comes back — the event stream is the first to know.
        mac.failure = null
        chat.noteStreamLive()
        await("it was asked again") { chat.usesRemoteConversations }

        chat.sendToMac(mac)
        await("sent") { !chat.isSending && chat.current?.id == "mac-new" }
        assertEquals("a conversation on the Mac, where the Mac keeps them", 1, mac.calls.count { it.startsWith("createConversation:") })
        assertTrue(macCallsAbout(OnDeviceIds.PREFIX).isEmpty())
    }

    @Test
    fun `the dashboard's own polling counts as the Mac being back`() {
        mac.keepsConversations = true
        chat.conversationAskFloorMillis = 0
        mac.failure = TransportError.Unreachable("100.64.0.9")
        chat.loadConversations(mac)
        await("the ask failed") { chat.error != null }
        chat.noteReachability(Reachability.Unreachable("100.64.0.9"), paired = true)
        assertTrue(chat.macState.isOutOfReach)

        // The failed ask is not over when the error appears — the local store is read
        // after it, on another thread — and a re-ask while it is still running is dropped
        // by the in-flight guard rather than by the floor.
        await("the ask settled") { !chat.conversationAskInFlight }
        mac.failure = null
        chat.noteMacAnswered()
        assertEquals(MacState.Answering, chat.macState)
        await("and it asked what the Mac keeps") { chat.usesRemoteConversations }
    }

    @Test
    fun `the tile and the widget only open the offer, and never answer by themselves`() {
        chat.offerFromShortcut()
        assertEquals(MacState.Unreachable, chat.macState)
        assertNotNull("the banner is up", chat.offer)
        assertNull("with nothing to answer yet", chat.offer!!.question)
        assertTrue("and nothing was answered", phone.answered.isEmpty())
        assertNull(chat.current)
        assertTrue("nothing was asked of the Mac either", mac.calls.isEmpty())
    }

    @Test
    fun `Send to Mac carries what was said, not a reply that failed`() {
        mac.failure = TransportError.Unreachable("100.64.0.9")
        ask("Are you there?", mac)
        chat.answerOnPhone()
        await("the phone answered") { !chat.isSending && chat.current?.onDevice == true }
        // A second question the phone refused: an empty reply with a failure on it.
        phone.preflightAnswer = { Preflight.Refused("Your phone is too hot to answer right now.") }
        chat.draft = "And now?"
        chat.send(mac)
        await("refused") { !chat.isSending && chat.refusal != null }

        assertEquals("the three things that were actually said", 3, chat.sendableCount)
        val message = dev.siliconoptimizer.buddy.chat.SendToMac.message(
            chat.current!!.messages.filter { it.content.isNotBlank() },
        )
        assertTrue(message.contains("**Me:** Are you there?"))
        assertTrue(message.contains("**Phone (Qwen3.5 2B):** Hello from the phone."))
        assertTrue(message.contains("**Me:** And now?"))
        assertFalse("nothing empty was quoted", message.contains("**Assistant:** \n"))
    }

    // MARK: - A new conversation while the Mac is out of reach

    @Test
    fun `the plus button still makes a conversation when the Mac that keeps them is out of reach`() {
        mac.keepsConversations = true
        chat.loadConversations(mac)
        await("the Mac's list") { chat.usesRemoteConversations }

        // The Mac goes. This is the moment the phone's own model exists for.
        mac.failure = TransportError.Unreachable("100.64.0.9")
        chat.noteReachability(Reachability.Unreachable("100.64.0.9"), paired = true)
        chat.newConversation(mac)

        val fresh = chat.current
        assertNotNull("a composer, not an empty list", fresh)
        assertFalse("made here, because the Mac could not make it", fresh!!.onDevice)
        assertNotNull("and the offer is on the screen with it", chat.offer)
        assertEquals(qwen.id, chat.offer!!.model.id)

        // And it answers on the phone from there, with one tap and no Mac.
        val before = mac.calls.size
        chat.answerOnPhone()
        chat.draft = "What is the capital of France?"
        chat.send(mac)
        await("the phone answered") { !chat.isSending && chat.current?.onDevice == true }
        assertEquals("Hello from the phone.", chat.current!!.messages.last().content)
        assertEquals("nothing was asked of the Mac", before, mac.calls.size)
    }

    @Test
    fun `the tile's Ask on this phone opens a composer with the offer, even after the Mac had answered`() {
        mac.keepsConversations = true
        chat.loadConversations(mac)
        await("the Mac's list") { chat.usesRemoteConversations }
        mac.failure = TransportError.Unreachable("100.64.0.9")

        // What the tile does: says the Mac is out of reach, then asks for a conversation.
        chat.offerFromShortcut()
        chat.newConversation(mac)

        assertNotNull("the composer is open", chat.current)
        assertNotNull("with the offer above it", chat.offer)
        assertTrue("and nothing has been answered", phone.answered.isEmpty())
        assertEquals("the Mac was not asked to make it", 0, mac.calls.count { it.startsWith("createConversation") })
    }

    @Test
    fun `a conversation made while the Mac was away is answered by the Mac when it comes back`() {
        mac.keepsConversations = true
        chat.loadConversations(mac)
        await("the Mac's list") { chat.usesRemoteConversations }

        // Made here, because the Mac was not answering.
        mac.failure = TransportError.Unreachable("100.64.0.9")
        chat.noteReachability(Reachability.Unreachable("100.64.0.9"), paired = true)
        chat.newConversation(mac)
        val id = chat.current!!.id

        // The Mac comes back. The first thing sent has to be answered, not refused: the
        // Mac has never heard of this conversation, and that is a fact about the
        // conversation, not about the Mac's conversations route.
        mac.failure = null
        chat.noteMacAnswered()
        ask("What is the capital of France?", mac)

        val reply = chat.current!!.messages.last()
        assertEquals("From the Mac.", reply.content)
        assertNull("no sentence about a conversation that was never there", reply.failure)
        assertNull(chat.error)
        assertTrue("it went as history, which is what the Mac can answer", mac.calls.any { it == "chatStream" })
        assertTrue(
            "and the Mac was not asked about a conversation it never made",
            mac.calls.none { it == "sendMessage:$id" },
        )
        assertTrue("the Mac still keeps its own conversations", chat.usesRemoteConversations)
        assertEquals("kept here, since the Mac has nowhere to put it", id, chat.current!!.id)
        assertTrue("the note says where it is kept", chat.storageNote.startsWith("Kept on this device"))
        runBlocking {
            assertEquals("and the transcript is saved here", 2, store.conversation(id)!!.messages.size)
        }

        // And a second question goes the same way.
        ask("And Portugal?", mac)
        assertEquals(0, mac.calls.count { it == "sendMessage:$id" })
        assertEquals("From the Mac.", chat.current!!.messages.last().content)

        // And it is still in the list after the Mac's own list is read again.
        // The count of asks rises when the request is made; the list is filled when it
        // answers. Waiting on the second would be waiting on the wrong thing.
        chat.loadConversations(mac)
        await("it is not dropped for not being the Mac's") { chat.conversations.any { it.id == id } }
    }

    @Test
    fun `a conversation the Mac has forgotten is still answered, and not called a dead end`() {
        // The other way into the same place: a conversation the Mac *did* make and has
        // since lost. A 404 from that route is about the conversation, and the question
        // still deserves an answer rather than a sentence about the Mac's filing.
        mac.keepsConversations = true
        chat.loadConversations(mac)
        await("the Mac's list") { chat.usesRemoteConversations }
        chat.open("mac-1", mac)
        await("opened") { chat.current?.id == "mac-1" }
        mac.known -= "mac-1"

        ask("What is the capital of France?", mac)

        val reply = chat.current!!.messages.last()
        assertEquals("From the Mac.", reply.content)
        assertNull("no dead end", reply.failure)
        assertNull(chat.error)
        assertTrue("it did try the Mac's own route first", mac.calls.any { it == "sendMessage:mac-1" })
        assertTrue("and fell back to plain history", mac.calls.any { it == "chatStream" })
        assertTrue("the Mac still keeps its own conversations", chat.usesRemoteConversations)
        runBlocking { assertNotNull("and this device keeps this one", store.conversation("mac-1")) }
    }

    @Test
    fun `opening a conversation the Mac never made opens the copy on this device`() {
        mac.keepsConversations = true
        chat.loadConversations(mac)
        await("the Mac's list") { chat.usesRemoteConversations }
        mac.failure = TransportError.Unreachable("100.64.0.9")
        chat.noteReachability(Reachability.Unreachable("100.64.0.9"), paired = true)
        chat.newConversation(mac)
        val id = chat.current!!.id
        mac.failure = null
        chat.noteMacAnswered()
        ask("What is the capital of France?", mac)

        // Away and back again — the Mac is asked about it, says no, and the device has it.
        chat.open("mac-1", mac)
        await("the Mac's conversation opened") { chat.current?.id == "mac-1" }
        chat.open(id, mac)
        await("back to ours") { chat.current?.id == id }

        assertEquals("with what was said in it", 2, chat.current!!.messages.size)
        assertNull("and no error about a conversation that is right here", chat.error)
        assertTrue("it is still in the list", chat.conversations.any { it.id == id })
    }

    @Test
    fun `a Mac that goes away mid-request still leaves a conversation to type in`() {
        mac.keepsConversations = true
        chat.loadConversations(mac)
        await("the Mac's list") { chat.usesRemoteConversations }

        // Believed up, so it is asked — and it is gone by the time it is. The owner is
        // left with a composer and the offer, not an error and an empty screen.
        mac.failure = TransportError.Unreachable("100.64.0.9")
        chat.newConversation(mac)
        await("a conversation was made here instead") { chat.current != null }

        assertFalse("made on the device", chat.current!!.onDevice)
        assertEquals(MacState.Unreachable, chat.macState)
        assertNotNull("with the offer above it", chat.offer)
        assertNotNull("and it says what happened", chat.error)
    }

    @Test
    fun `a Mac that is answering still makes its own conversations`() {
        mac.keepsConversations = true
        chat.loadConversations(mac)
        await("the Mac's list") { chat.usesRemoteConversations }

        chat.newConversation(mac)
        await("the Mac made one") { chat.current?.id == "mac-new" }
        assertEquals(1, mac.calls.count { it.startsWith("createConversation") })
        assertNull("nothing to offer: the Mac is right there", chat.offer)
    }

    @Test
    fun `a Mac that answers metrics but not conversations is not asked again on every poll`() {
        mac.keepsConversations = true
        var now = 1_000L
        chat.clock = { now }
        mac.conversationsFailure = TransportError.Server(500, "Boom")
        chat.loadConversations(mac)
        await("the ask failed") { chat.error != null }
        // The ask is not over when the error appears: the local store is read after it, on
        // another thread. A poll that lands in that window is dropped by the in-flight
        // guard rather than by the floor — which is the same thing to the owner, whose
        // next poll is four seconds away, and a coin toss to a test with a fixed clock.
        await("the ask settled") { !chat.conversationAskInFlight }
        val asks = mac.calls.count { it == "conversations" }
        assertEquals(1, asks)

        // Ten dashboard polls in the next few seconds, each one news that the Mac is up.
        repeat(10) {
            now += 400
            chat.noteMacAnswered()
        }
        assertEquals("one ask, not eleven", asks, mac.calls.count { it == "conversations" })

        // Once the floor has passed, it tries again — and this time the Mac answers.
        now += chat.conversationAskFloorMillis
        mac.conversationsFailure = null
        chat.noteMacAnswered()
        await("asked again") { chat.usesRemoteConversations }
        await("and that ask settled too") { !chat.conversationAskInFlight }
        assertEquals(asks + 1, mac.calls.count { it == "conversations" })
    }

    @Test
    fun `while the Mac has never answered, the transcript does not claim the Mac keeps nothing`() {
        mac.failure = TransportError.Unreachable("100.64.0.9")
        chat.loadConversations(mac)
        await("the ask failed") { chat.error != null }
        // …and finished failing: a re-ask while the first is still running is dropped by
        // the in-flight guard, and the news that the Mac is back would be lost with it.
        await("the ask settled") { !chat.conversationAskInFlight }
        chat.newConversation(mac)
        assertEquals("Kept on this device while your Mac is out of reach.", chat.storageNote)

        mac.failure = null
        mac.keepsConversations = true
        chat.conversationAskFloorMillis = 0
        chat.noteMacAnswered()
        await("asked again") { chat.usesRemoteConversations }
        assertEquals(
            "the one made while it was away stays here",
            "Kept on this device: your Mac was out of reach when this one started.",
            chat.storageNote,
        )

        // …and one the Mac makes now is the Mac's.
        chat.noteReachability(Reachability.Ready("1.0", "Qwen3 4B"), paired = true)
        chat.newConversation(mac)
        await("the Mac made one") { chat.current?.id == "mac-new" }
        assertEquals("Synced with the Mac.", chat.storageNote)
    }

    // MARK: - The Mac-facing code refuses phone ids outright

    @Test
    fun `the client refuses a phone conversation's id without sending a byte`() {
        LoopbackServer().use { server ->
            val client = ControlClient(ServerConfig("127.0.0.1", server.port, "t"))
            val id = OnDeviceIds.mint()
            runBlocking {
                try {
                    client.conversation(id)
                    fail()
                } catch (refused: TransportError.Forbidden) {
                    assertEquals(OnDeviceIds.REFUSAL, refused.message)
                }
                try {
                    client.sendMessage(id, ChatMessageWire("user", "hi")).collect()
                    fail()
                } catch (refused: TransportError.Forbidden) {
                    assertEquals(OnDeviceIds.REFUSAL, refused.message)
                }
            }
            assertTrue("nothing reached the Mac", server.requests.isEmpty())
        }
    }
}
