package com.bleural.croissant

/**
 * What the side wheel keeps across a restart (2026-10-17), as plain lists of keys: the places
 * last opened, newest first, and the Atelier apps pinned to it, in the order pinned. A key is a
 * design's own word for a place (Beam's: a feature's name); an Atelier app's is [app], which
 * Core reads to drop an app that is deleted. Pure, so the rules run on the Mac.
 */
object WheelKeep {
    /** How many recents are kept: more than the three shown, so one that goes leaves the rest. */
    const val KEEP = 8

    private const val APP = "app:"
    private const val AGENT = "agent:"

    fun app(id: String): String = APP + id

    /** The app a key names, or null for one that is not an app's. */
    fun appOf(key: String): String? = key.takeIf { it.startsWith(APP) }?.removePrefix(APP)?.takeIf { it.isNotEmpty() }

    /** An agent's key (the bridge's id for it: claude, codex), pinned or among the recents. */
    fun agent(id: String): String = AGENT + id

    /** The agent a key names, or null for one that is not an agent's. */
    fun agentOf(key: String): String? = key.takeIf { it.startsWith(AGENT) }?.removePrefix(AGENT)?.takeIf { it.isNotEmpty() }

    /** [key] opened: first, once, the oldest past [KEEP] gone. */
    fun opened(recents: List<String>, key: String): List<String> = (listOf(key) + recents.filter { it != key }).take(KEEP)

    /** [id] pinned last, or unpinned; pinning one already pinned keeps its place. */
    fun pinned(pins: List<String>, id: String, on: Boolean): List<String> = when {
        !on -> pins - id
        id in pins -> pins
        else -> pins + id
    }

    /** Both lists without app [id], deleted. */
    fun dropApp(recents: List<String>, pins: List<String>, id: String): Pair<List<String>, List<String>> =
        recents.filter { it != app(id) } to (pins - id)

    /** As Prefs keeps a list: one string, commas between; keys have none (an app id is letters, digits and dashes). */
    fun encode(keys: List<String>): String = keys.joinToString(",")

    fun decode(s: String?): List<String> = s.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
