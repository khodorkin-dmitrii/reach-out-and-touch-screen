package com.yavin.reachoutandtouchscreen

import java.util.Collections

internal data class RippleSnapshotEntry(
    val slotIndex: Int,
    val originX: Float,
    val originY: Float,
    val originZ: Float,
    val startTimeNanos: Long,
)

/** Immutable logical ripple state transferred between renderer instances. */
internal class RippleSnapshot(entries: List<RippleSnapshotEntry>) {
    val entries: List<RippleSnapshotEntry> = Collections.unmodifiableList(entries.toList())

    companion object {
        val Empty = RippleSnapshot(emptyList())
    }
}
