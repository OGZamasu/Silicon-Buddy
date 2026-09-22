package dev.siliconoptimizer.buddy.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import dev.siliconoptimizer.buddy.camera.CameraModeSheet
import dev.siliconoptimizer.buddy.camera.CameraModeViewModel
import dev.siliconoptimizer.buddy.reach.VoiceState
import dev.siliconoptimizer.buddy.voice.VoiceController
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.siliconoptimizer.buddy.AppState
import dev.siliconoptimizer.buddy.ondevice.InstalledPhoneModel
import dev.siliconoptimizer.buddy.ondevice.MakeRoomSheet
import dev.siliconoptimizer.buddy.ondevice.OnDeviceNotices
import dev.siliconoptimizer.buddy.ui.Format
import dev.siliconoptimizer.buddy.ui.KeepScreenOn
import dev.siliconoptimizer.buddy.ui.NoRecentsScreenshot
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import android.graphics.Bitmap
import android.util.Base64

/** The transcript, the composer, and everything between a question and an answer. */
@Composable
fun ChatScreen(
    app: AppState,
    model: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val expanded = remember { mutableStateOf(setOf<String>()) }
    // A clock that only runs while something is being waited for, so the spinner can
    // say how long it has been rather than sitting at zero.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(model.isSending) {
        while (model.isSending) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    // The phone's own model writes for as long as a minute, and Android dims and sleeps a
    // screen nobody is touching — which slows the answer down and then stops it, because an
    // app that is not in front does not get to keep writing. Only while it is writing.
    KeepScreenOn(model.keepsScreenOn)
    // A conversation the phone answered is on this phone and nowhere else. The Recents
    // thumbnail is a copy of it somewhere the owner did not put it.
    if (model.current?.onDevice == true) NoRecentsScreenshot()

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            attachmentFrom(context, uri)?.let { model.attach(it) }
        }
    }

    // MARK: - Camera mode and push-to-talk

    val voice: VoiceController = androidx.lifecycle.viewmodel.compose.viewModel()
    val camera: CameraModeViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var showCamera by remember { mutableStateOf(false) }
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var hasMicrophone by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCamera = granted
        if (granted) showCamera = true
    }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasMicrophone = granted
        if (granted) voice.permissionGranted() else voice.permissionRefused()
    }

    // A spoken question goes through the ordinary composer, so it lands in the same
    // transcript and the same conversation as a typed one.
    LaunchedEffect(Unit) {
        voice.onAsk = { text ->
            model.draft = text
            model.send(app.transport)
        }
    }
    // The reply is read out only when the person asked out loud and is still waiting:
    // `VoiceSession` decides, not this.
    var wasSending by remember { mutableStateOf(false) }
    LaunchedEffect(model.isSending) {
        if (wasSending && !model.isSending) {
            voice.answered(
                model.current?.messages?.lastOrNull {
                    it.role == ChatMessage.ROLE_ASSISTANT
                }?.content.orEmpty(),
            )
        }
        wasSending = model.isSending
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { voice.interrupt() }
    }

    if (showCamera) {
        CameraModeSheet(
            transport = app.transport,
            model = camera,
            supportsVision = null,
            onDismiss = { showCamera = false; camera.retake() },
        )
    }

    LaunchedEffect(model.current?.messages?.size, model.current?.messages?.lastOrNull()?.content) {
        val count = model.current?.messages?.size ?: 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        val messages = model.current?.messages.orEmpty()
        val onPhone = model.current?.onDevice == true
        if (messages.isEmpty() && onPhone) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Ask this phone", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "The model on this phone answers here, without your Mac. It reads text only, " +
                        "and nothing here leaves the phone unless you send it to your Mac.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    model.storageNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        } else if (messages.isEmpty()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Ask your Mac", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (app.reachability.isReady) {
                        "The model loaded on the Mac answers here. " +
                            "Attach a picture for a vision model."
                    } else {
                        app.reachability.detail
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    model.storageNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        waitingSince = if (message.isStreaming) model.sendingSince else null,
                        now = now,
                        isReasoningExpanded = expanded.value.contains(message.id),
                        onToggleReasoning = {
                            expanded.value = if (expanded.value.contains(message.id)) {
                                expanded.value - message.id
                            } else {
                                expanded.value + message.id
                            }
                        },
                        // Copied and shared with the chip's words, so an answer the phone
                        // wrote does not arrive somewhere else looking like the Mac's.
                        onCopy = { copyToClipboard(context, OnDeviceNotices.labelled(message.content, message.originLabel.takeIf { message.isFromPhone })) },
                        onShare = { share(context, OnDeviceNotices.labelled(message.content, message.originLabel.takeIf { message.isFromPhone })) },
                        // The failed Mac reply carries the same offer as the banner.
                        onAnswerOnPhone = if (model.offer?.failedMessageID == message.id) {
                            { model.answerOnPhone() }
                        } else {
                            null
                        },
                    )
                }
            }
        }

        HorizontalDivider()

        model.offer?.let { offer ->
            PhoneOfferBanner(
                modelLabel = offer.model.label,
                canRetry = offer.question != null && app.transport != null,
                onAnswer = { model.answerOnPhone() },
                onRetry = { model.retryOnMac(app.transport) },
            )
        }
        // "Make room" is reachable from both of the places that say memory is the problem.
        var makingRoom by remember { mutableStateOf<InstalledPhoneModel?>(null) }
        model.memoryWarning?.let { warning ->
            MemoryWarningBanner(
                message = warning.message,
                onMakeRoom = { makingRoom = warning.model },
                onDismiss = { model.dismissMemoryWarning() },
            )
        }
        model.refusal?.let { refusal ->
            RefusalBanner(
                message = refusal.message,
                alternative = refusal.alternative?.label,
                onAlternative = { model.useAlternative() },
                onMakeRoom = refusal.model?.let { { makingRoom = it } },
                onDismiss = { model.dismissRefusal() },
            )
        }
        makingRoom?.let { installed ->
            MakeRoomSheet(
                model = installed.model,
                installed = installed,
                onDismiss = { makingRoom = null },
                onTryAgain = { model.retryOnPhone() }.takeIf { model.refusal != null },
            )
        }
        var confirmingSend by remember { mutableStateOf(false) }
        // The chat's own view of the Mac — the last send, the probe, the event stream — not
        // the probe alone, which can be minutes old.
        if (model.macIsBack && app.isPaired) {
            MacIsBackBanner(
                canSend = model.sendableCount > 0,
                onNewMacConversation = { model.newMacConversation(app.transport) },
                onSendToMac = { confirmingSend = true },
            )
        }
        if (confirmingSend) {
            AlertDialog(
                onDismissRequest = { confirmingSend = false },
                title = { Text("Send to your Mac?") },
                text = { Text(OnDeviceNotices.sendToMacQuestion(model.sendableCount, app.macDisplayName)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmingSend = false
                        model.sendToMac(app.transport)
                    }) { Text("Send ${model.sendableCount}") }
                },
                dismissButton = { TextButton(onClick = { confirmingSend = false }) { Text("Keep it here") } },
            )
        }

        if (voice.state is VoiceState.Listening) {
            voice.partial?.takeIf { it.isNotEmpty() }?.let { heard ->
                Text(
                    heard,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            // Said every time the button is down, not once in Settings: where the
            // recording of a question goes is worth knowing while you are speaking it.
            Text(
                voice.recognitionNote,
                style = MaterialTheme.typography.bodySmall,
                color = if (voice.isOnDevice) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        voice.problem?.let { problem ->
            Text(
                problem,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        if (model.isConversationBusy && !model.isSending) {
            Text(
                "The Mac is still answering this conversation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        if (model.attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                model.attachments.forEach { dataUrl ->
                    AttachmentThumb(dataUrl) { model.attachments.remove(dataUrl) }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(
                onClick = {
                    photoPicker.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
                enabled = model.attachments.size < SendLimits.MAX_ATTACHMENTS && !onPhone,
            ) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Attach a picture")
            }
            IconButton(
                enabled = !onPhone,
                onClick = {
                    if (hasCamera) {
                        showCamera = true
                    } else {
                        cameraPermission.launch(android.Manifest.permission.CAMERA)
                    }
                },
            ) {
                Icon(
                    Icons.Filled.CameraAlt,
                    contentDescription = "Camera mode — point at something and ask about it",
                )
            }
            OutlinedTextField(
                value = model.draft,
                onValueChange = { model.draft = it },
                placeholder = { Text(if (onPhone) "Ask this phone" else "Message") },
                modifier = Modifier.weight(1f),
                maxLines = 6,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
            PushToTalkButton(voice, hasMicrophone) {
                microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO)
            }
            if (model.isSending) {
                IconButton(onClick = { model.cancel() }) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop generating")
                }
            } else {
                FilledIconButton(
                    onClick = { model.send(app.transport) },
                    enabled = (model.draft.isNotBlank() || model.attachments.isNotEmpty()) &&
                        !model.isConversationBusy,
                ) {
                    Icon(
                        Icons.Filled.ArrowUpward,
                        contentDescription = if (model.isConversationBusy) {
                            "The Mac is still answering this conversation"
                        } else {
                            "Send"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentThumb(dataUrl: String, onRemove: () -> Unit) {
    val bitmap = rememberBitmap(dataUrl, maxEdge = 128)
    Box {
        bitmap?.let {
            androidx.compose.foundation.Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Attached picture",
                modifier = Modifier.size(56.dp).background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(8.dp),
                ),
            )
        }
        TextButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .semantics { contentDescription = "Remove this picture" },
        ) { Text("×") }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    waitingSince: Long?,
    now: Long,
    isReasoningExpanded: Boolean,
    onToggleReasoning: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onAnswerOnPhone: (() -> Unit)? = null,
) {
    val isUser = message.role == ChatMessage.ROLE_USER
    val fromPhone = message.isFromPhone
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = when {
                    isUser -> "You said"
                    fromPhone -> "This phone's model replied"
                    else -> "The model replied"
                }
            },
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (message.images.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                message.images.forEach { dataUrl ->
                    rememberBitmap(dataUrl)?.let {
                        androidx.compose.foundation.Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Attached picture",
                            modifier = Modifier.size(96.dp),
                        )
                    }
                }
            }
        }

        message.reasoning?.takeIf { it.isNotEmpty() }?.let { reasoning ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(10.dp),
                    )
                    .padding(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        if (isReasoningExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (isReasoningExpanded) {
                            "Hide the model's thinking"
                        } else {
                            "Show the model's thinking"
                        },
                        modifier = Modifier.size(18.dp),
                    )
                    TextButton(
                        onClick = onToggleReasoning,
                        modifier = Modifier.semantics {
                            contentDescription = if (isReasoningExpanded) {
                                "Hide the model's thinking"
                            } else {
                                "Show the model's thinking"
                            }
                        },
                    ) { Text("Thinking") }
                }
                if (isReasoningExpanded) {
                    Text(
                        reasoning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Every answer the phone wrote says so, on the answer itself — not in a setting, not
        // in a banner that scrolls away. Drawn before the text, so it is read first too.
        if (fromPhone) PhoneChip(message.originLabel ?: "this phone's model")

        // A phone model holds a few thousand words of context. When the conversation ran
        // past that, the answer was written without the beginning of it, and says so.
        if (message.trimmedHistory) {
            Text(
                OnDeviceNotices.HISTORY_TRIMMED,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        if (message.content.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .then(
                        if (fromPhone) {
                            Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(14.dp))
                        } else {
                            Modifier
                        },
                    )
                    .background(
                        when {
                            isUser -> MaterialTheme.colorScheme.primaryContainer
                            fromPhone -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        RoundedCornerShape(14.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                if (isUser) {
                    Text(message.content, style = MaterialTheme.typography.bodyLarge)
                } else {
                    MarkdownText(message.content)
                }
            }
            if (!isUser) {
                Row {
                    TextButton(onClick = onCopy) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("  Copy")
                    }
                    TextButton(onClick = onShare) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("  Share")
                    }
                }
            }
        }

        if (message.isStreaming && message.content.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                val seconds = waitingSince?.let { (now - it) / 1000 }
                Text(
                    if (seconds != null) "  Thinking… ${seconds}s" else "  Thinking…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        message.failure?.let {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "  $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            onAnswerOnPhone?.let { answer ->
                TextButton(onClick = answer) {
                    Icon(Icons.Filled.PhoneAndroid, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("  ${OnDeviceNotices.ANSWER_ON_PHONE}")
                }
            }
        }

        message.verdict?.let { verdict ->
            // Three lines rather than one run-on sentence, the same shape iOS draws:
            // the Mac's word, then its reasons, then what it suggests doing about it.
            Text(
                verdict.summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            verdict.reasons?.takeIf { it.isNotEmpty() }?.let { reasons ->
                Text(
                    reasons.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            verdict.suggestion?.takeIf { it.isNotEmpty() }?.let { suggestion ->
                Text(
                    suggestion,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        message.metrics?.takeIf { it.generatedTokens > 0 }?.let {
            Text(
                "${it.generatedTokens} tokens · ${Format.rate(it.tokensPerSecond)}" +
                    (it.timeToFirstToken?.takeIf { fromPhone && it > 0 }
                        ?.let { first -> String.format(java.util.Locale.US, " · first word %.1f s", first) } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** "On this phone · Qwen3.5 2B", on every answer the phone wrote. */
@Composable
private fun PhoneChip(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(bottom = 4.dp)
            .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { contentDescription = OnDeviceNotices.chip(label) },
    ) {
        Icon(
            Icons.Filled.PhoneAndroid,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiary,
            modifier = Modifier.size(12.dp),
        )
        Text(
            " ${OnDeviceNotices.chip(label)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

/** "Your Mac isn't answering. [Answer on this phone] [Try again]" — never silent. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneOfferBanner(
    modelLabel: String,
    canRetry: Boolean,
    onAnswer: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(OnDeviceNotices.MAC_NOT_ANSWERING, style = MaterialTheme.typography.bodyMedium)
        Text(
            "$modelLabel on this phone can answer instead. It stays on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAnswer) {
                Icon(Icons.Filled.PhoneAndroid, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("  ${OnDeviceNotices.ANSWER_ON_PHONE}")
            }
            if (canRetry) OutlinedButton(onClick = onRetry) { Text(OnDeviceNotices.TRY_AGAIN) }
        }
    }
}

/**
 * The phone is answering anyway, with little memory to spare.
 *
 * Not an error — it is running — so it does not wear the error colours; but it is not a
 * detail either, because something else on the phone may be closed for it.
 */
@Composable
private fun MemoryWarningBanner(message: String, onMakeRoom: () -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onMakeRoom) { Text("Make room…") }
        TextButton(onClick = onDismiss) { Text("OK") }
    }
}

/** Why the phone would not answer, and the smaller model that would fit when it was memory. */
@Composable
private fun RefusalBanner(
    message: String,
    alternative: String?,
    onAlternative: () -> Unit,
    onMakeRoom: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            alternative?.let { TextButton(onClick = onAlternative) { Text("Use $it instead") } }
            onMakeRoom?.let { TextButton(onClick = it) { Text("Make room…") } }
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

/** In a conversation the phone answered, once the Mac is reachable again. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MacIsBackBanner(
    canSend: Boolean,
    onNewMacConversation: () -> Unit,
    onSendToMac: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            OnDeviceNotices.MAC_IS_BACK + " This conversation stays on the phone unless you send it.",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onNewMacConversation) { Text(OnDeviceNotices.NEW_MAC_CONVERSATION) }
            if (canSend) TextButton(onClick = onSendToMac) { Text(OnDeviceNotices.SEND_TO_MAC) }
        }
    }
}

/** Renders the blocks [Markdown] found, with inline bold, italic and code. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Markdown.blocks(markdown).forEach { block ->
            when (block) {
                is Markdown.Block.Paragraph -> Text(inline(block.text))
                is Markdown.Block.Heading -> Text(
                    inline(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                )
                is Markdown.Block.Bullets -> Column {
                    block.items.forEach { Text(inline("•  $it")) }
                }
                is Markdown.Block.Numbered -> Column {
                    block.items.forEachIndexed { index, item ->
                        Text(inline("${index + 1}.  $item"))
                    }
                }
                is Markdown.Block.Code -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(10.dp),
                ) {
                    block.language?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        block.text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
                is Markdown.Block.Quote -> Row {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .background(MaterialTheme.colorScheme.outline),
                    )
                    Text(
                        inline(block.text),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Markdown.Block.Rule -> HorizontalDivider()
            }
        }
    }
}

private fun inline(text: String) = buildAnnotatedString {
    Markdown.spans(text).forEach { span ->
        when (span) {
            is Markdown.Span.Plain -> append(span.text)
            is Markdown.Span.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(span.text)
            }
            is Markdown.Span.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(span.text)
            }
            is Markdown.Span.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                append(span.text)
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Silicon Buddy", text))
}

private fun share(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, null))
}

/**
 * Decodes a `data:` URL for display, at the size it will be displayed.
 *
 * Two passes, like the outgoing path: a 4000-pixel picture drawn into a 96dp box is
 * 60 MB of Bitmap for no visible difference, and doing that inside composition means
 * doing it on the main thread on every recomposition.
 */
private fun bitmapFrom(dataUrl: String, maxEdge: Int = 256): Bitmap? {
    val comma = dataUrl.indexOf(',')
    if (comma < 0) return null
    return runCatching {
        val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= maxEdge || bounds.outHeight / (sample * 2) >= maxEdge
        ) {
            sample *= 2
        }
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()
}

/** The same, off the composition thread, remembered per picture. */
@Composable
private fun rememberBitmap(dataUrl: String, maxEdge: Int = 256): Bitmap? {
    var bitmap by remember(dataUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(dataUrl) {
        bitmap = withContext(Dispatchers.Default) { bitmapFrom(dataUrl, maxEdge) }
    }
    return bitmap
}

/**
 * Scales a picture down and re-encodes it as JPEG.
 *
 * A 12-megapixel photo as a base64 data URL is about 15 MB of JSON, which is both
 * slower to send than the model is to answer and larger than most vision encoders can
 * use. 1024 on the long edge is what they actually look at.
 */
fun attachmentFrom(context: Context, uri: android.net.Uri, maxEdge: Int = 1024): String? =
    runCatching {
        // Two passes: measure first, then decode with an inSampleSize, so a
        // 50-megapixel photo never becomes a 200 MB Bitmap on the way to being made
        // small. The first pass allocates nothing.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= maxEdge || bounds.outHeight / (sample * 2) >= maxEdge
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val longest = maxOf(bitmap.width, bitmap.height)
        var scaled = if (longest > maxEdge) {
            val scale = maxEdge.toFloat() / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }

        // And then keep shrinking until it is inside the Mac's per-image limit: a
        // picture refused on arrival is worse than one made smaller before it left.
        var quality = 80
        var data: ByteArray
        var attempts = 0
        while (true) {
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            data = stream.toByteArray()
            attempts++
            if (data.size <= SendLimits.MAX_IMAGE_BYTES || attempts >= 4) break
            quality = maxOf(40, quality - 15)
            scaled = Bitmap.createScaledBitmap(
                scaled,
                (scaled.width * 0.75f).toInt().coerceAtLeast(1),
                (scaled.height * 0.75f).toInt().coerceAtLeast(1),
                true,
            )
        }
        "data:image/jpeg;base64," + Base64.encodeToString(data, Base64.NO_WRAP)
    }.getOrNull()


/**
 * Hold to talk, let go to ask.
 *
 * A hold rather than a toggle: it is the gesture people already know from every other
 * push-to-talk button, it cannot be left on by accident, and letting go is an
 * unambiguous "I have finished the sentence" that no silence detector gets right.
 */
@Composable
private fun PushToTalkButton(
    voice: VoiceController,
    hasMicrophone: Boolean,
    requestPermission: () -> Unit,
) {
    val listening = voice.state is VoiceState.Listening
    Box(
        modifier = Modifier
            .size(48.dp)
            .pointerInput(hasMicrophone) {
                detectTapGestures(
                    onPress = {
                        if (!hasMicrophone) {
                            requestPermission()
                            return@detectTapGestures
                        }
                        voice.press()
                        // `awaitRelease` is the whole gesture: the question is whatever
                        // was heard between the finger going down and coming up.
                        tryAwaitRelease()
                        voice.release()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            when (voice.state) {
                is VoiceState.Listening -> Icons.Filled.GraphicEq
                is VoiceState.Speaking -> Icons.AutoMirrored.Filled.VolumeUp
                else -> Icons.Filled.Mic
            },
            contentDescription = voice.buttonLabel,
            tint = if (listening) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}
