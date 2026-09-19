package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.reach.IntentMapping
import dev.siliconoptimizer.buddy.transport.DeviceScope
import dev.siliconoptimizer.buddy.transport.InstalledModel
import dev.siliconoptimizer.buddy.transport.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Turning what somebody said into what the Mac wants. */
class IntentMappingTest {

    private fun model(id: String, name: String) =
        InstalledModel(id, name, "Q4_K_M", 1, isLoaded = false, supportsVision = false)

    private val installed = listOf(
        model("qwen3-4b-mlx@MLX-4bit", "Qwen3 4B (MLX)"),
        model("gemma-3-12b-it@Q4_K_M", "Gemma 3 12B"),
        model("gemma-3-27b-it@Q4_K_M", "Gemma 3 27B"),
        model("qwen3-coder-30b-a3b@Q4_K_M", "Qwen3-Coder 30B A3B"),
    )

    // MARK: - Ask my Mac

    @Test
    fun `a question becomes one user message with no history`() {
        val request = IntentMapping.chatRequest("  What is a monad?  ")
        assertEquals(1, request.messages.size)
        assertEquals("user", request.messages[0].role)
        assertEquals("What is a monad?", request.messages[0].content)
        assertEquals(IntentMapping.SPOKEN_MAX_TOKENS, request.maxTokens)
    }

    @Test
    fun `an empty question is refused before it reaches the Mac`() {
        assertThrows(IntentMapping.Refusal.EmptyPrompt::class.java) {
            IntentMapping.chatRequest("   \n ")
        }
    }

    @Test
    fun `a picture with no words is still a question`() {
        val request = IntentMapping.chatRequest("", listOf("data:image/jpeg;base64,AA"))
        assertEquals(1, request.messages[0].images.size)
    }

    // MARK: - Load a model

    @Test
    fun `an exact id wins`() {
        assertEquals(
            "gemma-3-12b-it@Q4_K_M",
            IntentMapping.loadRequest("gemma-3-12b-it@Q4_K_M", installed, DeviceScope.Full).modelID,
        )
    }

    @Test
    fun `the name as a person says it resolves`() {
        assertEquals(
            "qwen3-4b-mlx@MLX-4bit",
            IntentMapping.loadRequest("Qwen3 4B (MLX)", installed, DeviceScope.Full).modelID,
        )
    }

    @Test
    fun `the id without its quantization resolves`() {
        assertEquals(
            "gemma-3-27b-it@Q4_K_M",
            IntentMapping.loadRequest("gemma-3-27b-it", installed, DeviceScope.Full).modelID,
        )
    }

    @Test
    fun `case and spacing do not matter`() {
        assertEquals(
            "qwen3-coder-30b-a3b@Q4_K_M",
            IntentMapping.loadRequest("qwen3 CODER 30b a3b", installed, DeviceScope.Full).modelID,
        )
    }

    /**
     * The case that matters: two 27B-sized mistakes are a gigabyte of memory and
     * several minutes each, so a phrase that could mean either has to ask again.
     */
    @Test
    fun `a phrase that matches two models refuses rather than guessing`() {
        val refusal = assertThrows(IntentMapping.Refusal.Ambiguous::class.java) {
            IntentMapping.loadRequest("gemma", installed, DeviceScope.Full)
        }
        assertEquals("gemma", refusal.phrase)
        assertEquals(2, refusal.candidates.size)
    }

    @Test
    fun `a phrase that matches nothing says so`() {
        val refusal = assertThrows(IntentMapping.Refusal.NoSuchModel::class.java) {
            IntentMapping.loadRequest("llama", installed, DeviceScope.Full)
        }
        assertEquals("llama", refusal.phrase)
    }

    /**
     * A device paired for chat cannot spend the machine, and it is told that here
     * rather than by a 403 after a round trip.
     */
    @Test
    fun `a chat-only device cannot load anything`() {
        assertThrows(IntentMapping.Refusal.NotAllowed::class.java) {
            IntentMapping.loadRequest("Gemma 3 12B", installed, DeviceScope.Chat)
        }
    }

    @Test
    fun `a prefix of one name resolves but a prefix of two does not`() {
        assertEquals(
            "qwen3-4b-mlx@MLX-4bit",
            IntentMapping.resolveModel("Qwen3 4B", installed, DeviceScope.Full).id,
        )
        assertThrows(IntentMapping.Refusal.Ambiguous::class.java) {
            IntentMapping.resolveModel("Gemma 3", installed, DeviceScope.Full)
        }
    }

    // MARK: - What is loaded

    @Test
    fun `the spoken summary names the model and the Mac`() {
        val status = Status(
            state = "Ready",
            loadedModelID = "gemma-3-12b-it@Q4_K_M",
            loadedModelName = "Gemma 3 12B",
            contextLength = 65536,
            lastGenerationTokensPerSecond = 42.4,
        )
        assertEquals(
            "Studio has Gemma 3 12B loaded, with a 64K context, last answering at 42 tokens a second.",
            IntentMapping.loadedSummary(status, "Studio"),
        )
    }

    @Test
    fun `nothing loaded is said plainly`() {
        assertEquals(
            "Nothing is loaded on your Mac right now.",
            IntentMapping.loadedSummary(Status(state = "Idle"), null),
        )
    }

    @Test
    fun `an odd context length keeps its decimal`() {
        assertEquals("64K", IntentMapping.contextPhrase(65536))
        assertEquals("40K", IntentMapping.contextPhrase(40960))
        assertEquals("1.5K", IntentMapping.contextPhrase(1536))
        assertTrue(IntentMapping.contextPhrase(512).startsWith("512"))
    }
}
