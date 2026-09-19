package dev.siliconoptimizer.buddy.pairing

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.siliconoptimizer.buddy.transport.DeviceScope
import dev.siliconoptimizer.buddy.transport.ServerConfig

/**
 * The paired Mac, stored on the device.
 *
 * The host and port are ordinary preferences; the token is a credential, so everything
 * goes in an [EncryptedSharedPreferences] file keyed by the hardware-backed keystore —
 * and the whole file is excluded from backups (see `xml/data_extraction_rules.xml`).
 *
 * If the keystore is unavailable — a rooted or broken device, a restored backup whose
 * key is gone — the app must still start, so the fallback is plain preferences with the
 * token dropped rather than a crash on launch.
 */
class TokenStore(context: Context) {

    /// Whether the token can be kept at all on this device.
    private var secure = true

    private val preferences: SharedPreferences = try {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (error: Exception) {
        // The keystore is gone — a rooted device, a restored backup, a broken vendor
        // implementation. The app still opens, and it still remembers which Mac it was
        // talking to, but the token stays in memory only: writing a bearer for the
        // owner's Mac into plain preferences would be worse than asking them to pair
        // again.
        secure = false
        context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
    }

    /// True when the token can be stored. When false, pairing survives only until the
    /// app is closed, and the UI says so.
    val isSecure: Boolean get() = secure

    fun save(config: ServerConfig) {
        val editor = preferences.edit()
        for ((key, value) in writableFields(config, secure)) {
            when (value) {
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
                null -> editor.remove(key)
            }
        }
        if (!secure) editor.remove(KEY_TOKEN)
        editor.apply()
    }

    fun load(): ServerConfig? {
        if (!secure) return null
        val host = preferences.getString(KEY_HOST, null) ?: return null
        val port = preferences.getInt(KEY_PORT, 0).takeIf { it > 0 } ?: return null
        val token = preferences.getString(KEY_TOKEN, null) ?: return null
        return ServerConfig(
            host = host,
            port = port,
            token = token,
            macName = preferences.getString(KEY_MAC_NAME, null),
            deviceID = preferences.getString(KEY_DEVICE_ID, null),
            scope = DeviceScope.from(preferences.getString(KEY_SCOPE, null)),
        )
    }

    fun noteMacName(name: String) {
        preferences.edit().putString(KEY_MAC_NAME, name).apply()
    }

    fun forget() {
        preferences.edit().clear().apply()
    }

    companion object {
        /**
         * What goes on disk. When the keystore is unavailable the token is not among
         * it — writing a bearer for the owner's Mac into plain preferences would be
         * worse than asking them to pair again — and the address is still remembered so
         * the form comes back filled in.
         */
        fun writableFields(config: ServerConfig, secure: Boolean): Map<String, Any?> = buildMap {
            put(KEY_HOST, config.host)
            put(KEY_PORT, config.port)
            put(KEY_MAC_NAME, config.macName)
            put(KEY_DEVICE_ID, config.deviceID)
            put(KEY_SCOPE, config.scope.name.lowercase())
            if (secure) put(KEY_TOKEN, config.token)
        }

        const val FILE = "buddy-secure"
        const val FALLBACK_FILE = "buddy-plain"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_TOKEN = "token"
        const val KEY_MAC_NAME = "macName"
        const val KEY_DEVICE_ID = "deviceID"
        const val KEY_SCOPE = "scope"
    }
}
