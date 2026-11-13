package com.ritim.ritimcraw.data.metadata

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import com.ritim.ritimcraw.data.SourceKind
import com.ritim.ritimcraw.engine.ContainerIdentityK
import com.ritim.ritimcraw.engine.NativeSource

/**
 * IN-13 Stage A — the device-gated extraction pass. THIN by design: every
 * read goes through an existing seam (nativePreviewOpen / nativeFramePtsUs
 * / nativeAudioInfo / the repository fingerprint) except the ONE new
 * stateless container-identity probe ([NativeSource.jniContainerIdentity])
 * — the camera/lens keys no other seam reaches (the mission's
 * "genuinely unreachable" rule). Every KNOWN/ESTIMATED/UNKNOWN verdict
 * lives in the pure [MetadataBuilder]; this class only gathers facts.
 *
 * MediaExtractor cannot run in JVM unit tests (framework stubs throw), so
 * the encoded probe is a device-gated adapter exactly like
 * MediaExtractorTrackReader; the pure assembly it feeds is JVM-tested
 * with fakes ([MetadataBuilderTest]).
 *
 * Never throws on the probe path: a failed read degrades to UNKNOWN
 * fields (the honest record), and the caller keeps whatever it already
 * had. The fd discipline is the gyro-probe one: the native side dup's the
 * descriptor, so the caller's copy is closed here after the probe.
 */
class MetadataExtractor {

    /** The platform's Dolby Vision mime (the DV diagnosis input). */
    internal companion object {
        val DOLBY_VISION_MIME: String = MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION

        /** A fresh read-only descriptor, detached for the probe (-1 = unavailable). */
        fun interface FdProvider { fun open(): Int }
    }

    /**
     * Probes one MCRAW clip statelessly and builds its honest record. All
     * native reads run on the CALLER's thread (each native probe is
     * self-contained and thread-safe; the handle-based reads below open
     * and close their own throwaway preview source, never a live session).
     */
    internal fun mcraw(sourceId: String, fdProvider: FdProvider): MetadataRecord {
        // The app deliberately runs without the .so (the shell works); the
        // pass-through seams would throw UnsatisfiedLinkError then. The
        // honest record for an unprobeable clip is all-UNKNOWN.
        if (!NativeSource.nativeAvailable) {
            return MetadataBuilder.mcraw(sourceId, McrawFacts())
        }
        val fd = fdProvider.open()
        if (fd < 0) return MetadataBuilder.mcraw(sourceId, McrawFacts())
        try {
            // Geometry + frame count through the existing preview-open seam
            // (a throwaway handle closed below, never a live session's).
            val opened = NativeSource.jniPreviewOpen(fd)
            if (opened == null || opened.size < 6) {
                return MetadataBuilder.mcraw(sourceId, McrawFacts())
            }
            val handle = opened[0].toLong()
            val frameCount = opened[1]
            val width = opened[2]
            val height = opened[3]
            try {
                val firstPts = NativeSource.jniFramePtsUs(handle, 0)
                val lastPts =
                    if (frameCount > 1) NativeSource.jniFramePtsUs(handle, frameCount - 1) else -1L
                val audio = NativeSource.jniAudioInfo(handle)
                val identity = NativeSource.jniContainerIdentity(fd)
                return MetadataBuilder.mcraw(
                    sourceId,
                    McrawFacts(
                        frameCount = frameCount,
                        width = width,
                        height = height,
                        bitDepth = 16, // MCRAW stores 16-bit samples (FrameInfo)
                        firstPtsUs = if (firstPts >= 0) firstPts else null,
                        lastPtsUs = if (lastPts >= 0) lastPts else null,
                        audioSampleRateHz = audio?.getOrNull(0),
                        audioChannels = audio?.getOrNull(1),
                        identity = identity?.toFacts(),
                    ),
                )
            } finally {
                NativeSource.jniCloseSource(handle)
            }
        } catch (e: Exception) {
            // A failed read degrades to the honest all-UNKNOWN record,
            // never a fabricated value.
            return MetadataBuilder.mcraw(sourceId, McrawFacts())
        } finally {
            // The probe dup'd the fd inside Source::open; close the caller's
            // copy (the descriptor discipline of the stateless probes).
            closeFd(fd)
        }
    }

    /**
     * Probes one encoded (MP4) clip through MediaExtractor and builds its
     * honest record. The track scan mirrors MediaExtractorTrackReader's
     * (first video track, first audio track's description); the sample
     * walk builds the pts facts for the measured fps.
     */
    internal fun encoded(sourceId: String, fdProvider: FdProvider): MetadataRecord {
        val fd = fdProvider.open()
        if (fd < 0) return MetadataBuilder.encoded(sourceId, EncodedFacts())
        val descriptor = ParcelFileDescriptor.adoptFd(fd)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(descriptor.fileDescriptor)
            var video = -1
            var audioMime: String? = null
            var audioRate = 0
            var audioChannels = 0
            var audioSeen = false
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (video < 0 && mime.startsWith("video/")) {
                    video = i
                    continue
                }
                if (!audioSeen && mime.startsWith("audio/")) {
                    audioSeen = true
                    audioMime = mime
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        audioRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        audioChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
            if (video < 0) {
                return MetadataBuilder.encoded(sourceId, EncodedFacts())
            }
            val format = extractor.getTrackFormat(video)
            val mime = format.getString(MediaFormat.KEY_MIME)
            extractor.selectTrack(video)

            // Sample walk: first/last pts for the measured fps (the same
            // walk MediaExtractorTrackReader's index build does).
            var firstPts = -1L
            var lastPts = -1L
            var count = 0
            while (true) {
                val pts = extractor.sampleTime
                if (pts < 0) break
                if (firstPts < 0) firstPts = pts
                lastPts = pts
                count++
                if (!extractor.advance()) break
            }

            val facts = EncodedFacts(
                mime = mime,
                width = intOrNull(format, MediaFormat.KEY_WIDTH),
                height = intOrNull(format, MediaFormat.KEY_HEIGHT),
                frameCount = if (count > 0) count else null,
                firstPtsUs = if (firstPts >= 0) firstPts else null,
                lastPtsUs = if (lastPts >= 0) lastPts else null,
                fpsNominal = fpsOrNull(format),
                colorStandard = intOrNull(format, MediaFormat.KEY_COLOR_STANDARD),
                colorTransfer = intOrNull(format, MediaFormat.KEY_COLOR_TRANSFER),
                colorRange = intOrNull(format, MediaFormat.KEY_COLOR_RANGE),
                audioTrackPresent = audioSeen,
                audioMime = audioMime,
                audioSampleRateHz = if (audioRate > 0) audioRate else null,
                audioChannels = if (audioChannels > 0) audioChannels else null,
            )
            return MetadataBuilder.encoded(sourceId, facts)
        } catch (e: Exception) {
            // Unreadable container: the honest all-UNKNOWN record (the
            // diagnoses stay INSUFFICIENT_DATA through the rules).
            return MetadataBuilder.encoded(sourceId, EncodedFacts())
        } finally {
            runCatching { extractor.release() }
            runCatching { descriptor.close() }
        }
    }

    /**
     * Routes by kind; the caller (the import hook) knows the source's
     * kind from the repository record.
     */
    internal fun probe(
        sourceId: String,
        kind: SourceKind,
        fdProvider: FdProvider,
    ): MetadataRecord = when (kind) {
        SourceKind.MCRAW -> mcraw(sourceId, fdProvider)
        SourceKind.ENCODED -> encoded(sourceId, fdProvider)
    }

    // ---- helpers ----

    private fun intOrNull(format: MediaFormat, key: String): Int? =
        if (format.containsKey(key)) format.getInteger(key) else null

    /**
     * The declared frame rate: KEY_FRAME_RATE first, KEY_CAPTURE_RATE as
     * the fallback (the capture rate is the honest nominal for camera
     * clips). Absent = null = UNKNOWN.
     */
    private fun fpsOrNull(format: MediaFormat): Double? {
        for (key in listOf(MediaFormat.KEY_FRAME_RATE, MediaFormat.KEY_CAPTURE_RATE)) {
            if (format.containsKey(key)) {
                val v = runCatching { format.getInteger(key) }.getOrNull()
                if (v != null && v > 0) return v.toDouble()
            }
        }
        return null
    }

    private fun closeFd(fd: Int) {
        // The native probes dup'd the descriptor; the caller's copy is
        // closed here. closeQuietly takes the ParcelFileDescriptor, so the
        // raw fd is closed through its wrapper.
        runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
    }

    private fun ContainerIdentityK.toFacts(): ContainerIdentityFacts =
        ContainerIdentityFacts(
            uniqueCameraModel = uniqueCameraModel.ifBlank { null },
            deviceModel = deviceModel.ifBlank { null },
            manufacturer = manufacturer.ifBlank { null },
            buildModel = buildModel.ifBlank { null },
            apertureMm = if (apertureMm > 0.0) apertureMm else null,
            focalLengthMm = if (focalLengthMm > 0.0) focalLengthMm else null,
        )
}
