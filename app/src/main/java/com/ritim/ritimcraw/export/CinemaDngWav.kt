package com.ritim.ritimcraw.export

import java.io.OutputStream

/**
 * The Kotlin half of the CinemaDNG sequence's companion WAV (night E-F,
 * docs/CINEMADNG_EXPORT_PLAN.md R1-4): the PCM bytes are the SOURCE's own
 * (read through the native readAudio seam — no resampling, the R1-4 rule),
 * and this object only frames them: the canonical 44-byte RIFF/WAVE PCM
 * 16-bit header + the verbatim samples, written to the caller's stream
 * (the SAF child document's OutputStream).
 *
 * BYTE CONTRACT (the core WavWriter twin, test_dng_sequence's
 * wav_header_field_by_field pins the same numbers):
 *   "RIFF" riffSize "WAVE"
 *   "fmt " 16, format=1 (PCM), channels, sampleRate,
 *   byteRate = rate*channels*2, blockAlign = channels*2, bits=16
 *   "data" dataSize
 *
 * The header carries the FINAL sizes computed up front (the caller knows
 * the sample count — the whole-clip PCM is in memory), so nothing is ever
 * back-patched mid-file. Sizes are computed in Long and saturate at the
 * WAV spec's 0xffffffff ceiling — an honest limit reported by [sizes], a
 * never a silent wrap.
 */
object CinemaDngWav {

    /** The WAV spec's own 4 GiB ceiling for the uint32 RIFF/data fields. */
    const val MAX_WAV_BYTES = 0xffffffffL

    /** The canonical header's byte length. */
    const val HEADER_BYTES = 44

    /**
     * The header sizes for [dataBytes] of PCM: (riffSize, dataSize).
     * riffSize = 36 + dataBytes, both capped at 0xffffffff (the field
     * width — a bigger clip is an honest failure, never a wrapped size a
     * reader would truncate).
     */
    fun sizes(dataBytes: Long): Pair<Long, Long> {
        val capped = dataBytes.coerceAtMost(MAX_WAV_BYTES)
        val riff = (36L + capped).coerceAtMost(MAX_WAV_BYTES)
        return Pair(riff, capped)
    }

    /**
     * Writes the whole WAV: the 44-byte header + [pcm] verbatim (the
     * source's own interleaved 16-bit samples — the bytes are copied,
     * never re-mathed). [sampleRate] and [channels] travel exactly as the
     * source reports them (no resampling, no downmix). Throws
     * IllegalArgumentException for an unusable argument set (the same
     * honesty shape as the core writer), IOException as the stream
     * reports it (the caller maps it to the export's FAILED detail).
     */
    fun write(out: OutputStream, pcm: ShortArray, sampleRate: Int, channels: Int) {
        require(sampleRate > 0) { "sample rate must be positive" }
        require(channels > 0) { "channel count must be positive" }
        val dataBytes = pcm.size.toLong() * 2L
        require(dataBytes <= MAX_WAV_BYTES) {
            "audio exceeds the WAV spec's 4 GiB ceiling ($dataBytes bytes)"
        }
        val (riff, data) = sizes(dataBytes)
        val h = ByteArray(HEADER_BYTES)
        fun fourcc(off: Int, s: String) {
            for (i in 0 until 4) h[off + i] = s[i].code.toByte()
        }
        fun u16(off: Int, v: Int) {
            h[off] = (v and 0xff).toByte()
            h[off + 1] = ((v shr 8) and 0xff).toByte()
        }
        fun u32(off: Int, v: Long) {
            h[off] = (v and 0xff).toByte()
            h[off + 1] = ((v shr 8) and 0xff).toByte()
            h[off + 2] = ((v shr 16) and 0xff).toByte()
            h[off + 3] = ((v shr 24) and 0xff).toByte()
        }
        fourcc(0, "RIFF")
        u32(4, riff)
        fourcc(8, "WAVE")
        fourcc(12, "fmt ")
        u32(16, 16) // PCM fmt chunk size
        u16(20, 1) // format: PCM
        u16(22, channels)
        u32(24, sampleRate.toLong())
        u32(28, sampleRate.toLong() * channels * 2L) // byte rate
        u16(32, channels * 2) // block align
        u16(34, 16) // bits per sample
        fourcc(36, "data")
        u32(40, data)
        out.write(h)
        // The samples VERBATIM: little-endian int16, the source's own bytes.
        val buf = ByteArray(8192)
        var off = 0
        while (off < pcm.size) {
            val n = minOf(buf.size / 2, pcm.size - off)
            for (i in 0 until n) {
                val v = pcm[off + i].toInt()
                buf[i * 2] = (v and 0xff).toByte()
                buf[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
            }
            out.write(buf, 0, n * 2)
            off += n
        }
    }
}
