package com.yavin.reachoutandtouchscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereDentStateTest {
    @Test
    fun dentAffectsCenterButNotDirectionOutsideRadius() {
        val state = SphereDentState(
            baseUnitDirections = floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
            ),
        )

        state.applyDent(hitX = 1f, hitY = 0f, hitZ = 0f)

        assertTrue(state.depthAt(0) > 0f)
        assertEquals(0f, state.depthAt(1), 0f)
    }

    @Test
    fun repeatedDentsDeepenSmoothlyAndRemainBelowMaximum() {
        val state = SphereDentState(floatArrayOf(1f, 0f, 0f))

        state.applyDent(hitX = 1f, hitY = 0f, hitZ = 0f)
        val firstDepth = state.depthAt(0)
        repeat(99) { state.applyDent(hitX = 1f, hitY = 0f, hitZ = 0f) }
        val repeatedDepth = state.depthAt(0)

        assertTrue(repeatedDepth > firstDepth)
        assertTrue(repeatedDepth < MAX_DENT_DEPTH)
    }
}
