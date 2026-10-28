package com.bleural.croissant

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.math.sqrt

/**
 * Rest and Sleep (Settings > Device > Battery): what the always-hot mic,
 * and the wake lock that keeps it hot, do while the screen is off.
 *
 * [Stage.Resting]: the screen went off, so the mic goes with it; the CPU stays up (Wi-Fi and
 * the tailnet with it) and reads the accelerometer, so a pick-up brings the mic back before
 * the thumb is on the button. Brought back, it stays on until the screen next goes off.
 * [Stage.Asleep]: still for the Sleep time with the screen off, the accelerometer goes too and
 * the wake lock is released, so the device suspends. Nothing but the button (or an alarm)
 * wakes it from there: the R1 has no wake-up motion sensor.
 * The screen coming on or a press put it back to [Stage.Awake].
 *
 * [busy] is asked at screen off and on every check; while it says yes (a hold, an answer, a
 * recording, a ringing timer, the tuner) the mic stays and the device counts as in use.
 * [onStage] runs on the main thread.
 */
class IdleWatch(
    ctx: Context,
    private val busy: () -> Boolean,
    private val onStage: (Stage) -> Unit,
) : SensorEventListener {

    enum class Stage { Awake, Resting, Asleep }

    companion object {
        private const val TAG = "IdleWatch"
        /**
         * A block this far from the resting vector, in m/s², is the device being moved.
         * Single samples on a desk stray up to 0.25, blocks to 0.07; a pick-up read 0.86.
         */
        private const val MOVED = 0.3f
        /** Samples averaged into one block: 200 ms at 50 Hz. */
        private const val BLOCK = 10
        /** How fast the resting vector follows the blocks: about a second. */
        private const val REST_ALPHA = 0.2f
        /** 50 Hz is the HAL's floor; batched a second at a time in the sensor hub's FIFO. */
        private const val SAMPLE_US = 20_000
        private const val BATCH_US = 1_000_000
        private const val CHECK_MS = 15_000L
        private const val PEAK_LOG_MS = 60_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val sensors = ctx.getSystemService(SensorManager::class.java)
    private val accel: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var screenOn = true
    /** The Sleep time in ms, or [Idle.NEVER]. */
    private var sleepAfter = Idle.NEVER
    /** The mic came back since the screen went off (a move, a press): it stays until the next screen off. */
    private var revived = false
    var stage = Stage.Awake
        private set

    private var listening = false
    private var stillSince = SystemClock.elapsedRealtime()
    private val rest = FloatArray(3)
    private var hasRest = false
    private val sum = FloatArray(3)
    private var n = 0
    private var peak = 0f
    private var peakSince = 0L

    fun setSleepAfter(ms: Int) { sleepAfter = ms; update() }

    fun setScreen(on: Boolean) {
        screenOn = on
        stillSince = SystemClock.elapsedRealtime()
        revived = false
        if (on) to(Stage.Awake, "screen on")
        update()
    }

    /** A press: the clock starts again, and the mic comes back now rather than when the screen-on broadcast lands. */
    fun used() {
        stillSince = SystemClock.elapsedRealtime()
        if (stage == Stage.Awake) return
        revived = true
        to(Stage.Awake, "used")
        update()
    }

    /** Rest if it is due, listen while something needs the accelerometer, check again later. */
    private fun update() {
        handler.removeCallbacks(check)
        if (screenOn || stage == Stage.Asleep) { listen(false); return }
        if (stage == Stage.Awake && !revived && !busy()) to(Stage.Resting, "screen off")
        listen(stage == Stage.Resting || sleepAfter != Idle.NEVER)
        handler.postDelayed(check, CHECK_MS)
    }

    private val check: Runnable = Runnable {
        if (screenOn || stage == Stage.Asleep) return@Runnable
        val now = SystemClock.elapsedRealtime()
        if (busy()) stillSince = now
        val still = now - stillSince
        if (sleepAfter != Idle.NEVER && still >= sleepAfter) {
            listen(false)
            to(Stage.Asleep, "still ${still / 1000} s")
            return@Runnable
        }
        update()
    }

    private fun to(s: Stage, why: String) {
        if (s == stage) return
        Log.i(TAG, "$stage -> $s ($why)")
        stage = s
        onStage(s)
    }

    private fun listen(on: Boolean) {
        if (on == listening || accel == null) return
        listening = on
        if (on) {
            // A block cut short by the last stop would otherwise open this one.
            hasRest = false; n = 0; sum.fill(0f)
            peak = 0f; peakSince = SystemClock.elapsedRealtime()
            sensors.registerListener(this, accel, SAMPLE_US, BATCH_US, handler)
        } else {
            sensors.unregisterListener(this)
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        for (i in 0..2) sum[i] += e.values[i]
        if (++n < BLOCK) return
        val v = FloatArray(3) { sum[it] / n }
        sum.fill(0f); n = 0
        if (!hasRest) { v.copyInto(rest); hasRest = true; return }
        val dx = v[0] - rest[0]; val dy = v[1] - rest[1]; val dz = v[2] - rest[2]
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        for (i in 0..2) rest[i] += REST_ALPHA * (v[i] - rest[i])
        if (d > peak) peak = d
        val now = SystemClock.elapsedRealtime()
        if (now - peakSince >= PEAK_LOG_MS) {
            Log.d(TAG, "peak %.3f m/s² over the last minute, %s, still %d s".format(peak, stage, (now - stillSince) / 1000))
            peak = 0f; peakSince = now
        }
        if (d > MOVED) {
            stillSince = now
            if (stage == Stage.Resting) {
                revived = true
                to(Stage.Awake, "moved %.2f m/s²".format(d))
                update()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun close() {
        handler.removeCallbacks(check)
        listen(false)
    }
}
