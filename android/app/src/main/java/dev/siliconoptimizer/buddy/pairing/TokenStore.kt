package dev.siliconoptimizer.buddy.pairing

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
        // A readable-but-useless store beats an app that cannot open.
        context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
    }

    val isEncrypted: Boolean = preferences is EncryptedSharedPreferences

    fun save(config: ServerConfig) {
        preferences.edit()
            .putString(KEY_HOST, config.host)
            .putInt(KEY_PORT, config.port)
            .putString(KEY_TOKEN, config.token)
            .putString(KEY_MAC_NAME, config.macName)
            .putString(KEY_DEVICE_ID, config.deviceID)
            .apply()
    }

    fun load(): ServerConfig? {
        val host = preferences.getString(KEY_HOST, null) ?: return null
        val port = preferences.getInt(KEY_PORT, 0).takeIf { it > 0 } ?: return null
        val token = preferences.getString(KEY_TOKEN, null) ?: return null
        return ServerConfig(
            host = host,
            port = port,
            token = token,
            macName = preferences.getString(KEY_MAC_NAME, null),
            deviceID = preferences.getString(KEY_DEVICE_ID, null),
        )
    }

    fun noteMacName(name: String) {
        preferences.edit().putString(KEY_MAC_NAME, name).apply()
    }

    fun forget() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val FILE = "buddy-secure"
        const val FALLBACK_FILE = "buddy-plain"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_TOKEN = "token"
        const val KEY_MAC_NAME = "macName"
        const val KEY_DEVICE_ID = "deviceID"
    }
}
