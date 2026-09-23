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
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.launch

/** The ways in, in the order most people need them. */
enum class PairingMode(val label: String) {
    Scan("Scan"),
    Code("Enter code"),
    /** The Mac's own control token, which the Mac takes only from itself. */
    Developer("Developer"),
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

    var mode by remember { mutableStateOf(startOn) }
    var host by remember { mutableStateOf(app.config?.host ?: "") }
    var code by remember { mutableStateOf("") }
    var codePort by remember { mutableStateOf(PairingInvite.DEFAULT_PORT.toString()) }
    // The Developer form's own. Loopback takes a fresh port every launch, so there is no
    // default worth offering.
    var developerHost by remember { mutableStateOf("") }
    var developerPort by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var message by remember { mutableStateOf(notice) }
    var working by remember { mutableStateOf(false) }
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
        val portNumber = developerPort.toIntOrNull()
        if (portNumber == null || portNumber !in 1..65535) {
            message = "That port isn't a number between 1 and 65535."
            return
        }
        if (!TailnetHost.isAllowed(developerHost.trim())) {
            message = TailnetHost.EXPLANATION
            return
        }
        working = true
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
                    working = false
                    onDone()
                }
                else -> {
                    working = false
                    message = result.detail
                }
            }
        }
    }

    /**
     * A scanned code is a claim about which machine to trust, made by whoever printed
     * the QR. It goes to the confirmation dialog, which names the host and — when a Mac
     * is already paired — asks a second time before replacing it.
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
     * A typed code goes where a scanned one does — [AppState.pair], and from there
     * `POST /buddy/pair` — held to the same host rule. It skips the scan's confirmation,
     * which is there because whoever printed a QR chose its host; here the person
     * holding the phone typed it. Replacing a paired Mac still asks first.
     */
    fun pairTyped() {
        val invite = runCatching { PairingInvite.typed(host, code, codePort) }.getOrElse {
            message = it.message
            return
        }
        message = null
        working = true
        scope.launch {
            try {
                app.pair(invite)
                working = false
                onDone()
            } catch (error: TransportError) {
                working = false
                message = if (error.isMissingRoute) MAC_TOO_OLD_FOR_CODES else error.message
            }
        }
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
            PairingMode.entries.forEachIndexed { index, each ->
                SegmentedButton(
                    selected = mode == each,
                    onClick = { mode = each },
                    shape = SegmentedButtonDefaults.itemShape(index, PairingMode.entries.size),
                    // Three labels share a phone's width; the selected one's tick would
                    // cut "Enter code" short before the fill says which is chosen anyway.
                    icon = {},
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
                        "A copied pairing link can go in the address field instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { typed -> code = typed.filter { it.isDigit() || it == ' ' } },
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
                        // A pasted link fills all three fields, so what will be dialled is
                        // on screen before anything is.
                        if (PairingInvite.isLink(typed)) {
                            runCatching { PairingInvite.parse(typed) }
                                .onSuccess {
                                    host = it.host
                                    codePort = it.port.toString()
                                    code = "${it.code.take(3)} ${it.code.takeLast(3)}"
                                    message = null
                                }
                                .onFailure { message = it.message }
                        } else {
                            host = typed
                        }
                    },
                    label = { Text("Mac's address") },
                    placeholder = { Text("100.x.y.z") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = codePort,
                    onValueChange = { codePort = it.filter(Char::isDigit) },
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
                    working = working,
                    onClick = {
                        if (app.isPaired) replacing = host.trim() to ::pairTyped else pairTyped()
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
                    "This is the Mac's own control token, from ~/Library/Application Support/" +
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
                    onValueChange = { developerPort = it.filter(Char::isDigit) },
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
                    working = working,
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

        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
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
