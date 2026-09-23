package dev.siliconoptimizer.buddy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.NavigationBarItemDefaults
import dev.siliconoptimizer.buddy.ui.EmptyState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.siliconoptimizer.buddy.agents.AgentNotices
import dev.siliconoptimizer.buddy.agents.AgentNotifier
import dev.siliconoptimizer.buddy.agents.AgentWatchService
import dev.siliconoptimizer.buddy.agents.AgentsScreen
import dev.siliconoptimizer.buddy.agents.AgentsViewModel
import dev.siliconoptimizer.buddy.agents.Confirm
import dev.siliconoptimizer.buddy.agents.ConfirmDialog
import dev.siliconoptimizer.buddy.agents.SessionScreen
import dev.siliconoptimizer.buddy.agents.knownEngine
import dev.siliconoptimizer.buddy.chat.ChatScreen
import dev.siliconoptimizer.buddy.chat.ChatViewModel
import dev.siliconoptimizer.buddy.dashboard.DashboardScreen
import dev.siliconoptimizer.buddy.dashboard.DashboardViewModel
import dev.siliconoptimizer.buddy.machines.MachinesScreen
import dev.siliconoptimizer.buddy.machines.MachinesViewModel
import dev.siliconoptimizer.buddy.media.CreateScreen
import dev.siliconoptimizer.buddy.media.MediaNotifier
import dev.siliconoptimizer.buddy.media.MediaViewModel
import dev.siliconoptimizer.buddy.modelsui.ModelsScreen
import dev.siliconoptimizer.buddy.modelsui.ModelsViewModel
import dev.siliconoptimizer.buddy.ondevice.OnDeviceEngine
import dev.siliconoptimizer.buddy.ondevice.OnDeviceNotices
import dev.siliconoptimizer.buddy.ondevice.PhoneModelsSection
import dev.siliconoptimizer.buddy.ondevice.PhoneModelsViewModel
import dev.siliconoptimizer.buddy.transport.Reachability
import dev.siliconoptimizer.buddy.pairing.MAC_TOO_OLD_FOR_CODES
import dev.siliconoptimizer.buddy.pairing.PairingConfirmation
import dev.siliconoptimizer.buddy.pairing.PairingExchange
import dev.siliconoptimizer.buddy.pairing.PairingInvite
import dev.siliconoptimizer.buddy.pairing.PairingMode
import dev.siliconoptimizer.buddy.pairing.PairingScreen
import dev.siliconoptimizer.buddy.reach.BuddyLink
import dev.siliconoptimizer.buddy.reach.QuickPrompt
import dev.siliconoptimizer.buddy.reach.SnapshotStore
import dev.siliconoptimizer.buddy.shortcuts.BuddyShortcuts
import dev.siliconoptimizer.buddy.ui.SiliconBuddyTheme
import dev.siliconoptimizer.buddy.widget.BuddyWidget
import androidx.glance.appwidget.updateAll

class MainActivity : ComponentActivity() {

    /**
     * The link this activity was opened or resumed with, as either an invite to
     * confirm or a refusal to explain. A link is a request, not an instruction: the
     * app only ever gets as far as asking.
     */
    private val arriving = mutableStateOf<LinkArrival?>(null)

    /** The same instances the screens draw from: Compose's `viewModel()` asks this activity. */
    private val agents: AgentsViewModel by viewModels()
    private val events: EventFeed by viewModels()
    private val appState: AppState by viewModels()
    private val dashboard: DashboardViewModel by viewModels()
    private val media: MediaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (arrivesFresh(restored = savedInstanceState != null, intent?.flags ?: 0)) {
            arriving.value = read(intent)
        }
        setContent {
            SiliconBuddyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BuddyApp(arriving = arriving)
                }
            }
        }
    }

    /**
     * Back in front: the Agents tab shows what the background service was watching, so the
     * service lets go — and takes its notifications with it, since the cards are here now.
     */
    override fun onStart() {
        super.onStart()
        AgentWatchService.stop(this)
        // Approval notifications a watcher left behind — its process died, say — have
        // nothing behind their buttons; the cards in the app are the live ones now.
        AgentNotifier(this).cancelApprovals()
        // The stream and the metrics this activity closed when it left the screen open
        // again. Only those: a first start is opened by the screen itself, once.
        dashboard.resumeLiveUpdates()
        // The phone's model keeps its idle clock again, rather than the background one.
        OnDeviceEngine.existing()?.appCameToForeground()
        if (events.resume()) {
            // Whatever the render queue did meanwhile was said to nobody; read it once, so a
            // clip that finished is announced now rather than never.
            media.startFollowing(appState.transport, MediaNotifier(this), live = true)
        }
    }

    /**
     * Leaving the foreground with a turn running in a session this phone opened. This is
     * the one moment Android still lets an app start a foreground service from here, and
     * the service is what keeps an approval from waiting unseen in a pocket.
     */
    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        // Off screen, the app's own stream and the dashboard's polling keep a radio awake to
        // tell nobody anything — and with the watcher running, at a foreground service's
        // priority. The watcher holds a stream of its own; these close until the app is back.
        events.pause()
        dashboard.pauseLiveUpdates()
        // An answer on the phone is only written while the app is in front: leaving stops
        // it, and the model is let go after thirty seconds unless the owner comes back.
        OnDeviceEngine.existing()?.appLeftForeground()
        val watched = agents.watchedTurns
        if (watched.isNotEmpty() && AgentNotifier(this).isAllowed) {
            AgentWatchService.start(this, watched)
        }
    }

    /**
     * A link tapped while the app is running arrives here rather than starting a second
     * activity — which would mean a second AppState, a second event stream, and two
     * screens disagreeing about which Mac is paired. The manifest's singleTop is the
     * other half of that.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        arriving.value = read(intent)
    }

    private fun read(intent: Intent?): LinkArrival? {
        // A finished render's notification opens the queue. Not a link: nothing outside
        // this app can send it, and it asks for a screen rather than for an action.
        if (intent?.getStringExtra(EXTRA_OPEN) == OPEN_QUEUE) return LinkArrival.OpenQueue
        // A phone-model download's notification: Settings, where that model's row is.
        if (intent?.getStringExtra(EXTRA_OPEN) == OPEN_PHONE_MODELS) return LinkArrival.OpenPhoneModels
        // An approval's notification opens its session. The same kind of request: a
        // screen, never a decision.
        if (intent?.getStringExtra(EXTRA_OPEN) == OPEN_AGENTS) {
            // An engine this build does not know is dropped, not opened; an approval id only
            // chooses which waiting card is in front, and is never sent anywhere.
            return LinkArrival.OpenAgents(
                knownEngine(intent.getStringExtra(EXTRA_ENGINE)),
                intent.getStringExtra(EXTRA_APPROVAL)?.takeIf { it.isNotBlank() && it.length <= 128 },
            )
        }
        val data = intent?.data ?: return null
        // `siliconbuddy://` means three things now: a pairing code, a composer to open,
        // and a conversation to show. All three are requests rather than instructions —
        // anything on the phone can fire one — so a composer link fills the box in and
        // never sends.
        return when (
            val link = BuddyLink.parse(
                data.scheme, data.host ?: data.path?.trim('/'),
                data.getQueryParameter("text"), data.getQueryParameter("id"),
                data.getQueryParameter("offer"),
            )
        ) {
            is BuddyLink.Compose -> LinkArrival.Compose(link.text, link.offerPhone)
            is BuddyLink.Conversation -> LinkArrival.OpenConversation(link.id)
            BuddyLink.Pair, null -> try {
                LinkArrival.Invite(PairingInvite.parse(data))
            } catch (error: PairingInvite.ParseError) {
                LinkArrival.Refused(error.message ?: "That isn't a Silicon Buddy code.")
            }
        }
    }

    companion object {
        /**
         * Whether the intent `onCreate` is handed is arriving now, rather than being handed
         * back. An activity recreated for a new font size or display density, or after its
         * process died, gets the intent it was first opened with again, and so does one
         * reopened from Recents. Read a second time, a pairing link asks to pair with a
         * code already spent — and when it pairs, to replace the Mac it just paired with.
         * What was read the first time lives on where it went: an invite still waiting is
         * in `AppState`, which outlives the activity.
         */
        fun arrivesFresh(restored: Boolean, flags: Int): Boolean =
            !restored && (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0

        /** Which screen a notification wants open. */
        const val EXTRA_OPEN = "dev.siliconoptimizer.buddy.OPEN"
        const val OPEN_QUEUE = "queue"
        const val OPEN_AGENTS = "agents"
        const val OPEN_PHONE_MODELS = "phone-models"
        const val EXTRA_ENGINE = "dev.siliconoptimizer.buddy.ENGINE"
        const val EXTRA_APPROVAL = "dev.siliconoptimizer.buddy.APPROVAL"
    }
}

/** What arrived on a `siliconbuddy://` link. */
sealed interface LinkArrival {
    data class Invite(val invite: PairingInvite) : LinkArrival
    data class Refused(val reason: String) : LinkArrival

    /**
     * Open the composer, with this typed into it. Never sent. [offerPhone] shows "Answer on
     * this phone" as well — the tile and the widget ask for it when the Mac was unreachable
     * — and still nothing is answered until it is tapped.
     */
    data class Compose(val text: String?, val offerPhone: Boolean = false) : LinkArrival

    /** Settings, where the phone's own models are. */
    data object OpenPhoneModels : LinkArrival
    data class OpenConversation(val id: String) : LinkArrival

    /** Show the render queue: where a job that just finished can be looked at. */
    data object OpenQueue : LinkArrival

    /** Show the Agents tab, on one engine's session when it names one. */
    data class OpenAgents(val engine: String?, val approvalID: String? = null) : LinkArrival
}

private enum class Destination(val label: String) {
    Dashboard("Mac"), Create("Create"), Agents("Agents"), Machines("Machines"),
    Models("Models"), Chat("Chat"), Settings("Settings"),
    ;

    companion object {
        /**
         * Saved by name, so a tab added or reordered later cannot restore somebody onto
         * a different screen than the one they left.
         */
        val Saver: androidx.compose.runtime.saveable.Saver<Destination, String> =
            androidx.compose.runtime.saveable.Saver(
                save = { it.name },
                restore = { name -> entries.firstOrNull { it.name == name } ?: Dashboard },
            )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuddyApp(arriving: androidx.compose.runtime.MutableState<LinkArrival?> = remember { mutableStateOf(null) }) {
    val app: AppState = viewModel()
    val dashboard: DashboardViewModel = viewModel()
    val models: ModelsViewModel = viewModel()
    val chat: ChatViewModel = viewModel()
    val events: EventFeed = viewModel()
    val media: MediaViewModel = viewModel()
    val machines: MachinesViewModel = viewModel()
    val agents: AgentsViewModel = viewModel()
    val phoneModels: PhoneModelsViewModel = viewModel()

    // Saved rather than merely remembered. Two things take this activity away and bring
    // it back: a configuration change the manifest does not absorb — font scale is the
    // common one — and One UI deciding a backgrounded app has had long enough. Either
    // way `remember` alone puts the person back on the dashboard, having lost the
    // conversation they were reading.
    var destination by rememberSaveable(stateSaver = Destination.Saver) {
        mutableStateOf(Destination.Dashboard)
    }
    // Where Settings was opened from, so its back arrow and the system's Back return there
    // rather than to the Mac tab — or, worse, out of the app.
    var settingsFrom by rememberSaveable(stateSaver = Destination.Saver) {
        mutableStateOf(Destination.Dashboard)
    }
    var pairing by remember { mutableStateOf(false) }
    // Set when a code met a Mac without `/buddy/pair`: the sheet opens where that Mac can
    // still be reached from an emulator, saying why.
    var pairingMacTooOld by remember { mutableStateOf(false) }
    var refusedLink by remember { mutableStateOf<String?>(null) }
    var openConversation by rememberSaveable { mutableStateOf<String?>(null) }
    // A new conversation on a Mac that keeps them is made *there*, which is a round trip:
    // the id to open does not exist at the moment the button is tapped. This says to open
    // whichever conversation the chat lands on next.
    var openingNew by remember { mutableStateOf(false) }
    var openAgent by rememberSaveable { mutableStateOf<String?>(null) }
    var agentMenu by remember { mutableStateOf(false) }
    var confirmingAgent by remember { mutableStateOf<Confirm?>(null) }
    var chatMenu by remember { mutableStateOf(false) }
    var confirmingSendToMac by remember { mutableStateOf(false) }

    // Every agent route runs commands on the Mac, so a device paired for chat does not get
    // the tab at all — Settings says why in one line. A destination saved before the scope
    // was known falls back rather than opening onto a refusal.
    val destinations = Destination.entries.filter { it != Destination.Agents || app.canControl }
    if (destination !in destinations) destination = Destination.Dashboard
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) { app.refreshReachability() }

    // A link is a request, not an instruction: anything on the device can open a URL in
    // this app. It only ever gets as far as asking — or, when the host is not on the
    // tailnet, explaining why not.
    LaunchedEffect(arriving.value) {
        when (val arrival = arriving.value) {
            is LinkArrival.Invite -> {
                // AppState holds it from here, until it is answered.
                app.pendingInvite = arrival.invite
                arriving.value = null
            }
            is LinkArrival.Refused -> refusedLink = arrival.reason
            is LinkArrival.Compose -> {
                destination = Destination.Chat
                // The offer first: the tile and the widget only send this when *they* found
                // the Mac unreachable, and a chat that knows that makes the conversation here
                // instead of asking a Mac that will not answer — which is the difference
                // between a composer and a spinner over a list.
                if (arrival.offerPhone) chat.offerFromShortcut()
                if (chat.current == null) chat.newConversation(app.transport)
                openConversation = chat.current?.id
                openingNew = openConversation == null
                arrival.text?.let { chat.draft = it }
                arriving.value = null
            }
            LinkArrival.OpenPhoneModels -> {
                if (destination != Destination.Settings) settingsFrom = destination
                destination = Destination.Settings
                arriving.value = null
            }
            is LinkArrival.OpenConversation -> {
                destination = Destination.Chat
                openConversation = arrival.id
                arriving.value = null
            }
            LinkArrival.OpenQueue -> {
                destination = Destination.Create
                media.tab = MediaViewModel.Tab.Queue
                arriving.value = null
            }
            is LinkArrival.OpenAgents -> {
                if (app.canControl) {
                    destination = Destination.Agents
                    openAgent = arrival.engine
                    // Review on a notification: that card, not whichever has waited longest.
                    val engine = arrival.engine
                    val approval = arrival.approvalID
                    if (engine != null && approval != null) agents.focus(engine, approval)
                }
                arriving.value = null
            }
            null -> Unit
        }
    }

    // What the Mac is running is what a widget, a tile and the launcher's long-press
    // menu all show, so it is written down every time this app learns it.
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(events.status, app.config) {
        events.status?.let {
            SnapshotStore(context).note(it, app.config?.macName)
            BuddyShortcuts.refresh(context)
            BuddyWidget().updateAll(context)
        }
    }

    // Whether the Mac can answer decides whether the phone offers to. The probe's answer
    // goes to the chat — which shows "Answer on this phone" before anything is sent when the
    // Mac is already known to be out of reach — and to the tile and the widget.
    LaunchedEffect(app.reachability, app.config) {
        chat.noteReachability(app.reachability, app.isPaired)
        when (app.reachability) {
            is Reachability.Ready -> SnapshotStore(context).noteMacReachable(true)
            is Reachability.Unreachable, Reachability.AppNotRunning -> SnapshotStore(context).noteMacReachable(false)
            else -> Unit
        }
    }

    // The event stream coming back is the quickest news that the Mac is answering again.
    LaunchedEffect(events.isLive) {
        if (events.isLive) chat.noteStreamLive()
    }

    // …and on a Mac with no event stream, the dashboard's polling is the news instead.
    // Settings → On this phone listens to the same news: its list of models comes from the
    // Mac, and a Mac that was out of reach when it was opened leaves it saying so.
    LaunchedEffect(dashboard.macAnsweredAt) {
        if (dashboard.macAnsweredAt > 0L) {
            chat.noteMacAnswered()
            phoneModels.macIsBack(app.transport, app.canControl)
        }
    }

    LaunchedEffect(events.isLive) {
        if (events.isLive) phoneModels.macIsBack(app.transport, app.canControl)
    }

    // A conversation the chat moved to by itself — the phone's own, answering; a new one on
    // the Mac from "Send to Mac…" or "New Mac conversation" — is the open one now, so it is
    // the one a restart comes back to. Only while a conversation is open: the list stays
    // the list.
    LaunchedEffect(chat.current?.id) {
        val id = chat.current?.id ?: return@LaunchedEffect
        if (destination == Destination.Chat && (openingNew || (openConversation != null && id != openConversation))) {
            openConversation = id
            openingNew = false
        }
    }

    // Answer checks arrive after the reply they are about, on the shared event stream,
    // so they are applied wherever the chat screen happens to be.
    LaunchedEffect(events.verdicts.size, openConversation) {
        val id = chat.current?.id ?: return@LaunchedEffect
        (events.verdicts[id] ?: events.verdicts[""])?.let { chat.apply(it) }
    }

    // The models list shows what the Mac pushes, and a load it started is followed by it:
    // `POST /load` may answer "still loading" and carry on. Here rather than on the models
    // screen, so a load keeps being followed while another screen is showing.
    LaunchedEffect(events.status) { events.status?.let(models::statusChanged) }
    LaunchedEffect(events.isLive) { models.eventsLive = events.isLive }

    // Pairing happens over the dashboard, so the first reading has to be triggered by
    // the Mac arriving — and a different Mac means everything on screen belongs to the
    // wrong machine.
    LaunchedEffect(app.connectionGeneration) {
        dashboard.reset()
        models.reset()
        chat.macChanged()
        dashboard.refresh(app.transport, app)
        dashboard.startLiveUpdates(app.transport)
        models.refresh(app.transport)
        media.reset()
        media.refresh(app.transport, MediaNotifier(context), app.canControl)
        machines.reset()
        machines.refresh(app.transport)
        chat.loadConversations(app.transport)
        agents.reset()
        openAgent = openAgent.takeIf { app.canControl }
        if (app.canControl) agents.refresh(app.transport)
        phoneModels.refresh(app.transport, app.canControl)
        events.start(app.transport)
    }

    // The agent sessions ride the same stream: every frame goes to the reducer, and a
    // break in it — a dropped connection, frames the Mac had to drop — arrives in order
    // with them, so the sessions know exactly which frames follow on and which do not.
    LaunchedEffect(Unit) {
        events.agentEvents.collect { feed ->
            when (feed) {
                is AgentFeed.Frame -> agents.apply(feed.event)
                AgentFeed.Broken -> agents.streamBroken()
            }
        }
    }
    // A 401 on any route is the Mac saying it no longer knows this phone, and that is as
    // true of every other route: nothing polls, nothing streams, nothing reads, until the
    // phone is paired again — which starts all of it afresh above.
    val revoked = agents.unpaired || events.unauthorized || dashboard.unpaired
    LaunchedEffect(revoked) {
        if (revoked) {
            agents.markUnpaired()
            dashboard.markUnpaired()
            media.stopFollowing()
            events.stop()
            app.noteRevoked()
        }
    }
    // Frames dropped for this phone may have been about anything it shows: the Mac asks
    // for the status and the render queue to be read again as well as the sessions.
    LaunchedEffect(events.resyncs) {
        if (events.resyncs > 0) {
            dashboard.refresh(app.transport, app)
            media.startFollowing(app.transport, MediaNotifier(context), live = true)
        }
    }
    // The tile says how many approvals are waiting, so it is told whenever that changes.
    LaunchedEffect(agents.board.pendingTotal) {
        SnapshotStore(context).notePendingApprovals(agents.board.pendingTotal)
        dev.siliconoptimizer.buddy.tile.BuddyTileService.refresh(context)
    }

    // Renders finish minutes after they were asked for, and on whatever screen happens
    // to be in front. The queue hears about it here, once, and says so.
    val notifier = remember(context) { MediaNotifier(context) }
    LaunchedEffect(Unit) {
        events.jobEvents.collect { media.apply(it, notifier, app.transport) }
    }

    // What the Mac pushes, when it can push.
    LaunchedEffect(events.status) {
        events.status?.let { dashboard.apply(it) }
    }
    LaunchedEffect(events.mustPoll) {
        dashboard.pollsStatus = events.mustPoll
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val wide = configuration.screenWidthDp >= 600
    // A phone on its side is wide and short: the rail cannot hold seven destinations at a
    // readable size, so it scrolls, and Settings keeps its gear in the top bar as well.
    val shortWindow = configuration.screenHeightDp < 480
    val gearInTopBar = !wide || shortWindow
    fun openSettings() {
        if (destination != Destination.Settings) settingsFrom = destination
        destination = Destination.Settings
    }
    val settingsOpenedAsPage = destination == Destination.Settings && gearInTopBar
    BackHandler(enabled = settingsOpenedAsPage) { destination = settingsFrom }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Text(
                        when (destination) {
                            Destination.Dashboard -> "Silicon Buddy"
                            Destination.Create -> "Create"
                            Destination.Agents -> openAgent?.let { AgentNotices.engine(it) } ?: "Agents"
                            Destination.Machines -> "Machines"
                            Destination.Models -> "Models"
                            Destination.Chat -> chat.current?.title ?: "Chat"
                            Destination.Settings -> "Settings"
                        },
                        maxLines = 1,
                        style = MaterialTheme.typography.titleLarge,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    when {
                        destination == Destination.Agents && openAgent != null ->
                            IconButton(onClick = { openAgent = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All agents")
                            }
                        settingsOpenedAsPage ->
                            IconButton(onClick = { destination = settingsFrom }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                    }
                },
                actions = {
                    when (destination) {
                        Destination.Dashboard -> IconButton(onClick = {
                            app.refreshReachability()
                            dashboard.refresh(app.transport, app)
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                        Destination.Chat -> {
                            if (chat.current?.onDevice == true && app.transport != null) {
                                IconButton(onClick = { chatMenu = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "Conversation actions")
                                }
                                DropdownMenu(expanded = chatMenu, onDismissRequest = { chatMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(OnDeviceNotices.SEND_TO_MAC) },
                                        // Not while the phone is still writing: half an
                                        // answer is not what anybody means to send.
                                        enabled = chat.sendableCount > 0 && !chat.isSending,
                                        onClick = {
                                            chatMenu = false
                                            confirmingSendToMac = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(OnDeviceNotices.NEW_MAC_CONVERSATION) },
                                        onClick = {
                                            chatMenu = false
                                            chat.newMacConversation(app.transport)
                                        },
                                    )
                                }
                            }
                            IconButton(onClick = {
                                // With the transport: on a Mac that keeps conversations, a
                                // new one belongs there, and one made without it is a
                                // device-only conversation the Mac never hears about.
                                chat.newConversation(app.transport)
                                openConversation = chat.current?.id
                                // Made on the Mac: the effect above opens it when it lands.
                                openingNew = openConversation == null
                            }) {
                                Icon(Icons.Filled.Add, contentDescription = "New conversation")
                            }
                        }
                        Destination.Machines -> IconButton(onClick = {
                            machines.refresh(app.transport)
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                        Destination.Create -> IconButton(onClick = {
                            media.refresh(app.transport, canControl = app.canControl)
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                        Destination.Agents -> {
                            val engine = openAgent
                            if (engine == null) {
                                IconButton(onClick = { agents.refresh(app.transport) }) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                                }
                            } else {
                                val summary = agents.board.session(engine).summary
                                IconButton(onClick = { agentMenu = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "Session actions")
                                }
                                DropdownMenu(expanded = agentMenu, onDismissRequest = { agentMenu = false }) {
                                    if (summary?.isRunning == true) {
                                        DropdownMenuItem(
                                            text = { Text("New thread…") },
                                            onClick = {
                                                agentMenu = false
                                                confirmingAgent = Confirm.NewThread(engine)
                                            },
                                        )
                                    }
                                    if (summary != null && !summary.isStopped) {
                                        DropdownMenuItem(
                                            text = { Text("Stop ${AgentNotices.engine(engine)}…") },
                                            onClick = {
                                                agentMenu = false
                                                confirmingAgent = Confirm.Stop(engine)
                                            },
                                        )
                                    } else {
                                        DropdownMenuItem(
                                            text = { Text("Start ${AgentNotices.engine(engine)}") },
                                            onClick = {
                                                agentMenu = false
                                                agents.start(engine)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        else -> Unit
                    }
                    // Seven destinations do not fit a phone's bar at a readable size, so
                    // on a phone Settings moves up here; a tablet's rail still lists it, and
                    // a phone on its side has both.
                    if (gearInTopBar && destination != Destination.Settings) {
                        IconButton(onClick = { openSettings() }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!wide) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    destinations.filter { it != Destination.Settings }.forEach { entry ->
                        NavigationBarItem(
                            selected = destination == entry,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                            onClick = { destination = entry },
                            icon = { DestinationIcon(entry, agents.board.pendingTotal) },
                            // Six destinations on a phone, at whatever text size the
                            // owner reads at: one line each, and the end of a word
                            // rather than half of it on the next line.
                            label = {
                                Text(
                                    entry.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.destinationSemantics(entry, agents.board.pendingTotal),
                                )
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // `consumeWindowInsets` is the half that is easy to leave out, and leaving it
        // out is visible: Scaffold hands down padding for the navigation bar and the
        // bottom bar, and then `ChatScreen`'s `imePadding()` measures the keyboard from
        // the bottom of the *window* and adds all of it again. The composer ends up
        // floating a navigation bar's height above the keyboard with dead space under
        // it. Consuming the padding here tells the descendants that much is already
        // dealt with, so `imePadding()` only adds the rest.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            if (wide) {
                // A tablet has room for the conversation list beside the transcript.
                NavigationRail {
                    // Scrolls, so a short window still reaches every destination.
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                    destinations.forEach { entry ->
                        NavigationRailItem(
                            selected = destination == entry,
                            onClick = {
                                if (entry == Destination.Settings) openSettings() else destination = entry
                            },
                            icon = { DestinationIcon(entry, agents.board.pendingTotal) },
                            label = {
                                Text(
                                    entry.label,
                                    modifier = Modifier.destinationSemantics(entry, agents.board.pendingTotal),
                                )
                            },
                        )
                    }
                    }
                }
                if (destination == Destination.Chat) {
                    Column(modifier = Modifier.width(260.dp).fillMaxSize()) {
                        ConversationList(chat, app) { openConversation = it }
                    }
                    HorizontalDivider()
                }
            }

            when (destination) {
                Destination.Dashboard -> DashboardScreen(
                    app = app,
                    model = dashboard,
                    events = events,
                    onPair = { pairing = true },
                    modifier = Modifier.fillMaxSize(),
                )
                Destination.Create -> CreateScreen(app, media, events.isLive, Modifier.fillMaxSize())
                Destination.Agents -> {
                    val engine = openAgent
                    if (engine == null) {
                        AgentsScreen(
                            app, agents,
                            onOpen = { openAgent = it },
                            onPair = { pairing = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        BackHandler { openAgent = null }
                        SessionScreen(agents, engine, onPair = { pairing = true }, modifier = Modifier.fillMaxSize())
                    }
                }
                Destination.Machines -> MachinesScreen(app, machines, Modifier.fillMaxSize())
                Destination.Models -> ModelsScreen(app, models, events, Modifier.fillMaxSize())
                Destination.Chat -> {
                    if (!wide && openConversation == null &&
                        (chat.conversations.isNotEmpty() || chat.phoneConversations.isNotEmpty())
                    ) {
                        ConversationList(chat, app) { openConversation = it }
                    } else {
                        // Keyed on readiness as well as on the id: a restored id
                        // arrives before the Mac has said whether it keeps conversations
                        // at all, and opening then yields an empty transcript with the
                        // right title. Re-runs once the answer is in.
                        LaunchedEffect(openConversation, chat.askedAboutConversations) {
                            // Not again when the chat already has it — the case where the
                            // chat moved there itself, possibly mid-answer.
                            openConversation?.let { if (chat.current?.id != it) chat.open(it, app.transport) }
                        }
                        ChatScreen(app, chat, Modifier.fillMaxSize())
                    }
                }
                Destination.Settings -> SettingsScreen(app, chat, events, phoneModels) { pairing = true }
            }
        }
    }

    if (confirmingSendToMac) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmingSendToMac = false },
            title = { Text("Send to your Mac?") },
            text = { Text(OnDeviceNotices.sendToMacQuestion(chat.sendableCount, app.macDisplayName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingSendToMac = false
                    chat.sendToMac(app.transport)
                }) { Text("Send ${chat.sendableCount}") }
            },
            dismissButton = { TextButton(onClick = { confirmingSendToMac = false }) { Text("Keep it here") } },
        )
    }

    confirmingAgent?.let { confirm ->
        ConfirmDialog(
            confirm = confirm,
            onDismiss = { confirmingAgent = null },
            onConfirm = {
                confirmingAgent = null
                when (confirm) {
                    is Confirm.Stop -> agents.stop(confirm.engine)
                    is Confirm.NewThread -> agents.newThread(confirm.engine)
                }
            },
        )
    }

    if (pairing) {
        // Closing the sheet leaves a code being spent to finish; a failure it was showing
        // has been seen, and one that comes later is said below instead.
        ModalBottomSheet(
            onDismissRequest = { pairing = false; pairingMacTooOld = false; app.pairing.acknowledge() },
            sheetState = sheetState,
        ) {
            PairingScreen(
                app = app,
                onDone = { pairing = false; pairingMacTooOld = false; app.pairing.acknowledge() },
                startOn = if (pairingMacTooOld) PairingMode.Developer else PairingMode.Scan,
                notice = if (pairingMacTooOld) MAC_TOO_OLD_FOR_CODES else null,
            )
        }
    }

    refusedLink?.let { reason ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { refusedLink = null; arriving.value = null },
            title = { Text("That link isn't a pairing code") },
            text = { Text(reason) },
            confirmButton = {
                TextButton(onClick = { refusedLink = null; arriving.value = null }) { Text("OK") }
            },
        )
    }

    // A code spent from a sheet that was closed before the Mac answered. Paired, it shows as
    // the Mac on the dashboard; refused, it is said here, since nothing else is left to.
    (app.pairing.state as? PairingExchange.State.Failed)?.let { failed ->
        if (!pairing && app.pendingInvite == null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { app.pairing.acknowledge() },
                title = { Text("Pairing didn't finish") },
                text = { Text("${failed.invite.displayAddress}: ${failed.message}") },
                confirmButton = {
                    TextButton(onClick = { app.pairing.acknowledge() }) { Text("OK") }
                },
            )
        }
    }

    // A code that arrived from a QR or a link: named, and agreed to, before anything
    // is dialled — and twice over when it would replace the Mac already paired.
    app.pendingInvite?.let { invite ->
        PairingConfirmation(
            app = app,
            invite = invite,
            onDismiss = { app.declinePendingInvite(); arriving.value = null },
            onMacTooOld = {
                app.pendingInvite = null
                pairingMacTooOld = true
                pairing = true
            },
        )
    }
}

/** A destination's icon — and on Agents, how many approvals are waiting. */
@Composable
private fun DestinationIcon(destination: Destination, waiting: Int) {
    if (destination == Destination.Agents && waiting > 0) {
        BadgedBox(badge = { Badge { Text(if (waiting > 99) "99+" else waiting.toString()) } }) {
            Icon(iconFor(destination), contentDescription = null)
        }
    } else {
        Icon(iconFor(destination), contentDescription = null)
    }
}

/**
 * What a screen reader says for a destination. The navigation bar clears its icons'
 * semantics whenever a label is shown — so a badge on the icon is silent — and the count
 * has to ride on the label instead: "Agents, 1 approval waiting".
 */
private fun Modifier.destinationSemantics(destination: Destination, waiting: Int): Modifier =
    if (destination == Destination.Agents && waiting > 0) {
        semantics {
            contentDescription = destination.label + ", " +
                if (waiting == 1) "1 approval waiting" else "$waiting approvals waiting"
        }
    } else {
        this
    }

private fun iconFor(destination: Destination) = when (destination) {
    Destination.Dashboard -> Icons.Filled.Speed
    Destination.Create -> Icons.Filled.AutoAwesome
    Destination.Agents -> Icons.Filled.SmartToy
    Destination.Machines -> Icons.Filled.Hub
    Destination.Models -> Icons.Filled.Layers
    Destination.Chat -> Icons.AutoMirrored.Filled.Chat
    Destination.Settings -> Icons.Filled.Settings
}

@Composable
private fun ConversationList(
    chat: ChatViewModel,
    app: AppState,
    onOpen: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (chat.conversations.isEmpty() && chat.phoneConversations.isEmpty()) {
            item {
                EmptyState(
                    "A space for your next idea",
                    "Start a conversation with the + button. Your chats will be here when you come back.",
                    Icons.AutoMirrored.Filled.Chat,
                )
            }
        }
        // Conversations the phone answered itself: their own section, and never the Mac's.
        if (chat.phoneConversations.isNotEmpty()) {
            item {
                Text(
                    OnDeviceNotices.SECTION,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp),
                )
            }
            items(chat.phoneConversations, key = { it.id }) { conversation ->
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    TextButton(onClick = { onOpen(conversation.id) }) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${conversation.messageCount} messages · on this phone",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
            if (chat.conversations.isNotEmpty()) {
                item {
                    Text(
                        "From your Mac",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp),
                    )
                }
            }
        }
        items(chat.conversations, key = { it.id }) { conversation ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                TextButton(onClick = { onOpen(conversation.id) }) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${conversation.messageCount} messages",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider()
        }
        item {
            Text(
                chat.storageNote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    app: AppState,
    chat: ChatViewModel,
    events: EventFeed,
    phoneModels: PhoneModelsViewModel,
    onPair: () -> Unit,
) {
    // A clock that runs only while the stream is down, so the "next try in Ns" line
    // counts down instead of freezing on the number it was given. It stops the moment
    // the stream is back, which is why it is keyed on `retryAt` rather than left ticking.
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(events.retryAt) {
        while (events.retryAt != null) {
            tick = System.currentTimeMillis()
            kotlinx.coroutines.delay(500)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Mac", style = MaterialTheme.typography.titleMedium)
            SettingRow("Name", app.macDisplayName)
            app.config?.let {
                SettingRow("Address", it.displayAddress)
                SettingRow(
                    "Token",
                    if (app.tokenIsEncrypted) {
                        "Stored encrypted on this device"
                    } else {
                        "Stored on this device (keystore unavailable)"
                    },
                )
                it.deviceID?.let { id -> SettingRow("Device id", id) }
            }
            SettingRow("Status", app.reachability.headline)
            SettingRow(
                "This device may",
                if (app.canControl) "Control the Mac" else "Chat and read only",
            )
            if (app.isPaired && !app.canControl) {
                Text(
                    AgentNotices.CHAT_SCOPE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!app.canStoreTokenSecurely) {
                Text(
                    "This device can't store the token securely — its keystore is " +
                        "unavailable — so pairing lasts only until the app closes. " +
                        "Pair again when you reopen it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPair) {
                    Text(if (app.isPaired) "Pair with another Mac" else "Pair with a Mac")
                }
                if (app.isPaired) {
                    TextButton(onClick = { app.forget() }) { Text("Forget this Mac") }
                }
            }
        }
        item {
            HorizontalDivider()
            Text("What this Mac supports", style = MaterialTheme.typography.titleMedium)
            SettingRow(
                "Streaming replies",
                if (chat.usesStreaming) "Using /chat/stream" else "Falling back to /chat",
            )
            SettingRow(
                "Conversations",
                if (chat.usesRemoteConversations) "On the Mac" else "On this device",
            )
            SettingRow(
                "Live events",
                when {
                    events.isLive -> "Streaming from /events"
                    events.mustPoll -> "Polling — this Mac has no /events"
                    events.retryAt != null ->
                        // `tick` is read here so this line recomposes once a second and
                        // the countdown actually counts down rather than freezing on
                        // whatever it said when the stream dropped.
                        "Reconnecting — next try in ${events.secondsUntilRetry(tick)}s"
                    else -> "Not started"
                },
            )
            Text(
                if (app.canControl) {
                    "Silicon Buddy asks for the newer routes and falls back quietly when a " +
                        "Mac doesn't have them yet. Nothing here needs configuring."
                } else {
                    app.scope.explanation
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            HorizontalDivider()
            Text("Reaching in", style = MaterialTheme.typography.titleMedium)
            val context = androidx.compose.ui.platform.LocalContext.current
            val snapshots = remember { SnapshotStore(context) }
            var speaks by remember { mutableStateOf(snapshots.speaksReplies) }
            var quickPrompt by remember { mutableStateOf(snapshots.quickPrompt) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Read answers out loud",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.Switch(
                    checked = speaks,
                    onCheckedChange = { speaks = it; snapshots.speaksReplies = it },
                )
            }
            Text(
                "Widget question",
                style = MaterialTheme.typography.bodyMedium,
            )
            QuickPrompt.presets.forEach { preset ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = quickPrompt == preset,
                        onClick = { quickPrompt = preset; snapshots.quickPrompt = preset },
                    )
                    Text(preset, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "The widget's button asks this question with one tap. Hold the " +
                    "microphone in a conversation to ask out loud; let go to send. " +
                    "Where this phone can recognise speech itself the audio never " +
                    "leaves it; where it cannot, the recording goes to Google to be " +
                    "turned into text, and the composer says which is happening while " +
                    "you hold the button.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            HorizontalDivider()
            PhoneModelsSection(
                model = phoneModels,
                macFrames = events.phoneModels,
                onRefresh = { phoneModels.refresh(app.transport, app.canControl) },
            )
        }
        item {
            HorizontalDivider()
            Text("This device", style = MaterialTheme.typography.titleMedium)
            SettingRow("Name", AppState.deviceName)
            SettingRow("Platform", AppState.platform)
            SettingRow("App", BuildConfig.VERSION_NAME)
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
