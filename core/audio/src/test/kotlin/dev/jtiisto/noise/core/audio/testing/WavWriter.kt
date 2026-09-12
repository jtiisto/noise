package dev.jtiisto.noise.core.audio.testing

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream

/**
 * Minimal 16-bit PCM stereo WAV writer for the offline render harness.
 *
 * 16-bit rather than float: the point of these files is to be dropped into
 * Audacity, a phone, or a Python script, and every one of those reads 16-bit
 * PCM without argument. The engine's own path stays float end to end.
 */
object WavWriter {

    fun write(file: File, left: FloatArray, right: FloatArray, sampleRate: Int) {
        require(left.size == right.size) { "channel length mismatch" }
        file.parentFile?.mkdirs()
        BufferedOutputStream(file.outputStream(), 1 shl 16).use { out ->
            val frames = left.size
            val dataBytes = frames * 2 * 2
            out.writeAscii("RIFF")
            out.writeLe32(36 + dataBytes)
            out.writeAscii("WAVE")
            out.writeAscii("fmt ")
            out.writeLe32(16)              // PCM chunk size
            out.writeLe16(1)               // format = PCM
            out.writeLe16(2)               // channels
            out.writeLe32(sampleRate)
            out.writeLe32(sampleRate * 2 * 2) // byte rate
            out.writeLe16(4)               // block align
            out.writeLe16(16)              // bits per sample
            out.writeAscii("data")
            out.writeLe32(dataBytes)
            for (i in 0 until frames) {
                out.writeLe16(toPcm16(left[i]))
                out.writeLe16(toPcm16(right[i]))
            }
        }
    }

    private fun toPcm16(sample: Float): Int {
        val clamped = when {
            sample > 1f -> 1f
            sample < -1f -> -1f
            else -> sample
        }
        return (clamped * 32767f).toInt()
    }

    private fun OutputStream.writeAscii(s: String) {
        for (c in s) write(c.code)
    }

    private fun OutputStream.writeLe16(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun OutputStream.writeLe32(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }
}
