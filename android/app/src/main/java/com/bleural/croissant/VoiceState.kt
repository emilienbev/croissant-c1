package com.bleural.croissant

import com.bleural.croissant.data.LanguagePair

enum class Phase {
    Idle,           // always-hot mic running, nothing retained
    Listening,      // hold confirmed, streaming up, partials coming back
    Transcribing,   // released, transcript settling
    Answering,      // the model's answer coming, from the C1's own call
    Error
}

/**
 * One tool call, shown on the Ask screen as a tool line: [label] is the
 * plain reading of the tool name, [value] the one argument worth showing (the timer's
 * length, the reminder's title). [generation] is set on the line for a picture being
 * made, to its id, so the line can lead to it.
 */
data class ToolCall(
    val call: String,
    val label: String,
    val value: String? = null,
    val running: Boolean = true,
    val failed: Boolean = false,
    val generation: String? = null,
)

/** The exchange after the transcript, in the order it happened: answer text and tool calls interleaved. */
sealed interface Part {
    data class Text(val text: String) : Part
    data class Tool(val call: ToolCall) : Part
}

data class VoiceState(
    val phase: Phase = Phase.Idle,
    val transcript: String = "",
    /** Answer text and tool calls as they arrived; the tool lines stay as the record once answered. */
    val parts: List<Part> = emptyList(),
    val error: String? = null,
    /** This exchange carried the camera's photo: the camera screen shows it, Ask is not brought up. */
    val withPhoto: Boolean = false,
    /** When the hold was confirmed, epoch ms: the question's time in the header once answered. */
    val askedAt: Long = 0L,
    /**
     * Set when a transform made the service keep the shot itself, to the photo's name.
     * The camera watches it: the original is in the library already, so the shot goes
     * and the live view says so (2026-09-18).
     */
    val keptPhoto: String? = null,
    /**
     * This hold went up as a transform (the photo page's Transform button): it makes a
     * picture and never answers. The photo page uses it to leave the exchange alone,
     * since the words belong to the transform page that asked for them.
     */
    val transform: Boolean = false,
    /** The id of the picture this exchange started. */
    val generation: String? = null,
    /**
     * This hold went up from the Translator, in this pair: [transcript] is what was said
     * and the answer is its translation. It never brings Ask up.
     */
    val translate: LanguagePair? = null,
    /**
     * This hold went up from a page that takes its words (an Atelier app, an agent's
     * conversation): its words alone, and they go to that page. It never brings Ask up.
     */
    val dictate: Boolean = false,
    /** The conversation this hold belongs to (an Ask or photo hold), and its own id. */
    val conv: String? = null,
    val turn: String? = null,
    /**
     * The hold stopped at its press: the chat model picked is this provider's, and it has no
     * key kept. Ask says so and offers to add it.
     */
    val noKey: String? = null,
) {
    /** The answer text alone, for the screens that show only that. */
    val answer: String get() = parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
    val tools: List<ToolCall> get() = parts.filterIsInstance<Part.Tool>().map { it.call }

    fun withText(text: String): VoiceState {
        val last = parts.lastOrNull()
        return if (last is Part.Text) copy(parts = parts.dropLast(1) + Part.Text(last.text + text))
        else copy(parts = parts + Part.Text(text))
    }
    fun withTool(call: ToolCall): VoiceState = copy(parts = parts + Part.Tool(call))
    fun updateTool(id: String, f: (ToolCall) -> ToolCall): VoiceState =
        copy(parts = parts.map { if (it is Part.Tool && it.call.call == id) Part.Tool(f(it.call)) else it })

    /** The hold had nowhere to go: no server has been set yet (the setup's step was skipped). */
    val noServer: Boolean get() = phase == Phase.Error && error == NO_SERVER

    /** The hold had no chat model to go to: none picked, and no default among what the providers list. */
    val noModel: Boolean get() = phase == Phase.Error && error == NO_MODEL

    /** The hold stopped at its press with something to set first (a server, a model, a key): Ask comes up to say so. */
    val refused: Boolean get() = noServer || noModel || (phase == Phase.Error && noKey != null)

    /**
     * What a cue and the cut to Ask follow (Core): the phase, of the hold asked at [askedAt], so
     * a refusal after a refusal is a step of its own.
     */
    val step: Pair<Phase, Long> get() = phase to askedAt

    companion object {
        const val NO_SERVER = "no server"
        const val NO_MODEL = "no model yet"
    }
}
