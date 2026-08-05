package com.yavin.reachoutandtouchscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.tan

class ScreenRayTest {
    @Test
    fun centerPointsTowardSphereCenter() {
        val ray = createRay(x = 500.0, y = 500.0)!!

        assertVectorEquals(Vector3(0.0, 0.0, -1.0), ray.direction)
        assertVectorEquals(Vector3(0.0, 0.0, 4.0), ray.origin)
    }

    @Test
    fun viewportEdgesHaveExpectedDirectionSigns() {
        assertTrue(createRay(x = 0.0, y = 500.0)!!.direction.x < 0.0)
        assertTrue(createRay(x = 1000.0, y = 500.0)!!.direction.x > 0.0)
        assertTrue(createRay(x = 500.0, y = 0.0)!!.direction.y > 0.0)
        assertTrue(createRay(x = 500.0, y = 1000.0)!!.direction.y < 0.0)
    }

    @Test
    fun widerAspectRatioProducesWiderHorizontalRay() {
        val square = createRay(x = 1000.0, y = 500.0, aspectRatio = 1.0)!!
        val wide = createRay(x = 1000.0, y = 500.0, aspectRatio = 2.0)!!

        assertTrue(wide.direction.x > square.direction.x)
    }

    @Test
    fun touchCoordinatesScaleToDifferentRenderingResolution() {
        val ray = ScreenRay.create(
            touch = TouchInput(250.0, 500.0, touchAreaWidth = 500, touchAreaHeight = 1000),
            viewportWidth = 1000,
            viewportHeight = 2000,
            projectionMatrix = perspective(aspectRatio = 0.5),
            viewMatrix = cameraAtZ(4.0),
        )

        assertNotNull(ray)
        assertEquals(0.0, ray!!.direction.x, TOLERANCE)
        assertEquals(0.0, ray.direction.y, TOLERANCE)
    }

    @Test
    fun zeroSizesAreRejected() {
        val projection = perspective(aspectRatio = 1.0)
        val view = cameraAtZ(4.0)

        assertNull(ScreenRay.create(TouchInput(0.0, 0.0, 0, 100), 100, 100, projection, view))
        assertNull(ScreenRay.create(TouchInput(0.0, 0.0, 100, 100), 0, 100, projection, view))
    }

    private fun createRay(
        x: Double,
        y: Double,
        aspectRatio: Double = 1.0,
    ) = ScreenRay.create(
        touch = TouchInput(x, y, touchAreaWidth = 1000, touchAreaHeight = 1000),
        viewportWidth = 1000,
        viewportHeight = 1000,
        projectionMatrix = perspective(aspectRatio),
        viewMatrix = cameraAtZ(4.0),
    )

    private fun perspective(aspectRatio: Double): DoubleArray {
        val near = 0.1
        val far = 100.0
        val focalLength = 1.0 / tan(45.0 * PI / 360.0)
        return doubleArrayOf(
            focalLength / aspectRatio, 0.0, 0.0, 0.0,
            0.0, focalLength, 0.0, 0.0,
            0.0, 0.0, (far + near) / (near - far), -1.0,
            0.0, 0.0, 2.0 * far * near / (near - far), 0.0,
        )
    }

    private fun cameraAtZ(z: Double) = doubleArrayOf(
        1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, -z, 1.0,
    )

    private fun assertVectorEquals(expected: Vector3, actual: Vector3) {
        assertEquals(expected.x, actual.x, TOLERANCE)
        assertEquals(expected.y, actual.y, TOLERANCE)
        assertEquals(expected.z, actual.z, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 1e-6
    }
}
