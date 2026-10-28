package com.bleural.croissant.ui.beam

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.bleural.croissant.atelier.InstalledApp
import com.bleural.croissant.ui.RoomState
import kotlin.math.floor

/*
 * The room's ground; the room itself is the side wheel
 * (SideWheel.kt), Home is Home.kt.
 */

/**
 * The ground under the room: the two nearest places' grounds blended by where it is, going to
 * Home's as Home comes; an app's place in its own hue ([apps], 2026-10-17).
 */
internal fun groundAt(room: RoomState, apps: List<InstalledApp>): Color = lerp(blend(room, apps) { it.ground }, HOME.ground, room.home.coerceIn(0f, 1f))

private inline fun blend(room: RoomState, apps: List<InstalledApp>, of: (Ramp) -> Color): Color {
    val places = room.places
    val last = places.lastIndex
    val p = room.pos.coerceIn(0f, last.toFloat())
    val lo = floor(p).toInt()
    val hi = (lo + 1).coerceAtMost(last)
    return lerp(of(rampOf(places[lo], apps)), of(rampOf(places[hi], apps)), p - lo)
}
