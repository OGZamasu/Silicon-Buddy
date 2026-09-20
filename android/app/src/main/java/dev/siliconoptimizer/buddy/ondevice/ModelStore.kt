package dev.siliconoptimizer.buddy.ondevice

import android.content.Context
import dev.siliconoptimizer.buddy.transport.PhoneModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * A model this phone has, verified.
 *
 * The Mac's entry is kept whole — label, licence, recommended threads and memory — because
 * the one moment it is needed is when the Mac cannot be asked.
 */
@Serializable
data class InstalledPhoneModel(
    val model: PhoneModel,
    /** `<sha256>.gguf`, in the store's folder. Named by content, so a new pin is a new file. */
    val fileName: String,
    /** Size and modification time when it was last hashed, to notice it changing since. */
    val verifiedBytes: Long,
    val verifiedModifiedAt: Long,
    val installedAt: Long,
) {
    val id: String get() = model.id
    val label: String get() = model.label
}

/**
 * Where the phone keeps its models: `noBackupFilesDir/models`.
 *
 * Not `filesDir`, because a gigabyte of weights has no business in a backup, and not the
 * cache, because the system would delete it under pressure and the owner would find out on
 * a train. Three kinds of file:
 *
 * - `<sha256>.gguf` — verified and in use.
 * - `<sha256>.part` — a download in progress, with the space for all of it reserved up front,
 *   so its length says nothing about how much has arrived…
 * - `<sha256>.part.json` — …which is what this says, updated as bytes are written.
 *
 * And `installed.json`, the entries for the verified ones.
 */
class ModelStore(val directory: File) {

    constructor(context: Context) : this(File(context.noBackupFilesDir, "models"))

    private val index = File(directory, "installed.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Serializable
    private data class Progress(val sha256: String, val received: Long)

    // MARK: - What is here

    /** Every verified model whose file is still there. */
    @Synchronized
    fun installed(): List<InstalledPhoneModel> = read().filter { file(it).exists() }

    fun installed(id: String): InstalledPhoneModel? = installed().firstOrNull { it.id == id }

    fun file(entry: InstalledPhoneModel): File = File(directory, entry.fileName)

    /**
     * Whether the file is exactly what was verified: same size, same modification time.
     * Anything else means it changed since, and it is hashed again before it is run.
     */
    fun isIntact(entry: InstalledPhoneModel): Boolean {
        val file = file(entry)
        return file.exists() && file.length() == entry.verifiedBytes &&
            file.lastModified() == entry.verifiedModifiedAt
    }

    /** Hashes an installed file again; keeps it if it still matches, forgets it if not. */
    @Synchronized
    fun reverify(entry: InstalledPhoneModel, isActive: () -> Boolean = { true }): Boolean {
        val file = file(entry)
        val matches = file.exists() && file.length() == entry.model.sizeBytes &&
            sha256(file, file.length(), isActive) == entry.model.sha256.lowercase()
        if (matches) {
            write(read().map {
                if (it.id == entry.id) it.copy(verifiedBytes = file.length(), verifiedModifiedAt = file.lastModified()) else it
            })
        } else {
            delete(entry.id)
        }
        return matches
    }

    // MARK: - Downloads in progress

    fun partFile(sha256: String): File = named(sha256, ".part")

    private fun progressFile(sha256: String): File = named(sha256, ".part.json")

    /**
     * A file named after a digest — and only after a digest.
     *
     * Every name here comes from the Mac's list of models. A digest is 64 hex characters;
     * anything else is not a digest, and a name that is not a digest is a path this app
     * would be writing wherever the string said. It is checked once, here, where the name
     * is made, rather than at each of the places that make one.
     */
    private fun named(sha256: String, extension: String): File {
        val sha = sha256.lowercase()
        require(isDigest(sha)) { "a model's sha256 is 64 hex characters, not \"$sha256\"" }
        return File(directory, sha + extension)
    }

    /** How much of this file has arrived, by the record rather than the file's length. */
    fun receivedBytes(sha256: String): Long {
        if (!isDigest(sha256.lowercase())) return 0
        val part = partFile(sha256)
        if (!part.exists()) return 0
        val progress = runCatching {
            json.decodeFromString<Progress>(progressFile(sha256).readText())
        }.getOrNull() ?: return 0
        if (!progress.sha256.equals(sha256, ignoreCase = true)) return 0
        return progress.received.coerceIn(0, part.length())
    }

    /** Records progress. Written beside the file and renamed, so it is never half a record. */
    fun noteReceived(sha256: String, received: Long) {
        directory.mkdirs()
        val target = progressFile(sha256)
        val temporary = File(directory, target.name + ".tmp")
        temporary.writeText(json.encodeToString(Progress(sha256.lowercase(), received)))
        if (!temporary.renameTo(target)) {
            target.delete()
            temporary.renameTo(target)
        }
    }

    /** Throws away a partial download: the bytes and the record of them. */
    fun discardPartial(sha256: String) {
        if (!isDigest(sha256.lowercase())) return
        partFile(sha256).delete()
        progressFile(sha256).delete()
        File(directory, progressFile(sha256).name + ".tmp").delete()
    }

    /**
     * The verified `.part` becomes the model: renamed in place — the same folder, so the
     * rename is atomic — and recorded. The caller has already checked the digest.
     */
    @Synchronized
    fun install(model: PhoneModel, now: Long = System.currentTimeMillis()): InstalledPhoneModel {
        val sha = model.sha256.lowercase()
        val part = partFile(sha)
        val target = named(sha, ".gguf")
        target.delete()
        if (!part.renameTo(target)) throw java.io.IOException("could not move the verified file into place")
        progressFile(sha).delete()
        val entry = InstalledPhoneModel(
            model = model,
            fileName = target.name,
            verifiedBytes = target.length(),
            verifiedModifiedAt = target.lastModified(),
            installedAt = now,
        )
        // The same model under a new pin is a different file. The one it replaces is a
        // gigabyte of nothing, and it is in the folder the phone reports as this app's
        // storage: it goes now, not at some later tidy-up.
        val previous = read().filter { it.id == model.id && it.fileName != target.name }
        previous.forEach { File(directory, it.fileName).delete() }
        write(read().filterNot { it.id == model.id } + entry)
        return entry
    }

    /** The model, its partial and its record, gone. Safe to call for something not here. */
    @Synchronized
    fun delete(id: String) {
        val entries = read()
        entries.filter { it.id == id }.forEach { File(directory, it.fileName).delete() }
        write(entries.filterNot { it.id == id })
    }

    /** Deletes a model and any download of it, by digest as well as by id. */
    @Synchronized
    fun deleteEverything(id: String, sha256: String?) {
        delete(id)
        sha256?.takeIf { isDigest(it.lowercase()) }?.let {
            discardPartial(it)
            named(it, ".gguf").delete()
        }
    }

    /** Bytes on disk for this model: the file, or the partial. */
    fun bytesOnPhone(id: String, sha256: String?): Long {
        installed(id)?.let { return file(it).length() }
        return sha256?.let { receivedBytes(it) } ?: 0
    }

    // MARK: - The index

    private fun read(): List<InstalledPhoneModel> {
        if (!index.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<InstalledPhoneModel>>(index.readText()) }
            .getOrElse { emptyList() }
    }

    private fun write(entries: List<InstalledPhoneModel>) {
        directory.mkdirs()
        val temporary = File(directory, "installed.json.tmp")
        temporary.writeText(json.encodeToString(entries))
        if (!temporary.renameTo(index)) {
            index.delete()
            temporary.renameTo(index)
        }
    }

    companion object {
        private val DIGEST = Regex("^[0-9a-f]{64}$")

        /** Whether a string is a SHA-256: 64 hex characters, and so safe as a file name. */
        fun isDigest(sha256: String): Boolean = DIGEST.matches(sha256.lowercase())

        /**
         * The SHA-256 of the first [length] bytes of [file], lower-case hex. [isActive] is
         * asked between reads so a cancelled download stops hashing too.
         */
        fun sha256(file: File, length: Long = file.length(), isActive: () -> Boolean = { true }): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1 shl 20)
            var remaining = length
            FileInputStream(file).use { input ->
                while (remaining > 0) {
                    if (!isActive()) throw kotlinx.coroutines.CancellationException("hashing stopped")
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    remaining -= read
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
