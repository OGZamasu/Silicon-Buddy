package dev.siliconoptimizer.buddy.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.TransportError
import dev.siliconoptimizer.buddy.transport.Uploads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The two directions a picture travels.
 *
 * Out: a photo on this phone becomes an id the Mac will render from — `POST /uploads`,
 * because a device may not name a path on somebody else's disk, and rightly so.
 *
 * In: a finished render becomes a row in this phone's own photo library, fetched from
 * `GET /media/{id}` and written straight into MediaStore rather than through a file of
 * our own. Both are here rather than in a view model because both are Android's, and
 * the rules around them — what may be sent, what a chat-scope device may fetch — are
 * the part worth reading in one place.
 */
object MediaLibrary {

    /** What a picked photo turned out to be. */
    data class Picked(val bytes: ByteArray, val contentType: String, val name: String?) {
        val size: Long get() = bytes.size.toLong()

        override fun equals(other: Any?): Boolean =
            other is Picked && other.contentType == contentType && other.name == name &&
                other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** Reads a picked photo, refusing before the network what the Mac would refuse after. */
    suspend fun read(context: Context, uri: Uri): Result<Picked> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val type = resolver.getType(uri) ?: "application/octet-stream"
            val name = displayName(context, uri)
            val bytes = resolver.openInputStream(uri)?.use { source ->
                val sink = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    // Stop at the ceiling rather than reading a 400 MB video into
                    // memory to be told about it afterwards.
                    if (total > Uploads.MAXIMUM_BYTES) {
                        error("That file is larger than the 24 MB this Mac will take.")
                    }
                    sink.write(buffer, 0, read)
                }
                sink.toByteArray()
            } ?: error("That picture could not be read from this phone.")
            Picked(bytes, type, name)
        }
    }

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    /**
     * Saves a finished render into the phone's photo library.
     *
     * Written through MediaStore's own pending row, so a download that fails leaves
     * nothing half-made in the gallery: the row only becomes visible once the bytes are
     * all there.
     */
    suspend fun save(
        context: Context,
        transport: ControlTransport,
        mediaID: String,
        name: String,
        video: Boolean,
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = if (video) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                if (video) {
                    "${Environment.DIRECTORY_MOVIES}/Silicon Buddy"
                } else {
                    "${Environment.DIRECTORY_PICTURES}/Silicon Buddy"
                },
            )
        }
        var row: Uri? = null
        runCatching {
            row = resolver.insert(collection, values)
                ?: error("This phone would not make room for it.")
            val destination = row ?: error("This phone would not make room for it.")
            resolver.openOutputStream(destination)?.use { sink ->
                transport.media(mediaID, sink)
            } ?: error("This phone would not open the file it just made.")
            resolver.update(
                destination,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            destination
        }.onFailure { failure ->
            // Nothing half-made in somebody's gallery.
            row?.let { runCatching { resolver.delete(it, null, null) } }
            if (failure is TransportError) throw failure
        }
    }

    /** A name a person can find again, with the extension the Mac's own file had. */
    fun nameFor(kind: String, title: String?, path: String?): String {
        val stem = (title ?: kind).trim().ifEmpty { kind }
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .take(40)
            .ifEmpty { kind }
        val extension = path?.substringAfterLast('.', "")?.takeIf { it.length in 1..5 }
            ?: if (kind == "video") "mp4" else "png"
        return "$stem-${System.currentTimeMillis() / 1000}.$extension"
    }
}
