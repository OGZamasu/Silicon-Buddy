package dev.siliconoptimizer.buddy.chat

/**
 * What a device may send, from the Mac's own contract: 4 MiB a body, about 1.5 MB an
 * image, eight images a message. Checked before sending rather than discovered as a 413
 * after a slow upload over a tailnet.
 */
object SendLimits {
    const val MAX_ATTACHMENTS = 8
    const val MAX_IMAGE_BYTES = 1_500_000
    const val MAX_BODY_BYTES = 4 * 1024 * 1024

    /** The decoded size of a `data:` URL, without decoding it. */
    fun encodedBytes(dataUrl: String): Int {
        val comma = dataUrl.indexOf(',')
        val base64 = if (comma >= 0) dataUrl.length - comma - 1 else dataUrl.length
        return base64 * 3 / 4
    }

    /** Why this message cannot be sent as it stands, if it cannot. */
    fun problem(attachments: List<String>, message: String): String? {
        if (attachments.isEmpty()) return null
        if (attachments.size > MAX_ATTACHMENTS) {
            return "The Mac takes at most $MAX_ATTACHMENTS pictures a message. " +
                "Remove ${attachments.size - MAX_ATTACHMENTS} and send again."
        }
        attachments.firstOrNull { encodedBytes(it) > MAX_IMAGE_BYTES }?.let {
            val megabytes = encodedBytes(it) / 1_000_000.0
            return "One picture is %.1f MB; the Mac takes about 1.5 MB each.".format(megabytes)
        }
        val body = attachments.sumOf { it.length + 64 } + message.toByteArray().size + 512
        if (body > MAX_BODY_BYTES) {
            return "That message is larger than the 4 MB the Mac accepts. Send fewer pictures."
        }
        return null
    }
}
