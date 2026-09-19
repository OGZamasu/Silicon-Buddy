package dev.siliconoptimizer.buddy.reach

import android.content.Context
import dev.siliconoptimizer.buddy.transport.Status
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The last thing worth showing without asking the Mac again.
 *
 * A widget gets a few seconds and a small budget; a Quick Settings tile gets less. So
 * the app leaves this behind every time it learns something, and the widget draws it
 * immediately and refreshes afterwards. Nothing here is a secret — the model's name,
 * the last question and the last answer — so it lives in ordinary preferences rather
 * than the encrypted file the token is in.
 */
@Serializable
data class BuddySnapshot(
    val macName: String? = null,
    val state: String = "Unknown",
    val loadedModelName: String? = null,
    val loadedModelID: String? = null,
    val lastQuestion: String? = null,
    val lastAnswer: String? = null,
    val updatedAt: Long = 0L,
) {
    /** What the widget puts on its one line when nothing is loaded. */
    val modelLine: String get() = loadedModelName ?: "Nothing loaded"

    fun answerPreview(limit: Int): String? = lastAnswer?.let { trim(it, limit) }

    companion object {
        /**
         * An answer cut to what a widget can show. On a word boundary rather than
         * mid-syllable: the difference between a summary and a glitch.
         */
        fun trim(text: String, limit: Int): String {
            val flattened = text.replace("\n", " ").replace("  ", " ").trim()
            if (flattened.length <= limit) return flattened
            val cut = flattened.take(limit)
            val space = cut.lastIndexOf(' ')
            return if (space > 0) cut.take(space) + "…" else "$cut…"
        }
    }
}

/**
 * Where the snapshot lives.
 *
 * Every part of this app is the same process — a Glance widget, a tile and a share
 * activity all run inside it — so this is plain `SharedPreferences` rather than the
 * cross-process machinery iOS needs for the same job.
 */
class SnapshotStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(): BuddySnapshot? = preferences.getString(KEY, null)
        ?.let { runCatching { json.decodeFromString<BuddySnapshot>(it) }.getOrNull() }

    fun write(snapshot: BuddySnapshot) {
        preferences.edit().putString(KEY, json.encodeToString(BuddySnapshot.serializer(), snapshot)).apply()
    }

    /** Records what the Mac says it is running, keeping the last answer already there. */
    fun note(status: Status, macName: String?) {
        val current = read() ?: BuddySnapshot()
        write(
            current.copy(
                state = status.state,
                loadedModelID = status.loadedModelID,
                loadedModelName = status.loadedModelName,
                macName = macName ?: current.macName,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Records an exchange. Called when a reply finishes, wherever it finished — the
     * chat screen, the share sheet, a widget's button — so "the last answer" means the
     * last one rather than the last one from the app.
     */
    fun note(question: String, answer: String) {
        val current = read() ?: BuddySnapshot()
        write(
            current.copy(
                lastQuestion = BuddySnapshot.trim(question, 240),
                lastAnswer = answer,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** A widget showing the last Mac's model after a re-pair is the wrong machine. */
    fun clear() {
        preferences.edit().remove(KEY).apply()
    }

    /** The preset question the widget's button asks. */
    var quickPrompt: String
        get() = preferences.getString(KEY_PROMPT, null) ?: QuickPrompt.DEFAULT
        set(value) { preferences.edit().putString(KEY_PROMPT, value).apply() }

    /**
     * The last answer the widget's own button produced. Separate from the snapshot's
     * `lastAnswer`, which is whatever was said last anywhere: a widget that replaced
     * its own answer with one from a chat in the app would look like it had changed
     * its mind.
     */
    var quickAnswer: String?
        get() = preferences.getString(KEY_ANSWER, null)
        set(value) {
            preferences.edit().apply {
                if (value == null) remove(KEY_ANSWER) else putString(KEY_ANSWER, value)
            }.apply()
        }

    /** Whether an answer is read out loud after a spoken question. */
    var speaksReplies: Boolean
        get() = preferences.getBoolean(KEY_SPEAKS, true)
        set(value) { preferences.edit().putBoolean(KEY_SPEAKS, value).apply() }

    companion object {
        const val FILE = "buddy-snapshot"
        const val KEY = "snapshot"
        const val KEY_PROMPT = "quickPrompt"
        const val KEY_ANSWER = "quickAnswer"
        const val KEY_SPEAKS = "speaksReplies"
    }
}

/** The preset question a widget can fire without opening the app. */
object QuickPrompt {
    const val DEFAULT = "What should I do next?"

    /**
     * The handful offered in Settings. Short, because the answer has to fit in a
     * widget, and useful without any context — a widget has no transcript behind it.
     */
    val presets = listOf(
        "What should I do next?",
        "Summarise my day",
        "Give me one idea",
        "Explain what you are loaded for",
        "What is on your mind?",
    )
}
