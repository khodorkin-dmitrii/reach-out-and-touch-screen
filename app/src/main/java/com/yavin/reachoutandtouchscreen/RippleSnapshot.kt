package com.yavin.reachoutandtouchscreen

import java.util.Collections

internal data class RippleSnapshotEntry(
    val slotIndex: Int,
    val originX: Float,
    val originY: Float,
    val originZ: Float,
    val startTimeNanos: Long,
)

/** Immutable logical scene state transferred between renderer instances. */
internal class RippleSnapshot(
    entries: List<RippleSnapshotEntry>,
    val sphereOrientation: Quaternion = Quaternion.Identity,
    val lightSourceDirection: Vector3 = DEFAULT_LIGHT_SOURCE_DIRECTION,
    val cameraFocusQuadrant: CameraFocusQuadrant? = null,
    accumulatedDentCompression: FloatArray = FloatArray(0),
) {
    val entries: List<RippleSnapshotEntry> = Collections.unmodifiableList(entries.toList())
    private val accumulatedDentCompression = accumulatedDentCompression.copyOf()

    fun copyAccumulatedDentCompression(): FloatArray = accumulatedDentCompression.copyOf()

    companion object {
        val Empty = RippleSnapshot(
            entries = emptyList(),
            sphereOrientation = Quaternion.Identity,
            lightSourceDirection = DEFAULT_LIGHT_SOURCE_DIRECTION,
            cameraFocusQuadrant = null,
            accumulatedDentCompression = FloatArray(0),
        )
    }
}
