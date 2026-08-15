package com.yavin.reachoutandtouchscreen

internal val DEFAULT_FILAMENT_LIGHT_DIRECTION =
    checkNotNull(Vector3(-0.55, -0.8, -0.65).normalized())

/** Normalized world vector from the Moon toward the notional directional-light source. */
internal val DEFAULT_LIGHT_SOURCE_DIRECTION = DEFAULT_FILAMENT_LIGHT_DIRECTION * -1.0

internal fun normalizedLightSourceDirection(direction: Vector3): Vector3 =
    direction.normalized() ?: DEFAULT_LIGHT_SOURCE_DIRECTION

internal fun rotatedLightSourceDirection(
    startDirection: Vector3,
    worldDelta: Quaternion,
): Vector3 = normalizedLightSourceDirection(
    worldDelta.normalizedOrIdentity().rotate(normalizedLightSourceDirection(startDirection)),
)

/** Filament's directional-light vector points from the source toward the lit scene. */
internal fun filamentDirectionForSource(sourceDirection: Vector3): Vector3 =
    normalizedLightSourceDirection(sourceDirection) * -1.0
