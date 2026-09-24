package dev.siliconoptimizer.buddy.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.siliconoptimizer.buddy.AppState
import dev.siliconoptimizer.buddy.transport.ConnectivityProbe
import dev.siliconoptimizer.buddy.transport.ControlClient
import dev.siliconoptimizer.buddy.transport.Reachability
import dev.siliconoptimizer.buddy.transport.ServerConfig
import dev.siliconoptimizer.buddy.transport.TailnetHost
import kotlinx.coroutines.launch

/** The ways in, in the order most people need them. */
enum class PairingMode(val label: String) {
    Scan("Scan"),
    Code("Enter code"),
    /** The Mac's own control token, which the Mac takes only from itself. */
    Developer("Developer"),
}

/**
 * Where the Developer form may send the Mac's own control token.
 *
 * That token is the Mac's, not a device's: whatever the control API can do, it can. The Mac
 * accepts it on its local listener and nowhere else, so the only place it is any use is this
 * machine — 10.0.2.2 from the emulator, or loopback — and typed with any other address it
 * would go to whoever answers there. And a build that does not dial this machine at all
 * (see [TailnetHost.allowsLocal]) has no use for the form, and does not offer it.
 */
object DeveloperConnection {
    const val LOCAL_ONLY =
        "The control token only works on the Mac's own listener: 10.0.2.2 from the emulator, " +
            "or 127.0.0.1. To pair a phone, use Scan or Enter code."

    /** Whether the form is offered at all. */
    val offered: Boolean get() = TailnetHost.allowsLocal

    /** Why the form's host and port will not do, or null when they will. */
    fun problem(host: String, port: String, local: Boolean = TailnetHost.allowsLocal): String? {
        val portNumber = port.toIntOrNull()
        if (portNumber == null || portNumber !in 1..65535) {
            return "That port isn't a number between 1 and 65535."
        }
        if (!local || !TailnetHost.isLocal(host)) return LOCAL_ONLY
        return null
    }

    const val CODE_WANTS_TAILNET =
        "Use the Mac's Tailscale address shown beside the code. For a local emulator " +
            "connection, use Developer with the port and full token from control.json."

    /**
     * What Enter code says under an address on this machine: a code is spent at the Mac's
     * tailnet listener, and the local one takes the control token, on this form. Null for
     * any other address, and in a build that does not offer this form.
     */
    fun codeAddressHint(address: String, local: Boolean = TailnetHost.allowsLocal): String? {
        if (!local) return null
        val host = runCatching { PairingInvite.typed(address, "000000").host }.getOrNull()
        return if (host != null && TailnetHost.isLocal(host)) CODE_WANTS_TAILNET else null
    }
}

/** What a Mac without `POST /buddy/pair` gets told, from either way of spending a code. */
const val MAC_TOO_OLD_FOR_CODES =
    "That Mac is too old for pairing codes. Update Silicon Optimizer on it, then pair again."

/**
 * Three ways to reach a Mac: scan the code it shows, type that same code in, or — from
 * the emulator on the Mac itself — use the Mac's own control token.
 *
 * Scan and Enter code both end at `POST /buddy/pair`, and they are how a phone gets in.
 * The token in the Mac's control.json is accepted on the Mac's loopback listener and
 * nowhere else, so over the tailnet it is refused whatever a phone does with it.
 * Developer stays for the emulator, which reaches that listener as 10.0.2.2, and says so.
 */
@Composable
fun PairingScreen(
    app: AppState,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    startOn: PairingMode = PairingMode.Scan,
    notice: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The ways offered here: all three, or — in a build that does not dial this machine —
    // the two a phone uses.
    val modes = PairingMode.entries.filter { it != PairingMode.Developer || DeveloperConnection.offered }
    var mode by remember { mutableStateOf(startOn.takeIf { it in modes } ?: PairingMode.Code) }
    var host by remember { mutableStateOf(app.config?.host ?: "") }
    var code by remember { mutableStateOf("") }
    var codePort by remember { mutableStateOf(PairingInvite.DEFAULT_PORT.toString()) }
    // The Developer form's own. Loopback takes a fresh port every launch, so there is no
    // default worth offering.
    var developerHost by remember { mutableStateOf("") }
    var developerPort by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var message by remember { mutableStateOf(notice) }
    // The Developer form's own probe. A code is spent by AppState, so that closing this
    // sheet mid-request never cuts off one the Mac may already have answered.
    var connecting by remember { mutableStateOf(false) }
    // The code this form handed to AppState, as its link, so another being spent — from a
    // confirmation closed while its Mac thought — is not shown as this form's. Saved, so a
    // sheet recreated with the activity still knows its own.
    var spentHere by rememberSaveable { mutableStateOf<String?>(null) }
    val attempt = app.pairing.state
    // Anything in flight, so nothing else may start: it would land over the first unasked.
    val working = connecting || attempt is PairingExchange.State.Working
    val spending = (attempt as? PairingExchange.State.Working)?.invite
    val spinning = connecting || (spending != null && spending.toUriString() == spentHere)
    val waitingFor = spending?.takeIf { it.toUriString() != spentHere }
    val failure = (attempt as? PairingExchange.State.Failed)?.message

    // Paired while this sheet is up — by its own form, or by a link confirmed over it — and
    // it has done its job. Forgetting the Mac bumps the generation too, and leaves it open.
    val openedAt = remember { app.connectionGeneration }
    LaunchedEffect(app.connectionGeneration) {
        if (app.connectionGeneration != openedAt && app.isPaired) onDone()
    }
    // What "Replace" goes on to do once it is agreed to — pair with a code, or connect —
    // and the address it would replace the paired Mac with.
    var replacing by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
        if (!granted) {
            mode = PairingMode.Code
            message = "Camera denied. Type the code your Mac shows instead."
        }
    }

    LaunchedEffect(mode) {
        if (mode == PairingMode.Scan && !cameraGranted) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    fun connect() {
        if (PairingInvite.looksLikePairingCode(token)) {
            message = "That is a pairing code. Choose Enter code and use the Mac's " +
                "Tailscale address shown beside it. Developer needs the full control " +
                "token and current port from control.json."
            return
        }
        DeveloperConnection.problem(developerHost.trim(), developerPort)?.let {
            message = it
            return
        }
        val portNumber = developerPort.toInt()
        connecting = true
        scope.launch {
            val candidate = ServerConfig(developerHost.trim(), portNumber, token.trim())
            // Prove it works before storing it: a saved address that does not answer is
            // worse than no address at all.
            when (val result = ConnectivityProbe(ControlClient(candidate)).check()) {
                is Reachability.Ready -> {
                    app.connect(candidate)
                    runCatching { ControlClient(candidate).node().name }
                        .getOrNull()?.let { app.noteMacName(it) }
                    app.refreshReachability()
                    connecting = false
                    onDone()
                }
                else -> {
                    connecting = false
                    message = result.detail
                }
            }
        }
    }

    /**
     * A scanned code is a claim about which machine to trust, made by whoever printed
     * the QR — and a pasted link is the same claim, made by whoever sent it. Both go to
     * the confirmation dialog, which names the host, says to pair only with a code on
     * your own Mac's screen, and — when a Mac is already paired — asks a second time
     * before replacing it.
     */
    fun usePairingCode(text: String) {
        val invite = runCatching { PairingInvite.parse(text) }.getOrElse {
            message = it.message
            return
        }
        host = invite.host
        codePort = invite.port.toString()
        app.pendingInvite = invite
        onDone()
    }

    /**
     * A typed code goes where a scanned one does — [AppState.startPairing], and from there
     * `POST /buddy/pair` — held to the same host rule. It skips the scan's confirmation,
     * which is there because whoever printed a QR chose its host; here the person
     * holding the phone typed it. Replacing a paired Mac still asks first. A link left
     * in the address field is the exception, and goes to that confirmation instead.
     */
    fun pairTyped() {
        if (PairingInvite.isLink(host)) {
            usePairingCode(host)
            return
        }
        val invite = runCatching { PairingInvite.typed(host, code, codePort) }.getOrElse {
            message = it.message
            return
        }
        message = null
        if (app.startPairing(invite)) spentHere = invite.toUriString()
    }

    replacing?.let { (address, proceed) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { replacing = null },
            title = { Text("Replace ${app.macDisplayName}?") },
            text = {
                Text(
                    "This device will stop talking to ${app.macDisplayName} and its token " +
                        "will be deleted from this device.",
                )
            },
            confirmButton = {
                TextButton(onClick = { replacing = null; proceed() }) {
                    Text("Replace with $address")
                }
            },
            dismissButton = {
                TextButton(onClick = { replacing = null }) {
                    Text("Keep ${app.macDisplayName}")
                }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, each ->
                SegmentedButton(
                    selected = mode == each,
                    onClick = { mode = each },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    // Three labels share a phone's width, and the selected one's tick
                    // would cut "Enter code" short. So the choice is marked by a fill
                    // strong enough to see — the default one is a near-white tint.
                    icon = {},
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary,
                        activeContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text(each.label) }
            }
        }

        when (mode) {
            PairingMode.Scan -> if (cameraGranted) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp)) {
                    QrScanner(onCode = { usePairingCode(it) }, modifier = Modifier.fillMaxSize())
                    Text(
                        "Point at the code in Silicon Optimizer, Settings, Silicon Buddy",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                    )
                }
            } else {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("The camera is off", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Allow the camera, or type the code your Mac shows under the QR.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { mode = PairingMode.Code },
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text("Enter the code by hand") }
                }
            }

            PairingMode.Code -> Column(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "In Silicon Optimizer, open Settings, Silicon Buddy, and choose Pair a " +
                        "device. Type the six-digit code it shows and the address beside it. " +
                        "A pairing link pasted into the address field is shown to you to " +
                        "confirm, as a scanned code is.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { typed ->
                        code = PairingInvite.asciiDigits(typed, keepSpaces = true)
                    },
                    label = { Text("Pairing code") },
                    placeholder = { Text("123 456") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { typed ->
                        host = typed
                        // A pasted link is not something the person typed: whoever made
                        // it chose its host. It never reaches this form's Pair.
                        if (PairingInvite.isLink(typed)) usePairingCode(typed)
                    },
                    label = { Text("Mac's address") },
                    placeholder = { Text("100.x.y.z") },
                    supportingText = DeveloperConnection.codeAddressHint(host)?.let { hint ->
                        { Text(hint) }
                    },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = codePort,
                    onValueChange = { codePort = PairingInvite.asciiDigits(it) },
                    label = { Text("Port") },
                    supportingText = {
                        Text("${PairingInvite.DEFAULT_PORT} unless your Mac says otherwise")
                    },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Actions(
                    app = app,
                    label = if (app.isPaired) "Replace this Mac…" else "Pair",
                    enabled = host.isNotBlank() && code.isNotBlank() && !working,
                    working = spinning,
                    onClick = {
                        if (app.isPaired && !PairingInvite.isLink(host)) {
                            replacing = host.trim() to ::pairTyped
                        } else {
                            pairTyped()
                        }
                    },
                )
            }

            PairingMode.Developer -> Column(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Local emulator and development only",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Use the full control token, not the six-digit pairing code, from ~/Library/Application Support/" +
                        "SiliconOptimizer/control.json. The Mac accepts it only on its local " +
                        "listener, so it works from the Android emulator on that Mac (host " +
                        "10.0.2.2, the port in control.json) and never from a phone over " +
                        "Tailscale. To pair a phone, use Scan or Enter code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = developerHost,
                    onValueChange = { developerHost = it },
                    label = { Text("Host") },
                    placeholder = { Text("10.0.2.2") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = developerPort,
                    onValueChange = { developerPort = PairingInvite.asciiDigits(it) },
                    label = { Text("Port") },
                    placeholder = { Text("From control.json") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Control token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Actions(
                    app = app,
                    label = if (app.isPaired) "Replace this Mac…" else "Connect",
                    enabled = developerHost.isNotBlank() && token.isNotBlank() && !working,
                    working = spinning,
                    onClick = {
                        if (app.isPaired) {
                            replacing = developerHost.trim() to ::connect
                        } else {
                            connect()
                        }
                    },
                )
            }
        }

        (message ?: failure)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        } ?: waitingFor?.let {
            Text(
                "Waiting for the other pairing (${it.displayAddress}) to finish…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        TextButton(onClick = onDone, modifier = Modifier.align(Alignment.End)) { Text("Close") }
    }
}

/** The form's own button, its spinner, and — when there is a Mac to lose — forgetting it. */
@Composable
private fun Actions(
    app: AppState,
    label: String,
    enabled: Boolean,
    working: Boolean,
    onClick: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onClick, enabled = enabled) { Text(label) }
        if (working) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
        if (app.isPaired) {
            OutlinedButton(onClick = { app.forget() }) { Text("Forget this Mac") }
        }
    }
}
