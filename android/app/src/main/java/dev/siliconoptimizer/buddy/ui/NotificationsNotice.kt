package dev.siliconoptimizer.buddy.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Whether anything this app posts would actually be seen, and how to fix it if not.
 *
 * Android 13 shows the permission dialog twice. After that `launch` does nothing at all and
 * returns denied, silently — which is how the owner ended up running a whole evening of
 * renders and agent approvals with no notification ever arriving, and nothing on screen
 * saying why. So: ask while asking still works, and send them to the right settings page
 * once it does not.
 */
object Notifications {

    private const val FILE = "notifications"
    private const val KEY_ASKS = "asks"

    /** Android stops showing the dialog after the second refusal. */
    const val MAX_ASKS = 2

    /** Not only the permission: notifications turned off for the app count as off too. */
    fun allowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun timesAsked(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_ASKS, 0)

    /** Whether the system would still show the dialog, so the button can say where to go. */
    fun canAsk(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && timesAsked(context) < MAX_ASKS

    fun noteAsked(context: Context) {
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        preferences.edit().putInt(KEY_ASKS, preferences.getInt(KEY_ASKS, 0) + 1).apply()
    }

    /** The app's own notification settings, for when the dialog will not come again. */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The sentence, kept here so every screen says the same one. */
    fun sentence(what: String): String = "Notifications are off, so $what won't reach you."
}

/**
 * One line where an unnoticed notification would matter, with the button that fixes it.
 *
 * Draws nothing when notifications are allowed. [what] finishes the sentence: "so
 * <what> won't reach you."
 */
@Composable
fun NotificationsOffNotice(what: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(Notifications.allowed(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = Notifications.allowed(context)
    }

    // Coming back from the settings page is the moment this changes.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = Notifications.allowed(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    if (allowed) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            Notifications.sentence(what),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            if (Notifications.canAsk(context)) {
                Notifications.noteAsked(context)
                ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Either Android will not show the dialog again, or the owner turned the
                // app's notifications off themselves; both are fixed in the same place.
                runCatching { context.startActivity(Notifications.settingsIntent(context)) }
            }
        }) { Text("Allow") }
    }
}
