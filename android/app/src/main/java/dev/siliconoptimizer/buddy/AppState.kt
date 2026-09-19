package dev.siliconoptimizer.buddy

import android.app.Application
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.siliconoptimizer.buddy.pairing.PairingInvite
import dev.siliconoptimizer.buddy.pairing.TokenStore
import dev.siliconoptimizer.buddy.reach.SnapshotStore
import dev.siliconoptimizer.buddy.widget.BuddyWidget
import androidx.glance.appwidget.updateAll
import dev.siliconoptimizer.buddy.transport.ConnectivityProbe
import dev.siliconoptimizer.buddy.transport.DeviceScope
import dev.siliconoptimizer.buddy.transport.TailnetHost
import dev.siliconoptimizer.buddy.transport.TransportError
import dev.siliconoptimizer.buddy.transport.ControlClient
import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.Reachability
import dev.siliconoptimizer.buddy.transport.ServerConfig
import dev.siliconoptimizer.buddy.transport.Status
import kotlinx.coroutines.launch

/**
 * The one piece of state every screen needs: which Mac we are talking to, whether it is
 * answering, and which of the newer routes it turned out to have.
 */
class AppState(application: Application) : AndroidViewModel(application) {

    private val tokens = TokenStore(application)
    private val snapshots = SnapshotStore(application)

    var config by mutableStateOf<ServerConfig?>(tokens.load())
        private set
    var reachability by mutableStateOf<Reachability>(Reachability.Unknown)
        private set
    var status by mutableStateOf<Status?>(null)
        private set

    /**
     * Bumped whenever the Mac changes. Screens watch it and throw away what they were
     * showing: a dashboard still displaying the last Mac's memory after a re-pair is
     * not a stale reading, it is the wrong machine.
     */
    var connectionGeneration by mutableStateOf(0)
        private set

    /**
     * An invite from a QR or a link that has not been agreed to yet. Nothing is dialled
     * and nothing is stored until the person says yes.
     */
    var pendingInvite by mutableStateOf<PairingInvite?>(null)

    val isPaired: Boolean get() = config != null

    /** Whether this device may change what the Mac is running. */
    val canControl: Boolean get() = config?.canControl ?: false

    val scope: DeviceScope get() = config?.scope ?: DeviceScope.Full

    /**
     * False when the keystore is unavailable, in which case the token lives only as
     * long as the process and the person has to be told.
     */
    val canStoreTokenSecurely: Boolean get() = tokens.isSecure

    /** The client for the paired Mac, or null when there is none. */
    val transport: ControlTransport?
        get() = config?.let { ControlClient(it) }

    val macDisplayName: String
        get() = config?.macName ?: config?.host ?: "No Mac"

    val tokenIsEncrypted: Boolean get() = tokens.isSecure

    // MARK: - Pairing

    /**
     * Trades a scanned invite for a per-device token. Throws
     * `TransportError.RouteUnavailable` on a Mac that has not shipped `/buddy/pair`, so
     * the caller can offer the advanced form instead.
     */
    suspend fun pair(invite: PairingInvite) {
        if (!TailnetHost.isAllowed(invite.host)) {
            throw TransportError.Forbidden(TailnetHost.EXPLANATION)
        }
        val probe = ControlClient(ServerConfig(invite.host, invite.port, token = ""))
        val paired = probe.pair(invite.code, deviceName, platform)
        connect(
            ServerConfig(
                host = invite.host,
                port = paired.port,
                token = paired.token,
                macName = paired.macName,
                deviceID = paired.deviceID,
                scope = DeviceScope.from(paired.scope),
            ),
        )
        refreshReachability()
    }

    /** The advanced form: host, port and the token from the Mac's control.json. */
    fun connect(newConfig: ServerConfig) {
        if (!TailnetHost.isAllowed(newConfig.host)) {
            throw TransportError.Forbidden(TailnetHost.EXPLANATION)
        }
        tokens.save(newConfig)
        config = newConfig
        // A widget showing the last Mac's model after a re-pair would be showing the
        // wrong machine, so the snapshot goes with the pairing.
        snapshots.clear()
        status = null
        reachability = Reachability.Unknown
        pendingInvite = null
        connectionGeneration++
    }

    fun forget() {
        tokens.forget()
        snapshots.clear()
        // And redraw, so "forget this Mac" is true on the home screen too rather than
        // at the widget's next scheduled refresh half an hour later.
        viewModelScope.launch {
            runCatching { BuddyWidget().updateAll(getApplication()) }
        }
        config = null
        status = null
        reachability = Reachability.Unknown
        connectionGeneration++
    }

    fun noteMacName(name: String) {
        val current = config ?: return
        if (current.macName == name) return
        tokens.noteMacName(name)
        config = current.copy(macName = name)
    }

    // MARK: - Connection state

    fun refreshReachability() {
        val client = transport ?: run {
            reachability = Reachability.Unknown
            return
        }
        viewModelScope.launch {
            reachability = Reachability.Checking
            val result = ConnectivityProbe(client).check()
            reachability = result
            if (result.isReady) {
                status = runCatching { client.status() }.getOrNull()
                status?.let { snapshots.note(it, config?.macName) }
            }
        }
    }

    companion object {
        /** What this device calls itself when it asks the Mac to pair. */
        val deviceName: String
            get() = listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL)
                .joinToString(" ")
                .ifBlank { "Android device" }

        const val platform = "android"
    }
}
