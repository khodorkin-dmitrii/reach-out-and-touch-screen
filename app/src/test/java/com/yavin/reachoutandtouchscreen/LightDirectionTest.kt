package com.yavin.reachoutandtouchscreen

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Test

class LightDirectionTest {
    @Test
    fun worldArcballDeltaRotatesSourceAndFilamentReceivesOppositeDirection() {
        val sourceDirection = rotatedLightSourceDirection(
            startDirection = Vector3(0.0, 0.0, 2.0),
            worldDelta = Quaternion.fromAxisAngle(Vector3(0.0, 1.0, 0.0), PI / 2.0),
        )
        val filamentDirection = filamentDirectionForSource(sourceDirection)

        assertVectorEquals(Vector3(1.0, 0.0, 0.0), sourceDirection)
        assertVectorEquals(Vector3(-1.0, 0.0, 0.0), filamentDirection)
    }

    private fun assertVectorEquals(expected: Vector3, actual: Vector3) {
        assertEquals(expected.x, actual.x, TOLERANCE)
        assertEquals(expected.y, actual.y, TOLERANCE)
        assertEquals(expected.z, actual.z, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
