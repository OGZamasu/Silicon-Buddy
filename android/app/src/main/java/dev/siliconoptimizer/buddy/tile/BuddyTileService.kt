package dev.siliconoptimizer.buddy.tile

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.siliconoptimizer.buddy.pairing.TokenStore
import dev.siliconoptimizer.buddy.reach.SnapshotStore

/**
 * The Quick Settings tile: one pull down and a tap to the composer.
 *
 * It starts an activity and nothing else. A tile that loaded a model or cancelled a job
 * would be a destructive action one accidental tap away from a locked phone's shade,
 * which is why the only thing behind it is a screen — and why long-pressing it opens
 * Settings rather than toggling anything.
 */
class BuddyTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val paired = TokenStore(this).load() != null
        val loaded = SnapshotStore(this).read()?.loadedModelName

        tile.state = if (paired) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Ask your Mac"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The subtitle is the one place a tile can say something true about the
            // Mac. "Not paired" there is worth more than a tile that looks ready.
            tile.subtitle = when {
                !paired -> "Not paired"
                loaded != null -> loaded
                else -> "Nothing loaded"
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("siliconbuddy://ask"))
            .setClassName(packageName, "dev.siliconoptimizer.buddy.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // On Android 14 and later a tile must hand the system a PendingIntent rather
        // than start the activity itself; the older call is deprecated and, from a
        // locked shade, simply does nothing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
