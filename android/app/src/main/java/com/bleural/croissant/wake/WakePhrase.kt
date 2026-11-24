package com.bleural.croissant.wake

/**
 * The wake phrase at the front of what a wake heard, as speech-to-text writes it ("Hey
 * Muffin,", "hey, muffin.", "Hey muffin!"), taken off so only the ask is asked.
 */
object WakePhrase {
    private val LEADING = Regex("""^\s*(hey|hay|hi)\b[\s,.!?;:-]*muffins?\b[\s,.!?;:-]*""", RegexOption.IGNORE_CASE)

    fun strip(text: String): String = text.replaceFirst(LEADING, "").trim()
}
