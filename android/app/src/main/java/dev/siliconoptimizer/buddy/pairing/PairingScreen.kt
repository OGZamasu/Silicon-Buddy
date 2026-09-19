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

/**
 * Two ways to reach a Mac: scan the code it shows, or type the address in.
 *
 * The advanced form is not a debugging leftover — it is the path that works today,
 * before the Mac has `POST /buddy/pair`, and it stays afterwards for anyone whose Mac
 * is on a screen they cannot point a camera at.
 */
@Composable
fun PairingScreen(
    app: AppState,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var scanning by remember { mutableStateOf(true) }
    var host by remember { mutableStateOf(app.config?.host ?: "") }
    var port by remember { mutableStateOf((app.config?.port ?: 8788).toString()) }
    var token by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var confirmingReplacement by remember { mutableStateOf(false) }

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
            scanning = false
            message = "Camera denied. Enter the address by hand instead."
        }
    }

    LaunchedEffect(scanning) {
        if (scanning && !cameraGranted) cameraPermission.launch(Manifest.permission.CAMERA)
    }

    fun connect() {
        val portNumber = port.toIntOrNull()
        if (portNumber == null || portNumber !in 1..65535) {
            message = "That port isn't a number between 1 and 65535."
            return
        }
        if (!TailnetHost.isAllowed(host.trim())) {
            message = TailnetHost.EXPLANATION
            return
        }
        working = true
        scope.launch {
            val candidate = ServerConfig(host.trim(), portNumber, token.trim())
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
        port = invite.port.toString()
        app.pendingInvite = invite
        onDone()
    }

    if (confirmingReplacement) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmingReplacement = false },
            title = { Text("Replace ${app.macDisplayName}?") },
            text = {
                Text(
                    "This device will stop talking to ${app.macDisplayName} and its token " +
                        "will be deleted from this device.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmingReplacement = false; connect() }) {
                    Text("Replace with $host")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReplacement = false }) {
                    Text("Keep ${app.macDisplayName}")
                }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = scanning,
                onClick = { scanning = true },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Scan") }
            SegmentedButton(
                selected = !scanning,
                onClick = { scanning = false },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Advanced") }
        }

        if (scanning) {
            if (cameraGranted) {
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
                        "Allow the camera, or use Advanced to type the address in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { scanning = false },
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text("Enter it by hand") }
                }
            }
        } else {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host") },
                    placeholder = { Text("100.x.y.z or 10.0.2.2") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "The Mac writes these to ~/Library/Application Support/SiliconOptimizer/" +
                        "control.json when it starts. From the Android emulator the Mac is " +
                        "10.0.2.2; on a phone it is the Mac's tailnet address (100.x.y.z). " +
                        "Nothing else is accepted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { if (app.isPaired) confirmingReplacement = true else connect() },
                        enabled = host.isNotBlank() && token.isNotBlank() && !working,
                    ) { Text(if (app.isPaired) "Replace this Mac…" else "Connect") }
                    if (working) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    if (app.isPaired) {
                        OutlinedButton(onClick = { app.forget() }) { Text("Forget this Mac") }
                    }
                }
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
