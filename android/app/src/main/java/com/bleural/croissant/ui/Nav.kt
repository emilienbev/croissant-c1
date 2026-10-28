package com.bleural.croissant.ui

import androidx.compose.runtime.mutableStateListOf
import android.os.SystemClock
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.data.BenchGroup
import com.bleural.croissant.net.ModelSlot
import com.bleural.croissant.wifi.WifiNet

/** Every screen that is not a feature panel. Sub-settings are screens too; they just sit deeper in the stack. */
sealed interface Screen {
    /**
     * The room: Home and the eight feature panels in one space. The features themselves
     * are not screens on this stack; what is left here is Home and
     * everything that opens over a panel.
     */
    data object Home : Screen
    data object Library : Screen
    data class Recording(val path: String) : Screen
    data class RenameRecording(val path: String) : Screen
    data class Reminder(val id: String) : Screen
    /** Reminders' plus: a new reminder, typed (Beam's). */
    data object ReminderAdd : Screen
    data class Event(val id: String) : Screen
    data object TimerAdd : Screen
    data class Photo(val path: String) : Screen
    /** A photo's Transform button: hold, describe, and a picture is made from it. */
    data class Transform(val path: String) : Screen
    /**
     * The code for a share: the device serves these itself. A list,
     * because a share is one photo from its page or a selection from the shelf.
     */
    data class Send(val paths: List<String>) : Screen
    /** The code for one tape's transcript, from its page. */
    data class SendTape(val path: String) : Screen
    data object Generated : Screen
    data class GeneratedItem(val id: String) : Screen
    /** Ask's past conversations, from its History key. */
    data object History : Screen
    data object Wifi : Screen
    data class WifiJoin(val net: WifiNet) : Screen
    /** Settings > Wi-Fi's scan a code: a Wi-Fi QR code joins its network. */
    data object WifiScan : Screen
    data object Esim : Screen
    data class EsimProfile(val iccid: String) : Screen
    /** [scan] opens on the viewfinder; a link carrying an activation code opens the form. */
    data class EsimAdd(val scan: Boolean = false) : Screen
    /** Reached by a long press on the control centre's tile; Settings' six rows are full. */
    data object Bluetooth : Screen
    data object Tailscale : Screen
    data object Server : Screen
    /** Server's two ways to another server, as the setup offers them: scan its code, or [ServerFind] (Beam's). */
    data object ServerWays : Screen
    /** Server's scan a QR code: the code croissant-server prints at its start (Beam's). */
    data object ServerScan : Screen
    /** Server's other servers: the tailnet asked as the setup does, and typing an address (Beam's). */
    data object ServerFind : Screen
    data object ServerAddress : Screen
    /**
     * Settings > ask's model for one slot, picked among what the server offers: first the one in
     * use and where models run, then, with [place] (its key), one place's models.
     */
    data class ModelPick(val slot: ModelSlot, val place: String? = null) : Screen
    /** The last 30 days of use, as the server counted and priced it (Beam's). */
    data object Usage : Screen
    data object Device : Screen
    /** Settings > update: GitHub releases of the update source, downloaded then installed (Beam's). */
    data object Update : Screen
    /** Update > source: the GitHub repository releases come from (Beam's). */
    data object UpdateSource : Screen
    data object Sound : Screen
    data object Display : Screen
    /** Settings > Device > Battery: Rest, Sleep and Stats (2026-09-30). */
    data object Battery : Screen
    /** Battery > Rest and Sleep, each a picker of [com.bleural.croissant.Idle] times under a paragraph. */
    data object Rest : Screen
    data object Sleep : Screen
    /** Battery > Stats: the log, the chart and the rates. */
    data object BatteryStats : Screen
    data object BatteryEvery : Screen
    data object Power : Screen
    data object Design : Screen
    /** Settings > Device > Tones: the interface's sound pack, the highlighted one heard on its play key. */
    data object Tones : Screen
    /** The Metronome's time signature, opened from it. */
    data object TimeSignature : Screen
    /** The Metronome's sound, picked from its list behind the dial's settings mark. */
    data object MetronomeSound : Screen
    /** The Metronome's practice, day by day, opened from today's minutes. */
    data object Practice : Screen
    /** The tuner, opened from the Metronome. */
    data object Tuner : Screen
    /** The tuner's tuning, picked from the instrument's list. */
    data object Tuning : Screen
    /** The Translator's languages, opened on the side tapped: [to] the language it goes into. */
    data class TranslatorLanguages(val to: Boolean) : Screen
    /** The Translator's saved translations for its pair, both ways round. */
    data object TranslatorSaved : Screen
    /** Weather's places: here and the ones added, each with its sky; a place's sheet pins it to Home or removes it. */
    data object WeatherPlaces : Screen
    /** Adding a place to Weather: a search as you type, a tap adds the place and shows it. */
    data object WeatherAdd : Screen
    /** Settings > ask: the chat model, its prompts and the test, then the speech, pictures and transform models (Beam's). */
    data object AskSettings : Screen
    /** Ask's prompts for the chat model in use: the one it gets ticked, each one's last score on it, add a prompt (Beam's). */
    data object Prompts : Screen
    /** A new prompt, its name typed: it starts as a copy of the one the chat model in use gets (Beam's). */
    data object PromptAdd : Screen
    /** One of Ask's prompts by its name: its first lines, the models it is used by, edit, the whole prompt, rename, reset, delete (Beam's). */
    data class Prompt(val name: String) : Screen
    data class PromptRename(val name: String) : Screen
    /** A prompt's text typed on the keys (Beam's). */
    data class PromptEdit(val name: String) : Screen
    /** A prompt as the chat model in use gets it, with what the device adds (Beam's). */
    data class PromptWhole(val name: String) : Screen
    /** The fixed prompt of a call besides Ask's ([call]: transform, translator, tapes, apps), read only (Beam's). */
    data class FixedPrompt(val call: String) : Screen
    /** The C1's own providers, from Settings > ask: the ones added with their state, then add a provider (Beam's). */
    data object Providers : Screen
    /** Adding a provider: the presets not added yet, then a server of one's own (Beam's). */
    data object ProviderAdd : Screen
    /** One provider by its id: its address or its key, its test and what it offers, remove (Beam's). */
    data class Provider(val id: String) : Screen
    /** A provider's key typed on the keys, tried before it is kept (Beam's). */
    data class ProviderKey(val id: String) : Screen
    /** A provider's address typed; [title], a server of one's own being added under that name (Beam's). */
    data class ProviderAddress(val id: String, val title: String? = null) : Screen
    /** A server of one's own: its name, typed before its address (Beam's). */
    data object ProviderOwn : Screen
    /** The page the C1 serves to a phone, as its QR code; [prompt], from that prompt's edit, with typing it here under it (Beam's). */
    data class OnPhone(val prompt: String? = null) : Screen
    /** The model test: the chat model asked the device's sentences with its tools, from Settings > ask. */
    data object ModelTest : Screen
    /** The test's model, for that run only: the chat model's picker, a pick there changing no pick; [place] as [ModelPick]'s (Beam's). */
    data class TestModel(val place: String? = null) : Screen
    /** The test's prompt, for that run only: the prompts with each one's last score on the test's model (Beam's). */
    data object TestPrompt : Screen
    /** What the test asks: a switch per feature and per app, each with its sentences' count (Beam's). */
    data object Testing : Screen
    /** One group of the test that ran: its sentences, the misses first with what was called (Beam's). */
    data class TestGroup(val group: BenchGroup) : Screen
    /** Adding an Atelier app: its GitHub repo typed as owner/repo; once found, Atelier with the install sheet. */
    data object AtelierAdd : Screen
    /** Adding an Atelier app by its repo's QR code. */
    data object AtelierScan : Screen
    /** Pairing with an acp-bridge: the viewfinder on the QR `acp-bridge pair` prints. */
    data object AgentsPair : Screen
    /** The paired bridges: the one in use, another to use instead, pair another, forget the one in use. */
    data object AgentsBridge : Screen
    /** A folder [path] ("" for the folder itself) in a session's folder [cwd] on the bridge's computer, to look through. */
    data class AgentFiles(val agent: String, val cwd: String, val path: String = "") : Screen
    /** A file there, shown by its kind: markdown, a table, lines, or a card. */
    data class AgentFile(val agent: String, val cwd: String, val path: String) : Screen
    /** One agent's sessions on the bridge, by their folders. */
    data class AgentSessions(val agent: String) : Screen
    /** The sessions of one of an agent's folders, [cwd], and a new chat there. */
    data class AgentFolder(val agent: String, val cwd: String) : Screen
    /** One of an agent's sessions, in its folder [cwd]: its conversation, where a hold is the next prompt. */
    data class AgentChat(val agent: String, val session: String, val cwd: String) : Screen
    /** A new chat of an agent in the folder [cwd], from its header's +: a hold there starts it, and the page becomes its conversation. */
    data class AgentNew(val agent: String, val cwd: String) : Screen
    /** An Atelier app's page: the app runs while it is up. */
    data class AtelierApp(val id: String) : Screen
    /** An Atelier app's info: where it came from, what it may use, delete. */
    data class AtelierInfo(val id: String) : Screen
}

/** Where the metronome keeps playing: its own screen (the room with it open) and its time signature, sound and practice. */
val METRONOME_PAGES = setOf(Screen.Home, Screen.TimeSignature, Screen.MetronomeSound, Screen.Practice)

/**
 * A plain back stack. No navigation library: seven screens, one activity, and the
 * only transitions are push and pop.
 */
class NavState {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    val canPop: Boolean get() = stack.size > 1

    /** Whether the last pop was an edge drag ([Sound.EdgeBack]): the page has left already, so the shell does not slide it. */
    var lastPopWasSwipe = false; private set

    /**
     * Every arrival fires [sound], whether it came from a tap, the wheel, or the side
     * button: a tap that navigates sounds like navigation. Null is for an arrival that is
     * not its own interaction, of which there are two: a confirmed hold brings Ask up and
     * fires [Sound.ListeningStart] for itself, and a row on Home opens its feature's panel
     * and pushes the item's page in the same tap, where the panel has already sounded.
     *
     * Opening a feature is not a push at all: it is the room, and it sounds where the
     * gesture is, in the design. The rule is the same wherever it is written.
     */
    fun push(s: Screen, sound: Sound? = Sound.ScreenIn) {
        if (current == s) return
        stack.add(s)
        sound?.let(Cues::play)
    }

    /**
     * Swaps the top screen for [s] instead of stacking another: an activation code
     * arriving as a link while the add page is open turns that page into the filled
     * form, and does not leave a second one under it. One arrival, one sound.
     */
    fun replace(s: Screen, sound: Sound? = Sound.ScreenIn, inPlace: Boolean = false) {
        if (current == s) return
        if (!canPop) return push(s, sound)
        if (inPlace) bornInPlace = s
        stack[stack.lastIndex] = s
        sound?.let(Cues::play)
    }

    /**
     * Puts [s] in place of the screen under the one on top, unseen and where it was: a
     * prompt renamed on its rename page is found under its new name when that page pops.
     */
    fun swapUnder(s: Screen) {
        if (stack.size < 3) return
        bornInPlace = s
        stack[stack.lastIndex - 1] = s
    }

    /**
     * The screen a replace put on top as the same page carrying on (an agent's new chat page
     * becoming the session's conversation): the shell shows it where the old one was, with
     * no slide in. Read once by the shell, then cleared.
     */
    var bornInPlace: Screen? = null

    /**
     * Takes [s] out from under the screen on top, silently and unseen: a page that finishes
     * for the one under it too (Weather's Add, over its Places) drops that one, then pops.
     */
    fun drop(s: Screen) {
        val i = stack.lastIndexOf(s)
        if (i in 1 until stack.lastIndex) stack.removeAt(i)
    }

    /**
     * Back to the room, with no sound and no slide: the side button's cut, which is
     * allowed to interrupt anything and is one gesture however many screens were open.
     */
    fun home() {
        if (!canPop) return
        Wheel.hush()
        lastPopWasSwipe = true
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /**
     * Every departure fires [sound]: [Sound.ScreenOut] for a tap, the chevron or the
     * wheel, [Sound.EdgeBack] for the edge drag. A pop that is the result of something
     * else passes that instead, so one interaction is one sound: deleting a photo is
     * [Sound.Destroy], not a delete and a back.
     */
    fun pop(sound: Sound = Sound.ScreenOut) {
        if (!canPop) return
        Wheel.hush()
        lastPopWasSwipe = sound == Sound.EdgeBack
        stack.removeAt(stack.lastIndex)
        Cues.play(sound)
    }
}

/**
 * The scroll wheel arrives as F11 / F10 key events. The activity hands
 * each one to the UI ([com.bleural.croissant.UiShell.wheel]), which ticks here, and whichever
 * screen is on top registers the handler. delta is -1 for up, +1 for down.
 */
object Wheel {
    /**
     * The wheel is a detented potentiometer, not a free spinning mouse wheel, so it
     * cannot physically outrun a person by much. This is a floor on the interval
     * between ticks the app acts on, not a coalescer: events that arrive inside it
     * are dropped, so a hurried spin travels a little less far and the selection
     * never runs away from the sound. ~~100 ms, ten a second, a little above where
     * real use sits (2026-09-16)~~: measured, the wheel reports on a ~52 ms grid
     * and a normal spin comes at 104 ms, so 100 dropped half of a fast flick. 45 ms
     * passes every tick the sensor can send and still drops a double report (2026-09-23).
     */
    private const val MIN_MS = 45L

    private var lastAt = 0L
    private val handlers = ArrayList<(Int) -> Boolean>()

    /** The screen on top registers last and hears the wheel; a sheet over it registers later still. */
    fun push(h: (Int) -> Boolean) { handlers.add(h) }

    /**
     * The room registers here instead, under everything, however late it comes.
     *
     * Registration order is the stack's order only as long as nobody re-registers, and
     * the room does: it gives the wheel up while a pushed screen covers it
     * ([WheelTarget], LocalLayerVisible) and asks for it back when that screen pops. Its
     * own open panel never went away, being drawn by the room itself, so the room came
     * back on top of it and the next tick reached the room rather than the panel: one
     * tick out of Settings closed the whole panel instead of walking the rows
     * (2026-09-18, coming back from Device). The room is the floor of the app, so it says
     * so here rather than relying on being early.
     */
    fun pushFloor(h: (Int) -> Boolean) { handlers.add(0, h) }

    fun remove(h: (Int) -> Boolean) { handlers.remove(h) }

    /**
     * A handler returns whether the tick moved a selection: [Sound.WheelMoved] if so,
     * [Sound.WheelStill] if not. The step belongs to a different thing being selected,
     * not to the key arriving. A tick that does something else (closes a panel, leaves
     * Home) fires that and returns false.
     *
     * Which means a tick always fires one of the two, and a handler that fired its own is
     * not two sounds: [Sound.WheelStill] has no cue ([com.bleural.croissant.audio.Cues.cueFor]),
     * so it is the sound of a detent that did nothing, which is silence.
     */
    fun tick(delta: Int) {
        val now = clock()
        if (hushed) {
            // Every tick of the spin that went back puts the silence off again, so only a
            // wheel that has been still for [QUIET_MS] is heard by the page it landed on.
            val still = now - hushAt >= QUIET_MS
            hushAt = now
            if (!still) return
            hushed = false
        }
        if (now - lastAt < MIN_MS) return
        lastAt = now
        Cues.play(if (handlers.lastOrNull()?.invoke(delta) == true) Sound.WheelMoved else Sound.WheelStill)
    }

    /**
     * One spin, one step back. The wheel does not stop turning because a step was taken,
     * and the page it lands on would hear the tail of the spin as its own: a spin out of a
     * tape could walk back through the Library, close the Recorder and climb the menu
     * (2026-09-22; first seen 2026-09-18 going back twice out of Battery). So
     * every step back hushes the wheel, whatever took it (the pull, a pop, a panel
     * closing, the room going Home), and the wheel is deaf until it has been still for
     * [QUIET_MS]. Here and not in each page, because the page that took the step is gone
     * by the time the next tick lands, and a page that forgot to ask would let it through.
     * A step taken by a finger hushes too, which costs nothing: the wheel is still then.
     */
    fun hush() { hushed = true; hushAt = clock() }

    /** How long the wheel has to be still after a step back before it is heard again. */
    private const val QUIET_MS = 400L

    private var hushed = false
    private var hushAt = 0L

    /** The time, in milliseconds; a test brings its own. */
    internal var clock: () -> Long = SystemClock::uptimeMillis
}

/**
 * The side button's short press while the camera's live preview is up: it is the
 * shutter, not sleep. The camera registers the handler for exactly as long as the
 * preview shows (not once a shot is up, when a short press is a short press again),
 * and the activity asks here first. [press] says whether the press was taken.
 */
object ShutterKey {
    var handler: (() -> Unit)? = null
    fun press(): Boolean { val h = handler ?: return false; h(); return true }
}
