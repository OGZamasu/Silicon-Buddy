package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.agents.ModelPicker
import dev.siliconoptimizer.buddy.transport.AgentModelChoice
import dev.siliconoptimizer.buddy.transport.AgentSessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer's model picker offers the session's `modelChoices` and nothing else.
 *
 * The Mac refuses any other model with a 400 rather than answering the turn with a
 * different one than the screen showed — so a model this phone offered from anywhere
 * else would be a button that fails, and a model it *sent* from anywhere else would be
 * worse.
 */
class ModelPickerTest {

    private val local = AgentModelChoice("local/qwen3-coder-30b", "Qwen3-Coder 30B A3B — serving now", "This Mac")
    private val node = AgentModelChoice("node/studio/qwen3.8-27b", "Qwen3.8 27B", "studio")

    private fun summary(model: String = local.id, choices: List<AgentModelChoice> = listOf(local, node)) =
        AgentSessionSummary(
            engine = "codex", state = "running", epoch = "E1", model = model, modelChoices = choices,
            cwd = "~/Developer/lisbon", approvalMode = AgentSessionSummary.APPROVALS_SCREENED,
            sandbox = "workspace-write", turnActive = false, pendingApprovals = 0,
            itemCount = 0, updatedAt = "2026-09-19T10:12:44Z",
        )

    @Test
    fun `the picker offers exactly the session's choices, once each, in the Mac's order`() {
        val offered = ModelPicker.choices(summary(choices = listOf(local, node, local)))
        assertEquals(listOf(local.id, node.id), offered.map { it.id })
    }

    @Test
    fun `no session, no choices`() {
        assertTrue(ModelPicker.choices(null).isEmpty())
        assertNull(ModelPicker.modelToSend(null, local.id))
    }

    @Test
    fun `a pick on the list is sent`() {
        assertEquals(node.id, ModelPicker.modelToSend(summary(), node.id))
    }

    @Test
    fun `a pick that is not on the list is never sent`() {
        assertNull(ModelPicker.modelToSend(summary(), "gpt-5"))
        // Including one that fell off the list after it was picked.
        assertNull(ModelPicker.modelToSend(summary(choices = listOf(local)), node.id))
    }

    @Test
    fun `the session's own model is not sent again`() {
        assertNull(ModelPicker.modelToSend(summary(), local.id))
        assertNull(ModelPicker.modelToSend(summary(), null))
    }

    @Test
    fun `what the picker shows is the pick while it is offered, else the session's model`() {
        assertEquals(node, ModelPicker.selected(summary(), node.id))
        assertEquals(local, ModelPicker.selected(summary(), "gpt-5"))
        assertEquals(local, ModelPicker.selected(summary(choices = listOf(local)), node.id))
    }

    /** The loaded model is not a choice by being loaded; only the list is. */
    @Test
    fun `a session model that is not on the list is not offered, and the caption says so`() {
        val lost = summary(model = "node/gone/llama", choices = listOf(local))
        assertNull(ModelPicker.selected(lost, null))
        assertEquals(listOf(local.id), ModelPicker.choices(lost).map { it.id })
        assertTrue(ModelPicker.caption(lost, null)!!.contains("not on this Mac's list"))
    }

    @Test
    fun `the caption names the model and where it runs`() {
        assertEquals("Qwen3.8 27B · studio", ModelPicker.caption(summary(), node.id))
    }
}
