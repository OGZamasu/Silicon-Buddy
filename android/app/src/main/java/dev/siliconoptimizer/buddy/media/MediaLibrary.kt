package dev.siliconoptimizer.buddy.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.Uploads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream

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
     * Saves a finished render onto the phone: a picture or a clip into its photo library, a
     * mesh into Downloads (see [destination]).
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
        kind: String,
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val destination = destination(kind, name)
        val (collection, folder) = when (destination.collection) {
            Destination.Collection.Video ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                    Environment.DIRECTORY_MOVIES
            Destination.Collection.Images ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                    Environment.DIRECTORY_PICTURES
            Destination.Collection.Downloads ->
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                    Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            destination.mimeType?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Silicon Buddy")
        }
        written(
            insert = { resolver.insert(collection, values) },
            open = { resolver.openOutputStream(it) },
            publish = { row ->
                resolver.update(
                    row,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            },
            discard = { row -> resolver.delete(row, null, null) },
            fetch = { sink -> transport.media(mediaID, sink) },
        )
    }

    /**
     * The order a save goes in, apart from MediaStore so it can be checked without one: a
     * pending row, the Mac's bytes into it, then the row made visible.
     *
     * Whatever goes wrong — the Mac answering 404 for a render it has since removed, the
     * tailnet dropping halfway, the phone running out of room — the row is taken away and
     * the failure is the result. It is never thrown: a save runs in a view model's
     * coroutine, where nothing catches, and a throw from there closes the app. Only a
     * cancelled save goes on as one, because its coroutine is ending anyway.
     */
    internal suspend fun <Row : Any> written(
        insert: () -> Row?,
        open: (Row) -> OutputStream?,
        publish: (Row) -> Unit,
        discard: (Row) -> Unit,
        fetch: suspend (OutputStream) -> Unit,
    ): Result<Row> {
        var row: Row? = null
        return runCatching {
            val destination = insert() ?: error("This phone would not make room for it.")
            row = destination
            open(destination)?.use { sink -> fetch(sink) }
                ?: error("This phone would not open the file it just made.")
            publish(destination)
            destination
        }.onFailure { failure ->
            // Nothing half-made in somebody's gallery.
            row?.let { runCatching { discard(it) } }
            if (failure is CancellationException) throw failure
        }
    }

    /** Which of MediaStore's collections a render goes into, and as what type. */
    data class Destination(val collection: Collection, val mimeType: String?) {
        enum class Collection { Images, Video, Downloads }
    }

    /**
     * Pictures and clips go where a gallery finds them, typed by their names. A mesh is
     * neither: MediaStore's image collection refuses a `.glb` outright from Android 11 on —
     * the insert throws before a byte is fetched — and on Android 10 files it as a picture.
     * So it goes to Downloads, which takes any file, as the type a 3D viewer opens.
     */
    fun destination(kind: String, name: String): Destination = when (kind) {
        "video" -> Destination(Destination.Collection.Video, null)
        "mesh" -> Destination(Destination.Collection.Downloads, meshType(name))
        else -> Destination(Destination.Collection.Images, null)
    }

    private fun meshType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "glb" -> "model/gltf-binary"
        "gltf" -> "model/gltf+json"
        "obj" -> "model/obj"
        "stl" -> "model/stl"
        "usdz" -> "model/vnd.usdz+zip"
        else -> "application/octet-stream"
    }

    /** A name a person can find again, with the extension the Mac's own file had. */
    fun nameFor(kind: String, title: String?, path: String?): String {
        val stem = (title ?: kind).trim().ifEmpty { kind }
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .take(40)
            .ifEmpty { kind }
        val extension = path?.substringAfterLast('.', "")?.takeIf { it.length in 1..5 }
            ?: when (kind) {
                "video" -> "mp4"
                "mesh" -> "glb"
                else -> "png"
            }
        return "$stem-${System.currentTimeMillis() / 1000}.$extension"
    }
}
