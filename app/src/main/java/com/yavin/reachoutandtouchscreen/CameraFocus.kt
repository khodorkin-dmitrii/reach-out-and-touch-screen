package com.yavin.reachoutandtouchscreen

internal enum class CameraFocusQuadrant(
    val horizontalSign: Double,
    val verticalSign: Double,
) {
    TOP_LEFT(horizontalSign = -1.0, verticalSign = 1.0),
    TOP_RIGHT(horizontalSign = 1.0, verticalSign = 1.0),
    BOTTOM_LEFT(horizontalSign = -1.0, verticalSign = -1.0),
    BOTTOM_RIGHT(horizontalSign = 1.0, verticalSign = -1.0),
}

internal fun cameraFocusQuadrantFor(
    x: Double,
    y: Double,
    viewportWidth: Int,
    viewportHeight: Int,
): CameraFocusQuadrant {
    require(viewportWidth > 0 && viewportHeight > 0)
    val isLeft = x < viewportWidth / 2.0
    val isTop = y < viewportHeight / 2.0
    return when {
        isLeft && isTop -> CameraFocusQuadrant.TOP_LEFT
        !isLeft && isTop -> CameraFocusQuadrant.TOP_RIGHT
        isLeft -> CameraFocusQuadrant.BOTTOM_LEFT
        else -> CameraFocusQuadrant.BOTTOM_RIGHT
    }
}

internal fun nextCameraFocus(
    current: CameraFocusQuadrant?,
    tapped: CameraFocusQuadrant,
): CameraFocusQuadrant? = if (current == null) tapped else null
