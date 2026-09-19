package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.reach.VoiceEffect
import dev.siliconoptimizer.buddy.reach.VoiceEvent
import dev.siliconoptimizer.buddy.reach.VoiceSession
import dev.siliconoptimizer.buddy.reach.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Push-to-talk, driven without a microphone. */
class VoiceSessionTest {

    @Test
    fun `holding opens the microphone`() {
        val session = VoiceSession()
        assertEquals(listOf(VoiceEffect.StartListening), session.apply(VoiceEvent.Pressed))
        assertTrue(session.isListening)
    }

    @Test
    fun `letting go sends what was heard`() {
        val session = VoiceSession()
        session.apply(VoiceEvent.Pressed)
        session.apply(VoiceEvent.Heard("what is"))
        session.apply(VoiceEvent.Heard("what is a monad"))
        assertEquals("what is a monad", session.partial)
        assertEquals(
            listOf(VoiceEffect.StopListening, VoiceEffect.Send("what is a monad")),
            session.apply(VoiceEvent.Released),
        )
        assertEquals(VoiceState.Thinking("what is a monad"), session.state)
    }

    /** A tap that catches one syllable of room noise should not wake a 27B model. */
    @Test
    fun `letting go with nothing heard sends nothing`() {
        val session = VoiceSession()
        session.apply(VoiceEvent.Pressed)
        assertEquals(listOf(VoiceEffect.StopListening), session.apply(VoiceEvent.Released))
        assertEquals(VoiceState.Idle, session.state)
    }

    @Test
    fun `one stray syllable is not a question`() {
        val session = VoiceSession()
        session.apply(VoiceEvent.Pressed)
        session.apply(VoiceEvent.Heard("a"))
        assertEquals(listOf(VoiceEffect.StopListening), session.apply(VoiceEvent.Released))
        assertEquals(VoiceState.Idle, session.state)
    }

    @Test
    fun `whitespace is trimmed before sending`() {
        val session = VoiceSession()
        session.apply(VoiceEvent.Pressed)
        session.apply(VoiceEvent.Heard("  hello there \n"))
        assertEquals(
            listOf(VoiceEffect.StopListening, VoiceEffect.Send("hello there")),
            session.apply(VoiceEvent.Released),
        )
    }

    // MARK: - Speaking the answer

    @Test
    fun `the answer is read out when the toggle is on`() {
        val session = ask(VoiceSession(speaksReplies = true))
        assertEquals(
            listOf(VoiceEffect.Speak("Hi there.")),
            session.apply(VoiceEvent.Answered("Hi there.")),
        )
        assertEquals(emptyList<VoiceEffect>(), session.apply(VoiceEvent.FinishedSpeaking))
        assertEquals(VoiceState.Idle, session.state)
    }

    @Test
    fun `the answer is silent when the toggle is off`() {
        val session = ask(VoiceSession(speaksReplies = false))
        assertEquals(emptyList<VoiceEffect>(), session.apply(VoiceEvent.Answered("Hi there.")))
        assertEquals(VoiceState.Idle, session.state)
    }

    @Test
    fun `an empty answer is not spoken`() {
        val session = ask(VoiceSession())
        assertEquals(emptyList<VoiceEffect>(), session.apply(VoiceEvent.Answered("   ")))
        assertEquals(VoiceState.Idle, session.state)
    }

    @Test
    fun `turning the toggle off mid-sentence stops the sentence`() {
        val session = ask(VoiceSession(speaksReplies = true))
        session.apply(VoiceEvent.Answered("A very long answer."))
        assertEquals(
            listOf(VoiceEffect.StopSpeaking),
            session.apply(VoiceEvent.SpeechEnabled(false)),
        )
        assertEquals(VoiceState.Idle, session.state)
    }

    // MARK: - Interruption

    /**
     * The commonest interruption: the answer is long, the person has heard enough, and
     * they press to ask the next thing.
     */
    @Test
    fun `pressing while it is talking interrupts and listens`() {
        val session = ask(VoiceSession())
        session.apply(VoiceEvent.Answered("A long answer that goes on."))
        assertEquals(
            listOf(VoiceEffect.StopSpeaking, VoiceEffect.StartListening),
            session.apply(VoiceEvent.Pressed),
        )
        assertTrue(session.isListening)
    }

    @Test
    fun `pressing while the Mac is still thinking abandons that answer`() {
        val session = ask(VoiceSession(), "first question")
        assertEquals(listOf(VoiceEffect.StartListening), session.apply(VoiceEvent.Pressed))
        session.apply(VoiceEvent.Heard("second question"))
        assertEquals(
            listOf(VoiceEffect.StopListening, VoiceEffect.Send("second question")),
            session.apply(VoiceEvent.Released),
        )
    }

    /** And then the first answer arrives. It must not be spoken over the new question. */
    @Test
    fun `an answer to an abandoned question is not spoken`() {
        val session = ask(VoiceSession(), "first")
        session.apply(VoiceEvent.Pressed)
        assertEquals(
            emptyList<VoiceEffect>(),
            session.apply(VoiceEvent.Answered("An answer to the first question.")),
        )
        assertTrue(session.isListening)
    }

    @Test
    fun `leaving the screen while listening closes the microphone`() {
        val session = VoiceSession()
        session.apply(VoiceEvent.Pressed)
        assertEquals(listOf(VoiceEffect.StopListening), session.apply(VoiceEvent.Interrupted))
        assertEquals(VoiceState.Idle, session.state)
    }

    @Test
    fun `leaving the screen while talking stops the voice`() {
        val session = ask(VoiceSession())
        session.apply(VoiceEvent.Answered("Speaking."))
        assertEquals(listOf(VoiceEffect.StopSpeaking), session.apply(VoiceEvent.Interrupted))
    }

    // MARK: - Failures

    @Test
    fun `a recogniser that gives up shows why and can be retried`() {
        val session = VoiceSession()
        session.apply(VoiceEvent.Pressed)
        assertEquals(
            listOf(VoiceEffect.StopListening),
            session.apply(VoiceEvent.Failed("No microphone.")),
        )
        assertEquals(VoiceState.Failed("No microphone."), session.state)
        assertEquals(listOf(VoiceEffect.StartListening), session.apply(VoiceEvent.Pressed))
    }

    @Test
    fun `late recognition after the button came up does not change the question`() {
        val session = ask(VoiceSession(), "what is a monad")
        assertEquals(
            emptyList<VoiceEffect>(),
            session.apply(VoiceEvent.Heard("what is a monad transformer")),
        )
        assertEquals(VoiceState.Thinking("what is a monad"), session.state)
    }

    @Test
    fun `pressing twice does not open two microphones`() {
        val session = VoiceSession()
        session.apply(VoiceEvent.Pressed)
        assertEquals(emptyList<VoiceEffect>(), session.apply(VoiceEvent.Pressed))
    }

    /** Hold, say something, let go. */
    private fun ask(session: VoiceSession, words: String = "hello"): VoiceSession {
        session.apply(VoiceEvent.Pressed)
        session.apply(VoiceEvent.Heard(words))
        session.apply(VoiceEvent.Released)
        return session
    }
}

