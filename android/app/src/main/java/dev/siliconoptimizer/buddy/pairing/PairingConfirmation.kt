package dev.siliconoptimizer.buddy.pairing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.siliconoptimizer.buddy.AppState
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.launch

/**
 * The dialog between scanning a code and holding a token.
 *
 * A QR is a picture of a URL: whoever printed it chose the host. A link can be opened
 * by a web page, a message, or anything else on the device. So a scan never pairs by
 * itself — it names the machine it wants to pair with and waits. And when a Mac is
 * already paired, replacing it takes a second, separate yes, because the first one was
 * about a new Mac, not about losing the old one.
 */
@Composable
fun PairingConfirmation(
    app: AppState,
    invite: PairingInvite,
    onDismiss: () -> Unit,
    onNeedsAdvanced: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var confirmingReplacement by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    fun pair() {
        working = true
        failure = null
        scope.launch {
            try {
                app.pair(invite)
                working = false
                onDismiss()
            } catch (error: TransportError) {
                working = false
                if (error.isMissingRoute) {
                    onNeedsAdvanced()
                } else {
                    failure = error.message
                }
            }
        }
    }

    val displayCode = if (invite.code.length == 6) {
        "${invite.code.take(3)} ${invite.code.takeLast(3)}"
    } else {
        invite.code
    }

    if (confirmingReplacement) {
        AlertDialog(
            onDismissRequest = { confirmingReplacement = false },
            title = { Text("Replace ${app.macDisplayName}?") },
            text = {
                Text(
                    "This device will stop talking to ${app.macDisplayName} and its token " +
                        "will be deleted from this device.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmingReplacement = false; pair() }) {
                    Text("Replace with ${invite.host}")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReplacement = false }) {
                    Text("Keep ${app.macDisplayName}")
                }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Pair with this Mac?") },
        text = {
            Column {
                Text("${invite.host}:${invite.port}", style = MaterialTheme.typography.titleMedium)
                Text("Code $displayCode", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Silicon Buddy will ask that address for a token of its own and keep it " +
                        "on this device. Only pair with a code you can see on your own Mac's " +
                        "screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (app.isPaired) {
                    Text(
                        "This replaces ${app.macDisplayName} " +
                            "(${app.config?.displayAddress ?: ""}).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                failure?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (working) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (app.isPaired) confirmingReplacement = true else pair() },
                enabled = !working,
            ) {
                Text(if (app.isPaired) "Replace this Mac…" else "Pair")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) { Text("Not now") }
        },
    )
}
