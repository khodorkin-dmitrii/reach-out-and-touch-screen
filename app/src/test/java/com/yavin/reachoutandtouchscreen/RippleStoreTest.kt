package com.yavin.reachoutandtouchscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RippleStoreTest {
    @Test
    fun firstRippleUsesFirstFreeSlot() {
        val store = RippleStore(capacity = 3, lifetimeNanos = 100L)

        assertEquals(0, store.add(ORIGIN_A, startTimeNanos = 10L))
        assertEquals(1, store.activeCount(nowNanos = 10L))
    }

    @Test
    fun rapidTouchesCreateIndependentActiveRipples() {
        val store = RippleStore(capacity = 3, lifetimeNanos = 100L)

        store.add(ORIGIN_A, 10L)
        store.add(ORIGIN_B, 11L)
        store.add(ORIGIN_C, 12L)

        assertEquals(3, store.activeCount(nowNanos = 12L))
        assertSlot(store, 0, ORIGIN_A, 10L)
        assertSlot(store, 1, ORIGIN_B, 11L)
        assertSlot(store, 2, ORIGIN_C, 12L)
    }

    @Test
    fun newRippleDoesNotModifyEarlierSlots() {
        val store = RippleStore(capacity = 3, lifetimeNanos = 100L)
        store.add(ORIGIN_A, 10L)
        store.add(ORIGIN_B, 20L)

        store.add(ORIGIN_C, 30L)

        assertSlot(store, 0, ORIGIN_A, 10L)
        assertSlot(store, 1, ORIGIN_B, 20L)
    }

    @Test
    fun expiredSlotIsReusedBeforeActiveSlots() {
        val store = RippleStore(capacity = 3, lifetimeNanos = 100L)
        store.add(ORIGIN_A, 10L)
        store.add(ORIGIN_B, 20L)

        assertEquals(0, store.add(ORIGIN_C, 110L))
        assertSlot(store, 0, ORIGIN_C, 110L)
        assertSlot(store, 1, ORIGIN_B, 20L)
    }

    @Test
    fun oldestActiveRippleIsReplacedAtCapacity() {
        val store = RippleStore(capacity = 3, lifetimeNanos = 100L)
        store.add(ORIGIN_A, 10L)
        store.add(ORIGIN_B, 20L)
        store.add(ORIGIN_C, 30L)

        assertEquals(0, store.add(ORIGIN_D, 40L))
        assertSlot(store, 0, ORIGIN_D, 40L)
        assertEquals(3, store.activeCount(nowNanos = 40L))
    }

    @Test
    fun equalStartTimesUseLowestSlotAsTieBreaker() {
        val store = RippleStore(capacity = 3, lifetimeNanos = 100L)
        store.add(ORIGIN_A, 10L)
        store.add(ORIGIN_B, 10L)
        store.add(ORIGIN_C, 10L)

        assertEquals(0, store.add(ORIGIN_D, 11L))
        assertSlot(store, 1, ORIGIN_B, 10L)
        assertSlot(store, 2, ORIGIN_C, 10L)
    }

    @Test
    fun activeCountNeverExceedsCapacity() {
        val store = RippleStore(capacity = 3, lifetimeNanos = 1_000L)

        repeat(20) { index ->
            store.add(ORIGIN_A, index.toLong())
            assertTrue(store.activeCount(index.toLong()) <= 3)
        }
    }

    @Test
    fun missDoesNotAddRipple() {
        val store = RippleStore(capacity = 3, lifetimeNanos = 100L)

        assertEquals(RippleStore.NO_SLOT, store.add(origin = null, startTimeNanos = 10L))
        assertEquals(0, store.activeCount(nowNanos = 10L))
    }

    @Test
    fun lifetimeBoundaryExpiresPredictably() {
        val store = RippleStore(capacity = 1, lifetimeNanos = 100L)
        store.add(ORIGIN_A, 10L)

        assertTrue(store.isActive(0, nowNanos = 109L))
        assertFalse(store.isActive(0, nowNanos = 110L))
    }

    @Test
    fun behaviorUsesOnlyCallerSuppliedMonotonicTime() {
        val store = RippleStore(capacity = 1, lifetimeNanos = 100L)
        store.add(ORIGIN_A, 5_000_000_000L)

        assertTrue(store.isActive(0, nowNanos = 5_000_000_099L))
        assertFalse(store.isActive(0, nowNanos = 5_000_000_100L))
    }

    private fun assertSlot(
        store: RippleStore,
        slot: Int,
        origin: Vector3,
        startTimeNanos: Long,
    ) {
        assertEquals(origin.x, store.originX(slot).toDouble(), TOLERANCE)
        assertEquals(origin.y, store.originY(slot).toDouble(), TOLERANCE)
        assertEquals(origin.z, store.originZ(slot).toDouble(), TOLERANCE)
        assertEquals(startTimeNanos, store.startTimeNanos(slot))
    }

    private companion object {
        const val TOLERANCE = 1e-6
        val ORIGIN_A = Vector3(1.0, 0.0, 0.0)
        val ORIGIN_B = Vector3(0.0, 1.0, 0.0)
        val ORIGIN_C = Vector3(0.0, 0.0, 1.0)
        val ORIGIN_D = Vector3(-1.0, 0.0, 0.0)
    }
}
