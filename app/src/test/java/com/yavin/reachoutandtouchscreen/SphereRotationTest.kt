package com.yavin.reachoutandtouchscreen

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereRotationTest {
    @Test
    fun insideAndOutsidePointersMapToFiniteNormalizedArcballVectors() {
        val projection = ArcballProjection(centerX = 100.0, centerY = 80.0, radius = 40.0)

        val inside = mapPointerToArcball(112.0, 64.0, projection)!!
        val outside = mapPointerToArcball(220.0, -40.0, projection)!!

        assertNormalizedFinite(inside)
        assertNormalizedFinite(outside)
        assertTrue(inside.z > 0.0)
        assertEquals(0.0, outside.z, TOLERANCE)
    }

    @Test
    fun fromToQuaternionLeavesIdenticalVectorAndRotatesRepresentativeDragToTarget() {
        val start = Vector3(0.0, 0.0, 1.0)
        val target = Vector3(0.6, 0.0, 0.8)

        val noRotation = Quaternion.rotating(start, start)
        val delta = Quaternion.rotating(start, target)
        val rotated = delta.rotate(start)

        assertEquals(Quaternion.Identity, noRotation)
        assertQuaternionNormalizedFinite(delta)
        assertEquals(target.x, rotated.x, TOLERANCE)
        assertEquals(target.y, rotated.y, TOLERANCE)
        assertEquals(target.z, rotated.z, TOLERANCE)
    }

    private fun assertNormalizedFinite(vector: Vector3) {
        assertTrue(vector.x.isFinite() && vector.y.isFinite() && vector.z.isFinite())
        assertEquals(1.0, vector.length(), TOLERANCE)
    }

    private fun assertQuaternionNormalizedFinite(quaternion: Quaternion) {
        assertTrue(
            quaternion.x.isFinite() && quaternion.y.isFinite() &&
                quaternion.z.isFinite() && quaternion.w.isFinite(),
        )
        val length = sqrt(
            quaternion.x * quaternion.x + quaternion.y * quaternion.y +
                quaternion.z * quaternion.z + quaternion.w * quaternion.w,
        )
        assertEquals(1.0, length, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
