package com.yavin.reachoutandtouchscreen

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sin
import kotlin.math.tan

object CameraFraming {
    fun distanceForSphere(
        radius: Double,
        verticalFovDegrees: Double,
        aspectRatio: Double,
        margin: Double,
    ): Double {
        require(radius > 0.0)
        require(verticalFovDegrees > 0.0 && verticalFovDegrees < 180.0)
        require(aspectRatio > 0.0)
        require(margin >= 1.0)

        val verticalHalfFov = verticalFovDegrees * PI / 360.0
        val horizontalHalfFov = atan(tan(verticalHalfFov) * aspectRatio)
        val limitingHalfFov = minOf(verticalHalfFov, horizontalHalfFov)
        return radius * margin / sin(limitingHalfFov)
    }
}
