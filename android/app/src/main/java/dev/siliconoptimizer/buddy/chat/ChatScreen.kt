package dev.siliconoptimizer.buddy.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
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
import dev.siliconoptimizer.buddy.ui.Format
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

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            attachmentFrom(context, uri)?.let { model.attach(it) }
        }
    }

    LaunchedEffect(model.current?.messages?.size, model.current?.messages?.lastOrNull()?.content) {
        val count = model.current?.messages?.size ?: 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        val messages = model.current?.messages.orEmpty()
        if (messages.isEmpty()) {
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
                        onCopy = { copyToClipboard(context, message.content) },
                        onShare = { share(context, message.content) },
                    )
                }
            }
        }

        HorizontalDivider()

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
                enabled = model.attachments.size < SendLimits.MAX_ATTACHMENTS,
            ) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Attach a picture")
            }
            OutlinedTextField(
                value = model.draft,
                onValueChange = { model.draft = it },
                placeholder = { Text("Message") },
                modifier = Modifier.weight(1f),
                maxLines = 6,
            )
            if (model.isSending) {
                IconButton(onClick = { model.cancel() }) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop generating")
                }
            } else {
                IconButton(
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
) {
    val isUser = message.role == ChatMessage.ROLE_USER
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = if (isUser) "You said" else "The model replied"
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

        if (message.content.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .background(
                        if (isUser) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
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
        }

        message.metrics?.takeIf { it.generatedTokens > 0 }?.let {
            Text(
                "${it.generatedTokens} tokens · ${Format.rate(it.tokensPerSecond)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
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
