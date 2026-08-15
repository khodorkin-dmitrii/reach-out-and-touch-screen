package com.yavin.reachoutandtouchscreen

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Active local-to-world rotation stored as (x, y, z, w).
 *
 * Hamilton multiplication composes right to left: `worldDelta * orientation` applies a
 * world-space delta, while `orientation * localDelta` applies a local-space delta.
 */
internal data class Quaternion(
    val x: Double,
    val y: Double,
    val z: Double,
    val w: Double,
) {
    operator fun times(other: Quaternion) = Quaternion(
        x = w * other.x + x * other.w + y * other.z - z * other.y,
        y = w * other.y - x * other.z + y * other.w + z * other.x,
        z = w * other.z + x * other.y - y * other.x + z * other.w,
        w = w * other.w - x * other.x - y * other.y - z * other.z,
    )

    fun normalizedOrNull(): Quaternion? {
        val lengthSquared = x * x + y * y + z * z + w * w
        if (!lengthSquared.isFinite() || lengthSquared <= NORMALIZATION_EPSILON) return null
        val inverseLength = 1.0 / sqrt(lengthSquared)
        return Quaternion(
            x * inverseLength,
            y * inverseLength,
            z * inverseLength,
            w * inverseLength,
        )
    }

    fun normalizedOrIdentity(): Quaternion = normalizedOrNull() ?: Identity

    fun conjugate() = Quaternion(-x, -y, -z, w)

    fun negated() = Quaternion(-x, -y, -z, -w)

    fun rotate(vector: Vector3): Vector3 {
        val quaternionVector = Vector3(x, y, z)
        val twiceCross = quaternionVector.cross(vector) * 2.0
        return vector + twiceCross * w + quaternionVector.cross(twiceCross)
    }

    fun writeColumnMajorRotationMatrix(destination: FloatArray) {
        require(destination.size >= 16)
        val normalized = normalizedOrIdentity()
        val xx = normalized.x * normalized.x
        val yy = normalized.y * normalized.y
        val zz = normalized.z * normalized.z
        val xy = normalized.x * normalized.y
        val xz = normalized.x * normalized.z
        val yz = normalized.y * normalized.z
        val wx = normalized.w * normalized.x
        val wy = normalized.w * normalized.y
        val wz = normalized.w * normalized.z
        destination.fill(0f)
        destination[0] = (1.0 - 2.0 * (yy + zz)).toFloat()
        destination[1] = (2.0 * (xy + wz)).toFloat()
        destination[2] = (2.0 * (xz - wy)).toFloat()
        destination[4] = (2.0 * (xy - wz)).toFloat()
        destination[5] = (1.0 - 2.0 * (xx + zz)).toFloat()
        destination[6] = (2.0 * (yz + wx)).toFloat()
        destination[8] = (2.0 * (xz + wy)).toFloat()
        destination[9] = (2.0 * (yz - wx)).toFloat()
        destination[10] = (1.0 - 2.0 * (xx + yy)).toFloat()
        destination[15] = 1f
    }

    companion object {
        val Identity = Quaternion(0.0, 0.0, 0.0, 1.0)

        fun fromAxisAngle(unitAxis: Vector3, angleRadians: Double): Quaternion {
            val axis = unitAxis.normalized() ?: return Identity
            if (!angleRadians.isFinite()) return Identity
            val halfAngle = angleRadians * 0.5
            val sine = sin(halfAngle)
            return Quaternion(
                axis.x * sine,
                axis.y * sine,
                axis.z * sine,
                cos(halfAngle),
            ).normalizedOrIdentity()
        }

        fun rotating(from: Vector3, to: Vector3): Quaternion {
            val start = from.normalized() ?: return Identity
            val target = to.normalized() ?: return Identity
            val dot = start.dot(target).coerceIn(-1.0, 1.0)
            if (dot >= 1.0 - FROM_TO_EPSILON) return Identity
            if (dot <= -1.0 + FROM_TO_EPSILON) {
                val helper = if (abs(start.x) < abs(start.y)) WORLD_X else WORLD_Y
                val axis = start.cross(helper).normalized()
                    ?: start.cross(WORLD_Z).normalized()
                    ?: return Identity
                return Quaternion(axis.x, axis.y, axis.z, 0.0)
            }
            val cross = start.cross(target)
            return Quaternion(cross.x, cross.y, cross.z, 1.0 + dot).normalizedOrIdentity()
        }

        private const val NORMALIZATION_EPSILON = 1e-24
        private const val FROM_TO_EPSILON = 1e-12
        private val WORLD_X = Vector3(1.0, 0.0, 0.0)
        private val WORLD_Y = Vector3(0.0, 1.0, 0.0)
        private val WORLD_Z = Vector3(0.0, 0.0, 1.0)
    }
}

internal data class CameraBasis(
    val right: Vector3,
    val up: Vector3,
    val towardCamera: Vector3,
) {
    fun viewToWorld(vector: Vector3): Vector3 =
        right * vector.x + up * vector.y + towardCamera * vector.z

    companion object {
        fun fromViewMatrix(viewMatrix: DoubleArray): CameraBasis? {
            if (viewMatrix.size < 16 || viewMatrix.any { !it.isFinite() }) return null
            val right = Vector3(viewMatrix[0], viewMatrix[4], viewMatrix[8]).normalized()
                ?: return null
            val rawUp = Vector3(viewMatrix[1], viewMatrix[5], viewMatrix[9])
            var up = (rawUp - right * rawUp.dot(right)).normalized() ?: return null
            var towardCamera = right.cross(up).normalized() ?: return null
            val matrixBackward = Vector3(viewMatrix[2], viewMatrix[6], viewMatrix[10])
            if (towardCamera.dot(matrixBackward) < 0.0) {
                up = up * -1.0
                towardCamera = towardCamera * -1.0
            }
            return CameraBasis(right, up, towardCamera)
        }
    }
}

internal data class ArcballProjection(
    val centerX: Double,
    val centerY: Double,
    val radius: Double,
) {
    fun contains(pointerX: Double, pointerY: Double): Boolean {
        if (
            !pointerX.isFinite() || !pointerY.isFinite() ||
            !centerX.isFinite() || !centerY.isFinite() ||
            !radius.isFinite() || radius <= 0.0
        ) {
            return false
        }
        return hypot(
            (pointerX - centerX) / radius,
            (pointerY - centerY) / radius,
        ) <= 1.0
    }
}

internal data class ArcballGesture(
    val startVectorView: Vector3,
    val startOrientation: Quaternion,
    val cameraBasis: CameraBasis,
    val projection: ArcballProjection,
) {
    fun orientationAt(pointerX: Double, pointerY: Double): Quaternion? {
        val currentVectorView = mapPointerToArcball(
            pointerX = pointerX,
            pointerY = pointerY,
            projection = projection,
        ) ?: return null
        if (currentVectorView == startVectorView) return startOrientation
        val startWorld = cameraBasis.viewToWorld(startVectorView)
        val currentWorld = cameraBasis.viewToWorld(currentVectorView)
        val worldDelta = Quaternion.rotating(startWorld, currentWorld)
        return (worldDelta * startOrientation).normalizedOrIdentity()
    }
}

/** Maps top-left-origin screen coordinates to the front hemisphere or the normalized rim. */
internal fun mapPointerToArcball(
    pointerX: Double,
    pointerY: Double,
    projection: ArcballProjection,
): Vector3? {
    if (
        !pointerX.isFinite() || !pointerY.isFinite() ||
        !projection.centerX.isFinite() || !projection.centerY.isFinite() ||
        !projection.radius.isFinite() || projection.radius <= 0.0
    ) {
        return null
    }
    val x = (pointerX - projection.centerX) / projection.radius
    val y = (projection.centerY - pointerY) / projection.radius
    if (!x.isFinite() || !y.isFinite()) return null
    val distance = hypot(x, y)
    return if (distance <= 1.0) {
        Vector3(x, y, sqrt((1.0 - distance * distance).coerceAtLeast(0.0))).normalized()
    } else {
        Vector3(x / distance, y / distance, 0.0)
    }
}

/**
 * Projects the Moon center and camera-facing cardinal surface points into Compose input space.
 * The smaller averaged screen axis is used as a stable, usable trackball radius.
 */
internal fun arcballProjectionForSphere(
    sphereCenter: Vector3,
    sphereRadius: Double,
    cameraBasis: CameraBasis,
    projectionMatrix: DoubleArray,
    viewMatrix: DoubleArray,
    touchAreaWidth: Int,
    touchAreaHeight: Int,
): ArcballProjection? {
    if (
        sphereRadius <= 0.0 || !sphereRadius.isFinite() ||
        touchAreaWidth <= 0 || touchAreaHeight <= 0
    ) {
        return null
    }
    fun project(point: Vector3) = projectToTouchArea(
        point,
        projectionMatrix,
        viewMatrix,
        touchAreaWidth,
        touchAreaHeight,
    )
    val center = project(sphereCenter) ?: return null
    val rightPositive = project(sphereCenter + cameraBasis.right * sphereRadius) ?: return null
    val rightNegative = project(sphereCenter - cameraBasis.right * sphereRadius) ?: return null
    val upPositive = project(sphereCenter + cameraBasis.up * sphereRadius) ?: return null
    val upNegative = project(sphereCenter - cameraBasis.up * sphereRadius) ?: return null
    val horizontalRadius = (
        hypot(rightPositive.first - center.first, rightPositive.second - center.second) +
            hypot(rightNegative.first - center.first, rightNegative.second - center.second)
        ) * 0.5
    val verticalRadius = (
        hypot(upPositive.first - center.first, upPositive.second - center.second) +
            hypot(upNegative.first - center.first, upNegative.second - center.second)
        ) * 0.5
    val radius = minOf(horizontalRadius, verticalRadius)
    if (!radius.isFinite() || radius <= PROJECTION_EPSILON) return null
    return ArcballProjection(center.first, center.second, radius)
}

private fun projectToTouchArea(
    point: Vector3,
    projection: DoubleArray,
    view: DoubleArray,
    width: Int,
    height: Int,
): Pair<Double, Double>? {
    if (projection.size < 16 || view.size < 16) return null
    val viewX = view[0] * point.x + view[4] * point.y + view[8] * point.z + view[12]
    val viewY = view[1] * point.x + view[5] * point.y + view[9] * point.z + view[13]
    val viewZ = view[2] * point.x + view[6] * point.y + view[10] * point.z + view[14]
    val viewW = view[3] * point.x + view[7] * point.y + view[11] * point.z + view[15]
    val clipX = projection[0] * viewX + projection[4] * viewY +
        projection[8] * viewZ + projection[12] * viewW
    val clipY = projection[1] * viewX + projection[5] * viewY +
        projection[9] * viewZ + projection[13] * viewW
    val clipW = projection[3] * viewX + projection[7] * viewY +
        projection[11] * viewZ + projection[15] * viewW
    if (!clipW.isFinite() || abs(clipW) <= PROJECTION_EPSILON) return null
    val ndcX = clipX / clipW
    val ndcY = clipY / clipW
    if (!ndcX.isFinite() || !ndcY.isFinite()) return null
    return Pair(
        (ndcX + 1.0) * 0.5 * width,
        (1.0 - ndcY) * 0.5 * height,
    )
}

internal fun inverseRotateRay(ray: Ray, orientation: Quaternion): Ray {
    val worldToLocal = orientation.normalizedOrIdentity().conjugate()
    return Ray(
        origin = worldToLocal.rotate(ray.origin),
        direction = worldToLocal.rotate(ray.direction),
    )
}

internal data class WorldAngularVelocity(
    val axis: Vector3,
    val speedRadiansPerSecond: Double,
)

/** Fixed-capacity renderer-thread history of controlling-pointer orientation changes. */
internal class RecentOrientationVelocityTracker(
    private val capacity: Int,
    private val windowNanos: Long,
    private val staleReleaseNanos: Long,
    private val minimumIntervalNanos: Long,
    private val minimumSpeedRadiansPerSecond: Double,
    private val maximumSpeedRadiansPerSecond: Double,
) {
    private val sampleTimes = LongArray(capacity)
    private val sampleX = DoubleArray(capacity)
    private val sampleY = DoubleArray(capacity)
    private val sampleZ = DoubleArray(capacity)
    private val sampleW = DoubleArray(capacity)
    private var sampleCount = 0

    init {
        require(capacity >= 2)
        require(windowNanos > 0L)
        require(staleReleaseNanos >= 0L)
        require(minimumIntervalNanos > 0L && minimumIntervalNanos <= windowNanos)
        require(minimumSpeedRadiansPerSecond >= 0.0)
        require(maximumSpeedRadiansPerSecond >= minimumSpeedRadiansPerSecond)
    }

    fun reset(orientation: Quaternion, timeNanos: Long) {
        clear()
        val normalized = orientation.normalizedOrNull() ?: return
        if (timeNanos <= 0L) return
        writeSample(0, normalized, timeNanos)
        sampleCount = 1
    }

    /** Adds only real orientation changes, keeping stationary holds detectable at release. */
    fun add(orientation: Quaternion, timeNanos: Long): Boolean {
        val normalized = orientation.normalizedOrNull() ?: return false
        if (sampleCount == 0) {
            reset(normalized, timeNanos)
            return false
        }
        if (timeNanos <= sampleTimes[sampleCount - 1]) return false
        if (sameRotationAt(sampleCount - 1, normalized)) return false
        if (sampleCount == capacity) {
            for (index in 1 until sampleCount) copySample(index, index - 1)
            sampleCount--
        }
        writeSample(sampleCount, normalized, timeNanos)
        sampleCount++
        return true
    }

    fun estimate(releaseTimeNanos: Long): WorldAngularVelocity? {
        if (sampleCount < 2 || releaseTimeNanos <= 0L) return null
        val finalIndex = sampleCount - 1
        val finalTime = sampleTimes[finalIndex]
        val latestAge = releaseTimeNanos - finalTime
        if (latestAge < 0L || latestAge > staleReleaseNanos) return null
        val oldestAllowedTime = finalTime - windowNanos
        var olderIndex = -1
        for (index in 0 until finalIndex) {
            val interval = finalTime - sampleTimes[index]
            if (sampleTimes[index] >= oldestAllowedTime && interval >= minimumIntervalNanos) {
                olderIndex = index
                break
            }
        }
        if (olderIndex < 0) return null

        val older = orientationAt(olderIndex).normalizedOrNull() ?: return null
        val final = orientationAt(finalIndex).normalizedOrNull() ?: return null
        var relativeWorldDelta = (final * older.conjugate()).normalizedOrNull() ?: return null
        // q and -q are the same rotation; non-negative w selects the shortest [0, pi] arc.
        if (relativeWorldDelta.w < 0.0) relativeWorldDelta = relativeWorldDelta.negated()
        val axisLength = hypot(
            hypot(relativeWorldDelta.x, relativeWorldDelta.y),
            relativeWorldDelta.z,
        )
        if (!axisLength.isFinite() || axisLength <= AXIS_EPSILON) return null
        val angleRadians = 2.0 * atan2(axisLength, relativeWorldDelta.w.coerceAtLeast(0.0))
        val intervalSeconds = (finalTime - sampleTimes[olderIndex]) / NANOS_PER_SECOND
        if (!angleRadians.isFinite() || intervalSeconds <= 0.0) return null
        val estimatedSpeed = angleRadians / intervalSeconds
        if (!estimatedSpeed.isFinite() || estimatedSpeed < minimumSpeedRadiansPerSecond) return null
        return WorldAngularVelocity(
            axis = Vector3(
                relativeWorldDelta.x / axisLength,
                relativeWorldDelta.y / axisLength,
                relativeWorldDelta.z / axisLength,
            ),
            speedRadiansPerSecond = estimatedSpeed.coerceAtMost(maximumSpeedRadiansPerSecond),
        )
    }

    fun clear() {
        sampleCount = 0
    }

    private fun writeSample(index: Int, orientation: Quaternion, timeNanos: Long) {
        sampleTimes[index] = timeNanos
        sampleX[index] = orientation.x
        sampleY[index] = orientation.y
        sampleZ[index] = orientation.z
        sampleW[index] = orientation.w
    }

    private fun copySample(source: Int, destination: Int) {
        sampleTimes[destination] = sampleTimes[source]
        sampleX[destination] = sampleX[source]
        sampleY[destination] = sampleY[source]
        sampleZ[destination] = sampleZ[source]
        sampleW[destination] = sampleW[source]
    }

    private fun orientationAt(index: Int) = Quaternion(
        sampleX[index],
        sampleY[index],
        sampleZ[index],
        sampleW[index],
    )

    private fun sameRotationAt(index: Int, orientation: Quaternion): Boolean {
        val dot = sampleX[index] * orientation.x + sampleY[index] * orientation.y +
            sampleZ[index] * orientation.z + sampleW[index] * orientation.w
        return 1.0 - abs(dot).coerceAtMost(1.0) <= ORIENTATION_CHANGE_EPSILON
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val AXIS_EPSILON = 1e-12
        const val ORIENTATION_CHANGE_EPSILON = 1e-12
    }
}

internal fun orientationAfterWorldInertia(
    orientation: Quaternion,
    worldAxis: Vector3,
    speedRadiansPerSecond: Double,
    deltaTimeSeconds: Double,
): Quaternion {
    if (
        !speedRadiansPerSecond.isFinite() || speedRadiansPerSecond <= 0.0 ||
        !deltaTimeSeconds.isFinite() || deltaTimeSeconds <= 0.0
    ) {
        return orientation.normalizedOrIdentity()
    }
    val delta = Quaternion.fromAxisAngle(
        worldAxis,
        speedRadiansPerSecond * deltaTimeSeconds,
    )
    return (delta * orientation).normalizedOrIdentity()
}

internal fun decayedAngularSpeed(
    speedRadiansPerSecond: Double,
    dampingRatePerSecond: Double,
    deltaTimeSeconds: Double,
): Double {
    if (
        !speedRadiansPerSecond.isFinite() || speedRadiansPerSecond <= 0.0 ||
        !dampingRatePerSecond.isFinite() || dampingRatePerSecond < 0.0 ||
        !deltaTimeSeconds.isFinite() || deltaTimeSeconds < 0.0
    ) {
        return 0.0
    }
    return speedRadiansPerSecond * exp(-dampingRatePerSecond * deltaTimeSeconds)
}

private fun Vector3.cross(other: Vector3) = Vector3(
    x = y * other.z - z * other.y,
    y = z * other.x - x * other.z,
    z = x * other.y - y * other.x,
)

private const val PROJECTION_EPSILON = 1e-12
