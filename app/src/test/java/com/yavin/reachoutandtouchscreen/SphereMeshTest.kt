package com.yavin.reachoutandtouchscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class SphereMeshTest {
    @Test
    fun createsExpectedBoundedMesh() {
        val rings = 24
        val sectors = 48

        val mesh = SphereMesh.create(radius = 1f, rings = rings, sectors = sectors)

        assertEquals((rings + 1) * (sectors + 1), mesh.vertexCount)
        assertEquals(rings * sectors * 6, mesh.indices.size)
        assertTrue(mesh.indices.all { it.toUShort().toInt() < mesh.vertexCount })
    }

    @Test
    fun positionsStayOnSphereAndTangentFramesAreNormalized() {
        val mesh = SphereMesh.create(radius = 2f, rings = 8, sectors = 16)

        for (offset in mesh.vertices.indices step SphereMeshData.FLOATS_PER_VERTEX) {
            val positionLength = length(mesh.vertices, offset, 3)
            val tangentFrameLength = length(mesh.vertices, offset + 3, 4)
            assertEquals(2f, positionLength, 0.0001f)
            assertEquals(1f, tangentFrameLength, 0.0001f)
        }
    }

    private fun length(values: FloatArray, offset: Int, count: Int): Float {
        var squaredLength = 0f
        repeat(count) { squaredLength += values[offset + it] * values[offset + it] }
        return sqrt(squaredLength)
    }
}
