package com.yavin.reachoutandtouchscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFramingTest {
    @Test
    fun portraitUsesHorizontalFovForShortSide() {
        val projection = projection(width = 900, height = 2_000)

        assertEquals(CameraFraming.FovAxis.HORIZONTAL, projection.fovAxis)
    }

    @Test
    fun landscapeUsesVerticalFovForShortSide() {
        val projection = projection(width = 2_000, height = 900)

        assertEquals(CameraFraming.FovAxis.VERTICAL, projection.fovAxis)
    }

    @Test
    fun rotatedViewportsHaveEquivalentShortSideFraming() {
        val portraitDistance = distance(projection(width = 900, height = 2_000), fovDegrees = 45.0)
        val landscapeDistance = distance(projection(width = 2_000, height = 900), fovDegrees = 45.0)

        assertEquals(landscapeDistance, portraitDistance, 1e-12)
    }

    @Test
    fun overviewAndFocusedFramingAreOrderedAndFinite() {
        val projection = projection(width = 900, height = 2_000)
        val overviewDistance = distance(projection, fovDegrees = 45.0)
        val focusedDistance = distance(projection, fovDegrees = 22.0)

        assertTrue(overviewDistance.isFinite())
        assertTrue(focusedDistance.isFinite())
        assertTrue(focusedDistance > overviewDistance)
    }

    @Test
    fun squareViewportUsesVerticalFovDeterministically() {
        val projection = projection(width = 1_000, height = 1_000)

        assertEquals(CameraFraming.FovAxis.VERTICAL, projection.fovAxis)
    }

    @Test
    fun invalidViewportHasNoProjectionPolicy() {
        assertEquals(null, CameraFraming.projectionForViewport(width = 0, height = 1_000))
        assertEquals(null, CameraFraming.projectionForViewport(width = 1_000, height = 0))
    }

    private fun projection(width: Int, height: Int) =
        requireNotNull(CameraFraming.projectionForViewport(width, height))

    private fun distance(
        projection: CameraFraming.ProjectionPolicy,
        fovDegrees: Double,
    ) = CameraFraming.distanceForSphere(
        radius = 1.0,
        shortSideFovDegrees = fovDegrees,
        projection = projection,
        margin = 1.18,
    )
}
