package com.yavin.reachoutandtouchscreen

import kotlin.math.exp
import kotlin.math.tanh

/** Pure reference model for the signed wave calculation mirrored in sphere.mat. */
internal object RippleWaveMath {
    const val SPEED_RADIANS_PER_SECOND = 1.12
    const val WIDTH_RADIANS = 0.085
    const val DAMPING_RATE_PER_SECOND = 0.55
    const val FADE_START_SECONDS = 2.55
    const val FADE_END_SECONDS = 2.90
    const val LIFETIME_SECONDS = 3.0
    const val DISPLAY_GAIN = 1.0

    fun contribution(
        angularDistanceRadians: Double,
        elapsedSeconds: Double,
        isActive: Boolean = true,
    ): Double {
        if (!isActive || elapsedSeconds < 0.0 || elapsedSeconds >= LIFETIME_SECONDS) return 0.0

        val frontDistance = SPEED_RADIANS_PER_SECOND * elapsedSeconds
        val x = (angularDistanceRadians - frontDistance) / WIDTH_RADIANS
        val envelope = exp(-0.5 * x * x)
        val signedWave = (1.0 - x * x) * envelope
        val timeDamping = exp(-DAMPING_RATE_PER_SECOND * elapsedSeconds) *
            (1.0 - smoothstep(FADE_START_SECONDS, FADE_END_SECONDS, elapsedSeconds))
        return signedWave * timeDamping
    }

    fun linearSum(contributions: DoubleArray): Double {
        var combinedWave = 0.0
        for (contribution in contributions) combinedWave += contribution
        return combinedWave
    }

    fun displayWave(combinedWave: Double): Double = tanh(DISPLAY_GAIN * combinedWave)

    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        val normalized = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return normalized * normalized * (3.0 - 2.0 * normalized)
    }
}
