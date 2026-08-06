package com.yavin.reachoutandtouchscreen

import kotlin.math.abs
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RippleWaveMathTest {
    @Test
    fun waveFrontCenterIsPositive() {
        val elapsed = 0.75
        val front = RippleWaveMath.SPEED_RADIANS_PER_SECOND * elapsed

        assertTrue(RippleWaveMath.contribution(front, elapsed) > 0.0)
    }

    @Test
    fun rickerSideLobeIsNegativeBeyondUnitDistance() {
        val elapsed = 0.5
        val distance = RippleWaveMath.SPEED_RADIANS_PER_SECOND * elapsed +
            1.5 * RippleWaveMath.WIDTH_RADIANS

        assertTrue(RippleWaveMath.contribution(distance, elapsed) < 0.0)
    }

    @Test
    fun contributionTendsToZeroFarFromPacket() {
        val distance = 12.0 * RippleWaveMath.WIDTH_RADIANS

        assertEquals(0.0, RippleWaveMath.contribution(distance, 0.0), 1e-25)
    }

    @Test
    fun positiveContributionsAddConstructively() {
        val contribution = 0.4

        assertEquals(0.8, RippleWaveMath.linearSum(doubleArrayOf(contribution, contribution)), TOLERANCE)
    }

    @Test
    fun negativeContributionsAddConstructivelyInNegativeDirection() {
        val contribution = -0.3

        assertEquals(-0.6, RippleWaveMath.linearSum(doubleArrayOf(contribution, contribution)), TOLERANCE)
    }

    @Test
    fun oppositeContributionsCancel() {
        assertEquals(0.0, RippleWaveMath.linearSum(doubleArrayOf(0.65, -0.65)), TOLERANCE)
    }

    @Test
    fun slotPermutationDoesNotChangeLinearSum() {
        val first = RippleWaveMath.linearSum(doubleArrayOf(0.8, -0.25, 0.1, -0.05))
        val permuted = RippleWaveMath.linearSum(doubleArrayOf(-0.05, 0.1, -0.25, 0.8))

        assertEquals(first, permuted, TOLERANCE)
    }

    @Test
    fun inactiveAndExpiredRipplesContributeZero() {
        val active = RippleWaveMath.contribution(0.0, 0.0)
        val inactive = RippleWaveMath.contribution(0.0, 0.0, isActive = false)
        val expired = RippleWaveMath.contribution(0.0, RippleWaveMath.LIFETIME_SECONDS)

        assertEquals(active, RippleWaveMath.linearSum(doubleArrayOf(active, inactive, expired)), TOLERANCE)
    }

    @Test
    fun displayMappingIsBoundedSymmetricAndSignPreserving() {
        val positive = RippleWaveMath.displayWave(3.5)
        val negative = RippleWaveMath.displayWave(-3.5)

        assertTrue(positive in 0.0..1.0)
        assertTrue(negative in -1.0..0.0)
        assertTrue(abs(positive) < 1.0)
        assertTrue(abs(negative) < 1.0)
        assertEquals(positive, -negative, TOLERANCE)
        assertEquals(0.0, RippleWaveMath.displayWave(0.0), 0.0)
    }

    @Test
    fun contributionMatchesRickerProfileAndTimeDamping() {
        val elapsed = 1.0
        val x = 0.5
        val distance = RippleWaveMath.SPEED_RADIANS_PER_SECOND * elapsed +
            x * RippleWaveMath.WIDTH_RADIANS
        val expected = (1.0 - x * x) * exp(-0.5 * x * x) *
            exp(-RippleWaveMath.DAMPING_RATE_PER_SECOND * elapsed)

        assertEquals(expected, RippleWaveMath.contribution(distance, elapsed), TOLERANCE)
    }

    @Test
    fun fadeAndLifetimeBoundariesRemainFinite() {
        val boundaries = doubleArrayOf(
            RippleWaveMath.FADE_START_SECONDS,
            RippleWaveMath.FADE_END_SECONDS,
            RippleWaveMath.LIFETIME_SECONDS,
        )

        for (elapsed in boundaries) {
            val distance = RippleWaveMath.SPEED_RADIANS_PER_SECOND * elapsed
            val contribution = RippleWaveMath.contribution(distance, elapsed)
            assertFalse(contribution.isNaN())
            assertFalse(contribution.isInfinite())
        }
    }

    @Test
    fun eightCoincidentRipplesRemainSafeAfterDisplayMapping() {
        val contributions = DoubleArray(MAX_ACTIVE_RIPPLES) {
            RippleWaveMath.contribution(angularDistanceRadians = 0.0, elapsedSeconds = 0.0)
        }
        val displayed = RippleWaveMath.displayWave(RippleWaveMath.linearSum(contributions))

        assertTrue(displayed > 0.0)
        assertTrue(displayed < 1.0)
    }

    private companion object {
        const val TOLERANCE = 1e-12
    }
}
