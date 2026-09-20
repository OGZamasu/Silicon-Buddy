package dev.siliconoptimizer.buddy.ondevice

import android.content.Context
import android.content.Intent
import android.os.Build
import dev.siliconoptimizer.buddy.transport.PhoneModel
import dev.siliconoptimizer.buddy.ui.Format

/**
 * Making room for the phone's model, honestly.
 *
 * The owner's own words, holding an S24 with 2.58 GB free: "perhaps we can add a task
 * management thing to help us free up memory to run it." This is that — with one thing
 * said plainly on it, because the alternative is a button that lies: **this app cannot
 * close other apps**. Android has not allowed it since 14, and could not be trusted to
 * before. What it can do is let go of everything it is holding itself, take the owner to
 * the phone's own memory tool, and show the number move while they work.
 */
object MakeRoom {

    /**
     * A screen on this phone that frees memory, in the order worth trying.
     *
     * As data rather than as `Intent`s so the choosing can be tested: on a JVM an `Intent`
     * is a stub that remembers nothing. The action strings are the platform's own, written
     * out for the same reason.
     */
    enum class Tool(val action: String?, val packageName: String?, val className: String?, val label: String) {
        /**
         * Samsung's Device care → Memory, which is where an S24's owner already goes to
         * close things. It is an activity of theirs, so it is tried by name.
         */
        SamsungDeviceCare(
            action = "android.intent.action.MAIN",
            packageName = "com.samsung.android.lool",
            className = "com.samsung.android.sm.ui.ram.RamActivity",
            label = "Open Device care",
        ),

        /** Samsung again, one screen out, on builds where the memory screen has moved. */
        SamsungSmartManager(
            action = "com.samsung.android.sm.ACTION_RAM",
            packageName = "com.samsung.android.lool",
            className = null,
            label = "Open Device care",
        ),

        /** Every Android: the app list, from which anything can be stopped. */
        AndroidApps(
            action = "android.settings.APPLICATION_SETTINGS",
            packageName = null,
            className = null,
            label = "Open app settings",
        ),

        /** And the settings app itself, if even that is refused. */
        AndroidSettings(
            action = "android.settings.SETTINGS",
            packageName = null,
            className = null,
            label = "Open settings",
        ),
    }

    /**
     * The first tool this phone will actually open, or null when none of them resolves —
     * which happens, and is not a crash: the button simply is not offered.
     */
    fun tool(canOpen: (Tool) -> Boolean): Tool? = Tool.entries.firstOrNull(canOpen)

    /**
     * Whether [tool] resolves on this phone.
     *
     * Through the package manager, not `Intent.resolveActivity`: that one hands an
     * explicit component straight back without checking anything exists behind it, so the
     * Samsung button appeared on every phone and did nothing at all when tapped.
     */
    fun canOpen(context: Context, tool: Tool): Boolean = runCatching {
        context.packageManager.resolveActivity(intent(tool), 0) != null
    }.getOrDefault(false)

    fun intent(tool: Tool): Intent = Intent(tool.action).apply {
        if (tool.packageName != null && tool.className != null) {
            setClassName(tool.packageName, tool.className)
        } else if (tool.packageName != null) {
            setPackage(tool.packageName)
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Why there is no "close them for you" button.
     *
     * Said on the sheet, not in a help page: an owner who is looking for that button
     * deserves to know it does not exist rather than to hunt for it.
     */
    val CANNOT_CLOSE_OTHERS: String =
        if (Build.VERSION.SDK_INT >= 34) {
            "Android doesn't let one app close another, so this one can't do it for you — " +
                "it can only let go of what it is holding itself."
        } else {
            "This app can't close other apps for you; it can only let go of what it is " +
                "holding itself."
        }

    /** How to see what is open, since no app may open that screen either. */
    const val HOW_TO_CLOSE =
        "Swipe up from the bottom and hold to see your open apps, then swipe each one away."

    // MARK: - The numbers on the sheet

    /** "2.58 GB → 4.10 GB free", or just the one number before anything has changed. */
    fun change(startedWith: Long, nowFree: Long): String =
        if (nowFree == startedWith) {
            "${Format.bytes(nowFree)} free"
        } else {
            "${Format.bytes(startedWith)} → ${Format.bytes(nowFree)} free"
        }

    /**
     * What the two figures are, and which one decides.
     *
     * The floor does not move with the context — see [ResourceGuard.residentFloor] — so
     * only the second number changes when a shorter context is chosen. [measuredResident]
     * is what this phone recorded for itself, when it has.
     */
    fun needs(model: PhoneModel, contextTokens: Int? = null, measuredResident: Long? = null): String {
        val context = contextTokens ?: model.recommended.contextLength
        val floor = ResourceGuard.residentFloor(model, measuredResident)
        val best = ResourceGuard.neededAt(model, context)
        return if (floor == null) {
            "${model.label} needs ${Format.bytes(best)} free."
        } else {
            "${model.label} needs ${Format.bytes(floor)} free to run at all — that is the one that " +
                "decides — and ${Format.bytes(best)} to run at its best."
        }
    }

    /** Whether the phone is now above the floor, which is what "Try again" waits for. */
    fun isEnough(model: PhoneModel, freeBytes: Long, contextTokens: Int? = null, measuredResident: Long? = null): Boolean {
        val context = contextTokens ?: model.recommended.contextLength
        val floor = ResourceGuard.residentFloor(model, measuredResident)
            ?: ResourceGuard.neededAt(model, context)
        return freeBytes >= floor
    }

    /** What this app got back by letting go of its own, or null when it was holding nothing. */
    fun recovered(before: Long, after: Long, unloaded: Boolean): String? {
        val freed = after - before
        return when {
            !unloaded && freed <= 0 -> null
            !unloaded -> "Let go of ${Format.bytes(freed)} of this app's own caches."
            freed <= 0 -> "Let go of the model on this phone. The phone has not counted it back yet."
            else -> "Let go of the model on this phone and this app's caches: ${Format.bytes(freed)}."
        }
    }

    /**
     * The shorter context on offer when memory is tight.
     *
     * With a number only when this phone has measured both: what a shorter context saves
     * is not something the Mac's two fields can say — Qwen3.5-2B keeps a recurrent state
     * that does not grow with the context at all — and a figure invented here would be a
     * promise the phone cannot keep. [saving] is the difference between what this phone
     * recorded at the two contexts, or 0 when it has not recorded both.
     */
    fun shorterContext(model: PhoneModel, saving: Long = 0): String? {
        val full = model.recommended.contextLength
        if (full <= ResourceGuard.SMALL_CONTEXT) return null
        val how = if (saving > 0) {
            "${Format.bytes(saving)} less memory on this phone"
        } else {
            "less memory — how much, this phone will know once it has run it both ways"
        }
        return "Remember less of the conversation (${ResourceGuard.SMALL_CONTEXT} words' worth instead of " +
            "$full): $how, and older messages are left out sooner."
    }
}
