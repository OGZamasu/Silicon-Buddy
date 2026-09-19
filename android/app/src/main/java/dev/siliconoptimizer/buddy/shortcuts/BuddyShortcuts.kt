package dev.siliconoptimizer.buddy.shortcuts

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.siliconoptimizer.buddy.R
import dev.siliconoptimizer.buddy.reach.SnapshotStore

/**
 * The long-press menu on the launcher icon, and what the Assistant can be taught.
 *
 * Two are static — "Ask my Mac" and "Camera" — because they mean the same thing on
 * every phone whether or not one is paired. The third is dynamic: it names the model
 * the Mac actually has loaded, which is what makes "what is loaded on my Mac" worth
 * asking from here rather than opening the app.
 */
object BuddyShortcuts {

    const val ID_ASK = "ask"
    const val ID_LOADED = "loaded"

    fun refresh(context: Context) {
        val loaded = SnapshotStore(context).read()?.loadedModelName
        val shortcuts = mutableListOf(
            ShortcutInfoCompat.Builder(context, ID_ASK)
                .setShortLabel("Ask my Mac")
                .setLongLabel("Ask my Mac a question")
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_launcher_foreground))
                .setIntent(
                    Intent(Intent.ACTION_VIEW, Uri.parse("siliconbuddy://ask"))
                        .setClassName(context, "dev.siliconoptimizer.buddy.MainActivity"),
                )
                .build(),
        )
        if (loaded != null) {
            shortcuts += ShortcutInfoCompat.Builder(context, ID_LOADED)
                .setShortLabel(loaded)
                .setLongLabel("Ask $loaded")
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_launcher_foreground))
                .setIntent(
                    Intent(Intent.ACTION_VIEW, Uri.parse("siliconbuddy://ask"))
                        .setClassName(context, "dev.siliconoptimizer.buddy.MainActivity"),
                )
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }
}
