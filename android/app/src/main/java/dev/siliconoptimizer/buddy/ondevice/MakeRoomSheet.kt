package dev.siliconoptimizer.buddy.ondevice

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.siliconoptimizer.buddy.transport.PhoneModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What a shorter context has actually saved on this phone: the difference between what it
 * measured at the two contexts, or 0 until it has run both.
 */
private fun savingFor(
    engine: OnDeviceEngine,
    installed: InstalledPhoneModel?,
    model: dev.siliconoptimizer.buddy.transport.PhoneModel,
): Long {
    val entry = installed ?: return 0
    val full = engine.measuredAt(entry, model.recommended.contextLength)
    val short = engine.measuredAt(entry, ResourceGuard.SMALL_CONTEXT)
    return if (full > 0 && short > 0) (full - short).coerceAtLeast(0) else 0
}

/**
 * "Make room": what this app can free, what only the owner can, and the number moving.
 *
 * [onTryAgain] is offered the moment the floor is met; [onTryAnyway] runs it in the
 * warning band and is never offered below the floor. [installed] is the model as this
 * phone has it, when it has it — the sheet is also reachable before a download, from the
 * Settings row, where there is nothing to unload and no answer to retry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MakeRoomSheet(
    model: PhoneModel,
    installed: InstalledPhoneModel?,
    onDismiss: () -> Unit,
    onTryAgain: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val engine = remember { OnDeviceEngine.get(context) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var free by remember { mutableStateOf(engine.memory().first) }
    val started = remember { free }
    var recovered by remember { mutableStateOf<String?>(null) }
    var freeing by remember { mutableStateOf(false) }
    var toolFailed by remember { mutableStateOf(false) }
    var shortContext by remember {
        mutableStateOf(installed?.let { engine.chosenContext(it) <= ResourceGuard.SMALL_CONTEXT } ?: false)
    }

    // Live, because the owner is closing things in another app while this is open and a
    // number that only changes when the sheet is reopened is no help at all.
    LaunchedEffect(Unit) {
        while (true) {
            free = engine.memory().first
            delay(1_000)
        }
    }

    val tokens = if (shortContext) ResourceGuard.SMALL_CONTEXT else model.recommended.contextLength
    val measured = installed?.let { engine.measuredResident(it) }
    val enough = MakeRoom.isEnough(model, free, tokens, measured)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Make room for ${model.label}", style = MaterialTheme.typography.titleMedium)
            Text(
                MakeRoom.change(started, free),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { contentDescription = "Memory: " + MakeRoom.change(started, free) },
            )
            Text(
                MakeRoom.needs(model, tokens, measured),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                MakeRoom.CANNOT_CLOSE_OTHERS,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            recovered?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            Button(
                onClick = {
                    freeing = true
                    scope.launch {
                        val gave = engine.makeRoom()
                        free = gave.after
                        recovered = MakeRoom.recovered(gave.before, gave.after, gave.hadModel)
                            ?: "This app was holding nothing to give back."
                        freeing = false
                    }
                },
                enabled = !freeing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (freeing) "Letting go…" else "Free what this app is holding") }

            val tool = remember { MakeRoom.tool { MakeRoom.canOpen(context, it) } }
            if (tool != null) {
                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(MakeRoom.intent(tool))
                        } catch (missing: ActivityNotFoundException) {
                            // It resolved a moment ago and does not now — a disabled
                            // package, a work profile. Say so rather than fall over.
                            toolFailed = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tool.label) }
            }
            if (tool == null || toolFailed) {
                Text(
                    "This phone has no memory screen this app can open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                MakeRoom.HOW_TO_CLOSE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // The other way to make it fit: ask for less. Only where the choice can be
            // remembered — before the model is on the phone there is nothing to remember
            // it against, and a switch that forgets is worse than no switch.
            MakeRoom.shorterContext(model, savingFor(engine, installed, model))
                ?.takeIf { installed != null }
                ?.let { line ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            shortContext = !shortContext
                            installed?.let {
                                engine.chooseContext(
                                    it,
                                    if (shortContext) ResourceGuard.SMALL_CONTEXT else model.recommended.contextLength,
                                )
                            }
                        },
                    ) { Text(if (shortContext) "Undo" else "Use it") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onTryAgain?.let {
                    Button(onClick = { it(); onDismiss() }, enabled = enough) {
                        Text(if (enough) "Try again" else "Not enough yet")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
