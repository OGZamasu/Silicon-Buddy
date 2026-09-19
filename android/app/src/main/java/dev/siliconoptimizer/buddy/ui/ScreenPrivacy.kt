package dev.siliconoptimizer.buddy.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * The screen itself, for the two screens that show something that should not outlive
 * looking at it, or should outlive looking away.
 */

/**
 * No Recents thumbnail of this screen while it is open.
 *
 * Android photographs the top screen when the app goes to the background, and that picture
 * sits in Recents where anybody holding the phone can read it. An agent's transcript and a
 * conversation the phone answered — which by design exists nowhere else — are both things
 * the owner chose to keep on the phone, not to leave on a lock screen.
 */
@Composable
fun NoRecentsScreenshot() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        activity.setRecentsScreenshotEnabled(false)
        onDispose { activity.setRecentsScreenshotEnabled(true) }
    }
}

/**
 * Keeps the screen awake while [active].
 *
 * The phone's own model writes at about seventeen words a second and can take a minute over
 * a long answer, with nothing touching the screen: the display times out, the CPU is thermal-
 * and power-managed down, and the answer the owner is waiting for slows to a crawl or is
 * cancelled outright when the app is judged to have left the foreground. This is exactly what
 * the flag is for, and it is cleared the moment the answer ends.
 */
@Composable
fun KeepScreenOn(active: Boolean) {
    val window = LocalContext.current.findActivity()?.window ?: return
    DisposableEffect(window, active) {
        if (active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
