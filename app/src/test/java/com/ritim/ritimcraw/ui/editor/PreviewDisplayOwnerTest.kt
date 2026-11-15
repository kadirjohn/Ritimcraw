package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.engine.PreviewFrame
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PreviewDisplayOwner tests: the pure display-owner decision for the
 * preview's content layer (owner finding 2026-11-15 — the GPU-served
 * preview showed the spinner/placeholder text over footage the window
 * was visibly presenting, because the serving steady state had no arm
 * in the display when and fell through to the placeholders).
 */
class PreviewDisplayOwnerTest {

    /** A minimal published frame (dims + pixels identity is all ownerOf reads). */
    private fun frame() = PreviewFrame(width = 256, height = 144, argb = IntArray(256 * 144))

    // ---- the three owners ----

    @Test
    fun a_serving_window_with_a_frame_is_the_gpu_window() {
        // The GPU path's steady state, playback (stateOf=RENDERING between
        // frames) and paused (stateOf=FRAME) alike: the window behind the
        // Compose window is the picture — no Image, no placeholder.
        assertEquals(
            PreviewDisplayOwner.Owner.GPU_WINDOW,
            PreviewDisplayOwner.ownerOf(frame = frame(), gpuWindowServing = true),
        )
    }

    @Test
    fun a_non_serving_window_with_a_frame_is_the_compose_image() {
        // The CPU path (and the GPU path's latched fallback): the Image on
        // top serves the published frame, exactly as before the GPU window
        // existed.
        assertEquals(
            PreviewDisplayOwner.Owner.COMPOSE_IMAGE,
            PreviewDisplayOwner.ownerOf(frame = frame(), gpuWindowServing = false),
        )
    }

    @Test
    fun no_frame_is_none_even_while_serving() {
        // The window may be composed and live before the first publish
        // (gpuWindowActive with gpuWindowServing still false is the norm,
        // but the decision stays honest for either flag shape): with no
        // published frame there is nothing for the window to have
        // presented yet — the placeholder layer owns the surface.
        assertEquals(
            PreviewDisplayOwner.Owner.NONE,
            PreviewDisplayOwner.ownerOf(frame = null, gpuWindowServing = true),
        )
        assertEquals(
            PreviewDisplayOwner.Owner.NONE,
            PreviewDisplayOwner.ownerOf(frame = null, gpuWindowServing = false),
        )
    }

    // ---- the fallback edge: a serving flag with no live surface ----

    @Test
    fun the_decision_reads_only_the_serving_flag_and_the_frame() {
        // gpuWindowServing already ANDs the live surface at its publisher
        // (gpuWindowLive = servedByGpuWindow && gpuSurfaceLive), so ownerOf
        // needs no second opinion: the flag is the contract. This test pins
        // the signature's shape — two inputs, three outputs, nothing else.
        for (serving in listOf(true, false)) {
            for (f in listOf<PreviewFrame?>(frame(), null)) {
                val owner = PreviewDisplayOwner.ownerOf(frame = f, gpuWindowServing = serving)
                assertEquals(
                    when {
                        f == null -> PreviewDisplayOwner.Owner.NONE
                        serving -> PreviewDisplayOwner.Owner.GPU_WINDOW
                        else -> PreviewDisplayOwner.Owner.COMPOSE_IMAGE
                    },
                    owner,
                )
            }
        }
    }
}
