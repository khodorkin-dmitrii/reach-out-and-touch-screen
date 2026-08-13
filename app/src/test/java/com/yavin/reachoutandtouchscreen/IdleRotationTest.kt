package com.yavin.reachoutandtouchscreen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleRotationTest {
    @Test
    fun rotationStartsOnlyAfterEnabledTimeout() {
        val lastInteraction = 1_000L
        val delay = 10_000L

        assertFalse(shouldUseIdleRotation(true, lastInteraction, 10_999L, delay))
        assertTrue(shouldUseIdleRotation(true, lastInteraction, 11_000L, delay))
        assertFalse(shouldUseIdleRotation(false, lastInteraction, 11_000L, delay))
    }
}
