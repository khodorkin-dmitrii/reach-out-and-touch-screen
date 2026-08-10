package com.yavin.reachoutandtouchscreen

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Compile-time axis controls. No runtime or UI state is derived from these values. */
internal object SphereRotationConfiguration {
    /** Deviation from world +Y. Positive values tilt the north pole toward the azimuth below. */
    const val AXIS_TILT_DEGREES = 45.0

    /** Tilt direction around +Y: 0 = +X (screen-right), 90 = +Z (toward the camera). */
    const val AXIS_AZIMUTH_DEGREES = 90.0

    val axisFrame = RotationAxisFrame.fromDegrees(
        tiltDegrees = AXIS_TILT_DEGREES,
        azimuthDegrees = AXIS_AZIMUTH_DEGREES,
    )
}

internal data class RotationAxisFrame(
    val axis: Vector3,
    val longitudeX: Vector3,
    val longitudeZ: Vector3,
) {
    companion object {
        fun fromDegrees(tiltDegrees: Double, azimuthDegrees: Double): RotationAxisFrame {
            require(tiltDegrees.isFinite() && tiltDegrees in -MAX_TILT_DEGREES..MAX_TILT_DEGREES) {
                "Axis tilt must be within [-$MAX_TILT_DEGREES, $MAX_TILT_DEGREES] degrees"
            }
            require(azimuthDegrees.isFinite())
            val tiltRadians = Math.toRadians(tiltDegrees)
            val azimuthRadians = Math.toRadians(azimuthDegrees)
            val tiltRotationAxis = Vector3(
                x = sin(azimuthRadians),
                y = 0.0,
                z = -cos(azimuthRadians),
            )
            return RotationAxisFrame(
                axis = rotateAroundAxis(WORLD_Y, tiltRotationAxis, tiltRadians),
                longitudeX = rotateAroundAxis(WORLD_X, tiltRotationAxis, tiltRadians),
                longitudeZ = rotateAroundAxis(WORLD_Z, tiltRotationAxis, tiltRadians),
            )
        }

        const val MAX_TILT_DEGREES = 60.0
        private val WORLD_X = Vector3(1.0, 0.0, 0.0)
        private val WORLD_Y = Vector3(0.0, 1.0, 0.0)
        private val WORLD_Z = Vector3(0.0, 0.0, 1.0)
    }
}

internal fun wrapRadians(angleRadians: Double): Double =
    atan2(sin(angleRadians), cos(angleRadians))

internal fun shortestAngleDelta(fromRadians: Double, toRadians: Double): Double =
    wrapRadians(toRadians - fromRadians)

internal fun anchoredAxisRotation(
    currentAngleRadians: Double,
    grabbedLocalLongitudeRadians: Double,
    pointerLocalLongitudeRadians: Double,
): Double = wrapRadians(
    currentAngleRadians + shortestAngleDelta(
        pointerLocalLongitudeRadians,
        grabbedLocalLongitudeRadians,
    ),
)

internal fun longitudeRadians(point: Vector3, axisFrame: RotationAxisFrame): Double = atan2(
    point.dot(axisFrame.longitudeZ),
    point.dot(axisFrame.longitudeX),
)

internal fun rotateAroundAxis(
    vector: Vector3,
    unitAxis: Vector3,
    angleRadians: Double,
): Vector3 {
    val cosine = cos(angleRadians)
    val sine = sin(angleRadians)
    val oneMinusCosine = 1.0 - cosine
    val axisDotVector = unitAxis.dot(vector)
    val axisCrossVector = Vector3(
        x = unitAxis.y * vector.z - unitAxis.z * vector.y,
        y = unitAxis.z * vector.x - unitAxis.x * vector.z,
        z = unitAxis.x * vector.y - unitAxis.y * vector.x,
    )
    return vector * cosine + axisCrossVector * sine +
        unitAxis * (axisDotVector * oneMinusCosine)
}

internal fun inverseRotateRayAroundAxis(
    ray: Ray,
    unitAxis: Vector3,
    angleRadians: Double,
): Ray = Ray(
    origin = rotateAroundAxis(ray.origin, unitAxis, -angleRadians),
    direction = rotateAroundAxis(ray.direction, unitAxis, -angleRadians),
)

internal fun decayedAngularVelocity(
    angularVelocityRadiansPerSecond: Double,
    frictionPerSecond: Double,
    deltaTimeSeconds: Double,
): Double {
    require(frictionPerSecond >= 0.0)
    require(deltaTimeSeconds >= 0.0)
    return angularVelocityRadiansPerSecond * exp(-frictionPerSecond * deltaTimeSeconds)
}

/** Fixed-size recent motion history used only on the renderer thread. */
internal class RecentAngularVelocityTracker(
    private val windowNanos: Long,
    private val capacity: Int = 8,
) {
    private val sampleTimes = LongArray(capacity)
    private val sampleAngles = DoubleArray(capacity)
    private var sampleCount = 0

    init {
        require(windowNanos > 0L)
        require(capacity >= 2)
    }

    fun reset(angleRadians: Double, timeNanos: Long) {
        sampleCount = 1
        sampleTimes[0] = timeNanos
        sampleAngles[0] = angleRadians
    }

    fun add(angleRadians: Double, timeNanos: Long) {
        if (sampleCount == 0) {
            reset(angleRadians, timeNanos)
            return
        }
        if (timeNanos <= sampleTimes[sampleCount - 1]) return

        val previousWrappedAngle = wrapRadians(sampleAngles[sampleCount - 1])
        val unwrappedAngle = sampleAngles[sampleCount - 1] +
            shortestAngleDelta(previousWrappedAngle, angleRadians)
        if (sampleCount == capacity) {
            for (index in 1 until sampleCount) {
                sampleTimes[index - 1] = sampleTimes[index]
                sampleAngles[index - 1] = sampleAngles[index]
            }
            sampleCount--
        }
        sampleTimes[sampleCount] = timeNanos
        sampleAngles[sampleCount] = unwrappedAngle
        sampleCount++
        discardOldSamples(timeNanos)
    }

    fun velocityRadiansPerSecond(nowNanos: Long): Double {
        if (sampleCount < 2 || nowNanos - sampleTimes[sampleCount - 1] > windowNanos) return 0.0
        discardOldSamples(nowNanos)
        if (sampleCount < 2) return 0.0
        val elapsedNanos = sampleTimes[sampleCount - 1] - sampleTimes[0]
        if (elapsedNanos <= 0L) return 0.0
        return (sampleAngles[sampleCount - 1] - sampleAngles[0]) /
            (elapsedNanos / NANOS_PER_SECOND)
    }

    private fun discardOldSamples(nowNanos: Long) {
        var firstRetained = 0
        while (
            firstRetained < sampleCount - 2 &&
            nowNanos - sampleTimes[firstRetained + 1] > windowNanos
        ) {
            firstRetained++
        }
        if (firstRetained == 0) return
        for (index in firstRetained until sampleCount) {
            sampleTimes[index - firstRetained] = sampleTimes[index]
            sampleAngles[index - firstRetained] = sampleAngles[index]
        }
        sampleCount -= firstRetained
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
