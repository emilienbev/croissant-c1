package com.bleural.croissant

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The steps Core's cues and the cut to Ask follow, as it reads them off the service's state. */
class VoiceStateTest {
    private fun steps(states: List<VoiceState>) = runBlocking { states.asFlow().map { it.step }.distinctUntilChanged().toList() }

    @Test fun aRefusalAfterARefusalIsAStepOfItsOwn() {
        val first = VoiceState(Phase.Error, error = VoiceState.NO_MODEL, askedAt = 1_000)
        val second = first.copy(askedAt = 3_000)
        assertEquals(listOf(Phase.Error to 1_000L, Phase.Error to 3_000L), steps(listOf(first, second)))
    }

    @Test fun anAnswerComingIsOneStep() {
        val asked = VoiceState(Phase.Answering, transcript = "hi", askedAt = 1_000)
        val states = listOf(asked, asked.withText("Hel"), asked.withText("Hello"), asked.copy(phase = Phase.Idle))
        assertEquals(listOf(Phase.Answering to 1_000L, Phase.Idle to 1_000L), steps(states))
    }
}
