package dev.siliconoptimizer.buddy.share

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.siliconoptimizer.buddy.chat.attachmentFrom
import dev.siliconoptimizer.buddy.pairing.TokenStore
import dev.siliconoptimizer.buddy.reach.BuddySnapshot
import dev.siliconoptimizer.buddy.reach.OneShotAsk
import dev.siliconoptimizer.buddy.reach.SharePayload
import dev.siliconoptimizer.buddy.reach.ShareNormaliser
import dev.siliconoptimizer.buddy.reach.SharedDraft
import dev.siliconoptimizer.buddy.reach.SnapshotStore
import dev.siliconoptimizer.buddy.transport.ControlClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SharePhase {
    object Reading : SharePhase
    object Ready : SharePhase
    object Sending : SharePhase
    data class Answered(val text: String) : SharePhase
    data class Failed(val problem: String) : SharePhase
}

/** The share target's state: what arrived, what will be asked, and what came back. */
class ShareViewModel(application: Application) : AndroidViewModel(application) {

    var phase by mutableStateOf<SharePhase>(SharePhase.Reading)
        private set
    var draft by mutableStateOf(SharedDraft("", "", emptyList(), ""))
        private set
    var prompt by mutableStateOf("")
    var streamed by mutableStateOf("")
        private set
    var storedOnMac by mutableStateOf(false)
        private set
    var isPaired by mutableStateOf(false)
        private set

    private val snapshots = SnapshotStore(application)

    val isBusy: Boolean
        get() = phase is SharePhase.Sending || phase is SharePhase.Answered

    val canSend: Boolean
        get() = isPaired && phase == SharePhase.Ready && prompt.isNotBlank()

    private var started = false

    fun start(arrived: SharedIntent) {
        if (started) return
        started = true
        isPaired = TokenStore(getApplication()).load() != null
        viewModelScope.launch {
            // Decoding several photographs is not main-thread work, and a share sheet
            // that hangs for a second before it draws looks like it failed to open.
            val payload = withContext(Dispatchers.Default) { read(arrived) }
            draft = ShareNormaliser.draft(payload)
            prompt = draft.prompt
            phase = if (draft.isEmpty) {
                SharePhase.Failed("There was nothing in that to send.")
            } else {
                SharePhase.Ready
            }
        }
    }

    private fun read(arrived: SharedIntent): SharePayload {
        val items = mutableListOf<SharePayload.Item>()
        for (uri in arrived.streams) {
            // Only pictures. A share target that read whatever file URI it was handed
            // and posted the bytes to a Mac would be a file exfiltration tool with a
            // friendly name, so this goes through the image decoder and keeps only
            // what comes out of it.
            attachmentFrom(getApplication(), uri)?.let { items += SharePayload.Item.Picture(it) }
        }
        arrived.text?.let { text ->
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) {
                if (looksLikeALink(trimmed)) {
                    items += SharePayload.Item.Link(trimmed)
                } else {
                    items += SharePayload.Item.Text(trimmed)
                }
            }
        }
        // The subject is the page's title in most browsers, which is worth more to a
        // model than it costs — but only when it is not the text all over again.
        arrived.subject?.trim()?.takeIf {
            it.isNotEmpty() && it != arrived.text?.trim()
        }?.let { items += SharePayload.Item.Text(it) }
        return SharePayload(items)
    }

    /** Enough to tell a pasted URL from a paragraph. Not a validator. */
    private fun looksLikeALink(text: String): Boolean =
        !text.contains(' ') && !text.contains('\n') &&
            (text.startsWith("http://") || text.startsWith("https://"))

    fun send() {
        if (!canSend) return
        val outgoing = draft.copy(prompt = prompt.trim())
        streamed = ""
        phase = SharePhase.Sending
        val transport = TokenStore(getApplication()).load()?.let { ControlClient(it) }
        viewModelScope.launch {
            try {
                val outcome = OneShotAsk.send(
                    message = outgoing.message,
                    images = outgoing.images,
                    // Titled so the Mac's own conversation list says where it came
                    // from, rather than showing an untitled thread nobody recognises.
                    title = "Shared: " + BuddySnapshot.trim(outgoing.prompt, 40),
                    transport = transport,
                    snapshots = snapshots,
                    onToken = { streamed += it },
                )
                storedOnMac = outcome.storedOnMac
                phase = SharePhase.Answered(outcome.answer)
            } catch (error: Exception) {
                phase = SharePhase.Failed(error.message ?: "Your Mac didn't answer.")
            }
        }
    }
}
