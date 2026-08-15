package com.yavin.reachoutandtouchscreen

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sin
import kotlin.math.tan

object CameraFraming {
    enum class FovAxis {
        HORIZONTAL,
        VERTICAL,
    }

    data class ProjectionPolicy(
        val aspectRatio: Double,
        val fovAxis: FovAxis,
    )

    fun projectionForViewport(width: Int, height: Int): ProjectionPolicy? {
        if (width <= 0 || height <= 0) return null
        return ProjectionPolicy(
            aspectRatio = width.toDouble() / height.toDouble(),
            // A square has no unique short side; vertical is the deterministic convention.
            fovAxis = if (width < height) FovAxis.HORIZONTAL else FovAxis.VERTICAL,
        )
    }

    fun distanceForSphere(
        radius: Double,
        shortSideFovDegrees: Double,
        projection: ProjectionPolicy,
        margin: Double,
    ): Double {
        require(radius > 0.0)
        require(shortSideFovDegrees > 0.0 && shortSideFovDegrees < 180.0)
        require(projection.aspectRatio > 0.0)
        require(margin >= 1.0)

        val suppliedHalfFov = shortSideFovDegrees * PI / 360.0
        val verticalHalfFov = when (projection.fovAxis) {
            FovAxis.HORIZONTAL -> atan(tan(suppliedHalfFov) / projection.aspectRatio)
            FovAxis.VERTICAL -> suppliedHalfFov
        }
        val horizontalHalfFov = when (projection.fovAxis) {
            FovAxis.HORIZONTAL -> suppliedHalfFov
            FovAxis.VERTICAL -> atan(tan(suppliedHalfFov) * projection.aspectRatio)
        }
        val limitingHalfFov = minOf(verticalHalfFov, horizontalHalfFov)
        return radius * margin / sin(limitingHalfFov)
    }
}
