package com.yavin.reachoutandtouchscreen

import org.junit.Assert.assertEquals
import org.junit.Test

class PointerDownDetectionTest {
    @Test
    fun firstPointerDownIsDispatchedOnce() {
        val dispatched = dispatch(
            listOf(Change("first", wasPressed = false, isPressed = true, x = 10, y = 20)),
        )

        assertEquals(listOf("first" to (10 to 20)), dispatched)
    }

    @Test
    fun secondPointerDownIsDispatchedWhileFirstRemainsPressed() {
        val dispatched = dispatch(
            listOf(
                Change("first", wasPressed = true, isPressed = true, x = 10, y = 20),
                Change("second", wasPressed = false, isPressed = true, x = 30, y = 40),
            ),
        )

        assertEquals(listOf("second" to (30 to 40)), dispatched)
    }

    @Test
    fun multipleDownsPreserveEventChangeOrderAndCoordinates() {
        val dispatched = dispatch(
            listOf(
                Change("third", wasPressed = false, isPressed = true, x = 50, y = 60),
                Change("first", wasPressed = true, isPressed = true, x = 11, y = 21),
                Change("fourth", wasPressed = false, isPressed = true, x = 70, y = 80),
            ),
        )

        assertEquals(
            listOf("third" to (50 to 60), "fourth" to (70 to 80)),
            dispatched,
        )
    }

    @Test
    fun moveUpAndCancellationDoNotDispatchDowns() {
        val dispatched = dispatch(
            listOf(
                Change("move", wasPressed = true, isPressed = true, x = 11, y = 21),
                Change("up", wasPressed = true, isPressed = false, x = 30, y = 40),
                Change("cancel", wasPressed = true, isPressed = false, x = 50, y = 60),
            ),
        )

        assertEquals(emptyList<Pair<String, Pair<Int, Int>>>(), dispatched)
    }

    private fun dispatch(changes: List<Change>): List<Pair<String, Pair<Int, Int>>> = buildList {
        forEachNewPointerDown(
            changes = changes,
            wasPressed = { it.wasPressed },
            isPressed = { it.isPressed },
        ) { change ->
            add(change.id to (change.x to change.y))
        }
    }

    private data class Change(
        val id: String,
        val wasPressed: Boolean,
        val isPressed: Boolean,
        val x: Int,
        val y: Int,
    )
}
