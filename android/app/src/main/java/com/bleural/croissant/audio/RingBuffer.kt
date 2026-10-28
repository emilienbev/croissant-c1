package com.bleural.croissant.audio

/**
 * Fixed-size circular byte buffer holding the most recent audio.
 *
 * This is what makes the hold-threshold free: the microphone runs continuously and
 * every chunk lands here, so when a press is finally confirmed as a hold we can reach
 * *backwards* in time and recover both the audio during the threshold and a little
 * before the press. Nothing is lost, and no connection is opened speculatively.
 */
class RingBuffer(private val capacity: Int) {

    private val buf = ByteArray(capacity)
    private var writePos = 0
    private var filled = 0

    @Synchronized
    fun write(src: ByteArray, len: Int) {
        var offset = 0
        var remaining = len
        // A chunk larger than the whole buffer can only leave its tail behind.
        if (remaining > capacity) {
            offset = remaining - capacity
            remaining = capacity
        }
        while (remaining > 0) {
            val room = capacity - writePos
            val n = if (remaining < room) remaining else room
            System.arraycopy(src, offset, buf, writePos, n)
            writePos = (writePos + n) % capacity
            offset += n
            remaining -= n
            if (filled < capacity) filled = minOf(capacity, filled + n)
        }
    }

    /** Oldest-to-newest copy of everything currently held. */
    @Synchronized
    fun snapshot(): ByteArray {
        val out = ByteArray(filled)
        if (filled == 0) return out
        val start = (writePos - filled + capacity) % capacity
        val firstRun = minOf(filled, capacity - start)
        System.arraycopy(buf, start, out, 0, firstRun)
        if (firstRun < filled) {
            System.arraycopy(buf, 0, out, firstRun, filled - firstRun)
        }
        return out
    }

    @Synchronized
    fun clear() {
        writePos = 0
        filled = 0
    }
}
