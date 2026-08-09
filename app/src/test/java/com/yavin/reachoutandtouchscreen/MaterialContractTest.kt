package com.yavin.reachoutandtouchscreen

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialContractTest {
    @Test
    fun materialArrayAndLoopMatchKotlinRippleCapacity() {
        val source = File("src/main/materials/sphere.mat").readText()

        assertTrue(
            source.contains("{ type : float4[$MAX_ACTIVE_RIPPLES], name : ripples }"),
        )
        assertTrue(source.contains("slot < $MAX_ACTIVE_RIPPLES"))
    }
}
