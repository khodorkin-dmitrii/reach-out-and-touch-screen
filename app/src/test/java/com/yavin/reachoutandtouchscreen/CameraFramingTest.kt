package com.yavin.reachoutandtouchscreen

import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFramingTest {
    @Test
    fun portraitNeedsMoreDistanceThanLandscape() {
        val portraitDistance = distance(aspectRatio = 9.0 / 20.0)
        val landscapeDistance = distance(aspectRatio = 20.0 / 9.0)

        assertTrue(portraitDistance > landscapeDistance)
    }

    @Test
    fun squareViewportIncludesConfiguredMargin() {
        val distance = distance(aspectRatio = 1.0)

        assertTrue(distance > 3.0)
    }

    private fun distance(aspectRatio: Double) = CameraFraming.distanceForSphere(
        radius = 1.0,
        verticalFovDegrees = 45.0,
        aspectRatio = aspectRatio,
        margin = 1.18,
    )
}
