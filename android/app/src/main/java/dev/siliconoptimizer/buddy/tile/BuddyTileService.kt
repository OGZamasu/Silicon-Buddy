package dev.siliconoptimizer.buddy.tile

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.siliconoptimizer.buddy.MainActivity
import dev.siliconoptimizer.buddy.ondevice.ModelStore
import dev.siliconoptimizer.buddy.ondevice.OnDeviceEngine
import dev.siliconoptimizer.buddy.ondevice.OnDeviceNotices
import dev.siliconoptimizer.buddy.pairing.TokenStore
import dev.siliconoptimizer.buddy.reach.BuddyLink
import dev.siliconoptimizer.buddy.reach.SnapshotStore
import dev.siliconoptimizer.buddy.transport.ControlClient
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Quick Settings tile: one pull down and a tap to the composer.
 *
 * It starts an activity and nothing else. A tile that loaded a model or cancelled a job
 * would be a destructive action one accidental tap away from a locked phone's shade,
 * which is why the only thing behind it is a screen — and why long-pressing it opens
 * Settings rather than toggling anything.
 */
class BuddyTileService : TileService() {

    /** Lives while the shade is open: the one reachability check a tile gets. */
    private var listening: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        draw()
        // The snapshot is the last thing anyone learned; a quick look at the Mac now makes
        // "Mac unreachable" a fact rather than a memory. /health only, a few seconds at most.
        val config = TokenStore(this).load() ?: return
        listening?.cancel()
        listening = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope ->
            scope.launch {
                // Three answers, not two: yes, no, and "it did not say in time". Tailscale
                // sleeps when the phone does, and the first request after it wakes can take
                // longer than a tile may wait. Recording that as "Mac unreachable" would put
                // the phone's model in front of an owner whose Mac is sitting there awake —
                // so a slow answer changes nothing, and what was known before stands.
                val reachable: Boolean? = withTimeoutOrNull(PROBE_MS) {
                    try {
                        ControlClient(config).health()
                        true
                    } catch (error: TransportError) {
                        when (error) {
                            is TransportError.TimedOut -> null
                            is TransportError.Unreachable, is TransportError.AppNotRunning -> false
                            // It answered, even if it answered with a refusal.
                            else -> true
                        }
                    }
                }
                reachable?.let { SnapshotStore(this@BuddyTileService).noteMacReachable(it) }
                draw()
            }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    private fun draw() {
        val tile = qsTile ?: return
        val paired = TokenStore(this).load() != null
        val snapshots = SnapshotStore(this)
        val snapshot = snapshots.read()

        tile.state = if (paired) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Ask your Mac"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The subtitle is the one place a tile can say something true about the
            // Mac. "Not paired" there is worth more than a tile that looks ready.
            tile.subtitle = subtitle(
                paired, snapshot?.loadedModelName, snapshots.pendingApprovals(),
                macReachable = snapshot?.macReachable, phoneReady = phoneModelReady(),
            )
        }
        tile.updateTile()
    }

    private fun phoneModelReady(): Boolean =
        OnDeviceEngine.couldRun && runCatching { ModelStore(this).installed().isNotEmpty() }.getOrDefault(false)

    override fun onClick() {
        super.onClick()
        // Approvals waiting outrank a question: the tap goes where something is blocked
        // on the owner. Either way it opens a screen and does nothing else — with the Mac
        // out of reach and a model on the phone, the composer opens with the offer showing,
        // and still nothing is answered until the owner taps it.
        val waiting = SnapshotStore(this).pendingApprovals() > 0
        val offerPhone = SnapshotStore(this).read()?.macReachable == false && phoneModelReady()
        val intent = if (waiting) {
            Intent(Intent.ACTION_MAIN)
                .setClassName(packageName, "dev.siliconoptimizer.buddy.MainActivity")
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_AGENTS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(if (offerPhone) BuddyLink.ASK_ON_PHONE_URI else "siliconbuddy://ask"))
                .setClassName(packageName, "dev.siliconoptimizer.buddy.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
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

    companion object {
        /** How long the tile's own look at the Mac may take. */
        const val PROBE_MS = 4_000L

        /**
         * What the tile's second line says. Approvals first: they are what is blocked. Then
         * a Mac that did not answer — with a model on the phone, that the phone can stand in.
         */
        fun subtitle(
            paired: Boolean,
            loaded: String?,
            waitingApprovals: Int,
            macReachable: Boolean? = null,
            phoneReady: Boolean = false,
        ): String = when {
            !paired -> "Not paired"
            waitingApprovals == 1 -> "1 approval waiting"
            waitingApprovals > 1 -> "$waitingApprovals approvals waiting"
            macReachable == false && phoneReady -> OnDeviceNotices.TILE_READY
            macReachable == false -> OnDeviceNotices.MAC_UNREACHABLE
            loaded != null -> loaded
            else -> "Nothing loaded"
        }

        /** Asks the system to redraw the tile, if it is in somebody's shade. */
        fun refresh(context: android.content.Context) {
            runCatching {
                requestListeningState(
                    context,
                    android.content.ComponentName(context, BuddyTileService::class.java),
                )
            }
        }
    }
}
