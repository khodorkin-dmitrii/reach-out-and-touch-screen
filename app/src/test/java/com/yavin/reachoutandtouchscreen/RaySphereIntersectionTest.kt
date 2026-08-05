package com.yavin.reachoutandtouchscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaySphereIntersectionTest {
    private val center = Vector3(0.0, 0.0, 0.0)

    @Test
    fun hitsThroughCenterAndChoosesNearestPositiveIntersection() {
        val hit = hit(Ray(Vector3(0.0, 0.0, 3.0), Vector3(0.0, 0.0, -1.0)))!!

        assertVectorEquals(Vector3(0.0, 0.0, 1.0), hit)
    }

    @Test
    fun hitsAwayFromCenter() {
        val hit = hit(
            Ray(Vector3(0.0, 0.0, 3.0), Vector3(0.2, 0.0, -1.0)),
        )!!

        assertTrue(hit.x > 0.0)
        assertEquals(1.0, hit.length(), TOLERANCE)
    }

    @Test
    fun missesSphere() {
        assertNull(hit(Ray(Vector3(0.0, 0.0, 3.0), Vector3(2.0, 0.0, -1.0))))
    }

    @Test
    fun tangentCountsAsHit() {
        val hit = hit(Ray(Vector3(1.0, 0.0, 3.0), Vector3(0.0, 0.0, -1.0)))!!

        assertVectorEquals(Vector3(1.0, 0.0, 0.0), hit)
    }

    @Test
    fun intersectionBehindOriginIsRejected() {
        assertNull(hit(Ray(Vector3(0.0, 0.0, -3.0), Vector3(0.0, 0.0, -1.0))))
    }

    @Test
    fun rayStartingInsideSphereUsesForwardIntersection() {
        val hit = hit(Ray(center, Vector3(1.0, 0.0, 0.0)))!!

        assertVectorEquals(Vector3(1.0, 0.0, 0.0), hit)
    }

    private fun hit(ray: Ray) = RaySphereIntersection.nearestHit(ray, center, 1.0)

    private fun assertVectorEquals(expected: Vector3, actual: Vector3) {
        assertEquals(expected.x, actual.x, TOLERANCE)
        assertEquals(expected.y, actual.y, TOLERANCE)
        assertEquals(expected.z, actual.z, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 1e-6
    }
}
