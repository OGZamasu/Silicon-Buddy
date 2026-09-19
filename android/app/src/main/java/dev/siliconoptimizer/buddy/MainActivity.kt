package dev.siliconoptimizer.buddy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
import dev.siliconoptimizer.buddy.pairing.PairingConfirmation
import dev.siliconoptimizer.buddy.pairing.PairingInvite
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        arriving.value = read(intent)
        setContent {
            SiliconBuddyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BuddyApp(arriving = arriving)
                }
            }
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
        val data = intent?.data ?: return null
        // `siliconbuddy://` means three things now: a pairing code, a composer to open,
        // and a conversation to show. All three are requests rather than instructions —
        // anything on the phone can fire one — so a composer link fills the box in and
        // never sends.
        return when (
            val link = BuddyLink.parse(
                data.scheme, data.host ?: data.path?.trim('/'),
                data.getQueryParameter("text"), data.getQueryParameter("id"),
            )
        ) {
            is BuddyLink.Compose -> LinkArrival.Compose(link.text)
            is BuddyLink.Conversation -> LinkArrival.OpenConversation(link.id)
            BuddyLink.Pair, null -> try {
                LinkArrival.Invite(PairingInvite.parse(data))
            } catch (error: PairingInvite.ParseError) {
                LinkArrival.Refused(error.message ?: "That isn't a Silicon Buddy code.")
            }
        }
    }

    companion object {
        /** Which screen a notification wants open. */
        const val EXTRA_OPEN = "dev.siliconoptimizer.buddy.OPEN"
        const val OPEN_QUEUE = "queue"
    }
}

/** What arrived on a `siliconbuddy://` link. */
sealed interface LinkArrival {
    data class Invite(val invite: PairingInvite) : LinkArrival
    data class Refused(val reason: String) : LinkArrival

    /** Open the composer, with this typed into it. Never sent. */
    data class Compose(val text: String?) : LinkArrival
    data class OpenConversation(val id: String) : LinkArrival

    /** Show the render queue: where a job that just finished can be looked at. */
    data object OpenQueue : LinkArrival
}

private enum class Destination(val label: String) {
    Dashboard("Mac"), Create("Create"), Machines("Machines"), Models("Models"),
    Chat("Chat"), Settings("Settings"),
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

    // Saved rather than merely remembered. Two things take this activity away and bring
    // it back: a configuration change the manifest does not absorb — font scale is the
    // common one — and One UI deciding a backgrounded app has had long enough. Either
    // way `remember` alone puts the person back on the dashboard, having lost the
    // conversation they were reading.
    var destination by rememberSaveable(stateSaver = Destination.Saver) {
        mutableStateOf(Destination.Dashboard)
    }
    var pairing by remember { mutableStateOf(false) }
    var refusedLink by remember { mutableStateOf<String?>(null) }
    var openConversation by rememberSaveable { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) { app.refreshReachability() }

    // A link is a request, not an instruction: anything on the device can open a URL in
    // this app. It only ever gets as far as asking — or, when the host is not on the
    // tailnet, explaining why not.
    LaunchedEffect(arriving.value) {
        when (val arrival = arriving.value) {
            is LinkArrival.Invite -> app.pendingInvite = arrival.invite
            is LinkArrival.Refused -> refusedLink = arrival.reason
            is LinkArrival.Compose -> {
                destination = Destination.Chat
                if (chat.current == null) chat.newConversation()
                openConversation = chat.current?.id
                arrival.text?.let { chat.draft = it }
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

    // Answer checks arrive after the reply they are about, on the shared event stream,
    // so they are applied wherever the chat screen happens to be.
    LaunchedEffect(events.verdicts.size, openConversation) {
        val id = chat.current?.id ?: return@LaunchedEffect
        (events.verdicts[id] ?: events.verdicts[""])?.let { chat.apply(it) }
    }

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
        media.refresh(app.transport, MediaNotifier(context))
        machines.reset()
        machines.refresh(app.transport)
        chat.loadConversations(app.transport)
        events.start(app.transport)
    }

    // Renders finish minutes after they were asked for, and on whatever screen happens
    // to be in front. The queue hears about it here, once, and says so.
    val notifier = remember(context) { MediaNotifier(context) }
    LaunchedEffect(Unit) {
        events.jobEvents.collect { media.apply(it, notifier) }
    }

    // What the Mac pushes, when it can push.
    LaunchedEffect(events.status) {
        events.status?.let { dashboard.apply(it) }
    }
    LaunchedEffect(events.mustPoll) {
        dashboard.pollsStatus = events.mustPoll
    }

    val windowWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val wide = windowWidth >= 600

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (destination) {
                            Destination.Dashboard -> "Silicon Buddy"
                            Destination.Create -> "Create"
                            Destination.Machines -> "Machines"
                            Destination.Models -> "Models"
                            Destination.Chat -> chat.current?.title ?: "Chat"
                            Destination.Settings -> "Settings"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    when (destination) {
                        Destination.Dashboard -> IconButton(onClick = {
                            app.refreshReachability()
                            dashboard.refresh(app.transport, app)
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                        Destination.Chat -> IconButton(onClick = {
                            chat.newConversation()
                            openConversation = chat.current?.id
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = "New conversation")
                        }
                        Destination.Machines -> IconButton(onClick = {
                            machines.refresh(app.transport)
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                        Destination.Create -> IconButton(onClick = {
                            media.refresh(app.transport)
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                        else -> Unit
                    }
                },
            )
        },
        bottomBar = {
            if (!wide) {
                NavigationBar {
                    Destination.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = destination == entry,
                            onClick = { destination = entry },
                            icon = { Icon(iconFor(entry), contentDescription = null) },
                            // Six destinations on a phone: one line each, or "Machines"
                            // breaks in half.
                            label = {
                                Text(
                                    entry.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
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
                    Destination.entries.forEach { entry ->
                        NavigationRailItem(
                            selected = destination == entry,
                            onClick = { destination = entry },
                            icon = { Icon(iconFor(entry), contentDescription = null) },
                            label = { Text(entry.label) },
                        )
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
                Destination.Create -> CreateScreen(app, media, Modifier.fillMaxSize())
                Destination.Machines -> MachinesScreen(app, machines, Modifier.fillMaxSize())
                Destination.Models -> ModelsScreen(app, models, events, Modifier.fillMaxSize())
                Destination.Chat -> {
                    if (!wide && openConversation == null && chat.conversations.isNotEmpty()) {
                        ConversationList(chat, app) { openConversation = it }
                    } else {
                        // Keyed on readiness as well as on the id: a restored id
                        // arrives before the Mac has said whether it keeps conversations
                        // at all, and opening then yields an empty transcript with the
                        // right title. Re-runs once the answer is in.
                        LaunchedEffect(openConversation, chat.askedAboutConversations) {
                            openConversation?.let { chat.open(it, app.transport) }
                        }
                        ChatScreen(app, chat, Modifier.fillMaxSize())
                    }
                }
                Destination.Settings -> SettingsScreen(app, chat, events) { pairing = true }
            }
        }
    }

    if (pairing) {
        ModalBottomSheet(onDismissRequest = { pairing = false }, sheetState = sheetState) {
            PairingScreen(app = app, onDone = { pairing = false })
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

    // A code that arrived from a QR or a link: named, and agreed to, before anything
    // is dialled — and twice over when it would replace the Mac already paired.
    app.pendingInvite?.let { invite ->
        PairingConfirmation(
            app = app,
            invite = invite,
            onDismiss = { app.pendingInvite = null; arriving.value = null },
            onNeedsAdvanced = {
                app.pendingInvite = null
                pairing = true
            },
        )
    }
}

private fun iconFor(destination: Destination) = when (destination) {
    Destination.Dashboard -> Icons.Filled.Speed
    Destination.Create -> Icons.Filled.AutoAwesome
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
