package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.transport.CatalogModel
import dev.siliconoptimizer.buddy.transport.ChatMessageWire
import dev.siliconoptimizer.buddy.transport.ChatRequest
import dev.siliconoptimizer.buddy.transport.ChatResponse
import dev.siliconoptimizer.buddy.transport.ChatStreamEvent
import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.ConversationDetail
import dev.siliconoptimizer.buddy.transport.ConversationSummary
import dev.siliconoptimizer.buddy.transport.Health
import dev.siliconoptimizer.buddy.transport.ImageModel
import dev.siliconoptimizer.buddy.transport.InstalledModel
import dev.siliconoptimizer.buddy.transport.LoadRequest
import dev.siliconoptimizer.buddy.transport.Metrics
import dev.siliconoptimizer.buddy.transport.NodeAdvertisement
import dev.siliconoptimizer.buddy.transport.PairResponse
import dev.siliconoptimizer.buddy.transport.Profile
import dev.siliconoptimizer.buddy.transport.Status
import dev.siliconoptimizer.buddy.transport.SwarmView
import dev.siliconoptimizer.buddy.transport.VideoModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A Mac that is awake, reachable, and never finishes thinking.
 *
 * Not the same as a Mac that is down, and the difference is the whole point: a request
 * that hangs is the case the widget's own deadline exists for, and nothing that talks
 * to a socket can produce it on demand.
 */
open class HangingTransport : ControlTransport {

    private suspend fun hang(): Nothing = awaitCancellation()

    override suspend fun health(): Health = hang()
    override suspend fun status(): Status = hang()
    override suspend fun profile(): Profile = hang()
    override suspend fun metrics(): Metrics = hang()
    override suspend fun installed(): List<InstalledModel> = hang()
    override suspend fun catalog(category: String?, onlyRunnable: Boolean): List<CatalogModel> =
        hang()

    override suspend fun swarm(): SwarmView = hang()
    override suspend fun node(): NodeAdvertisement = hang()
    override suspend fun videoModels(): List<VideoModel> = hang()
    override suspend fun imageModels(): List<ImageModel> = hang()
    override suspend fun load(request: LoadRequest): Status = hang()
    override suspend fun install(request: LoadRequest): String = hang()
    override suspend fun unload(): Unit = hang()
    override suspend fun chat(request: ChatRequest): ChatResponse = hang()
    override suspend fun pair(code: String, deviceName: String, platform: String): PairResponse =
        hang()

    override fun chatStream(request: ChatRequest): Flow<ChatStreamEvent> = flow { hang() }
    override fun events(): Flow<dev.siliconoptimizer.buddy.transport.ServerEvent> = flow { hang() }
    override suspend fun conversations(): List<ConversationSummary> = hang()
    override suspend fun createConversation(title: String?): ConversationSummary = hang()
    override suspend fun conversation(id: String): ConversationDetail = hang()
    override fun sendMessage(
        conversationID: String,
        message: ChatMessageWire,
        maxTokens: Int?,
    ): Flow<ChatStreamEvent> = flow { hang() }
}
