package dev.siliconoptimizer.buddy.pairing

import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
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

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            scanner.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            val previewView = PreviewView(viewContext)
            val providerFuture = ProcessCameraProvider.getInstance(viewContext)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                    val image = proxy.image
                    if (image == null || handled[0]) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    val input = InputImage.fromMediaImage(
                        image, proxy.imageInfo.rotationDegrees,
                    )
                    scanner.process(input)
                        .addOnSuccessListener { codes ->
                            val text = codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                                ?.rawValue
                            if (text != null && !handled[0]) {
                                handled[0] = true
                                ContextCompat.getMainExecutor(viewContext).execute { onCode(text) }
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, ContextCompat.getMainExecutor(viewContext))
            previewView
        },
    )
}
