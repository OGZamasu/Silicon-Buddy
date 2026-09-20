package dev.siliconoptimizer.buddy.transport

import kotlinx.serialization.Serializable
import java.io.Closeable
import java.io.InputStream

/**
 * `/ondevice/models`: the small models this phone can run by itself when the Mac is out of
 * reach, and the Mac's part in getting one here.
 *
 * The phone never leaves the tailnet for them. The Mac fetches the pinned file from Hugging
 * Face, verifies it, and serves it to the phone with ranges; the phone checks the SHA-256
 * again itself before it runs a byte of it. Every route is full scope only — a chat-only
 * device is told why it cannot have one — and mirrored field for field from the Mac's
 * `PhoneModelsAPI.swift`; `ContractTest` round-trips the export.
 */
@Serializable
data class PhoneModelList(val models: List<PhoneModel>)

@Serializable
data class PhoneModel(
    /** The catalogue key. The only thing a route takes; never a path. */
    val id: String,
    val label: String,
    /** The one to offer first. */
    val isDefault: Boolean,
    val sizeBytes: Long,
    /** Lower-case hex: the file route's ETag, and what this phone checks at the end. */
    val sha256: String,
    val licence: String,
    val source: PhoneModelSource,
    val onMac: PhoneModelOnMac,
    val recommended: PhoneModelRecommended,
    val measured: PhoneModelMeasured? = null,
    /** Larger and noticeably slower on the phone than the default. */
    val slowerOnPhone: Boolean,
) {
    /** The `id` of the `download` frames on `/events` while the Mac fetches this one. */
    val downloadEventID: String get() = DOWNLOAD_EVENT_PREFIX + id

    companion object {
        const val DOWNLOAD_EVENT_PREFIX = "ondevice:"
    }
}

/** Where the bytes come from: a Hugging Face repository at a fixed commit. */
@Serializable
data class PhoneModelSource(val repo: String, val commit: String, val file: String)

/** The Mac's own copy. */
@Serializable
data class PhoneModelOnMac(
    /** `absent`, `downloading`, `ready` or `failed`. */
    val state: String,
    /** While downloading: `fetching`, `checking` or `moving`. */
    val stage: String? = null,
    /** How far that stage has got; on a failure that kept a partial, how much the Mac has. */
    val fraction: Double? = null,
    /** Why it failed, in a sentence this phone can show. */
    val reason: String? = null,
    /** `diskFull`, `checksumMismatch`, `network`, `server`, `interrupted`, `driveMissing`, `other`. */
    val failure: String? = null,
) {
    val isReady: Boolean get() = state == READY
    val isDownloading: Boolean get() = state == DOWNLOADING
    val isFailed: Boolean get() = state == FAILED

    companion object {
        const val ABSENT = "absent"
        const val DOWNLOADING = "downloading"
        const val READY = "ready"
        const val FAILED = "failed"
    }
}

/** How to run it on this phone, from the Mac's measurements on a real one. */
@Serializable
data class PhoneModelRecommended(
    val threadsPrompt: Int,
    val threadsGenerate: Int,
    val contextLength: Int,
    /** How much memory should be free before loading it. Below this, the phone refuses. */
    val minFreeMemoryBytes: Long,
    /** The chat template is rendered with thinking on or off. Off for both today. */
    val thinking: Boolean,
)

/** What one benchmark on a real phone measured, so this one can say what to expect. */
@Serializable
data class PhoneModelMeasured(
    val device: String,
    val runtime: String,
    val conditions: String,
    val tokensPerSecond: Double,
    val threadSweep: List<PhoneModelThreadSample>,
    val promptTokensPerSecond: Double,
    val secondsToFirstWord300: Double,
    val firstWordEstimated: Boolean,
    val sustainedTokensPerSecond: Double? = null,
    val sustainedMeasured: Boolean,
    val peakMemoryBytes: Long,
    val peakMemoryContextTokens: Int,
)

@Serializable
data class PhoneModelThreadSample(val threads: Int, val tokensPerSecond: Double)

/**
 * What `POST /ondevice/models/{id}/prepare` said: the entry, and whether it was ready
 * already (200) rather than on its way (202). The status is the part the body cannot say.
 */
data class PhoneModelPreparation(val model: PhoneModel, val wasReady: Boolean)

/**
 * `GET /ondevice/models/{id}/file`, open and not yet read.
 *
 * [status] is 206 for the rest of a file this phone already has part of, or 200 for the
 * whole file — which, in answer to a `Range` with `If-Range`, means the Mac's file is not
 * the one the partial came from, and the partial must go.
 */
class PhoneModelStream(
    val status: Int,
    /** Bytes in this body, or -1 when the Mac did not say. */
    val contentLength: Long,
    /** Where a 206 starts, from `Content-Range`. */
    val rangeStart: Long?,
    /** The whole file's size, from `Content-Range` or, for a 200, `Content-Length`. */
    val totalBytes: Long?,
    /** `X-Content-SHA256`, or the `ETag` without its quotes. */
    val sha256: String?,
    val body: InputStream,
    private val release: () -> Unit,
) : Closeable {
    override fun close() {
        runCatching { body.close() }
        release()
    }

    companion object {
        /**
         * `bytes 100-199/1000` → (100, 1000). An unsatisfied range — `bytes *` and the size —
         * gives (null, 1000).
         */
        fun parseContentRange(header: String?): Pair<Long?, Long?> {
            if (header.isNullOrBlank()) return null to null
            val spec = header.trim().removePrefix("bytes").trim()
            val slash = spec.lastIndexOf('/')
            if (slash < 0) return null to null
            val total = spec.substring(slash + 1).trim().toLongOrNull()
            val range = spec.substring(0, slash).trim()
            val start = if (range == "*") null else range.substringBefore('-').trim().toLongOrNull()
            return start to total
        }

        /** An entity tag without `W/` and its quotes. */
        fun opaqueTag(tag: String?): String? =
            tag?.trim()?.removePrefix("W/")?.trim('"')?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Conversations the phone answered itself live only on the phone. Their ids carry this
 * prefix so that every Mac-facing path can refuse them by looking — a conversation that
 * never left the phone must not reach the Mac by accident, as an id in a URL or otherwise.
 * The Mac mints UUIDs; none of them starts with a word.
 */
object OnDeviceIds {
    const val PREFIX = "phone-"

    const val REFUSAL =
        "That conversation lives only on this phone. Send it to the Mac from its menu first."

    fun isOnDevice(id: String?): Boolean = id?.startsWith(PREFIX) == true

    fun mint(): String = PREFIX + java.util.UUID.randomUUID().toString()
}
