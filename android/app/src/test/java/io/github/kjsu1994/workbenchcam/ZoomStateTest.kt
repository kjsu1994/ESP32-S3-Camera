package io.github.kjsu1994.workbenchcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomStateTest {
    @Test fun clampsZoomWithinAllowedRangeAndResetsAtOne() {
        val state = ZoomState(minZoom = 1f, maxZoom = 5f)
        state.applyZoom(10f, 640f, 360f, 1280f, 720f, 1280f, 720f, fillCrop = false)
        assertEquals(5f, state.zoom, 0.001f)
        assertTrue(state.isZoomed())

        state.applyZoom(0.1f, 640f, 360f, 1280f, 720f, 1280f, 720f, fillCrop = false)
        assertEquals(1f, state.zoom, 0.001f)
        assertFalse(state.isZoomed())
        assertEquals(0f, state.panX, 0.001f)
        assertEquals(0f, state.panY, 0.001f)
    }

    @Test fun clampsPanInsideScaledImageBounds() {
        val state = ZoomState()
        state.applyZoom(2f, 500f, 500f, 1000f, 1000f, 1000f, 1000f, fillCrop = false)
        state.panBy(2000f, -2000f, 1000f, 1000f, 1000f, 1000f, fillCrop = false)
        assertEquals(500f, state.panX, 0.001f)
        assertEquals(-500f, state.panY, 0.001f)
    }

    @Test fun doubleTapTogglesBetweenZoomedAndReset() {
        val state = ZoomState()
        state.toggleDoubleTap(640f, 360f, 1280f, 720f, 1280f, 720f, fillCrop = false)
        assertEquals(2.5f, state.zoom, 0.001f)
        assertTrue(state.isZoomed())

        state.toggleDoubleTap(640f, 360f, 1280f, 720f, 1280f, 720f, fillCrop = false)
        assertEquals(1f, state.zoom, 0.001f)
        assertFalse(state.isZoomed())
    }
}
