package dev.siliconoptimizer.buddy.ondevice

import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.PhoneModel
import dev.siliconoptimizer.buddy.transport.PhoneModelOnMac
import dev.siliconoptimizer.buddy.transport.TransportError
import dev.siliconoptimizer.buddy.ui.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

/** Where the room for a download comes from: `StorageManager` on a phone, a number in a test. */
interface SpaceReserver {
    /** What the app could have on the volume holding [directory], cache the system may clear included. */
    fun allocatableBytes(directory: File): Long

    /**
     * Reserves all [totalBytes] of [file] now — so a full phone is found out before the
     * download rather than at 90% of it.
     */
    fun reserve(file: File, totalBytes: Long)

    /** A plain file system: free space, and a file extended to its full length. */
    object Plain : SpaceReserver {
        override fun allocatableBytes(directory: File): Long = directory.usableSpace

        override fun reserve(file: File, totalBytes: Long) {
            RandomAccessFile(file, "rw").use { if (it.length() < totalBytes) it.setLength(totalBytes) }
        }
    }
}

/** A step of a download, for a notification and a Settings row. */
sealed interface DownloadStep {
    /** The Mac is fetching it from Hugging Face, checking it, or moving it. */
    data class OnMac(val stage: String?, val fraction: Double?) : DownloadStep

    /** Bytes arriving on this phone. */
    data class Copying(val received: Long, val total: Long) : DownloadStep {
        val fraction: Double get() = if (total > 0) received.toDouble() / total else 0.0
    }

    /** This phone hashing what arrived. */
    data object Verifying : DownloadStep
}

/**
 * Why a download stopped. [transient] failures are worth trying again by themselves — the
 * network, a Mac restarting — and the others need the owner: space, the scope this phone
 * was paired with, the Mac's drive, a file that failed its checksum twice.
 */
class DownloadFailure(message: String, val transient: Boolean) : Exception(message)

/**
 * Gets a model from the Mac onto this phone, and proves it is the right one.
 *
 * 1. The Mac is asked to have it ready (`prepare`), and followed while it fetches it from
 *    Hugging Face — the phone itself never leaves the tailnet.
 * 2. The file comes over in one ranged request, resumed with `Range` + `If-Range` from what
 *    arrived before: a 206 is exactly the rest, and a 200 means the Mac's file is not the
 *    one the partial came from, so the partial goes. The room for the whole file is
 *    reserved before the first byte.
 * 3. This phone hashes it. A match is renamed into place; a mismatch is deleted, the Mac is
 *    asked once to hash its own copy again (`?verify=1`), and the file is fetched once more
 *    from zero. A second mismatch stops, and says so.
 *
 * Pure Kotlin over [ControlTransport]: the job and the service that run it on a phone add
 * Wi-Fi, a notification and the system's scheduling, and nothing else.
 */
class ModelDownloader(
    private val mac: ControlTransport,
    private val store: ModelStore,
    private val space: SpaceReserver = SpaceReserver.Plain,
    private val pollMillis: Long = 2_000,
    private val retryDelays: List<Long> = listOf(1_000, 3_000, 8_000),
    private val progressEveryBytes: Long = 4L shl 20,
    /** How long the Mac may take to fetch it before this phone stops waiting. */
    private val macBudgetMillis: Long = 3 * 60 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun download(id: String, onStep: (DownloadStep) -> Unit = {}): InstalledPhoneModel {
        var model = listed(id)
        store.installed(id)?.let { here ->
            if (here.model.sha256.equals(model.sha256, ignoreCase = true) && store.isIntact(here)) return here
        }
        var verify = false
        var askedMacToVerify = false
        while (true) {
            model = readyOnMac(model, verify, onStep)
            verify = false
            copy(model, onStep)
            onStep(DownloadStep.Verifying)
            val sha = model.sha256.lowercase()
            val digest = withContext(Dispatchers.IO) {
                val context = coroutineContext
                ModelStore.sha256(store.partFile(sha), model.sizeBytes) { context.isActive }
            }
            if (digest == sha) return withContext(Dispatchers.IO) { store.install(model) }
            // Wrong bytes: none of them are kept. The Mac hashes its own copy again — once —
            // and the whole file comes over once more.
            store.discardPartial(sha)
            if (askedMacToVerify) throw DownloadFailure(checksumTwice(model), transient = false)
            askedMacToVerify = true
            verify = true
        }
    }

    // MARK: - The Mac's copy

    private suspend fun listed(id: String): PhoneModel {
        val models = persist { mac.phoneModels() }.models
        return models.firstOrNull { it.id == id }
            ?: throw DownloadFailure("Your Mac no longer offers that model for this phone.", transient = false)
    }

    /** Asks the Mac to have [model] ready and waits until it is, following its progress. */
    private suspend fun readyOnMac(model: PhoneModel, verify: Boolean, onStep: (DownloadStep) -> Unit): PhoneModel {
        val deadline = clock() + macBudgetMillis
        var retriesLeft = 2
        var entry = persist { mac.preparePhoneModel(model.id, verify) }.model
        while (!entry.onMac.isReady) {
            if (entry.onMac.isFailed || entry.onMac.state == PhoneModelOnMac.ABSENT) {
                // A cut connection or an interrupted fetch resumes on the Mac; a bad download
                // there starts over. Either is worth asking again, a couple of times.
                val retryable = entry.onMac.state == PhoneModelOnMac.ABSENT ||
                    entry.onMac.failure in setOf("network", "interrupted", "checksumMismatch", "server")
                if (!retryable || retriesLeft-- <= 0) {
                    throw DownloadFailure(
                        entry.onMac.reason ?: "Your Mac couldn't fetch ${model.label}.",
                        transient = entry.onMac.failure in setOf("network", "interrupted", "server"),
                    )
                }
                entry = persist { mac.preparePhoneModel(model.id, false) }.model
                continue
            }
            onStep(DownloadStep.OnMac(entry.onMac.stage, entry.onMac.fraction))
            if (clock() > deadline) {
                throw DownloadFailure("Your Mac is still fetching ${model.label}. Try again later.", transient = true)
            }
            delay(pollMillis)
            entry = listed(model.id)
        }
        return entry
    }

    // MARK: - The transfer

    /** Leaves the whole file in the `.part`, however many connections that takes. */
    private suspend fun copy(model: PhoneModel, onStep: (DownloadStep) -> Unit) {
        val sha = model.sha256.lowercase()
        var received = store.receivedBytes(sha)
        var failures = 0
        while (received < model.sizeBytes) {
            try {
                val before = received
                received = transfer(model, received, onStep)
                if (received > before) failures = 0
                if (received < model.sizeBytes) throw IOException("the Mac stopped sending")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: DownloadFailure) {
                throw failure
            } catch (error: TransportError) {
                when (error) {
                    // The Mac's copy is not verified and in place: it is checking it again,
                    // or it went. Wait for it.
                    is TransportError.Conflict -> readyOnMac(model, false, onStep)
                    // Past the end: either all of it is here already, or the partial is wrong.
                    is TransportError.RangeNotSatisfiable -> {
                        if (store.receivedBytes(sha) >= model.sizeBytes) return
                        store.discardPartial(sha)
                    }
                    else -> {
                        if (!isTransient(error)) throw DownloadFailure(describe(error, model), transient = false)
                        failures = retry(failures, error, model)
                    }
                }
                received = store.receivedBytes(sha)
            } catch (error: IOException) {
                failures = retry(failures, error, model)
                received = store.receivedBytes(sha)
            }
        }
    }

    private suspend fun retry(failures: Int, error: Exception, model: PhoneModel): Int {
        if (failures >= retryDelays.size) {
            throw DownloadFailure(
                (error as? TransportError)?.let { describe(it, model) }
                    ?: "The connection to your Mac kept dropping. The download will carry on from where it stopped.",
                transient = true,
            )
        }
        delay(retryDelays[failures])
        return failures + 1
    }

    /** One connection's worth. Returns how much of the file is here afterwards. */
    private suspend fun transfer(model: PhoneModel, from: Long, onStep: (DownloadStep) -> Unit): Long {
        val sha = model.sha256.lowercase()
        val stream = mac.openPhoneModelFile(model.id, from, if (from > 0) sha else null)
        return withContext(Dispatchers.IO) {
            coroutineScope {
                // Reading from a socket blocks where cancellation cannot reach; closing the
                // stream from another thread is what makes the read return.
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        stream.close()
                    }
                }
                try {
                    stream.sha256?.let {
                        if (!it.equals(sha, ignoreCase = true)) {
                            throw DownloadFailure(
                                "Your Mac is serving a different file for ${model.label} than it listed. " +
                                    "Try again in a minute.",
                                transient = true,
                            )
                        }
                    }
                    val start = when (stream.status) {
                        206 -> stream.rangeStart ?: -1
                        else -> 0L // the whole file: whatever arrived before is not part of it
                    }
                    if (start != from && stream.status == 206) {
                        // A range that is not the one asked for: start again cleanly.
                        store.discardPartial(sha)
                        return@coroutineScope 0L
                    }
                    val total = stream.totalBytes ?: model.sizeBytes
                    if (total != model.sizeBytes) {
                        throw DownloadFailure(
                            "Your Mac is serving ${Format.bytes(total)} for ${model.label}, not " +
                                "the ${Format.bytes(model.sizeBytes)} it listed.",
                            transient = true,
                        )
                    }
                    if (start == 0L) store.discardPartial(sha)
                    val part = store.partFile(sha)
                    part.parentFile?.mkdirs()
                    if (!part.exists() || part.length() < model.sizeBytes) reserve(part, model)
                    write(stream.body, part, start, model, onStep)
                } finally {
                    watcher.cancel()
                    stream.close()
                }
            }
        }
    }

    private fun reserve(part: File, model: PhoneModel) {
        val needed = model.sizeBytes - (if (part.exists()) part.length() else 0L)
        val available = space.allocatableBytes(store.directory)
        if (needed > available) {
            throw DownloadFailure(
                "This phone needs ${Format.bytes(needed)} free for ${model.label} and has " +
                    "${Format.bytes(available)}. Free some space and try again.",
                transient = false,
            )
        }
        try {
            space.reserve(part, model.sizeBytes)
        } catch (error: IOException) {
            throw DownloadFailure(
                "This phone couldn't set aside ${Format.bytes(model.sizeBytes)} for ${model.label}. " +
                    "Free some space and try again.",
                transient = false,
            )
        }
    }

    private suspend fun write(
        body: java.io.InputStream,
        part: File,
        start: Long,
        model: PhoneModel,
        onStep: (DownloadStep) -> Unit,
    ): Long {
        val sha = model.sha256.lowercase()
        var position = start
        var sinceNote = 0L
        RandomAccessFile(part, "rw").use { file ->
            file.seek(start)
            val buffer = ByteArray(256 * 1024)
            try {
                while (true) {
                    coroutineContext.ensureActive()
                    val read = body.read(buffer)
                    if (read < 0) break
                    if (position + read > model.sizeBytes) {
                        throw DownloadFailure("Your Mac sent more of ${model.label} than it listed.", transient = true)
                    }
                    file.write(buffer, 0, read)
                    position += read
                    sinceNote += read
                    if (sinceNote >= progressEveryBytes) {
                        store.noteReceived(sha, position)
                        onStep(DownloadStep.Copying(position, model.sizeBytes))
                        sinceNote = 0
                    }
                }
            } catch (error: IOException) {
                // Closed under the read because the download was cancelled: that is the
                // cancellation, not a Mac that dropped the line.
                coroutineContext.ensureActive()
                throw error
            } finally {
                // Whatever arrived is kept and counted, so the next attempt starts after it.
                store.noteReceived(sha, position)
            }
        }
        onStep(DownloadStep.Copying(position, model.sizeBytes))
        return position
    }

    // MARK: - Failures

    /** Retries a call a few times across the kind of failure that passes. */
    private suspend fun <T> persist(call: suspend () -> T): T {
        var failures = 0
        while (true) {
            try {
                return call()
            } catch (error: TransportError) {
                if (!isTransient(error) || failures >= retryDelays.size) {
                    throw DownloadFailure(describe(error, null), transient = isTransient(error))
                }
                delay(retryDelays[failures++])
            }
        }
    }

    private fun isTransient(error: TransportError): Boolean = when (error) {
        is TransportError.Unreachable, is TransportError.AppNotRunning, is TransportError.TimedOut,
        is TransportError.Busy, is TransportError.Server, is TransportError.Decoding,
        -> true
        else -> false
    }

    private fun describe(error: TransportError, model: PhoneModel?): String = when (error) {
        // A chat-only phone: the Mac's own sentence says how to pair again.
        is TransportError.Forbidden -> OnDeviceNotices.chatScope(error.message)
        is TransportError.Unauthorized -> "This phone isn't paired with your Mac any more. Pair it again, then download."
        is TransportError.NotFound -> "Your Mac no longer offers ${model?.label ?: "that model"} for this phone."
        is TransportError.RouteUnavailable ->
            "Your Mac doesn't serve models for this phone yet. Update Silicon Optimizer on it."
        // The drive the Mac keeps them on, or its free space: the Mac's sentence names it.
        is TransportError.Unavailable, is TransportError.InsufficientStorage -> error.message ?: ""
        else -> error.message ?: "Your Mac didn't answer."
    }

    private fun checksumTwice(model: PhoneModel) =
        "${model.label} arrived twice and did not match its published checksum either time, " +
            "so this phone deleted it. Nothing was kept. Try again later, or ask the Mac to " +
            "fetch it afresh."
}
