package com.yavin.reachoutandtouchscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchReactionModeTest {
    @Test
    fun disabledTouchReactionSuppressesRippleAndDent() {
        val mode = TouchReactionMode.from(touchReactionEnabled = false, dentsEnabled = true)

        assertEquals(TouchReactionMode.NONE, mode)
        assertFalse(mode.createsRipple)
        assertFalse(mode.createsDent)
    }

    @Test
    fun disabledDentsKeepRippleEnabled() {
        val mode = TouchReactionMode.from(touchReactionEnabled = true, dentsEnabled = false)

        assertEquals(TouchReactionMode.RIPPLE_ONLY, mode)
        assertTrue(mode.createsRipple)
        assertFalse(mode.createsDent)
    }

    @Test
    fun enabledDentsAddToRippleReaction() {
        val mode = TouchReactionMode.from(touchReactionEnabled = true, dentsEnabled = true)

        assertEquals(TouchReactionMode.RIPPLE_AND_DENT, mode)
        assertTrue(mode.createsRipple)
        assertTrue(mode.createsDent)
    }
}
