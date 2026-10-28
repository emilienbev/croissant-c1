package com.bleural.croissant.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The eleven features, in room order (the Recorder second beside Ask, the two microphone
 * features together; the Metronome and then the Translator last
 * before Settings, 2026-09-22 and 2026-09-23; Weather beside the Calendar, the two about the
 * day, 2026-09-29): the order of the panels, of the tiles in the stack and of the objects.
 * Nothing keeps an ordinal across a restart, so a feature can go anywhere in it. Each
 * carries its word and its hue; what its pages draw is each design's own.
 */
enum class Feature(val word: String, val hue: Color) {
    Ask("Ask", Hue.Ask),
    Recorder("Recorder", Hue.Recorder),
    Reminders("Reminders", Hue.Reminders),
    Timers("Timers", Hue.Timers),
    Camera("Camera", Hue.Camera),
    Calendar("Calendar", Hue.Calendar),
    Weather("Weather", Hue.Weather),
    Photos("Photos", Hue.Photos),
    Metronome("Metronome", Hue.Metronome),
    Translator("Translator", Hue.Translator),
    Settings("Settings", Hue.Settings),
    /**
     * Small apps anyone writes (atelier/). Beam's alone: it puts it before Settings in its own
     * room (beam/Places.kt); every other design's room is [room], which leaves it out. Last
     * here, with Agents, so their ordinals stay their room's places.
     */
    Atelier("Atelier", Hue.Atelier),
    /** Coding agents on a computer running acp-bridge (Agents.kt). Beam's alone too, before Atelier in its room. */
    Agents("Agents", Hue.Agents);

    companion object {
        /** The room every design but Beam has: every feature but [Atelier] and [Agents], in order. */
        val room: List<Feature> = entries - Atelier - Agents
    }

    val glyph: ImageVector
        get() = when (this) {
            Ask -> Glyphs.Ask
            Recorder -> Glyphs.Tape
            Reminders -> Glyphs.Reminders
            Timers -> Glyphs.Timers
            Camera -> Glyphs.Camera
            Calendar -> Glyphs.Calendar
            Weather -> Glyphs.Sun
            Photos -> Glyphs.Photos
            Metronome -> Glyphs.Metronome
            Translator -> Glyphs.Translate
            Settings -> Glyphs.Settings
            Atelier -> Glyphs.Atelier
            Agents -> Glyphs.Agents
        }
}

/** The feature a screen belongs to; null for Home. Every settings page is Settings. */
val Screen.feature: Feature?
    get() = when (this) {
        Screen.Home -> null
        Screen.Library, is Screen.Recording, is Screen.RenameRecording, is Screen.SendTape -> Feature.Recorder
        is Screen.Reminder, Screen.ReminderAdd -> Feature.Reminders
        Screen.TimerAdd -> Feature.Timers
        is Screen.Event -> Feature.Calendar
        is Screen.Photo, Screen.Generated, is Screen.GeneratedItem -> Feature.Photos
        Screen.History -> Feature.Ask
        Screen.TimeSignature, Screen.MetronomeSound, Screen.Practice, Screen.Tuner, Screen.Tuning -> Feature.Metronome
        is Screen.TranslatorLanguages, Screen.TranslatorSaved -> Feature.Translator
        Screen.WeatherPlaces, Screen.WeatherAdd -> Feature.Weather
        Screen.AtelierAdd, Screen.AtelierScan, is Screen.AtelierApp, is Screen.AtelierInfo -> Feature.Atelier
        Screen.AgentsPair, Screen.AgentsBridge, is Screen.AgentSessions, is Screen.AgentFolder, is Screen.AgentChat, is Screen.AgentNew, is Screen.AgentFiles, is Screen.AgentFile -> Feature.Agents
        else -> Feature.Settings
    }
