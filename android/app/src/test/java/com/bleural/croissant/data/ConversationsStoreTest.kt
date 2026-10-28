package com.bleural.croissant.data

import com.bleural.croissant.Part
import com.bleural.croissant.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConversationsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, "conversations.json")
    private fun store() = ConversationsStore(file())

    private fun done(id: String, q: String, a: String, at: Long = 1L) =
        Turn(id, at, q, listOf(Part.Text(a)), TurnState.Done)

    @Test fun idsHaveTheShapeTheServerChecks() {
        assertTrue(Regex("^t_[0-9a-f]{16}$").matches(ConversationsStore.newTurnId()))
        assertTrue(Regex("^c_[0-9a-f]{16}$").matches(ConversationsStore.newConvId()))
    }

    @Test fun aHoldBeginsAConversationAndTheNextContinuesIt() {
        val s = store()
        s.begin("c1", Turn("t1", 1, ""))
        s.begin("c1", Turn("t2", 2, ""))
        s.begin("c2", Turn("t3", 3, ""))
        assertEquals(listOf("t1", "t2"), s.get("c1")!!.turns.map { it.id })
        assertEquals("c2", s.find("t3")!!.first.id)
    }

    @Test fun partsStateAndPhotoSurviveAReload() {
        val s = store()
        s.begin("c1", Turn("t1", 5, "", photo = "20261006-101010-000.jpg"))
        val parts = listOf(
            Part.Tool(ToolCall("c1", "Starting timer", "10:00", running = false)),
            Part.Tool(ToolCall("g_ab12cd", "Generating image", "a red fox", running = false, generation = "g_ab12cd")),
            Part.Text("Done."),
        )
        s.update("t1") { it.copy(question = "Set a timer", parts = parts, state = TurnState.Done) }
        val t = store().get("c1")!!.turns.single()
        assertEquals("Set a timer", t.question)
        assertEquals(TurnState.Done, t.state)
        assertEquals("20261006-101010-000.jpg", t.photo)
        assertEquals("Done.", t.answer)
        val pic = t.parts[1] as Part.Tool
        assertEquals("g_ab12cd", pic.call.generation)
        assertEquals("a red fox", pic.call.value)
        assertNull((t.parts[0] as Part.Tool).call.generation)
    }

    @Test fun priorIsTheLastAnsweredTurnsOldestFirst() {
        val s = store()
        repeat(8) { i -> s.begin("c1", done("t$i", "q$i", "a$i")) }
        s.begin("c1", Turn("failed", 9, "q9", state = TurnState.Failed, error = "LLM error"))
        s.begin("c1", Turn("asking", 10, ""))
        val prior = s.prior("c1")
        assertEquals(ConversationsStore.PRIOR, prior.size)
        assertEquals("q2" to "a2", prior.first())
        assertEquals("q7" to "a7", prior.last())
    }

    @Test fun droppingTheOnlyTurnDropsTheConversation() {
        val s = store()
        s.begin("c1", Turn("t1", 1, ""))
        s.drop("t1")
        assertNull(s.get("c1"))
        assertTrue(store().list().isEmpty())
    }

    @Test fun deleteTakesSeveral() {
        val s = store()
        s.begin("c1", done("t1", "q", "a"))
        s.begin("c2", done("t2", "q", "a"))
        s.begin("c3", done("t3", "q", "a"))
        s.delete(setOf("c1", "c3"))
        assertEquals(listOf("c2"), store().list().map { it.id })
    }
}
