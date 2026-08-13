package com.yavin.reachoutandtouchscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraFocusTest {
    @Test
    fun screenCoordinatesMapToFourQuadrants() {
        assertEquals(CameraFocusQuadrant.TOP_LEFT, quadrant(10.0, 10.0))
        assertEquals(CameraFocusQuadrant.TOP_RIGHT, quadrant(90.0, 10.0))
        assertEquals(CameraFocusQuadrant.BOTTOM_LEFT, quadrant(10.0, 190.0))
        assertEquals(CameraFocusQuadrant.BOTTOM_RIGHT, quadrant(90.0, 190.0))
    }

    @Test
    fun firstBackgroundDoubleTapFocusesAndNextOneReturnsToOverview() {
        val focused = nextCameraFocus(null, CameraFocusQuadrant.TOP_RIGHT)

        assertEquals(CameraFocusQuadrant.TOP_RIGHT, focused)
        assertNull(nextCameraFocus(focused, CameraFocusQuadrant.BOTTOM_LEFT))
    }

    private fun quadrant(x: Double, y: Double) = cameraFocusQuadrantFor(
        x = x,
        y = y,
        viewportWidth = 100,
        viewportHeight = 200,
    )
}
