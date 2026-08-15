package com.yavin.reachoutandtouchscreen

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereInertiaTest {
    @Test
    fun recentWorldRotationProducesExpectedVelocityWhileZeroAndStaleMotionAreRejected() {
        val tracker = createTracker()
        val older = Quaternion.fromAxisAngle(Vector3(1.0, 0.0, 0.0), 0.4)
        val final = Quaternion.fromAxisAngle(Vector3(0.0, 0.0, 1.0), 0.2) * older
        tracker.reset(older, 1_000_000_000L)
        tracker.add(final.negated(), 1_100_000_000L)

        val velocity = tracker.estimate(1_100_000_000L)!!

        assertEquals(0.0, velocity.axis.x, TOLERANCE)
        assertEquals(0.0, velocity.axis.y, TOLERANCE)
        assertEquals(1.0, velocity.axis.z, TOLERANCE)
        assertEquals(2.0, velocity.speedRadiansPerSecond, TOLERANCE)
        assertTrue(velocity.speedRadiansPerSecond.isFinite())
        assertNull(tracker.estimate(1_221_000_000L))

        tracker.reset(Quaternion.Identity, 2_000_000_000L)
        tracker.add(Quaternion.Identity, 2_100_000_000L)
        assertNull(tracker.estimate(2_100_000_000L))
    }

    @Test
    fun worldInertiaLeftMultipliesOrientationAndDampingIsFrameRateIndependent() {
        val initial = Quaternion.fromAxisAngle(Vector3(1.0, 0.0, 0.0), 0.4)
        val expected = Quaternion.fromAxisAngle(Vector3(0.0, 0.0, 1.0), 0.5) * initial

        val actual = orientationAfterWorldInertia(
            orientation = initial,
            worldAxis = Vector3(0.0, 0.0, 1.0),
            speedRadiansPerSecond = 2.0,
            deltaTimeSeconds = 0.25,
        )
        val oneFrameSpeed = decayedAngularSpeed(2.0, 3.5, 1.0 / 30.0)
        val twoFrameSpeed = decayedAngularSpeed(
            decayedAngularSpeed(2.0, 3.5, 1.0 / 60.0),
            3.5,
            1.0 / 60.0,
        )

        assertSameRotation(expected, actual)
        assertNormalizedFinite(actual)
        assertTrue(oneFrameSpeed < 2.0)
        assertEquals(oneFrameSpeed, twoFrameSpeed, TOLERANCE)
    }

    private fun createTracker() = RecentOrientationVelocityTracker(
        capacity = 12,
        windowNanos = 100_000_000L,
        staleReleaseNanos = 120_000_000L,
        minimumIntervalNanos = 16_000_000L,
        minimumSpeedRadiansPerSecond = 0.35,
        maximumSpeedRadiansPerSecond = 6.0,
    )

    private fun assertSameRotation(expected: Quaternion, actual: Quaternion) {
        val dot = expected.x * actual.x + expected.y * actual.y +
            expected.z * actual.z + expected.w * actual.w
        assertEquals(1.0, kotlin.math.abs(dot), TOLERANCE)
    }

    private fun assertNormalizedFinite(quaternion: Quaternion) {
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
