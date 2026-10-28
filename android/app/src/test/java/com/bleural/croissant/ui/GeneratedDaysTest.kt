package com.bleural.croissant.ui

import com.bleural.croissant.data.Generated
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class GeneratedDaysTest {
    private val today = LocalDate.of(2026, 9, 18)

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun g(id: String, at: Long) = Generated(id, "a fox", at, null, null, 0, null)

    @Test fun groupsByDayNewestFirstWithTheDayWords() {
        val items = listOf(
            g("g_a", at(2026, 9, 18, 9, 0)),
            g("g_b", at(2026, 9, 18, 14, 0)),
            g("g_c", at(2026, 9, 17, 11, 0)),
            g("g_d", at(2026, 9, 13, 8, 0)),
        )
        val days = generatedDays(items, today)
        assertEquals(listOf("Today", "Yesterday", "Sun 13 Sept"), days.map { it.label })
        assertEquals(listOf("g_b", "g_a"), days[0].items.map { it.id })
        assertEquals(listOf("g_c"), days[1].items.map { it.id })
    }

    @Test fun emptyIsEmpty() {
        assertEquals(emptyList<GeneratedDay>(), generatedDays(emptyList(), today))
    }
}

/**
 * The words the picture page shows where the picture is not. The interesting rule is
 * that "Asking again" wins over the reason it failed: a page that still shows the last
 * failure while a fresh ask is in flight reads as if the tap did nothing. A picture
 * still coming says one steady thing, because the page fetches it every few seconds and
 * a word that flickered with each of those would read as trouble.
 */
class GeneratedStatusTest {
    // Asked for just now, so the clock has nothing to say about it.
    private fun g(failed: String? = null, tries: Int = 0, file: java.io.File? = null,
                  at: Long = System.currentTimeMillis()) =
        Generated("g_a", "a fox", at, file, failed, tries, null)

    @Test fun oneStillComingSaysSoHoweverManyFetchesHaveGoneBy() {
        assertEquals(GeneratedStatus("Being made", false), generatedStatus(g(tries = 0), asking = false))
        assertEquals(GeneratedStatus("Being made", false), generatedStatus(g(tries = 40), asking = false))
    }

    /**
     * The picture that can never be fetched: the link dropped, nothing was ever told to
     * the device, and a day went by. It stops saying "being made" without anyone having
     * to reach the server to find out, which is the point, since there is no reaching it.
     */
    @Test fun oneThatNeverCameSaysSoAfterTheServersKeepWindow() {
        val old = System.currentTimeMillis() - Generated.KEEP_MS - 1
        assertEquals(GeneratedStatus("Never arrived", true), generatedStatus(g(at = old), asking = false))
        // A reason the server did give still wins: it says more than the clock does.
        assertEquals(GeneratedStatus("Generation failed", true),
            generatedStatus(g(failed = "generation failed", at = old), asking = false))
    }

    @Test fun aFailureIsItsOwnReason() {
        assertEquals(GeneratedStatus("No longer on the server", true),
            generatedStatus(g(failed = "No longer on the server", tries = 2), asking = false))
    }

    @Test fun aServerReasonIsCapitalisedAsItArrives() {
        assertEquals(GeneratedStatus("Generation failed", true),
            generatedStatus(g(failed = "generation failed"), asking = false))
    }

    @Test fun askingAgainCoversTheReasonItIsBeingAskedAbout() {
        assertEquals(GeneratedStatus("Asking again", false),
            generatedStatus(g(failed = "the model fell over"), asking = true))
    }

    @Test fun aFileThatWillNotDecodeIsAFailureLikeAnyOther() {
        val status = generatedStatus(g(file = java.io.File("g_a.jpg")), asking = false, unreadable = true)
        assertEquals(GeneratedStatus("Picture will not open", true), status)
    }
}
