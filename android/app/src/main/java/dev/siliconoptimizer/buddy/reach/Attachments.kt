package dev.siliconoptimizer.buddy.reach

import android.graphics.Bitmap
import android.util.Base64
import dev.siliconoptimizer.buddy.chat.SendLimits
import java.io.ByteArrayOutputStream

/**
 * A picture already in memory, on its way to a vision model.
 *
 * The gallery path measures and samples a file before it ever allocates a Bitmap; this
 * one starts with the Bitmap — a camera frame — so all it has to do is the second half:
 * shrink until the Mac will take it. A picture refused on arrival is worse than one
 * made smaller before it left.
 */
object Attachments {

    fun dataUrl(bitmap: Bitmap, maxEdge: Int = 1024): String? = runCatching {
        var scaled = bitmap
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > maxEdge) {
            val scale = maxEdge.toFloat() / longest
            scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }
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
}
