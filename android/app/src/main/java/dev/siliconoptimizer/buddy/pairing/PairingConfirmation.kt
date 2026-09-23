package dev.siliconoptimizer.buddy.pairing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.siliconoptimizer.buddy.AppState

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
    onMacTooOld: () -> Unit,
) {
    var confirmingReplacement by remember { mutableStateOf(false) }
    // The exchange is AppState's, so a dialog recreated with the activity, mid-request,
    // picks up where it was. This only shows how it is going.
    val phase = app.pairing.phaseOf(invite)
    val spending = phase == PairingExchange.Phase.Spending
    val failed = phase as? PairingExchange.Phase.Failed

    LaunchedEffect(failed) {
        if (failed?.macTooOld == true) {
            app.pairing.acknowledge()
            onMacTooOld()
        }
    }

    // Paired, the invite is gone from AppState and this dialog with it.
    fun pair() {
        app.startPairing(invite)
    }

    fun dismiss() {
        // A refusal this dialog was showing has been seen — its own, or the other code's.
        if (phase is PairingExchange.Phase.Failed || phase is PairingExchange.Phase.OtherFailed) {
            app.pairing.acknowledge()
        }
        onDismiss()
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
        // Not while this code is being spent: it cannot be taken back, and "Not now" would
        // say it had been. While another one is, saying no to this one is fine.
        onDismissRequest = { if (!spending) dismiss() },
        title = { Text("Pair with this Mac?") },
        text = {
            Column {
                Text(invite.displayAddress, style = MaterialTheme.typography.titleMedium)
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
                when (phase) {
                    is PairingExchange.Phase.Failed -> Text(
                        phase.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    is PairingExchange.Phase.OtherFailed -> Text(
                        "The other pairing (${phase.other.displayAddress}) didn't finish: ${phase.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    // Not a spinner: nothing is happening to *this* code yet.
                    is PairingExchange.Phase.Waiting -> Text(
                        "Waiting for the other pairing (${phase.other.displayAddress}) to finish…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    PairingExchange.Phase.Spending ->
                        CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                    PairingExchange.Phase.Ready -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (app.isPaired) confirmingReplacement = true else pair() },
                // One code at a time: a second one would land over the first without asking.
                enabled = !app.pairing.isWorking,
            ) {
                Text(if (app.isPaired) "Replace this Mac…" else "Pair")
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss, enabled = !spending) { Text("Not now") }
        },
    )
}
