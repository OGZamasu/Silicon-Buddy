package dev.siliconoptimizer.buddy.pairing

import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.ensureActive
import java.util.concurrent.Executors

/**
 * The camera, looking for the QR code the Mac shows.
 *
 * CameraX for the preview and the frame pump, ML Kit for the decode. The first code
 * that comes back is handed up once and only once — the analyzer keeps running until
 * the composable leaves, and a second callback for the same code would pair twice.
 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
fun QrScanner(onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }
    val handled = remember { booleanArrayOf(false) }
    val previewView = remember { PreviewView(context) }
    val preview = remember { Preview.Builder().build() }
    val analysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }
    // Written before the bind and read on dispose, so the two cannot disagree about
    // whether there is anything to release.
    val bound = remember { java.util.concurrent.atomic.AtomicReference<ProcessCameraProvider?>() }

    // Bound for exactly as long as the scanner is on screen.
    //
    // `bindToLifecycle` ties the camera to the *activity*, and the pairing sheet is not
    // the activity: paired, or closed, it leaves, and the camera stayed on behind it —
    // privacy indicator lit, sensor streaming frames into an analyser nobody reads — until
    // the whole app went to the background. Unbinding on dispose is the other half of the
    // bind. `awaitInstance` in an effect rather than a listener, as in CameraMode: a sheet
    // closed while the camera service is still starting never reaches the bind.
    LaunchedEffect(lifecycleOwner) {
        val provider = runCatching { ProcessCameraProvider.awaitInstance(context) }
            .getOrNull() ?: return@LaunchedEffect
        bound.set(provider)
        ensureActive()
        preview.surfaceProvider = previewView.surfaceProvider
        analysis.setAnalyzer(executor) { proxy: ImageProxy ->
            val image = proxy.image
            if (image == null || handled[0]) {
                proxy.close()
                return@setAnalyzer
            }
            val input = InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees)
            scanner.process(input)
                .addOnSuccessListener { codes ->
                    val text = codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
                    if (text != null && !handled[0]) {
                        handled[0] = true
                        ContextCompat.getMainExecutor(context).execute { onCode(text) }
                    }
                }
                .addOnCompleteListener { proxy.close() }
        }
        runCatching {
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // These two use cases, not `unbindAll`: the camera button in the chat binds its
            // own to the same provider, and this sheet has no business stopping it.
            runCatching { bound.getAndSet(null)?.unbind(preview, analysis) }
            analysis.clearAnalyzer()
            executor.shutdown()
            scanner.close()
        }
    }

    AndroidView(modifier = modifier, factory = { previewView })
}
