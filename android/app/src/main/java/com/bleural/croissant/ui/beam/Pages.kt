package com.bleural.croissant.ui.beam

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import com.bleural.croissant.Core
import com.bleural.croissant.VoiceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.net.ModelSlot
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.PanelDeps
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.feature
import com.bleural.croissant.ui.rememberAskConversation

/* The router: what each opened feature and each pushed page is. */

/** A feature opened, on the whole canvas. */
@Composable
internal fun Opened(f: Feature, d: PanelDeps, core: Core, onGallery: () -> Unit) {
    when (f) {
        Feature.Ask -> AskRoute(d, core)
        Feature.Recorder -> RecorderDeck(d)
        Feature.Reminders -> RemindersPage(d.reminders, onOpen = { d.nav.push(Screen.Reminder(it)) }, onAdd = { d.nav.push(Screen.ReminderAdd) }, onBack = d::close)
        Feature.Timers -> TimersPage(d.timers, onCancel = { d.onCancelTimer(it); Cues.play(Sound.Destroy) }, onRename = { id, name -> runCatching { core.timers.rename(id, name) } }, onAdd = { d.nav.push(Screen.TimerAdd) }, onBack = d::close)
        Feature.Camera -> CameraPage(d.voice, d.level, onPhoto = d.onPhoto, onGallery = onGallery, onBack = d::close)
        Feature.Calendar -> CalendarPage(d.events, onOpen = { d.nav.push(Screen.Event(it)) }, onBack = d::close)
        Feature.Weather -> WeatherPage(d.forecasts, onPlaces = { d.nav.push(Screen.WeatherPlaces) }, onBack = d::close)
        Feature.Photos -> PhotosPage(
            d.photos, d.generated,
            onOpen = { d.nav.push(Screen.Photo(it.path)) },
            onGenerated = { d.nav.push(Screen.Generated) },
            onSend = { d.nav.push(Screen.Send(it)) },
            onDelete = d.onDeletePhotos,
            onBack = d::close,
        )
        Feature.Metronome -> MetronomePage(d.metronome, d.metronomeState, onTimeSig = { d.nav.push(Screen.TimeSignature) }, onSound = { d.nav.push(Screen.MetronomeSound) }, onPractice = { d.nav.push(Screen.Practice) }, onTuner = { d.nav.push(Screen.Tuner) }, onBack = d::close)
        Feature.Translator -> TranslatorPage(
            d.voice, d.level, d.translatorPair, d.translations,
            onPick = { d.nav.push(Screen.TranslatorLanguages(it)) },
            onSwap = d.onSwapTranslator,
            onSaved = { d.nav.push(Screen.TranslatorSaved) },
            local = d.local, hears = d.localHears, onLocal = d.onLocal,
            onBack = d::close,
        )
        Feature.Settings -> {
            LaunchedEffect(Unit) { core.refreshUsage(); core.refreshModels() }
            val update by core.update.ui.collectAsStateWithLifecycle()
            SettingsPage(d.device, d.settings, d.simLabel, onOpen = d.nav::push, onBack = d::close, ask = core.chatModel?.let { chatLabel(core, it) },
                usage = usageValue(core.usage), update = updateValue(update))
        }
        Feature.Atelier -> AtelierRoute(d, core)
        Feature.Agents -> AgentsRoute(d, core)
    }
}

/**
 * Every page pushed over the room, by the feature it belongs to (`Screen.feature`: every
 * Settings page is Settings). A feature's branch becomes a function
 * exhaustive over its own screens when it is drawn.
 */
@Composable
internal fun BeamPage(screen: Screen, core: Core, nav: NavState, transforming: MutableState<String?>) {
    // Send and Transform are Photos' pages, though `Screen.feature` files them under Settings.
    if (screen is Screen.Send || screen is Screen.Transform) return PhotosPages(screen, core, nav, transforming)
    when (screen.feature) {
        Feature.Recorder -> RecorderPages(screen, core, nav)
        Feature.Reminders -> RemindersPages(screen, core, nav)
        Feature.Timers -> TimersPages(screen, core, nav)
        Feature.Calendar -> CalendarPages(screen, core, nav)
        Feature.Photos -> PhotosPages(screen, core, nav, transforming)
        Feature.Metronome -> MetronomePages(screen, core, nav)
        Feature.Translator -> TranslatorPages(screen, core, nav)
        Feature.Weather -> WeatherPages(screen, core, nav)
        Feature.Settings -> SettingsPages(screen, core, nav)
        Feature.Atelier -> AtelierPages(screen, core, nav)
        Feature.Agents -> AgentsPages(screen, core, nav)
        // Home is the room, drawn by the shell, its day in place on it (HomeDay.kt).
        null -> Unit
        // History is the one page pushed over Ask; nothing is pushed over the Camera.
        Feature.Ask -> if (screen == Screen.History) HistoryRoute(core, nav)
        Feature.Camera -> Unit
    }
}

/** Ask on the conversation it shows (ui/Conversations.kt). */
@Composable
private fun AskRoute(d: PanelDeps, core: Core) {
    val a = rememberAskConversation(core, d.voice)
    AskPage(
        a.live, d.level, a.conv, a.turns,
        onHistory = { d.nav.push(Screen.History) },
        onNew = { core.newConversation(); Cues.play(Sound.ScreenOut) },
        onBack = d::close,
        onKey = { d.nav.push(Screen.ProviderKey(it)) },
        onModel = { d.nav.push(Screen.ModelPick(ModelSlot.Chat)) },
    )
}

/** History over Ask: a row opens its conversation there; delete takes one or many. */
@Composable
private fun HistoryRoute(core: Core, nav: NavState) {
    val conversations by core.conversations.items.collectAsStateWithLifecycle()
    HistoryPage(
        conversations,
        onOpen = { core.openConversation(it.id); nav.pop() },
        onDelete = core::deleteConversations,
        onBack = nav::pop,
    )
}
