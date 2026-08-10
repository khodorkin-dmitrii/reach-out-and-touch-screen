package com.yavin.reachoutandtouchscreen

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Test

class SphereRotationTest {
    @Test
    fun anchoredRotationCrossesLongitudeWrapWithoutJump() {
        val angle = anchoredAxisRotation(
            currentAngleRadians = 0.0,
            grabbedLocalLongitudeRadians = PI - 0.02,
            pointerLocalLongitudeRadians = -PI + 0.02,
        )

        assertEquals(-0.04, angle, TOLERANCE)
    }

    @Test
    fun axisFrameUsesTiltAndAzimuthConventions() {
        val screenRightTilt = RotationAxisFrame.fromDegrees(
            tiltDegrees = 30.0,
            azimuthDegrees = 0.0,
        )
        val cameraTilt = RotationAxisFrame.fromDegrees(
            tiltDegrees = 30.0,
            azimuthDegrees = 90.0,
        )

        assertVector(Vector3(0.5, SQRT_THREE_OVER_TWO, 0.0), screenRightTilt.axis)
        assertVector(Vector3(0.0, SQRT_THREE_OVER_TWO, 0.5), cameraTilt.axis)
        assertEquals(0.0, screenRightTilt.axis.dot(screenRightTilt.longitudeX), TOLERANCE)
        assertEquals(0.0, screenRightTilt.axis.dot(screenRightTilt.longitudeZ), TOLERANCE)
    }

    @Test
    fun exponentialVelocityDecayIsFrameRateIndependent() {
        val oneFrame = decayedAngularVelocity(
            angularVelocityRadiansPerSecond = 6.0,
            frictionPerSecond = 4.5,
            deltaTimeSeconds = 1.0 / 30.0,
        )
        val twoFrames = decayedAngularVelocity(
            angularVelocityRadiansPerSecond = decayedAngularVelocity(
                angularVelocityRadiansPerSecond = 6.0,
                frictionPerSecond = 4.5,
                deltaTimeSeconds = 1.0 / 60.0,
            ),
            frictionPerSecond = 4.5,
            deltaTimeSeconds = 1.0 / 60.0,
        )

        assertEquals(oneFrame, twoFrames, TOLERANCE)
    }

    private fun assertVector(expected: Vector3, actual: Vector3) {
        assertEquals(expected.x, actual.x, TOLERANCE)
        assertEquals(expected.y, actual.y, TOLERANCE)
        assertEquals(expected.z, actual.z, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 1e-9
        const val SQRT_THREE_OVER_TWO = 0.8660254037844386
    }
}
