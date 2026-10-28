package com.bleural.croissant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.bleural.croissant.DeviceLevels
import com.bleural.croissant.Prefs
import com.bleural.croissant.R

/**
 * The timer sound: `res/raw/ringtone_bonvoyage.mp3` on the alarm stream, played
 * [PLAYS] times over unless [stop] comes first, then [onFinished] so the caller can
 * let the screen go while the ringing page stays up.
 */
class Ringtone(private val ctx: Context, private val onFinished: () -> Unit) {
    companion object {
        const val PLAYS = 4
        private const val TAG = "Ringtone"
    }

    private var player: MediaPlayer? = null
    private var played = 0

    fun start() {
        if (player != null) return
        val p = MediaPlayer.create(
            ctx, R.raw.ringtone_bonvoyage,
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            0,
        )
        if (p == null) { Log.e(TAG, "could not open the ringtone"); onFinished(); return }
        p.setOnCompletionListener {
            played++
            if (played < PLAYS && player === p) p.start()
            else { stop(); onFinished() }
        }
        p.setOnErrorListener { _, what, extra -> Log.e(TAG, "playback error $what/$extra"); stop(); onFinished(); true }
        val g = DeviceLevels.gain(Prefs.volume(ctx))
        p.setVolume(g, g)
        player = p
        p.start()
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }
}
