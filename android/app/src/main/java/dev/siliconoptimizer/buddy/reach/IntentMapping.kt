package dev.siliconoptimizer.buddy.reach

import dev.siliconoptimizer.buddy.transport.ChatMessageWire
import dev.siliconoptimizer.buddy.transport.ChatRequest
import dev.siliconoptimizer.buddy.transport.DeviceScope
import dev.siliconoptimizer.buddy.transport.InstalledModel
import dev.siliconoptimizer.buddy.transport.LoadRequest
import dev.siliconoptimizer.buddy.transport.Status

/**
 * The part of "Hey Google, ask my Mac…" that is worth testing.
 *
 * A shortcut parameter is a phrase a person said or typed, and the Mac wants an id. The
 * mapping between the two is where a shortcut either works or quietly loads the wrong
 * model, so it is here, in one place, with no Assistant anywhere near it.
 */
object IntentMapping {

    /** Why a shortcut could not be carried out, in words that can be read aloud. */
    sealed class Refusal(message: String) : Exception(message) {
        object NotPaired : Refusal(
            "Silicon Buddy isn't paired with a Mac yet. Open the app and pair first.",
        )

        object NotAllowed : Refusal(
            "This device is paired for chat only. Loading, installing and rendering are " +
                "hidden. Pair it again with full control from Settings, Silicon Buddy on the Mac.",
        )

        object EmptyPrompt : Refusal("There was nothing to ask.")

        class NoSuchModel(val phrase: String) :
            Refusal("No model on your Mac is called \"$phrase\".")

        class Ambiguous(val phrase: String, val candidates: List<String>) : Refusal(
            "\"$phrase\" matches ${candidates.size} models: " +
                candidates.take(3).joinToString(", ") + ". Say more of the name.",
        )
    }

    /**
     * How much a shortcut may generate. Smaller than the chat screen's on purpose: a
     * spoken answer that runs for two thousand tokens is not an answer, it is a
     * monologue, and the Assistant cuts it off anyway.
     */
    const val SPOKEN_MAX_TOKENS = 512

    /**
     * "Ask my Mac <text>" — one question, no history. A shortcut has no transcript
     * behind it, and pretending otherwise would attach whatever the phone last talked
     * about to an unrelated question.
     */
    fun chatRequest(
        prompt: String,
        images: List<String> = emptyList(),
        maxTokens: Int = SPOKEN_MAX_TOKENS,
    ): ChatRequest {
        val text = prompt.trim()
        if (text.isEmpty() && images.isEmpty()) throw Refusal.EmptyPrompt
        return ChatRequest(
            messages = listOf(ChatMessageWire("user", text, images)),
            maxTokens = maxTokens,
        )
    }

    /**
     * "Load <model>" — a phrase, against what the Mac actually has installed.
     *
     * Exact id first, then exact name, then the id without its quantization, then a
     * unique prefix, then a unique containment. Each step is narrower than the next,
     * and a step that matches more than one model refuses rather than picking: loading
     * the wrong 27B costs minutes and a gigabyte of memory, and the person is standing
     * there listening.
     */
    fun resolveModel(
        phrase: String,
        installed: List<InstalledModel>,
        scope: DeviceScope,
    ): InstalledModel {
        if (!scope.canControl) throw Refusal.NotAllowed
        val needle = normalise(phrase)
        if (needle.isEmpty()) throw Refusal.NoSuchModel(phrase)

        installed.firstOrNull { normalise(it.id) == needle }?.let { return it }

        fun pick(candidates: List<InstalledModel>): InstalledModel? = when {
            candidates.size == 1 -> candidates[0]
            candidates.size > 1 -> throw Refusal.Ambiguous(phrase, candidates.map { it.name })
            else -> null
        }

        pick(installed.filter { normalise(it.name) == needle })?.let { return it }
        // The bare id without its quantization — "gemma-3-12b-it" for
        // "gemma-3-12b-it@Q4_K_M" — which is how a person says it and how the catalog
        // prints it.
        pick(installed.filter { normalise(it.id.substringBefore('@')) == needle })?.let { return it }
        pick(installed.filter { normalise(it.name).startsWith(needle) })?.let { return it }
        pick(
            installed.filter {
                normalise(it.name).contains(needle) || normalise(it.id).contains(needle)
            },
        )?.let { return it }

        throw Refusal.NoSuchModel(phrase)
    }

    fun loadRequest(
        phrase: String,
        installed: List<InstalledModel>,
        scope: DeviceScope,
    ): LoadRequest =
        // An installed model carries its quantization in its id, so the request needs
        // nothing else: the Mac already knows what it has on disk.
        LoadRequest(modelID = resolveModel(phrase, installed, scope).id)

    /** "What is loaded on my Mac" — one sentence, spoken. */
    fun loadedSummary(status: Status, macName: String?): String {
        val mac = macName ?: "your Mac"
        val name = status.loadedModelName ?: status.loadedModelID
            ?: return "Nothing is loaded on $mac right now."
        val sentence = StringBuilder("$mac has $name loaded")
        status.contextLength?.takeIf { it > 0 }?.let {
            sentence.append(", with a ${contextPhrase(it)} context")
        }
        status.lastGenerationTokensPerSecond?.takeIf { it > 0 }?.let {
            sentence.append(", last answering at ${it.toInt()} tokens a second")
        }
        return "$sentence."
    }

    /** "65,536" spoken as "64K", because that is how anybody says it. */
    fun contextPhrase(tokens: Int): String {
        if (tokens < 1024) return "$tokens token"
        val thousands = tokens / 1024.0
        return if (thousands == kotlin.math.floor(thousands)) {
            "${thousands.toInt()}K"
        } else {
            String.format("%.1fK", thousands)
        }
    }

    /** Case, spacing and punctuation are not how a person distinguishes two models. */
    fun normalise(text: String): String = text.lowercase()
        .replace('_', '-')
        .replace(' ', '-')
        .trim('-', '.', ',', '!', '?', ' ')
}
