package com.yavin.reachoutandtouchscreen

import kotlin.math.abs
import kotlin.math.sqrt

data class Vector3(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    operator fun plus(other: Vector3) = Vector3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3) = Vector3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Double) = Vector3(x * scale, y * scale, z * scale)

    fun dot(other: Vector3) = x * other.x + y * other.y + z * other.z
    fun length() = sqrt(dot(this))

    fun normalized(): Vector3? {
        val length = length()
        return if (length > EPSILON) this * (1.0 / length) else null
    }

    private companion object {
        const val EPSILON = 1e-12
    }
}

data class Ray(
    val origin: Vector3,
    val direction: Vector3,
)

data class TouchInput(
    val x: Double,
    val y: Double,
    val touchAreaWidth: Int,
    val touchAreaHeight: Int,
)

object ScreenRay {
    /**
     * Converts top-left-origin Compose coordinates to a world-space ray.
     *
     * Filament matrices are column-major and use OpenGL NDC. The render surface may have a
     * different pixel size from the Compose input area, so coordinates are scaled explicitly.
     */
    fun create(
        touch: TouchInput,
        viewportWidth: Int,
        viewportHeight: Int,
        projectionMatrix: DoubleArray,
        viewMatrix: DoubleArray,
    ): Ray? {
        if (
            touch.touchAreaWidth <= 0 || touch.touchAreaHeight <= 0 ||
            viewportWidth <= 0 || viewportHeight <= 0 ||
            projectionMatrix.size < MATRIX_SIZE || viewMatrix.size < MATRIX_SIZE ||
            !touch.x.isFinite() || !touch.y.isFinite()
        ) {
            return null
        }

        val viewportX = touch.x * viewportWidth / touch.touchAreaWidth
        val viewportY = touch.y * viewportHeight / touch.touchAreaHeight
        val ndcX = 2.0 * viewportX / viewportWidth - 1.0
        val ndcY = 1.0 - 2.0 * viewportY / viewportHeight

        val inverseView = Matrix4.inverse(viewMatrix) ?: return null
        val inverseViewProjection = Matrix4.inverse(
            Matrix4.multiply(projectionMatrix, viewMatrix),
        ) ?: return null
        val origin = Matrix4.transformPoint(inverseView, 0.0, 0.0, 0.0) ?: return null
        val pointOnNearPlane = Matrix4.transformPoint(
            inverseViewProjection,
            ndcX,
            ndcY,
            OPEN_GL_NEAR_NDC,
        ) ?: return null
        val direction = (pointOnNearPlane - origin).normalized() ?: return null
        return Ray(origin, direction)
    }

    private const val MATRIX_SIZE = 16
    private const val OPEN_GL_NEAR_NDC = -1.0
}

object RaySphereIntersection {
    fun nearestHit(
        ray: Ray,
        sphereCenter: Vector3,
        sphereRadius: Double,
    ): Vector3? {
        require(sphereRadius > 0.0)
        val direction = ray.direction.normalized() ?: return null
        val originFromCenter = ray.origin - sphereCenter
        val halfB = originFromCenter.dot(direction)
        val c = originFromCenter.dot(originFromCenter) - sphereRadius * sphereRadius
        val discriminant = halfB * halfB - c
        if (discriminant < 0.0) return null

        val root = sqrt(discriminant)
        val nearDistance = -halfB - root
        val farDistance = -halfB + root
        val distance = when {
            nearDistance >= 0.0 -> nearDistance
            farDistance >= 0.0 -> farDistance
            else -> return null
        }
        return ray.origin + direction * distance
    }
}

private object Matrix4 {
    fun multiply(left: DoubleArray, right: DoubleArray): DoubleArray {
        val result = DoubleArray(16)
        for (column in 0 until 4) {
            for (row in 0 until 4) {
                var value = 0.0
                for (index in 0 until 4) {
                    value += left[index * 4 + row] * right[column * 4 + index]
                }
                result[column * 4 + row] = value
            }
        }
        return result
    }

    fun inverse(matrix: DoubleArray): DoubleArray? {
        val augmented = Array(4) { row ->
            DoubleArray(8).also { values ->
                for (column in 0 until 4) values[column] = matrix[column * 4 + row]
                values[row + 4] = 1.0
            }
        }

        for (pivotColumn in 0 until 4) {
            var pivotRow = pivotColumn
            for (candidate in pivotColumn + 1 until 4) {
                if (abs(augmented[candidate][pivotColumn]) > abs(augmented[pivotRow][pivotColumn])) {
                    pivotRow = candidate
                }
            }
            if (abs(augmented[pivotRow][pivotColumn]) < INVERSE_EPSILON) return null

            if (pivotRow != pivotColumn) {
                val temporary = augmented[pivotColumn]
                augmented[pivotColumn] = augmented[pivotRow]
                augmented[pivotRow] = temporary
            }

            val pivot = augmented[pivotColumn][pivotColumn]
            for (column in 0 until 8) augmented[pivotColumn][column] /= pivot

            for (row in 0 until 4) {
                if (row == pivotColumn) continue
                val factor = augmented[row][pivotColumn]
                for (column in 0 until 8) {
                    augmented[row][column] -= factor * augmented[pivotColumn][column]
                }
            }
        }

        return DoubleArray(16).also { inverse ->
            for (row in 0 until 4) {
                for (column in 0 until 4) inverse[column * 4 + row] = augmented[row][column + 4]
            }
        }
    }

    fun transformPoint(matrix: DoubleArray, x: Double, y: Double, z: Double): Vector3? {
        val transformedX = matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12]
        val transformedY = matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13]
        val transformedZ = matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14]
        val transformedW = matrix[3] * x + matrix[7] * y + matrix[11] * z + matrix[15]
        if (abs(transformedW) < TRANSFORM_EPSILON) return null
        return Vector3(
            transformedX / transformedW,
            transformedY / transformedW,
            transformedZ / transformedW,
        )
    }

    private const val INVERSE_EPSILON = 1e-12
    private const val TRANSFORM_EPSILON = 1e-12
}
