package dev.siliconoptimizer.buddy.camera

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.siliconoptimizer.buddy.reach.Attachments
import dev.siliconoptimizer.buddy.reach.OneShotAsk
import dev.siliconoptimizer.buddy.reach.SnapshotStore
import dev.siliconoptimizer.buddy.transport.ControlTransport
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Point the camera at something and ask about it.
 *
 * Not the system camera intent. That flow is take, review, accept, and only then do you
 * get to say what you wanted to know; here the question is typed first and the shutter
 * sends, which is what "what is this?" actually needs. The frame is shrunk to the Mac's
 * per-image cap before it leaves.
 */
sealed interface CameraPhase {
    object Framing : CameraPhase
    object Sending : CameraPhase
    data class Answered(val text: String) : CameraPhase
    data class Failed(val problem: String) : CameraPhase
}

class CameraModeViewModel(application: Application) : AndroidViewModel(application) {

    var prompt by mutableStateOf("What is this?")
    var phase by mutableStateOf<CameraPhase>(CameraPhase.Framing)
        private set
    var captured by mutableStateOf<String?>(null)
        private set
    var streamed by mutableStateOf("")
        private set
    var storedOnMac by mutableStateOf(false)
        private set

    private val snapshots = SnapshotStore(application)

    fun retake() {
        captured = null
        streamed = ""
        phase = CameraPhase.Framing
    }

    fun captured(bitmap: Bitmap, transport: ControlTransport?) {
        val dataUrl = Attachments.dataUrl(bitmap)
        if (dataUrl == null) {
            phase = CameraPhase.Failed("That picture couldn't be prepared for sending.")
            return
        }
        captured = dataUrl
        ask(transport)
    }

    fun ask(transport: ControlTransport?) {
        val image = captured ?: return
        val question = prompt.trim().ifEmpty { "What is this?" }
        streamed = ""
        phase = CameraPhase.Sending
        viewModelScope.launch {
            try {
                val outcome = OneShotAsk.send(
                    message = question,
                    images = listOf(image),
                    title = "Camera",
                    transport = transport,
                    snapshots = snapshots,
                    onToken = { streamed += it },
                )
                storedOnMac = outcome.storedOnMac
                phase = CameraPhase.Answered(outcome.answer)
            } catch (error: Exception) {
                phase = CameraPhase.Failed(error.message ?: "Your Mac didn't answer.")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraModeSheet(
    transport: ControlTransport?,
    model: CameraModeViewModel,
    /** Null when nothing is known; false warns that the loaded model cannot see. */
    supportsVision: Boolean?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (supportsVision == false) {
                Text(
                    "The model loaded on your Mac doesn't read pictures. Load one that " +
                        "does from Models, or the answer will be a guess.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Box(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                if (model.captured == null) {
                    CameraPreview { bitmap -> model.captured(bitmap, transport) }
                } else {
                    Text(
                        "Photo taken.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            OutlinedTextField(
                value = model.prompt,
                onValueChange = { model.prompt = it },
                label = { Text("What do you want to know?") },
                modifier = Modifier.fillMaxWidth(),
            )

            when (val phase = model.phase) {
                is CameraPhase.Sending -> Text(
                    model.streamed.ifEmpty { "Thinking…" },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                )
                is CameraPhase.Answered -> Text(
                    phase.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                )
                is CameraPhase.Failed -> Text(
                    phase.problem,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                CameraPhase.Framing -> Unit
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.captured != null) {
                    TextButton(onClick = { model.retake() }) { Text("Retake") }
                    FilledIconButton(
                        onClick = { model.ask(transport) },
                        enabled = model.phase !is CameraPhase.Sending,
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "Ask again")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

/**
 * A live CameraX preview with a shutter over it.
 *
 * `takePicture` into memory rather than a file: the frame is going straight into a
 * base64 data URL, and writing somebody's photograph to disk on the way would leave a
 * copy nobody asked for.
 */
@Composable
private fun CameraPreview(onImage: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val capture = remember { ImageCapture.Builder().build() }
    val preview = remember { Preview.Builder().build() }
    val previewView = remember { PreviewView(context) }
    // Written before the bind and read on dispose, so the two cannot disagree about
    // whether there is anything to release.
    val bound = remember { java.util.concurrent.atomic.AtomicReference<ProcessCameraProvider?>() }

    // Bound for exactly as long as the preview is on screen.
    //
    // `bindToLifecycle` ties the camera to the *activity*, so a sheet that is dismissed
    // leaves it running: the privacy indicator stays lit and the sensor keeps drawing
    // power until the whole activity stops. Unbinding on dispose is the other half of
    // the bind.
    //
    // `awaitInstance` rather than `getInstance(...).get()`, which blocks the main thread
    // inside composition, or a listener, which has no way of knowing the sheet has since
    // closed — dismiss it while the camera service is still starting and the listener
    // binds afterwards, to a preview nobody is looking at, with no dispose left to undo
    // it. A cancelled coroutine simply never reaches the bind.
    LaunchedEffect(lifecycleOwner) {
        val provider = runCatching { ProcessCameraProvider.awaitInstance(context) }
            .getOrNull() ?: return@LaunchedEffect
        // Recorded before binding, so a dispose racing the next two lines still finds
        // something to unbind.
        bound.set(provider)
        ensureActive()
        preview.surfaceProvider = previewView.surfaceProvider
        runCatching {
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture,
            )
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            // These two use cases, not `unbindAll`: the pairing screen's QR scanner
            // binds its own analyser to the same provider, and tearing that down from
            // here would stop a scan that has nothing to do with this sheet.
            runCatching { bound.getAndSet(null)?.unbind(preview, capture) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        FilledIconButton(
            onClick = {
                capture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            // Closed whatever `onImage` does. An ImageProxy holds a
                            // buffer out of a fixed-size pool: leak two or three and
                            // `takePicture` stops calling back at all, which looks
                            // exactly like a shutter button that has stopped working.
                            try {
                                bitmapOf(image)?.let(onImage)
                            } finally {
                                image.close()
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            image_error(exception)
                        }
                    },
                )
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
        ) {
            Icon(Icons.Filled.CameraAlt, contentDescription = "Take a photo and ask")
        }
    }
}

/** CameraX hands back a JPEG in a single plane; this is the whole conversion. */
private fun bitmapOf(image: ImageProxy): Bitmap? = runCatching {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

/**
 * A capture that fails has nowhere useful to go: the preview is still on screen and the
 * shutter can be pressed again, which is a better answer than a dialog.
 */
private fun image_error(exception: ImageCaptureException) {
    android.util.Log.w("SiliconBuddy", "Camera capture failed", exception)
}
