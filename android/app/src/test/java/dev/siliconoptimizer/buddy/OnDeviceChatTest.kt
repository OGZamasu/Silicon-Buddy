package dev.siliconoptimizer.buddy

import android.app.Application
import dev.siliconoptimizer.buddy.chat.ChatMessage
import dev.siliconoptimizer.buddy.chat.ChatViewModel
import dev.siliconoptimizer.buddy.chat.ConversationStore
import dev.siliconoptimizer.buddy.ondevice.InstalledPhoneModel
import dev.siliconoptimizer.buddy.ondevice.MacState
import dev.siliconoptimizer.buddy.ondevice.OnDeviceChat
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
            fail()
            if (!keepsConversations) throw TransportError.RouteUnavailable("/conversations")
            return listOf(ConversationSummary("mac-1", "On the Mac", "2026-09-19T00:00:00Z", 2))
        }

        override suspend fun createConversation(title: String?): ConversationSummary {
            record("createConversation:$title")
            fail()
            return ConversationSummary("mac-new", title ?: "New conversation", "2026-09-19T00:00:00Z", 0)
        }

        override suspend fun conversation(id: String): ConversationDetail {
            record("conversation:$id")
            fail()
            return ConversationDetail(id, "On the Mac", "2026-09-19T00:00:00Z", messages = emptyList())
        }

        override fun sendMessage(conversationID: String, message: ChatMessageWire, maxTokens: Int?): Flow<ChatStreamEvent> = flow {
            record("sendMessage:$conversationID")
            fail()
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

        override suspend fun preflight(model: InstalledPhoneModel) = preflightAnswer(model)

        override fun answer(model: InstalledPhoneModel, history: List<ChatMessage>, maxTokens: Int) = flow {
            answered += model.id to history
            emit(ChatStreamEvent.Token("Hello "))
            emit(ChatStreamEvent.Token("from the phone."))
            emit(ChatStreamEvent.Finished(ChatMetrics(12, 4, 18.5, timeToFirstToken = 0.4)))
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

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mac = RecordingMac()
        phone = FakePhone(listOf(qwen))
        chat = ChatViewModel(Application(), ConversationStore(folder.newFolder()), phone)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("never: $what")
            Thread.sleep(10)
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
