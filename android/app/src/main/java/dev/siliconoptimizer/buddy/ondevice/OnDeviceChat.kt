package dev.siliconoptimizer.buddy.ondevice

import android.content.Context
import dev.siliconoptimizer.buddy.chat.ChatMessage
import dev.siliconoptimizer.buddy.chat.ConversationStore
import dev.siliconoptimizer.buddy.transport.ChatStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * What the chat needs from the phone's own model, and nothing else — so a test can stand
 * in for it and the chat can be checked without llama.cpp or a device.
 */
interface OnDeviceChat {
    /** Conversations the phone answered itself. They live here and nowhere else. */
    val conversations: ConversationStore

    /** Verified models on this phone. */
    fun installed(): List<InstalledPhoneModel>

    /** Whether this phone could run one at all, answered without loading anything. */
    val couldRun: Boolean

    /** The model the owner prefers, when there is more than one. */
    val preferredID: String?

    /** Everything that has to be true before an answer: temperature, memory, the file. */
    suspend fun preflight(model: InstalledPhoneModel): Preflight

    /** The answer, as the chat reads a Mac's: tokens, then `Finished`, or `Failed`. */
    fun answer(model: InstalledPhoneModel, history: List<ChatMessage>, maxTokens: Int): Flow<ChatStreamEvent>

    /** Stop, while the model is still being read into memory. Does nothing once it is. */
    fun cancelLoading()

    /** Nothing here: no models, no store worth reading, never an offer. */
    class None(directory: File) : OnDeviceChat {
        override val conversations = ConversationStore(directory)
        override fun installed(): List<InstalledPhoneModel> = emptyList()
        override val couldRun: Boolean = false
        override val preferredID: String? = null
        override suspend fun preflight(model: InstalledPhoneModel): Preflight =
            Preflight.Refused(dev.siliconoptimizer.buddy.llama.LlamaRuntime.NOT_AVAILABLE)
        override fun answer(model: InstalledPhoneModel, history: List<ChatMessage>, maxTokens: Int): Flow<ChatStreamEvent> =
            flow { emit(ChatStreamEvent.Failed(dev.siliconoptimizer.buddy.llama.LlamaRuntime.NOT_AVAILABLE)) }
        override fun cancelLoading() = Unit
    }

    companion object {
        /** Where on-device conversations are kept: out of backups, like the models. */
        fun conversationFolder(context: Context): File = File(context.noBackupFilesDir, "ondevice")
    }
}

/** The real one: the engine, the model store and the phone's own conversation file. */
class AndroidOnDeviceChat(private val context: Context) : OnDeviceChat {
    private val engine: OnDeviceEngine get() = OnDeviceEngine.get(context)
    private val settings = OnDeviceSettings(context)

    override val conversations = ConversationStore(OnDeviceChat.conversationFolder(context))

    override fun installed(): List<InstalledPhoneModel> = ModelStore(context).installed()

    override val couldRun: Boolean get() = OnDeviceEngine.couldRun

    override val preferredID: String? get() = settings.preferredModelID

    override suspend fun preflight(model: InstalledPhoneModel): Preflight = engine.preflight(model)

    override fun answer(model: InstalledPhoneModel, history: List<ChatMessage>, maxTokens: Int) =
        engine.answer(model, history, maxTokens)

    override fun cancelLoading() = engine.cancelLoading()
}

/** The few choices the owner makes about the phone's model. Ordinary preferences: none is secret. */
class OnDeviceSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Which installed model answers, when there is more than one. */
    var preferredModelID: String?
        get() = preferences.getString(KEY_PREFERRED, null)
        set(value) {
            preferences.edit().apply { if (value == null) remove(KEY_PREFERRED) else putString(KEY_PREFERRED, value) }.apply()
        }

    companion object {
        const val FILE = "ondevice"
        const val KEY_PREFERRED = "preferredModel"
    }
}
