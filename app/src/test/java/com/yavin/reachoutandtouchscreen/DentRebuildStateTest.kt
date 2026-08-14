package com.yavin.reachoutandtouchscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DentRebuildStateTest {
    @Test
    fun rapidUpdatesAreCoalescedIntoLatestAuthoritativeVersion() {
        val state = DentRebuildState(slotCount = 2)

        assertTrue(state.onDentUpdated())
        assertFalse(state.onDentUpdated())
        assertFalse(state.onDentUpdated())

        val request = checkNotNull(state.beginPendingRebuild())
        assertEquals(3L, request.version)
        assertEquals(3, request.coalescedDentUpdates)
        assertFalse(request.waitedForFreeSlot)
        assertFalse(state.markSubmitted(request))
        assertEquals(3L, state.submittedVersion)
        assertFalse(state.isDirty())
    }

    @Test
    fun poolExhaustionKeepsLatestVersionDirtyUntilBothCallbacksReleaseASlot() {
        val state = DentRebuildState(slotCount = 2)
        val initialSlot = state.reserveInitialUploadSlot()
        state.markInitialUploadSubmitted(initialSlot)

        assertTrue(state.onDentUpdated())
        val firstDentRequest = checkNotNull(state.beginPendingRebuild())
        assertEquals(1 - initialSlot, firstDentRequest.slotIndex)
        assertFalse(state.markSubmitted(firstDentRequest))
        assertEquals(2, state.inFlightSlotCount())

        assertFalse(state.onDentUpdated())
        assertFalse(state.onDentUpdated())
        assertTrue(state.isDirty())
        assertFalse(state.isRebuildTaskPending())

        assertFalse(state.onUploadPartCompleted(initialSlot))
        assertTrue(state.isDirty())
        assertTrue(state.onUploadPartCompleted(initialSlot))
        assertTrue(state.isRebuildTaskPending())

        val latestRequest = checkNotNull(state.beginPendingRebuild())
        assertEquals(3L, latestRequest.version)
        assertEquals(2, latestRequest.coalescedDentUpdates)
        assertTrue(latestRequest.waitedForFreeSlot)
        assertFalse(state.markSubmitted(latestRequest))
        assertEquals(3L, state.submittedVersion)
        assertFalse(state.isDirty())
    }
}
