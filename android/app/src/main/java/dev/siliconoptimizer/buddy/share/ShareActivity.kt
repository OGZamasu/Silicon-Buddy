package dev.siliconoptimizer.buddy.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.siliconoptimizer.buddy.ui.SiliconBuddyTheme

/**
 * The share target: text, a link or a picture from any app, asked about in place.
 *
 * A separate activity rather than a route inside the app, because that is what a share
 * target is on Android — and because it must not disturb whatever conversation the app
 * has open behind it.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val arrived = read(intent)
        setContent {
            SiliconBuddyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val model: ShareViewModel = viewModel()
                    ShareComposer(model, arrived) { finish() }
                }
            }
        }
    }

    /** What the sending app attached, as URIs and strings — read, never followed. */
    private fun read(intent: Intent?): SharedIntent {
        if (intent == null) return SharedIntent()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val streams: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(stream(intent, Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> streams(intent)
            else -> emptyList()
        }
        return SharedIntent(text = text, subject = subject, streams = streams)
    }

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            intent.getParcelableExtra(key)
        }

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
}

/** What arrived on the intent, before anything has been decided about it. */
data class SharedIntent(
    val text: String? = null,
    val subject: String? = null,
    val streams: List<Uri> = emptyList(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareComposer(model: ShareViewModel, arrived: SharedIntent, close: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { model.start(arrived) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ask your Mac") },
                navigationIcon = { TextButton(onClick = close) { Text("Cancel") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!model.isPaired) {
                Text(
                    "Silicon Buddy isn't paired with a Mac yet. Open the app and pair first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = model.prompt,
                onValueChange = { model.prompt = it },
                label = { Text("What should the model do with this?") },
                enabled = !model.isBusy,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                model.draft.summary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            model.draft.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (model.draft.quoted.isNotEmpty()) {
                Text(
                    model.draft.quoted,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 8,
                )
            }

            when (val phase = model.phase) {
                is SharePhase.Reading -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text("Reading what you shared…")
                }

                is SharePhase.Sending -> Text(
                    model.streamed.ifEmpty { "Thinking…" },
                    style = MaterialTheme.typography.bodyMedium,
                )

                is SharePhase.Answered -> Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(phase.text, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (model.storedOnMac) {
                            "Saved to a conversation on your Mac."
                        } else {
                            "This Mac doesn't store conversations, so this stays here."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                is SharePhase.Failed -> Text(
                    phase.problem,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                SharePhase.Ready -> Unit
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.phase is SharePhase.Answered) {
                    Button(onClick = close) { Text("Done") }
                } else {
                    Button(
                        onClick = { model.send() },
                        enabled = model.canSend,
                    ) { Text("Send") }
                }
            }
        }
    }
}
