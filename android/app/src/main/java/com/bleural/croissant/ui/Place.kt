package com.bleural.croissant.ui

import com.bleural.croissant.WheelKeep

/**
 * A place in a room: what the room's `pos` counts. A feature in every design; in Beam an Atelier
 * app too, pinned to the wheel or among the recents above Ask (2026-10-17). [feature] is what it
 * belongs to, an app's Atelier, so a reader asking which feature the room is on still has one.
 */
sealed interface Place {
    val feature: Feature

    /** Its key in what Core keeps ([WheelKeep]): a feature's name, an app's [WheelKeep.app]. */
    val key: String

    data class Of(override val feature: Feature) : Place {
        override val key: String get() = feature.name
    }

    data class App(val id: String) : Place {
        override val feature: Feature get() = Feature.Atelier
        override val key: String get() = WheelKeep.app(id)
    }

    /** In Beam, an agent on the paired bridge (claude, codex), pinned to the wheel or among the recents. */
    data class Agent(val id: String) : Place {
        override val feature: Feature get() = Feature.Agents
        override val key: String get() = WheelKeep.agent(id)
    }

    companion object {
        /** The place a kept [key] names, or null for one this build has no feature for. */
        fun of(key: String): Place? =
            WheelKeep.appOf(key)?.let(::App) ?: WheelKeep.agentOf(key)?.let(::Agent)
                ?: Feature.entries.firstOrNull { it.name == key }?.let(::Of)
    }
}
