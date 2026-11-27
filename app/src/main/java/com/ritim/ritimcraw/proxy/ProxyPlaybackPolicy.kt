package com.ritim.ritimcraw.proxy

import com.ritim.ritimcraw.engine.PreviewTiers.RenderClass

/**
 * Owner's proxy playback decisions as pure rules (the screen only applies them):
 *  - [sourceFor]: the paused full-resolution frame (SETTLE) comes from the
 *    ORIGINAL; the proxy only serves playback and scrubbing (PLAYBACK, DRAG).
 *  - [shouldReopen]: a finished build is picked up at the NEXT seek or play,
 *    never while playback is running.
 */
object ProxyPlaybackPolicy {

    enum class FrameSource { ORIGINAL, PROXY }

    /** Which file serves a render of [renderClass] when the session may be on a proxy. */
    fun sourceFor(renderClass: RenderClass, sessionOnProxy: Boolean): FrameSource =
        if (!sessionOnProxy || renderClass == RenderClass.SETTLE) FrameSource.ORIGINAL
        else FrameSource.PROXY

    /**
     * True when the transport moved from (wasPlaying, prevFrame) to (playing, frame)
     * in a way that counts as a fresh seek/play: play just started, or a position
     * change while NOT playing. Position changes during playback are the clock
     * ticking, never a trigger.
     */
    fun shouldReopen(
        pending: Boolean,
        wasPlaying: Boolean,
        prevFrame: Int,
        playing: Boolean,
        frame: Int,
    ): Boolean {
        if (!pending) return false
        if (playing && !wasPlaying) return true
        if (!playing && !wasPlaying && frame != prevFrame) return true
        return false
    }
}
