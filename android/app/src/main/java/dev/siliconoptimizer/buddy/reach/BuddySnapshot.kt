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
    /**
     * The phone model's name when the last answer came from the phone rather than the Mac,
     * so a widget can label it as the chat does.
     */
    val lastAnswerOnPhone: String? = null,
    /**
     * Whether the Mac answered the last time anything here asked — the app, the widget or
     * the tile — and when. Null until something has asked.
     */
    val macReachable: Boolean? = null,
    val macCheckedAt: Long = 0L,
) {
    /** What the widget puts on its one line when nothing is loaded. */
    val modelLine: String get() = headline(loadedModelName)

    fun answerPreview(limit: Int): String? = lastAnswer?.let { trim(it, limit) }

    companion object {
        /**
         * A model name that fits on one line of a small widget.
         *
         * Names carry a parenthesised qualifier — "Qwen3 4B (MLX)", "Gemma 3 12B (it)"
         * — and a widget two cells wide truncates mid-bracket to "Qwen3 4B (…", which
         * reads like the name itself is broken. The qualifier is the least load-bearing
         * part, so it is what goes first, and only when keeping it would not have
         * fitted anyway.
         */
        fun headline(name: String?, limit: Int = 18): String {
            if (name.isNullOrEmpty()) return "Nothing loaded"
            if (name.length <= limit) return name
            val bracket = name.lastIndexOf('(')
            if (bracket <= 0) return name
            return name.take(bracket).trim().ifEmpty { name }
        }

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
    fun note(question: String, answer: String, answeredOnPhone: String? = null) {
        val current = read() ?: BuddySnapshot()
        write(
            current.copy(
                lastQuestion = BuddySnapshot.trim(question, 240),
                lastAnswer = answer,
                lastAnswerOnPhone = answeredOnPhone,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Records whether the Mac answered just now. The tile and the widget read it to say
     * "Mac unreachable" — and, with a model on the phone, to offer the phone.
     */
    fun noteMacReachable(reachable: Boolean, at: Long = System.currentTimeMillis()) {
        val current = read() ?: BuddySnapshot()
        if (current.macReachable == reachable && at - current.macCheckedAt < 5_000) return
        write(current.copy(macReachable = reachable, macCheckedAt = at))
    }

    /**
     * Forgets everything about a Mac, the widget's own answer included.
     *
     * "Forget this Mac" has to mean it. The snapshot alone is not the whole of what
     * this Mac left behind: the widget's preset answer is a reply from that Mac sitting
     * on the home screen, and the preset question is a setting about it. A re-pair that
     * left either in place would show the last Mac's words under the new one's name.
     */
    fun clear() {
        preferences.edit()
            .remove(KEY)
            .remove(KEY_ANSWER)
            .remove(KEY_PROMPT)
            .remove(KEY_APPROVALS)
            .remove(KEY_APPROVALS_AT)
            .apply()
    }

    /**
     * How many agent approvals were waiting when this phone last heard, for the Quick
     * Settings tile. Written whenever the count changes while the app or its watcher is
     * listening.
     */
    fun notePendingApprovals(count: Int, at: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putInt(KEY_APPROVALS, count)
            .putLong(KEY_APPROVALS_AT, at)
            .apply()
    }

    /**
     * The count, while it is fresh. A number from an hour ago is a claim about a Mac this
     * phone has not heard from since — the owner may well have answered at the desk — so
     * past [APPROVALS_FRESH_MS] the tile says nothing about approvals rather than
     * something stale.
     */
    fun pendingApprovals(now: Long = System.currentTimeMillis()): Int =
        pendingApprovals(
            preferences.getInt(KEY_APPROVALS, 0),
            preferences.getLong(KEY_APPROVALS_AT, 0L),
            now,
        )

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
        const val KEY_APPROVALS = "pendingApprovals"
        const val KEY_APPROVALS_AT = "pendingApprovalsAt"

        /** How long a count of waiting approvals is worth repeating. */
        const val APPROVALS_FRESH_MS = 15 * 60 * 1000L

        fun pendingApprovals(count: Int, at: Long, now: Long): Int =
            if (count > 0 && now - at in 0..APPROVALS_FRESH_MS) count else 0
    }
}

/** The preset question a widget can fire without opening the app. */
object QuickPrompt {
    const val DEFAULT = "What should I do next?"

    /**
     * How long a widget may wait.
     *
     * Well under the Glance callback's own budget, and nothing like the transport's own
     * minutes: a widget whose worker is killed for overrunning leaves the last content
     * on screen, which looks like a button that does nothing. Twenty seconds, then a
     * sentence saying the Mac was slow.
     */
    const val TIMEOUT_MS = 20_000L

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
