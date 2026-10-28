package com.bleural.croissant.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The 44-byte canonical WAV header for the capture's own format, 16 kHz mono 16-bit
 * PCM. A recording is written with a zero data size and the header is patched as it
 * grows (every ten seconds) and on stop, so a crash or a reboot mid-recording leaves
 * a file that is at worst ten seconds short of playable; [RecordingsStore] repairs
 * the size from the file's length on the next start.
 */
object Wav {
    const val HEADER_BYTES = 44
    private const val RATE = AudioCapture.SAMPLE_RATE
    private const val CHANNELS = AudioCapture.CHANNELS
    private const val BYTES_PER_SAMPLE = AudioCapture.BYTES_PER_SAMPLE

    fun header(dataBytes: Int): ByteArray {
        val b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt(36 + dataBytes)
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)                                   // PCM chunk size
        b.putShort(1)                                  // PCM
        b.putShort(CHANNELS.toShort())
        b.putInt(RATE)
        b.putInt(RATE * CHANNELS * BYTES_PER_SAMPLE)   // byte rate
        b.putShort((CHANNELS * BYTES_PER_SAMPLE).toShort())
        b.putShort((8 * BYTES_PER_SAMPLE).toShort())
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(dataBytes)
        return b.array()
    }

    /** Rewrites the two sizes in an open file's header; the position is left at the end. */
    fun patch(raf: RandomAccessFile, dataBytes: Long) {
        val d = dataBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        raf.seek(4); raf.write(le(36 + d))
        raf.seek(40); raf.write(le(d))
        raf.seek(raf.length())
    }

    /** The data size the header claims, or null when the file is not a WAV of ours. */
    fun dataSize(file: File): Int? = runCatching {
        RandomAccessFile(file, "r").use { r ->
            if (r.length() < HEADER_BYTES) return null
            val head = ByteArray(HEADER_BYTES); r.readFully(head)
            if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 36, 4, Charsets.US_ASCII) != "data") return null
            ByteBuffer.wrap(head, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }
    }.getOrNull()

    /** Sets the header's sizes from the file's length: what a file left open by a crash needs. */
    fun repair(file: File) {
        RandomAccessFile(file, "rw").use { patch(it, it.length() - HEADER_BYTES) }
    }

    /** Milliseconds of audio in [dataBytes] of the capture's format. */
    fun millis(dataBytes: Long): Long = dataBytes / AudioCapture.BYTES_PER_MS

    private fun le(v: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
}
