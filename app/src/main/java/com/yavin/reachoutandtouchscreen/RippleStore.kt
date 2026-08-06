package com.yavin.reachoutandtouchscreen

internal const val MAX_ACTIVE_RIPPLES = 8
internal const val RIPPLE_LIFETIME_NANOS = 3_000_000_000L

/**
 * Fixed-capacity ripple state owned by the renderer thread.
 *
 * Free and expired slots are reused from the lowest index. If every slot is active, the oldest
 * start time is replaced; equal timestamps keep the lowest slot index as the deterministic tie
 * breaker.
 */
internal class RippleStore(
    val capacity: Int = MAX_ACTIVE_RIPPLES,
    private val lifetimeNanos: Long = RIPPLE_LIFETIME_NANOS,
) {
    private val occupied = BooleanArray(capacity)
    private val originX = FloatArray(capacity)
    private val originY = FloatArray(capacity)
    private val originZ = FloatArray(capacity)
    private val startTimesNanos = LongArray(capacity)

    init {
        require(capacity > 0)
        require(lifetimeNanos > 0L)
    }

    fun add(origin: Vector3?, startTimeNanos: Long): Int {
        if (origin == null) return NO_SLOT

        var target = NO_SLOT
        for (slot in 0 until capacity) {
            if (!isActive(slot, startTimeNanos)) {
                target = slot
                break
            }
        }

        if (target == NO_SLOT) {
            target = 0
            for (slot in 1 until capacity) {
                if (startTimesNanos[slot] < startTimesNanos[target]) {
                    target = slot
                }
            }
        }

        occupied[target] = true
        originX[target] = origin.x.toFloat()
        originY[target] = origin.y.toFloat()
        originZ[target] = origin.z.toFloat()
        startTimesNanos[target] = startTimeNanos
        return target
    }

    fun isActive(slot: Int, nowNanos: Long): Boolean {
        checkSlot(slot)
        if (!occupied[slot]) return false
        val elapsed = if (nowNanos > startTimesNanos[slot]) {
            nowNanos - startTimesNanos[slot]
        } else {
            0L
        }
        return elapsed < lifetimeNanos
    }

    fun hasActive(nowNanos: Long): Boolean {
        for (slot in 0 until capacity) {
            if (isActive(slot, nowNanos)) return true
        }
        return false
    }

    fun activeCount(nowNanos: Long): Int {
        var count = 0
        for (slot in 0 until capacity) {
            if (isActive(slot, nowNanos)) count++
        }
        return count
    }

    fun originX(slot: Int): Float {
        checkSlot(slot)
        return originX[slot]
    }

    fun originY(slot: Int): Float {
        checkSlot(slot)
        return originY[slot]
    }

    fun originZ(slot: Int): Float {
        checkSlot(slot)
        return originZ[slot]
    }

    fun startTimeNanos(slot: Int): Long {
        checkSlot(slot)
        return startTimesNanos[slot]
    }

    private fun checkSlot(slot: Int) {
        require(slot in 0 until capacity)
    }

    companion object {
        const val NO_SLOT = -1
    }
}
