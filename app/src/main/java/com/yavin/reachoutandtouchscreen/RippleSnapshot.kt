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
    val sphereAngleRadians: Double = 0.0,
) {
    val entries: List<RippleSnapshotEntry> = Collections.unmodifiableList(entries.toList())

    companion object {
        val Empty = RippleSnapshot(emptyList(), sphereAngleRadians = 0.0)
    }
}
