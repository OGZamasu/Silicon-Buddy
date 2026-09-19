package dev.siliconoptimizer.buddy.reach

import dev.siliconoptimizer.buddy.chat.SendLimits

/**
 * What another app handed us, before anything has been decided about it.
 *
 * A share target receives whatever the sending app felt like attaching: a selection of
 * text, a link, a photograph, or several at once, in any order. Turning that into one
 * message for a model is the only part worth testing, so it lives here rather than in
 * the activity.
 */
data class SharePayload(val items: List<Item>) {

    sealed interface Item {
        data class Text(val text: String) : Item
        data class Link(val url: String) : Item

        /** A base64 `data:` URL, already shrunk to the Mac's per-image cap. */
        data class Picture(val dataUrl: String) : Item
    }

    val isEmpty: Boolean get() = items.isEmpty()
}

/** One message, ready for the composer: what to ask, what was shared, and the pictures. */
data class SharedDraft(
    /** The editable question, prefilled. The person can replace it entirely. */
    val prompt: String,
    /** The shared material, quoted under the question. Empty when only pictures came. */
    val quoted: String,
    val images: List<String>,
    /** One line naming what arrived, for the sheet's header. */
    val summary: String,
    /** Said out loud when something had to be dropped, rather than dropped quietly. */
    val note: String? = null,
) {
    /** What actually goes to the Mac: the question, then the material under it. */
    val message: String get() = if (quoted.isEmpty()) prompt else "$prompt\n\n$quoted"

    /**
     * True when nothing actually arrived. The prompt does not count: it is this app's
     * suggestion, not the other app's contribution, and a sheet offering to summarise
     * nothing is a sheet that should not have opened.
     */
    val isEmpty: Boolean get() = quoted.isEmpty() && images.isEmpty()
}

/** Turns what arrived into what to send. */
object ShareNormaliser {

    /**
     * How much shared text to carry. A share target is handed whole web pages, and a
     * message that fills the model's context leaves nothing for the answer — so the
     * text is cut here, visibly, rather than by the Mac on arrival.
     */
    const val MAX_QUOTED_CHARACTERS = 8_000

    fun draft(payload: SharePayload): SharedDraft {
        val texts = payload.items.filterIsInstance<SharePayload.Item.Text>()
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        val links = payload.items.filterIsInstance<SharePayload.Item.Link>().map { it.url }
        var images = payload.items.filterIsInstance<SharePayload.Item.Picture>().map { it.dataUrl }

        val notes = mutableListOf<String>()

        // A link that also came through as text — which is what Chrome sends — is one
        // thing shared, not two. Quoting it twice would waste the model's context.
        texts.removeAll { it in links }

        if (images.size > SendLimits.MAX_ATTACHMENTS) {
            notes += "Only the first ${SendLimits.MAX_ATTACHMENTS} pictures were kept — " +
                "that is all the Mac takes in one message."
            images = images.take(SendLimits.MAX_ATTACHMENTS)
        }
        val oversized = images.count { SendLimits.encodedBytes(it) > SendLimits.MAX_IMAGE_BYTES }
        if (oversized > 0) {
            notes += if (oversized == 1) {
                "One picture was too large to send even after shrinking."
            } else {
                "$oversized pictures were too large to send even after shrinking."
            }
            images = images.filter { SendLimits.encodedBytes(it) <= SendLimits.MAX_IMAGE_BYTES }
        }

        var quoted = (texts + links).joinToString("\n\n")
        if (quoted.length > MAX_QUOTED_CHARACTERS) {
            quoted = quoted.take(MAX_QUOTED_CHARACTERS)
            notes += "The text was cut to the first $MAX_QUOTED_CHARACTERS characters."
        }

        // Eight pictures each inside the per-image cap can still be three times the
        // body the Mac accepts, and eight legal pictures adding up to an illegal
        // message is exactly the shape a share target produces — select several in
        // Photos, share. So the last cap is on the whole thing, and pictures come off
        // the end until it fits rather than the Mac answering 413 after the upload.
        val fitted = fit(images, quoted)
        if (fitted.size < images.size) {
            val dropped = images.size - fitted.size
            notes += if (dropped == 1) {
                "One picture was left out to keep the message under the 4 MB the Mac takes."
            } else {
                "$dropped pictures were left out to keep the message under the 4 MB the Mac takes."
            }
        }
        images = fitted

        val nothingArrived = quoted.isEmpty() && images.isEmpty()
        return SharedDraft(
            prompt = if (nothingArrived) "" else prompt(images.isNotEmpty(), quoted.isNotEmpty()),
            quoted = quoted,
            images = images,
            summary = summary(texts.size, links, images.size),
            note = notes.takeIf { it.isNotEmpty() }?.joinToString(" "),
        )
    }

    /** Drops pictures from the end until the encoded message fits. */
    fun fit(images: List<String>, text: String): List<String> {
        var kept = images
        fun size() = kept.sumOf { it.length + 64 } + text.toByteArray().size + 512
        while (kept.isNotEmpty() && size() > SendLimits.MAX_BODY_BYTES) {
            kept = kept.dropLast(1)
        }
        return kept
    }

    /**
     * The question that is already in the box. A picture is nearly always "what is
     * this?" and a page is nearly always "summarise this"; anything else is a word or
     * two of typing away.
     */
    fun prompt(hasImages: Boolean, hasText: Boolean): String = when {
        hasImages && hasText -> "What is this, and what does the text say about it?"
        hasImages -> "What is this?"
        else -> "Summarise this"
    }

    fun summary(textCount: Int, links: List<String>, images: Int): String {
        val parts = mutableListOf<String>()
        when {
            images == 1 -> parts += "a picture"
            images > 1 -> parts += "$images pictures"
        }
        links.firstOrNull()?.let { first ->
            parts += if (links.size == 1) (hostOf(first) ?: "a link") else "${links.size} links"
        }
        when {
            textCount == 1 -> parts += "some text"
            textCount > 1 -> parts += "$textCount pieces of text"
        }
        if (parts.isEmpty()) return "Nothing to send"
        if (parts.size == 1) return "Shared: ${parts[0]}"
        return "Shared: " + parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }

    /** The host, without pulling in `android.net.Uri` — this has to run in a unit test. */
    fun hostOf(url: String): String? {
        val withoutScheme = url.substringAfter("://", url)
        val host = withoutScheme.substringBefore('/').substringBefore('?').substringAfter('@')
        return host.ifEmpty { null }
    }
}
