package dev.siliconoptimizer.buddy.chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.siliconoptimizer.buddy.ondevice.AndroidOnDeviceChat
import dev.siliconoptimizer.buddy.ondevice.FallbackDecision
import dev.siliconoptimizer.buddy.ondevice.FallbackPolicy
import dev.siliconoptimizer.buddy.ondevice.InstalledPhoneModel
import dev.siliconoptimizer.buddy.ondevice.MacState
import dev.siliconoptimizer.buddy.ondevice.OnDeviceChat
import dev.siliconoptimizer.buddy.ondevice.OnDeviceNotices
import dev.siliconoptimizer.buddy.ondevice.PhoneHistory
import dev.siliconoptimizer.buddy.ondevice.Preflight
import dev.siliconoptimizer.buddy.transport.ChatMessageWire
import dev.siliconoptimizer.buddy.transport.ChatMetrics
import dev.siliconoptimizer.buddy.transport.ChatRequest
import dev.siliconoptimizer.buddy.transport.OnDeviceIds
import dev.siliconoptimizer.buddy.transport.Reachability
import dev.siliconoptimizer.buddy.transport.ChatStreamEvent
import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.reach.SnapshotStore
import dev.siliconoptimizer.buddy.reach.VerdictMatching
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The chat screen's state and the one piece of logic worth testing here: how a reply
 * arrives, whether it streams or not.
 */
class ChatViewModel(
    application: Application,
    private val store: ConversationStore,
    /** The phone's own model and the conversations it answered, which never go to the Mac. */
    private val phone: OnDeviceChat,
) : AndroidViewModel(application) {

    /**
     * The constructor the default ViewModel factory looks for. A default argument would
     * not do: the factory reflects for exactly `(Application)` and a synthesised
     * constructor is not it — which crashed the app on its first launch.
     */
    constructor(application: Application) :
        this(application, ConversationStore(application), AndroidOnDeviceChat(application))

    /** A chat with no model of its own on the phone, as the tests of the Mac's side want. */
    constructor(application: Application, store: ConversationStore) :
        this(application, store, OnDeviceChat.None(java.io.File(store.directory, "ondevice")))

    val conversations = mutableStateListOf<Conversation>()
    var current by mutableStateOf<Conversation?>(null)
        private set
    var isSending by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var draft by mutableStateOf("")
    val attachments = mutableStateListOf<String>()

    /** When the current reply was asked for, so the transcript can say how long. */
    var sendingSince by mutableStateOf<Long?>(null)
        private set

    /** Whether the Mac answered `/chat/stream` and `/conversations`, learned by asking. */
    var usesStreaming by mutableStateOf(true)
        private set
    var usesRemoteConversations by mutableStateOf(false)
        private set
    /**
     * Whether `/conversations` has been asked about yet.
     *
     * Observable because a screen has to wait for it. Until the question has been put to
     * the Mac, `usesRemoteConversations` is false because nothing is known — not because
     * the Mac keeps no conversations — and the two are indistinguishable from outside.
     */
    var askedAboutConversations by mutableStateOf(false)
        private set

    /** How much history to send when the phone is the one keeping it. */
    var historyLimit = 24

    /**
     * A ceiling on the answer. Without one, a reasoning model asked a small question can
     * think for ten minutes and the phone shows a spinner the whole time.
     */
    var maxTokens = 2048

    /**
     * The phone's ceiling. Lower than the Mac's: at 17 tokens a second, 1024 is a minute of
     * writing, which is as long as anybody holds a phone waiting.
     */
    var phoneMaxTokens = 1024

    private var sendJob: Job? = null

    /**
     * The ask in flight for what the Mac keeps, so opening a conversation can wait for the
     * answer instead of guessing — and the last transport seen, so the chat can ask again
     * by itself when the Mac comes back.
     */
    private var conversationsAsk: Job? = null
    private var lastTransport: ControlTransport? = null
    private var lastConversationAsk = 0L

    /**
     * Conversations this device keeps although the Mac keeps its own: the ones made here
     * while the Mac was out of reach. The Mac has never heard of them, so its conversation
     * route answers 404 for them — which is a fact about that conversation, not about the
     * Mac — and the transcript is this device's to save and to send as history.
     */
    private val keptHere = mutableSetOf<String>()

    /** Whether an ask about the Mac's conversations is still running. For the tests. */
    internal val conversationAskInFlight: Boolean get() = conversationsAsk?.isActive == true

    /**
     * How long to leave between asking a Mac what it keeps, when the last ask failed for a
     * reason that says nothing. The dashboard polls every few seconds and every answer is
     * news that the Mac is up; without a floor, a Mac that answers `/metrics` and fails
     * `/conversations` would be asked — and would show an error — on every one of them.
     */
    var conversationAskFloorMillis = 30_000L

    /** Replaced in tests, where waiting thirty seconds is not an option. */
    internal var clock: () -> Long = System::currentTimeMillis

    // MARK: - The phone's own model

    /** Conversations the phone answered itself, in their own section. Never the Mac's. */
    val phoneConversations = mutableStateListOf<Conversation>()

    /**
     * "Your Mac isn't answering. [Answer on this phone] [Try again]" — shown only when the
     * Mac is out of reach and a model is on this phone. Nothing is ever answered on the
     * phone without the tap.
     */
    var offer by mutableStateOf<PhoneOffer?>(null)
        private set

    /** The phone declined to answer — too warm, too little memory — and what to try instead. */
    var refusal by mutableStateOf<PhoneRefusal?>(null)
        private set

    /**
     * The phone is answering with little memory to spare, and what that costs. Shown with
     * the answer it is about; the owner takes it down when they have read it.
     */
    var memoryWarning by mutableStateOf<PhoneMemoryWarning?>(null)
        private set

    fun dismissMemoryWarning() {
        memoryWarning = null
    }

    /** What is known about the Mac answering: from the probe, and from the last send. */
    var macState by mutableStateOf(MacState.Answering)
        private set

    /** The failure of the last request to the Mac, for deciding whether to offer the phone. */
    private var lastFailure: Throwable? = null

    // MARK: - Conversations

    fun loadConversations(transport: ControlTransport?) {
        // Two reads, neither waiting on the other: the Mac is asked at once, as before, and
        // the phone's own list comes off the phone's disk beside it.
        lastTransport = transport ?: lastTransport
        lastConversationAsk = clock()
        conversationsAsk = viewModelScope.launch { loadConversationsNow(transport) }
        viewModelScope.launch { loadPhoneConversations() }
    }

    /**
     * Waits for the Mac to have answered what it keeps, asking if nobody has.
     *
     * A definite answer is the list or "this Mac has no /conversations"; a timeout is
     * neither, and leaves the question open for the next time the Mac is reachable.
     */
    private suspend fun awaitConversationAnswer(transport: ControlTransport?) {
        if (transport == null || askedAboutConversations) return
        val ask = conversationsAsk
            ?: viewModelScope.launch { loadConversationsNow(transport) }.also { conversationsAsk = it }
        ask.join()
    }

    /** The phone's own conversations, from the phone. No Mac is asked about these. */
    private suspend fun loadPhoneConversations() {
        val stored = phone.conversations.all()
        phoneConversations.clear()
        phoneConversations.addAll(stored)
        current?.takeIf { it.onDevice }?.let { open ->
            if (phoneConversations.none { it.id == open.id }) phoneConversations.add(0, open)
        }
    }

    private suspend fun loadConversationsNow(transport: ControlTransport?) {
            if (transport != null && (usesRemoteConversations || !askedAboutConversations)) {
                try {
                    val remote = transport.conversations()
                    usesRemoteConversations = true
                    askedAboutConversations = true
                    // What this device made while the Mac was out of reach is in the store
                    // and in no Mac's list. Dropping it here is how it would be lost —
                    // including after a restart, where nothing else remembers it.
                    val here = store.all().filterNot { stored -> remote.any { it.id == stored.id } }
                    keptHere += here.map { it.id }
                    conversations.clear()
                    conversations.addAll(here)
                    conversations.addAll(
                        remote.map {
                            Conversation(
                                id = it.id,
                                title = it.title,
                                updatedAt = System.currentTimeMillis(),
                                remoteMessageCount = it.messageCount,
                            )
                        },
                    )
                    return
                } catch (failure: TransportError) {
                    if (failure.isMissingRoute) {
                        // This Mac has no /conversations at all; the device keeps them.
                        usesRemoteConversations = false
                        askedAboutConversations = true
                    } else {
                        // A timeout, a dropped tailnet, a Mac mid-restart. Nothing was
                        // learned, so the question stays open and is put again when the Mac
                        // answers — a Mac that was merely unreachable at launch still keeps
                        // its conversations, and the device must not adopt them.
                        error = failure.message
                        if (usesRemoteConversations) return
                    }
                }
            }
            val stored = store.all()
            conversations.clear()
            conversations.addAll(stored)
            // A conversation started but not yet sent to is not in the store.
            current?.takeIf { !it.onDevice }?.let { open ->
                if (conversations.none { it.id == open.id }) conversations.add(0, open)
            }
    }

    /**
     * A new conversation, wherever one can be had.
     *
     * On a Mac that keeps conversations, the Mac makes it — but only while the Mac is
     * answering. Out of reach, this makes one here instead, as it always did before the Mac
     * was asked at all: a "+" that does nothing, and a tile whose "Ask on this phone" lands
     * on a list rather than a composer, are how the phone's own model becomes unreachable
     * exactly when it is the only thing left that works.
     *
     * Asynchronous whenever there is a Mac to ask, because both questions it turns on —
     * does this Mac keep conversations, and what id did it give this one — are the Mac's
     * to answer. The conversation is open once it answers; the screen follows `current`.
     */
    fun newConversation(transport: ControlTransport? = null) {
        // The Mac the chat already has, when the caller brought none. A caller that
        // forgets the transport is not the same thing as there being no Mac, and reading
        // it that way is how "+" came to make conversations on the phone that the Mac had
        // never heard of: missing from its list, missing from its other devices, and
        // answered 404 by the conversation route on the very first message.
        val mac = transport ?: lastTransport
        lastTransport = mac
        if (mac == null || macState.isOutOfReach) {
            startLocalConversation()
            return
        }
        viewModelScope.launch {
            // Nothing is known about what a Mac keeps until it has been asked, and
            // `usesRemoteConversations` is false for "not asked yet" exactly as it is for
            // "keeps none". Deciding on it inside the first round trip puts the first
            // conversation of every session on the phone alone. `open` waits for the same
            // answer for the same reason; this costs at most the one ask already in
            // flight, and the screen shows the list until the conversation lands.
            awaitConversationAnswer(mac)
            if (!usesRemoteConversations || macState.isOutOfReach) {
                startLocalConversation()
                return@launch
            }
            try {
                val created = mac.createConversation(null)
                current = Conversation(id = created.id, title = created.title)
                loadConversationsNow(mac)
            } catch (failure: TransportError) {
                if (failure.isMissingRoute) {
                    // No such route on this Mac: the device keeps them from now on.
                    usesRemoteConversations = false
                    startLocalConversation()
                } else {
                    // The Mac was there a moment ago and is not now. One here, and the
                    // offer with it if the phone can stand in.
                    MacState.of(failure)?.let { macState = it }
                    error = failure.message
                    startLocalConversation()
                }
            }
        }
    }

    /**
     * A conversation on the device, and — when the Mac is out of reach and this phone has a
     * model — the offer on the screen beside it. Nothing is answered until it is tapped.
     */
    private fun startLocalConversation() {
        val fresh = Conversation()
        keptHere += fresh.id
        current = fresh
        conversations.add(0, fresh)
        refusal = null
        if (macState.isOutOfReach) refreshOffer(null, null)
    }

    fun open(id: String, transport: ControlTransport?) {
        lastTransport = transport ?: lastTransport
        // The conversation that is already open is left exactly as it is. Re-reading it
        // would replace what is on the screen with the copy on disk — and an answer still
        // being written into it is in neither the store nor the Mac yet.
        if (current?.id == id) return
        // With a Mac, nothing opens until it has been asked what it keeps.
        //
        // The id can arrive before the answer does: it is saved across process death, so
        // after One UI kills the app it is restored and handed straight back here while
        // `usesRemoteConversations` is still false-because-unknown. The remote branch
        // below is skipped, the local store has nothing under that id, and the fallback
        // invents `Conversation(id = id)` — a real conversation replaced by an empty
        // transcript wearing its id. Waiting costs one round trip; `loadConversations`
        // is already in flight, and the caller re-runs this when the answer lands.
        //
        // Only with a Mac, though. Unpaired, local is the only place a conversation can
        // be, there is nothing to wait for, and waiting would mean never opening one.
        // The phone's own conversations open from the phone, whatever the Mac is doing —
        // and their ids never reach it.
        //
        // Waiting, rather than returning and trusting the caller to come back: a screen
        // that forgets to re-run leaves the owner looking at nothing.
        if (OnDeviceIds.isOnDevice(id)) {
            viewModelScope.launch {
                current = phone.conversations.conversation(id)
                    ?: phoneConversations.firstOrNull { it.id == id }
                    ?: Conversation(id = id, onDevice = true)
                refusal = null
                refreshOffer(null, null)
            }
            return
        }
        viewModelScope.launch {
            awaitConversationAnswer(transport)
            if (usesRemoteConversations && transport != null) {
                try {
                    val detail = transport.conversation(id)
                    isConversationBusy = detail.isGenerating
                    current = Conversation(
                        id = detail.id,
                        title = detail.title,
                        messages = detail.messages.map {
                            ChatMessage(
                                // The Mac's own id where it gives one, so a `verdict`
                                // event lands on the reply it is about.
                                id = it.id ?: java.util.UUID.randomUUID().toString(),
                                role = it.role,
                                content = it.content,
                                reasoning = it.reasoning,
                                images = it.images.orEmpty(),
                                verdict = it.verification,
                            )
                        },
                    )
                    return@launch
                } catch (failure: TransportError) {
                    when {
                        failure.isMissingRoute -> usesRemoteConversations = false
                        // No such conversation *there*. It may be one this device made
                        // while the Mac was out of reach, in which case it is here.
                        failure is TransportError.NotFound && store.conversation(id) != null ->
                            keptHere += id
                        else -> {
                            error = failure.message
                            conversations.removeAll { it.id == id }
                            current = null
                            return@launch
                        }
                    }
                }
            }
            val stored = store.conversation(id) ?: conversations.firstOrNull { it.id == id }
            // A Mac that never answered may well be holding this conversation. Opening an
            // empty transcript under its id is the one outcome worse than not opening it:
            // it reads as the Mac having lost it. The screen re-runs this when the Mac
            // does answer.
            if (stored == null && transport != null && !askedAboutConversations) return@launch
            current = stored ?: Conversation(id = id)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                if (OnDeviceIds.isOnDevice(id)) phone.conversations.delete(id) else store.delete(id)
            } catch (failure: java.io.IOException) {
                // Nothing was taken out; it stays in the list, where it still is.
                error = NOT_DELETED
                return@launch
            }
            if (OnDeviceIds.isOnDevice(id)) {
                phoneConversations.removeAll { it.id == id }
            } else {
                conversations.removeAll { it.id == id }
            }
            if (current?.id == id) current = null
        }
    }

    // MARK: - Sending

    fun send(transport: ControlTransport?) {
        lastTransport = transport ?: lastTransport
        // A conversation the phone is answering stays on the phone.
        if (current?.onDevice == true) {
            sendOnPhone(null)
            return
        }
        val text = draft.trim()
        if (text.isEmpty() && attachments.isEmpty()) return
        if (transport == null) {
            error = TransportError.NotConfigured.message
            macState = MacState.Unpaired
            refreshOffer(text.ifEmpty { null }, null)
            return
        }
        SendLimits.problem(attachments, text)?.let {
            error = it
            return
        }
        val images = attachments.toList()
        draft = ""
        attachments.clear()
        stopSending()

        var conversation = current ?: Conversation()
        val placeholderID = java.util.UUID.randomUUID().toString()
        conversation = conversation.copy(
            messages = conversation.messages +
                ChatMessage(role = ChatMessage.ROLE_USER, content = text, images = images) +
                ChatMessage(
                    id = placeholderID,
                    role = ChatMessage.ROLE_ASSISTANT,
                    content = "",
                    isStreaming = true,
                ),
        ).titledFromFirstMessage()
        current = conversation
        isSending = true
        sendingSince = System.currentTimeMillis()
        isConversationBusy = false
        error = null
        refusal = null
        lastFailure = null

        sendJob = viewModelScope.launch {
            run(placeholderID, transport)
            isSending = false
            sendingSince = null
            val failure = lastFailure
            if (failure == null) {
                macState = MacState.Answering
                offer = null
            } else {
                MacState.of(failure)?.let { macState = it }
                // The failed reply carries the same offer as the banner.
                refreshOffer(text, placeholderID)
            }
            noteLastExchange(text)
        }
    }

    /**
     * Leaves the exchange where the widget, the tile and the Assistant find it. Only
     * the text: a picture is not something a widget has room for.
     */
    private fun noteLastExchange(question: String) {
        val reply = current?.messages?.lastOrNull {
            it.role == ChatMessage.ROLE_ASSISTANT && it.content.isNotEmpty()
        } ?: return
        runCatching {
            SnapshotStore(getApplication()).note(question, reply.content, reply.originLabel.takeIf { reply.isFromPhone })
        }
    }

    fun cancel() {
        // A phone model can be a minute arriving in memory. Cancelling the job alone would
        // leave llama.cpp reading the file for a question nobody is waiting for any more.
        if (current?.onDevice == true) phone.cancelLoading()
        sendJob?.cancel()
        sendJob = null
        isSending = false
        sendingSince = null
        finishStreaming("Stopped.")
        if (current?.onDevice == true) viewModelScope.launch { persistPhone() }
    }

    /**
     * Ends the send in flight, if there is one: its reply is closed as stopped, and its
     * coroutine ends where it is. Before a new send takes its place — the new reply is the
     * one still streaming after this, and nothing of the old send's ending may land on it.
     */
    private fun stopSending() {
        if (sendJob?.isActive == true) finishStreaming("Stopped.")
        sendJob?.cancel()
    }

    private enum class Outcome {
        Answered, MissingRoute, Busy, Failed, Stopped,

        /** The Mac has no such conversation: one this device made while it was away. */
        NoSuchConversation,

        /**
         * The route was there and said nothing: a stream that closed without one event.
         *
         * Not an answer, so the next route is worth trying — but not evidence about what
         * the Mac has either, which is the difference that matters on the conversation
         * route. A Mac that listed a conversation and then goes quiet about it has not
         * stopped keeping conversations.
         */
        Silent,
    }

    /** True while the Mac is answering the open conversation, ours or anyone's. */
    var isConversationBusy by mutableStateOf(false)
        private set

    private suspend fun run(messageID: String, transport: ControlTransport) {
        val history = (current?.messages ?: emptyList())
            .filter { it.role != ChatMessage.ROLE_ASSISTANT || it.content.isNotEmpty() }
            .takeLast(historyLimit)
            .map { it.wire }
        val request = ChatRequest(history, maxTokens = maxTokens)

        if (usesStreaming) {
            // First choice: the conversation route, so the Mac keeps the transcript.
            val id = current?.id
            val last = history.lastOrNull()
            if (usesRemoteConversations && id != null && id !in keptHere && last != null) {
                when (consume(transport.sendMessage(id, last, maxTokens), messageID)) {
                    Outcome.Answered -> {
                        finishStreaming(null); persist(transport); return
                    }
                    // Only this route is missing; plain streaming may still be there.
                    Outcome.MissingRoute -> usesRemoteConversations = false
                    // The route answered and sent nothing. Plain streaming next — but the
                    // Mac listed this conversation a moment ago, so silence about it says
                    // nothing about whether it keeps conversations, and reading it as "it
                    // keeps none" would move every one of them onto the phone.
                    Outcome.Silent -> Unit
                    // This Mac keeps conversations and has never heard of this one: it was
                    // made here while the Mac was out of reach. It goes as plain history
                    // instead, and this device keeps the transcript. The Mac still keeps
                    // its own, so that flag stays exactly as it was.
                    Outcome.NoSuchConversation -> keptHere += id
                    Outcome.Busy -> {
                        // The Mac is still answering the previous message here. Sending
                        // it again would only be refused again.
                        isConversationBusy = true
                        persist(transport); return
                    }
                    Outcome.Stopped -> {
                        finishStreaming("Stopped."); return
                    }
                    Outcome.Failed -> {
                        persist(transport); return
                    }
                }
            }

            // Second: streaming without a stored conversation.
            when (consume(transport.chatStream(request), messageID)) {
                Outcome.Answered -> {
                    finishStreaming(null); persist(transport); return
                }
                Outcome.NoSuchConversation, Outcome.MissingRoute, Outcome.Silent ->
                    usesStreaming = false
                Outcome.Busy -> {
                    isConversationBusy = true
                    persist(transport); return
                }
                Outcome.Stopped -> {
                    finishStreaming("Stopped."); return
                }
                Outcome.Failed -> {
                    persist(transport); return
                }
            }
        }

        // Last: one request, one answer. Every Mac has this.
        try {
            val response = transport.chat(request)
            update(messageID) {
                it.copy(
                    content = response.content,
                    reasoning = response.reasoning,
                    metrics = ChatMetrics(
                        response.promptTokens,
                        response.generatedTokens,
                        response.tokensPerSecond,
                    ),
                    isStreaming = false,
                    failure = truncationNote(response.content, response.generatedTokens, maxTokens),
                )
            }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            currentCoroutineContext().ensureActive()
            lastFailure = failure
            val description = failure.message ?: "The Mac didn't answer."
            finishStreaming(description)
            error = description
        }
        persist(transport)
    }

    /** Drains one SSE stream into the placeholder message. */
    private suspend fun consume(
        stream: Flow<ChatStreamEvent>,
        messageID: String,
        limit: Int = maxTokens,
    ): Outcome {
        var sawAnything = false
        var finished = false
        try {
            stream.collect { event ->
                sawAnything = true
                when (event) {
                    is ChatStreamEvent.Token -> update(messageID) {
                        it.copy(content = it.content + event.text)
                    }
                    is ChatStreamEvent.Reasoning -> update(messageID) {
                        it.copy(reasoning = (it.reasoning ?: "") + event.text)
                    }
                    is ChatStreamEvent.Finished -> {
                        update(messageID) {
                            it.copy(
                                metrics = event.metrics,
                                isStreaming = false,
                                failure = truncationNote(
                                    it.content, event.metrics.generatedTokens, limit,
                                ),
                            )
                        }
                        // `finished` is the end of the reply, whatever else the Mac
                        // sends after it. Jev's answer checking appends a `verdict`
                        // frame, and a client that kept the composer closed until the
                        // socket closed would sit on a finished answer waiting for a
                        // check it can get from `/events` instead.
                        finished = true
                        throw StreamFinished
                    }
                    is ChatStreamEvent.Failed -> {
                        update(messageID) { it.copy(failure = event.message, isStreaming = false) }
                        error = event.message
                    }
                }
            }
        } catch (failure: StreamFinished) {
            return Outcome.Answered
        } catch (failure: TransportError) {
            // Given up on — Stop, or a new question — the socket closed under the read, and
            // the failure that comes of it is the cancellation's, not the Mac's.
            currentCoroutineContext().ensureActive()
            if (failure.isMissingRoute) return Outcome.MissingRoute
            if (failure is TransportError.Cancelled) return Outcome.Stopped
            // Nothing has been written into the reply yet, so it is still open for the
            // next attempt: no sentence is left on it, and no error is shown for something
            // the app is about to do another way.
            if (failure is TransportError.NotFound && !sawAnything) return Outcome.NoSuchConversation
            if (failure is TransportError.Conflict) {
                finishStreaming(failure.message)
                error = failure.message
                return Outcome.Busy
            }
            lastFailure = failure
            finishStreaming(failure.message)
            error = failure.message
            return Outcome.Failed
        } catch (failure: kotlinx.coroutines.CancellationException) {
            // This send was stopped or replaced. It ends here: whatever it would have done
            // next — close "the streaming reply", open the composer — is the next send's
            // to do, and would land on that send's reply.
            currentCoroutineContext().ensureActive()
            return Outcome.Stopped
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            lastFailure = failure
            finishStreaming(failure.message)
            error = failure.message
            return Outcome.Failed
        }
        // A stream that ends without one event is not an answer; try the next thing.
        return if (sawAnything || finished) Outcome.Answered else Outcome.Silent
    }

    /**
     * Thrown to leave `collect` the moment `finished` arrives. A flow has no `break`,
     * and cancelling the collector is the documented way to stop one early.
     */
    private object StreamFinished : kotlinx.coroutines.CancellationException("finished")

    /**
     * Attaches an answer check to the reply it belongs to.
     *
     * By the Mac's message id when it names one, so a check that arrives after the
     * person has sent something else still lands on the right reply.
     *
     * A named id that matches nothing here is dropped rather than guessed at. It means
     * the check is about a message this screen does not have — an older one scrolled
     * out of a transcript the device kept, or a reply in a conversation that was
     * reopened since — and stamping it on the newest reply would put the Mac's words
     * under an answer it never read.
     *
     * Only an unnamed check falls back to the newest finished reply, which is right
     * whenever the Mac checks a reply as it finishes, and is the only thing a
     * device-kept transcript can do: its ids are its own and the Mac has never seen them.
     */
    fun apply(verdict: dev.siliconoptimizer.buddy.transport.Verdict) {
        val conversation = current ?: return
        if (!VerdictMatching.belongs(verdict, conversation.id)) return
        val index = VerdictMatching.index(verdict, conversation.messages)
        if (index < 0) return
        val messages = conversation.messages.toMutableList()
        messages[index] = messages[index].copy(verdict = verdict)
        current = conversation.copy(messages = messages)
    }

    private fun update(messageID: String, change: (ChatMessage) -> ChatMessage) {
        val conversation = current ?: return
        current = conversation.copy(
            messages = conversation.messages.map { if (it.id == messageID) change(it) else it },
        )
    }

    private fun finishStreaming(failure: String?) {
        val conversation = current ?: return
        val index = conversation.messages.indexOfLast { it.isStreaming }
        if (index < 0) return
        val message = conversation.messages[index]
        val updated = message.copy(
            isStreaming = false,
            failure = if (failure != null && message.content.isEmpty()) failure else message.failure,
        )
        current = conversation.copy(
            messages = conversation.messages.toMutableList().also { it[index] = updated },
        )
    }

    private suspend fun persist(transport: ControlTransport? = null) {
        if (current?.onDevice == true) {
            persistPhone()
            return
        }
        if (usesRemoteConversations && current?.id !in keptHere) {
            // The Mac keeps the transcript, so the list it publishes is the one worth
            // showing: the title and the count are its answers, not ours.
            loadConversationsNow(transport)
            return
        }
        val conversation = current ?: return
        val saved = try {
            store.save(conversation)
        } catch (failure: java.io.IOException) {
            // The store wrote nothing and kept its file. The conversation is still on screen,
            // and the next save writes all of it.
            error = NOT_SAVED
            return
        }
        current = saved
        val index = conversations.indexOfFirst { it.id == saved.id }
        if (index >= 0) conversations[index] = saved else conversations.add(0, saved)
    }

    fun clearError() {
        error = null
    }

    /**
     * Called when the paired Mac changes. What this Mac supports is a fact about that
     * Mac, and so is its transcript: neither survives a re-pair.
     */
    fun macChanged() {
        sendJob?.cancel()
        sendJob = null
        isSending = false
        sendingSince = null
        isConversationBusy = false
        usesStreaming = true
        usesRemoteConversations = false
        askedAboutConversations = false
        conversationsAsk = null
        lastTransport = null
        lastConversationAsk = 0L
        keptHere.clear()
        conversations.clear()
        // The phone's own conversations belong to no Mac, so a new one does not take them.
        if (current?.onDevice != true) current = null
        error = null
        offer = null
        macState = MacState.Answering
    }

    /** Where the transcript came from, said plainly so nobody wonders. */
    val storageNote: String
        get() = when {
            current?.onDevice == true -> "Kept only on this phone — not synced with your Mac."
            current?.id in keptHere && usesRemoteConversations ->
                "Kept on this device: your Mac was out of reach when this one started."
            usesRemoteConversations -> "Synced with the Mac."
            // The Mac has never answered, so whether it keeps conversations is not known —
            // and saying it does not would be a claim about a Mac nobody has heard from.
            lastTransport != null && !askedAboutConversations ->
                "Kept on this device while your Mac is out of reach."
            else -> "Kept on this device — the Mac doesn't store conversations yet."
        }

    /** Adds a picture, or says why it cannot be added. The cap is the Mac's. */
    fun attach(dataUrl: String) {
        if (attachments.size >= SendLimits.MAX_ATTACHMENTS) {
            error = "The Mac takes at most ${SendLimits.MAX_ATTACHMENTS} pictures a message."
            return
        }
        if (SendLimits.encodedBytes(dataUrl) > SendLimits.MAX_IMAGE_BYTES) {
            error = "That picture is still too large after shrinking. Try a smaller one."
            return
        }
        attachments.add(dataUrl)
    }

    // MARK: - Answering on the phone

    /**
     * Whether to offer the phone, from what is known now. [question] is what the offer
     * would answer — the message the Mac did not — and [failedMessageID] the reply that
     * failed, which carries the same button.
     */
    private fun refreshOffer(question: String?, failedMessageID: String?) {
        val decision = FallbackPolicy.decide(
            mac = macState,
            installed = runCatching { phone.installed() }.getOrElse { emptyList() },
            runtimeAvailable = phone.couldRun,
            inPhoneConversation = current?.onDevice == true,
            preferredID = phone.preferredID,
        )
        offer = (decision as? FallbackDecision.Offer)?.let {
            PhoneOffer(
                question = question ?: offer?.question,
                model = it.model,
                reason = macState,
                failedMessageID = failedMessageID ?: offer?.failedMessageID,
            )
        }
    }

    /**
     * What the reachability probe found. The banner appears before anything is sent when
     * the Mac is already known to be out of reach, and goes when it answers again.
     */
    fun noteReachability(reachability: Reachability, paired: Boolean) {
        if (reachability is Reachability.Checking) return
        val state = MacState.of(reachability, paired)
        if (state == MacState.Answering && reachability is Reachability.Unknown && paired) return
        macState = state
        if (!state.isOutOfReach) {
            offer = null
            askAgainIfTheMacWasOutOfReach()
        } else {
            refreshOffer(null, null)
        }
    }

    /**
     * The event stream is open again: the Mac is answering, whatever the last probe said.
     * A stream that drops says nothing either way — it is reconnecting — so only this half
     * is taken from it.
     */
    fun noteStreamLive() = noteMacAnswered()

    /**
     * Something reached the Mac and it answered — the event stream coming up, or the
     * dashboard's own polling, which carries on every few seconds whatever the chat is
     * doing. Either is better news than the last probe.
     */
    fun noteMacAnswered() {
        macState = MacState.Answering
        offer = null
        askAgainIfTheMacWasOutOfReach()
    }

    /**
     * A Mac that was unreachable at launch was never answered about its conversations, and
     * `usesRemoteConversations` has been false-because-unknown ever since. Now that it is
     * answering, the question goes again — otherwise "Send to Mac…" would quietly make a
     * device-only conversation on a Mac that keeps its own.
     */
    private fun askAgainIfTheMacWasOutOfReach() {
        if (askedAboutConversations) return
        val transport = lastTransport ?: return
        if (conversationsAsk?.isActive == true) return
        if (clock() - lastConversationAsk < conversationAskFloorMillis) return
        lastConversationAsk = clock()
        conversationsAsk = viewModelScope.launch { loadConversationsNow(transport) }
    }

    /**
     * Whether the screen should stay awake: the phone's own model is loading or writing.
     *
     * Not for the Mac's answers — those are the Mac's watts, and the phone is only waiting.
     */
    val keepsScreenOn: Boolean
        get() = isSending && current?.onDevice == true

    /** Whether to say "Your Mac is back" in a conversation the phone answered. */
    val macIsBack: Boolean
        get() = current?.onDevice == true && macState == MacState.Answering && !isSending

    /**
     * Arrived from the tile or the widget, which found the Mac unreachable and a model ready.
     * The offer is shown; nothing is answered until it is tapped. If the app's own check
     * finds the Mac after all, the offer goes again.
     */
    fun offerFromShortcut() {
        if (current?.onDevice == true) return
        if (!macState.isOutOfReach) macState = MacState.Unreachable
        refreshOffer(null, null)
    }

    /**
     * "Answer on this phone": a new conversation that lives only on the phone, starting
     * with the question the Mac did not answer — or empty, ready for one.
     */
    fun answerOnPhone(model: InstalledPhoneModel? = null) {
        val chosen = model ?: offer?.model ?: return
        val question = offer?.question ?: draft.trim().takeIf { it.isNotEmpty() }
        offer = null
        refusal = null
        val fresh = Conversation(id = OnDeviceIds.mint(), onDevice = true, phoneModelID = chosen.id)
        current = fresh
        phoneConversations.removeAll { it.id == fresh.id }
        phoneConversations.add(0, fresh)
        if (question != null) {
            draft = question
            sendOnPhone(chosen)
        }
    }

    /** "Try again": the same question to the Mac, in place of the reply that failed. */
    fun retryOnMac(transport: ControlTransport?) {
        val pending = offer
        offer = null
        val question = pending?.question ?: return
        pending.failedMessageID?.let { failed ->
            current?.let { conversation ->
                val index = conversation.messages.indexOfFirst { it.id == failed }
                if (index >= 0) {
                    // The failed reply and the question it failed, so the retry is not a duplicate.
                    val keep = conversation.messages.toMutableList()
                    keep.removeAt(index)
                    if (index - 1 >= 0 && keep.getOrNull(index - 1)?.role == ChatMessage.ROLE_USER) keep.removeAt(index - 1)
                    current = conversation.copy(messages = keep)
                }
            }
        }
        draft = question
        send(transport)
    }

    /** The model a phone conversation answers with: its own, else the preferred one. */
    private fun modelFor(conversation: Conversation?, requested: InstalledPhoneModel?): InstalledPhoneModel? {
        if (requested != null) return requested
        val installed = runCatching { phone.installed() }.getOrElse { emptyList() }
        return installed.firstOrNull { it.id == conversation?.phoneModelID }
            ?: FallbackPolicy.choose(installed, phone.preferredID)
    }

    /**
     * Sends the draft to the phone's own model. Nothing here reaches the Mac: not the
     * question, not the answer, not the conversation's id.
     */
    fun sendOnPhone(requested: InstalledPhoneModel?) {
        val text = draft.trim()
        if (text.isEmpty()) return
        if (attachments.isNotEmpty()) {
            error = "The model on this phone reads text only. Remove the pictures, or send them to your Mac."
            return
        }
        var conversation = current?.takeIf { it.onDevice }
            ?: Conversation(id = OnDeviceIds.mint(), onDevice = true)
        val model = modelFor(conversation, requested) ?: run {
            error = OnDeviceNotices.NO_MODEL_YET
            return
        }
        draft = ""
        refusal = null
        error = null
        stopSending()
        // Read again: stopping closed the reply that was still arriving in it.
        conversation = current?.takeIf { it.id == conversation.id } ?: conversation
        val placeholderID = java.util.UUID.randomUUID().toString()
        conversation = conversation.copy(
            phoneModelID = model.id,
            messages = conversation.messages +
                ChatMessage(role = ChatMessage.ROLE_USER, content = text) +
                ChatMessage(
                    id = placeholderID,
                    role = ChatMessage.ROLE_ASSISTANT,
                    content = "",
                    isStreaming = true,
                    origin = ChatMessage.PHONE_ORIGIN + model.id,
                    originLabel = model.label,
                ),
        ).titledFromFirstMessage()
        current = conversation
        if (phoneConversations.none { it.id == conversation.id }) phoneConversations.add(0, conversation)
        isSending = true
        sendingSince = System.currentTimeMillis()

        sendJob = viewModelScope.launch {
            // Written down before anything slow happens. Loading a model is the longest
            // minute in this app and the likeliest moment for Android to take the process:
            // the question is already on the phone's disk by then, not only on the screen.
            persistPhone()
            val check = phone.preflight(model)
            when (check) {
                is Preflight.Refused -> {
                    update(placeholderID) { it.copy(isStreaming = false, failure = check.message) }
                    refusal = PhoneRefusal(check.message, check.alternative, text, placeholderID, model)
                }
                // It will run, on a phone with little to spare. Said before the answer
                // starts, not after something else has been closed for it.
                is Preflight.Warned -> memoryWarning = PhoneMemoryWarning(check.message, model)
                Preflight.Ready -> memoryWarning = null
            }
            if (check !is Preflight.Refused) {
                // Only the newest turns: the phone reads a prompt at about a hundred
                // tokens a second, and a long conversation would be a minute of silence
                // before the first word. The reply says when older ones were left out.
                val capped = PhoneHistory.cap(current?.messages.orEmpty().filter { it.id != placeholderID })
                if (capped.wasTrimmed) update(placeholderID) { it.copy(trimmedHistory = true) }
                consume(phone.answer(model, capped.messages, phoneMaxTokens), placeholderID, phoneMaxTokens)
                finishStreaming(null)
            }
            persistPhone()
            noteLastExchange(text)
            isSending = false
            sendingSince = null
        }
    }

    /** "Use the smaller model instead", after a refusal for memory. */
    fun useAlternative() {
        val declined = refusal ?: return
        askAgain(declined, declined.alternative ?: return)
    }

    /**
     * "Try again", after making room: the same question to the same model. The refusal was
     * about the phone at that moment, not about the question.
     */
    fun retryOnPhone() {
        val declined = refusal ?: return
        askAgain(declined, declined.model ?: modelFor(current, null) ?: return)
    }

    private fun askAgain(declined: PhoneRefusal, model: InstalledPhoneModel) {
        refusal = null
        current?.let { conversation ->
            val index = conversation.messages.indexOfFirst { it.id == declined.failedMessageID }
            if (index >= 0) {
                val keep = conversation.messages.toMutableList()
                keep.removeAt(index)
                if (index - 1 >= 0 && keep.getOrNull(index - 1)?.role == ChatMessage.ROLE_USER) keep.removeAt(index - 1)
                current = conversation.copy(messages = keep, phoneModelID = model.id)
            }
        }
        draft = declined.question
        sendOnPhone(model)
    }

    fun dismissRefusal() {
        refusal = null
    }

    private suspend fun persistPhone() {
        val conversation = current?.takeIf { it.onDevice } ?: return
        val saved = try {
            phone.conversations.save(conversation)
        } catch (failure: java.io.IOException) {
            error = NOT_SAVED
            return
        }
        current = saved
        val index = phoneConversations.indexOfFirst { it.id == saved.id }
        if (index >= 0) phoneConversations[index] = saved else phoneConversations.add(0, saved)
    }

    /** How many messages "Send to Mac…" would carry: what was actually said. */
    val sendableCount: Int
        get() = current?.takeIf { it.onDevice }?.messages?.count { it.content.isNotBlank() } ?: 0

    /**
     * "Send to Mac…", once the owner has agreed: a new conversation on the Mac that starts
     * from this one. The phone's copy stays on the phone. The Mac's route takes one message
     * at a time, so the transcript travels as one, quoted, and the Mac answers from where it
     * ends; a Mac that keeps no conversations is sent it as history instead.
     */
    fun sendToMac(transport: ControlTransport?) {
        val source = current?.takeIf { it.onDevice } ?: return
        // Half an answer is not what the owner meant to send. Stop it, or wait for it.
        if (isSending) return
        if (transport == null) {
            error = TransportError.NotConfigured.message
            return
        }
        val said = source.messages.filter { it.content.isNotBlank() }
        if (said.isEmpty()) return
        val message = SendToMac.message(said)
        val title = SendToMac.title(source.title)
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            val placeholderID = java.util.UUID.randomUUID().toString()
            isSending = true
            sendingSince = System.currentTimeMillis()
            error = null
            if (usesRemoteConversations) {
                try {
                    val created = transport.createConversation(title)
                    current = Conversation(
                        id = created.id,
                        title = created.title,
                        messages = listOf(
                            ChatMessage(role = ChatMessage.ROLE_USER, content = message),
                            ChatMessage(id = placeholderID, role = ChatMessage.ROLE_ASSISTANT, content = "", isStreaming = true),
                        ),
                    )
                    consume(
                        transport.sendMessage(created.id, ChatMessageWire(ChatMessage.ROLE_USER, message), maxTokens),
                        placeholderID,
                    )
                    finishStreaming(null)
                    loadConversationsNow(transport)
                } catch (failure: TransportError) {
                    error = failure.message
                }
            } else {
                // A Mac that keeps no conversations: a new one on the device, carrying the
                // whole exchange as history — but the Mac's, not the phone's.
                val history = said.map { ChatMessage(role = it.role, content = it.content) }
                current = Conversation(
                    title = title,
                    messages = history + ChatMessage(id = placeholderID, role = ChatMessage.ROLE_ASSISTANT, content = "", isStreaming = true),
                )
                consume(
                    transport.chatStream(ChatRequest(history.map { it.wire } + ChatMessageWire(ChatMessage.ROLE_USER, SendToMac.CONTINUE), maxTokens = maxTokens)),
                    placeholderID,
                )
                finishStreaming(null)
                persist(transport)
            }
            isSending = false
            sendingSince = null
        }
    }

    /** "New Mac conversation", from a phone conversation once the Mac is back. */
    fun newMacConversation(transport: ControlTransport?) {
        current = null
        newConversation(transport)
    }

    companion object {
        /** Said when this phone could not write a conversation down. It is still on screen. */
        const val NOT_SAVED =
            "This phone couldn't save the conversation just now. It's still here, and the " +
                "next message saves it again."

        /** Said when this phone could not take a conversation out of its store. */
        const val NOT_DELETED = "This phone couldn't delete that conversation just now. Try again."

        /**
         * A reasoning model can spend its whole budget thinking and answer nothing. An
         * empty bubble would look like a bug; saying what happened is the honest version.
         */
        fun truncationNote(content: String, generatedTokens: Int, limit: Int): String? =
            if (content.isEmpty() && generatedTokens >= limit) {
                "The model spent all $limit tokens thinking and never got to an answer. " +
                    "Ask again more narrowly, or load a model that thinks less."
            } else {
                null
            }
    }
}

/** The offer under a Mac that did not answer. */
data class PhoneOffer(
    /** What the phone would answer: the message the Mac did not. */
    val question: String?,
    val model: InstalledPhoneModel,
    val reason: MacState,
    /** The failed reply that carries the same button, if the offer came from one. */
    val failedMessageID: String?,
)

/** Why the phone declined, and the smaller model that would fit when memory was why. */
data class PhoneRefusal(
    val message: String,
    val alternative: InstalledPhoneModel?,
    val question: String,
    val failedMessageID: String,
    /** The model that was refused, so "make room" knows whose numbers to show. */
    val model: InstalledPhoneModel? = null,
)

/** The phone is answering with little to spare: the sentence, and the model it is about. */
data class PhoneMemoryWarning(val message: String, val model: InstalledPhoneModel)

/** How a conversation the phone answered is handed to the Mac. */
object SendToMac {
    const val CONTINUE = "Carry on from here."

    fun title(phoneTitle: String): String = "$phoneTitle (from my phone)"

    /**
     * One message carrying the whole exchange, labelled so the Mac's model can tell who
     * said what, and which answers were the phone's.
     */
    fun message(said: List<ChatMessage>): String = buildString {
        append("While you were out of reach I asked the model on my phone. Here is that conversation; ")
        append("carry on from where it ends.\n\n")
        said.forEach { message ->
            val who = when {
                message.role == ChatMessage.ROLE_USER -> "Me"
                message.isFromPhone -> "Phone (${message.originLabel ?: "on-device model"})"
                else -> "Assistant"
            }
            append("**").append(who).append(":** ").append(message.content.trim()).append("\n\n")
        }
    }.trimEnd()
}
