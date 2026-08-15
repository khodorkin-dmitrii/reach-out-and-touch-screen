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
            cameraFocusQuadrant = null,
            accumulatedDentCompression = FloatArray(0),
        )
    }
}
