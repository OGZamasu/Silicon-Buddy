package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.transport.CatalogModel
import dev.siliconoptimizer.buddy.transport.ChatMessageWire
import dev.siliconoptimizer.buddy.transport.ChatRequest
import dev.siliconoptimizer.buddy.transport.ChatResponse
import dev.siliconoptimizer.buddy.transport.ErrorResponse
import dev.siliconoptimizer.buddy.transport.Health
import dev.siliconoptimizer.buddy.transport.ImageModel
import dev.siliconoptimizer.buddy.transport.InstalledModel
import dev.siliconoptimizer.buddy.transport.LoadRequest
import dev.siliconoptimizer.buddy.transport.MeshModel
import dev.siliconoptimizer.buddy.transport.Metrics
import dev.siliconoptimizer.buddy.transport.NodeAdvertisement
import dev.siliconoptimizer.buddy.transport.Profile
import dev.siliconoptimizer.buddy.transport.Status
import dev.siliconoptimizer.buddy.transport.SwarmView
import dev.siliconoptimizer.buddy.transport.VideoModel
import dev.siliconoptimizer.buddy.transport.VideoQueueView
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mirrored types, checked against real answers from a running Mac.
 *
 * The fixtures in `contract/` were captured from Silicon Optimizer's control server,
 * not written by hand. The round trip is the part that matters: decode, re-encode,
 * compare the JSON. A field this app forgot to mirror disappears in the re-encode, so
 * the comparison fails — which is exactly the drift that would otherwise be discovered
 * by a person staring at a blank row.
 */
class ContractTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private fun fixture(name: String): String =
        javaClass.classLoader?.getResourceAsStream("$name.json")?.bufferedReader()?.readText()
            ?: error("Missing fixture $name.json — capture it from a running Mac into contract/")

    private inline fun <reified T> roundTrip(name: String): T {
        val text = fixture(name)
        val decoded = json.decodeFromString<T>(text)
        val reencoded = json.encodeToString(decoded)
        assertEquals("$name changed shape on a round trip", decoded, json.decodeFromString<T>(reencoded))

        val original = strip(Json.parseToJsonElement(text))
        val rebuilt = strip(Json.parseToJsonElement(reencoded))
        val differences = difference(original, rebuilt)
        assertTrue(
            "$name.json lost or changed fields on a round trip:\n" + differences.joinToString("\n"),
            differences.isEmpty(),
        )
        return decoded
    }

    /** An absent optional and an explicit null are the same thing to this API. */
    private fun strip(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.filterValues { it !is JsonNull }.mapValues { strip(it.value) },
        )
        is JsonArray -> JsonArray(element.map { strip(it) })
        else -> element
    }

    /** A readable account of what differs, so a failing test names the field. */
    private fun difference(mine: JsonElement, theirs: JsonElement, path: String = ""): List<String> =
        when {
            mine is JsonObject && theirs is JsonObject -> {
                (mine.keys + theirs.keys).sorted().flatMap { key ->
                    val childPath = if (path.isEmpty()) key else "$path.$key"
                    val a = mine[key]
                    val b = theirs[key]
                    when {
                        a == null -> listOf("+ $childPath appeared after the round trip")
                        b == null -> listOf("- $childPath was lost — is it mirrored?")
                        else -> difference(a, b, childPath)
                    }
                }
            }
            mine is JsonArray && theirs is JsonArray -> {
                if (mine.size != theirs.size) {
                    listOf("$path has ${mine.size} items, round trip has ${theirs.size}")
                } else {
                    mine.indices.flatMap { difference(mine[it], theirs[it], "$path[$it]") }
                }
            }
            mine is JsonPrimitive && theirs is JsonPrimitive -> {
                val a = mine.doubleOrNull
                val b = theirs.doubleOrNull
                val same = if (a != null && b != null) a == b else mine.content == theirs.content
                if (same) emptyList() else listOf("$path: $mine != $theirs")
            }
            else -> if (mine == theirs) emptyList() else listOf("$path: $mine != $theirs")
        }

    // MARK: - Every type this app mirrors

    @Test
    fun health() {
        assertEquals("ok", roundTrip<Health>("health").status)
    }

    @Test
    fun status() {
        roundTrip<Status>("status")
    }

    @Test
    fun profile() {
        val profile = roundTrip<Profile>("profile")
        assertTrue(profile.chip.isNotEmpty())
        assertTrue(profile.totalMemoryBytes > 0)
    }

    @Test
    fun metrics() {
        val metrics = roundTrip<Metrics>("metrics")
        assertTrue(metrics.memoryTotalBytes > 0)
        assertTrue(metrics.gpuUtilization in 0.0..1.0)
    }

    @Test
    fun installedModels() {
        val installed = roundTrip<List<InstalledModel>>("installed")
        assertTrue("The fixture should have been captured with models installed", installed.isNotEmpty())
        assertTrue("One installed model is a vision model", installed.any { it.supportsVision })
    }

    @Test
    fun catalog() {
        val catalog = roundTrip<List<CatalogModel>>("catalog")
        assertTrue(catalog.size > 5)
        assertTrue("The Mac plans for this machine", catalog.any { it.recommendation != null })
        assertTrue("Optional fields must survive", catalog.any { it.featured == true })
    }

    @Test
    fun swarm() {
        roundTrip<SwarmView>("swarm")
    }

    @Test
    fun nodeAdvertisement() {
        val node = roundTrip<NodeAdvertisement>("v1-node")
        assertTrue(node.name.isNotEmpty())
        assertTrue("snake_case keys must map", node.profile.memoryGB > 0)
    }

    @Test
    fun videoModels() {
        val models = roundTrip<List<VideoModel>>("video-models")
        assertTrue(models.any { it.node != null })
        assertTrue("An unavailable model has no node", models.any { it.node == null })
    }

    @Test
    fun videoQueue() {
        roundTrip<VideoQueueView>("video-queue")
    }

    @Test
    fun imageModels() {
        assertTrue(roundTrip<List<ImageModel>>("image-models").any { it.recommendation != null })
    }

    @Test
    fun meshModels() {
        roundTrip<List<MeshModel>>("mesh-models")
    }

    @Test
    fun chatResponse() {
        assertTrue(roundTrip<ChatResponse>("chat").content.isNotEmpty())
    }

    @Test
    fun errorBodies() {
        assertTrue(roundTrip<ErrorResponse>("error-401").error.lowercase().contains("token"))
        roundTrip<ErrorResponse>("error-404")
    }

    // MARK: - Requests

    @Test
    fun `requests encode the way the Mac reads them`() {
        val encoded = json.encodeToString(LoadRequest("bonsai-2-27b", "PTQ1_0"))
        val fields = Json.parseToJsonElement(encoded) as JsonObject
        assertEquals("bonsai-2-27b", (fields["modelID"] as JsonPrimitive).content)
        assertEquals("PTQ1_0", (fields["quantization"] as JsonPrimitive).content)
        assertNull("Absent options must not be sent as null", fields["contextLength"])
    }

    @Test
    fun `a chat request carries images as an array`() {
        val request = ChatRequest(
            listOf(ChatMessageWire("user", "What is this?", listOf("data:image/jpeg;base64,AAA"))),
            maxTokens = 64,
        )
        val fields = Json.parseToJsonElement(json.encodeToString(request)) as JsonObject
        val message = (fields["messages"] as JsonArray)[0] as JsonObject
        assertEquals("user", (message["role"] as JsonPrimitive).content)
        assertEquals(1, (message["images"] as JsonArray).size)
        assertEquals("64", (fields["maxTokens"] as JsonPrimitive).content)
    }

    @Test
    fun `an empty image list is still sent`() {
        // The Mac's Message.images is not optional; sending no key at all would be a
        // decode failure on a stricter server.
        val request = ChatRequest(listOf(ChatMessageWire("user", "hi")))
        val fields = Json.parseToJsonElement(json.encodeToString(request)) as JsonObject
        val message = (fields["messages"] as JsonArray)[0] as JsonObject
        assertEquals(0, (message["images"] as JsonArray).size)
    }

    // MARK: - Decoding is forgiving where the Mac says it may be

    @Test
    fun `a status from an older Mac still decodes`() {
        val status = json.decodeFromString<Status>("""{"state":"Not loaded","expertStreaming":false}""")
        assertNull(status.loadedModelID)
        assertTrue(!status.hasLoadedModel)
    }

    @Test
    fun `a catalog entry without the optional fields decodes`() {
        val entry = json.decodeFromString<CatalogModel>(
            """
            {"id":"x","name":"X","author":"A","license":"MIT","summary":"s","category":"General",
             "parameters":"7B","isMoE":false,"capabilities":[],"rating":3,"maxContext":8192,
             "quantizations":["Q4_K_M"]}
            """.trimIndent(),
        )
        assertNull(entry.featured)
        assertNull(entry.recommendation)
        assertNull(entry.runtimeNote)
    }
}
