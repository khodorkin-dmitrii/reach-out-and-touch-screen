package com.yavin.reachoutandtouchscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sign
import kotlin.math.sqrt

class SphereMeshTest {
    @Test
    fun createsExpectedBoundedMesh() {
        val rings = ACTIVE_SPHERE_MESH_DENSITY.rings
        val sectors = ACTIVE_SPHERE_MESH_DENSITY.sectors

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

    @Test
    fun equirectangularUvsCoverExpectedRangeAndDuplicateSeam() {
        val rings = 4
        val sectors = 8
        val mesh = SphereMesh.create(radius = 1f, rings = rings, sectors = sectors)

        for (ring in 0..rings) {
            val first = vertexOffset(ring, 0, sectors)
            val last = vertexOffset(ring, sectors, sectors)
            assertEquals(0f, mesh.vertices[first + SphereMeshData.UV_OFFSET], 0f)
            assertEquals(1f, mesh.vertices[last + SphereMeshData.UV_OFFSET], 0f)
            assertEquals(
                1f - ring.toFloat() / rings,
                mesh.vertices[first + SphereMeshData.UV_OFFSET + 1],
                0f,
            )
            repeat(3) { component ->
                assertEquals(
                    mesh.vertices[first + SphereMeshData.POSITION_OFFSET + component],
                    mesh.vertices[last + SphereMeshData.POSITION_OFFSET + component],
                    0.000_001f,
                )
            }
        }

        for (offset in mesh.vertices.indices step SphereMeshData.FLOATS_PER_VERTEX) {
            val u = mesh.vertices[offset + SphereMeshData.UV_OFFSET]
            val v = mesh.vertices[offset + SphereMeshData.UV_OFFSET + 1]
            assertTrue(u in 0f..1f)
            assertTrue(v in 0f..1f)
        }

        assertEquals(1f, mesh.vertices[vertexOffset(0, 0, sectors) + SphereMeshData.UV_OFFSET + 1], 0f)
        assertEquals(0f, mesh.vertices[vertexOffset(rings, 0, sectors) + SphereMeshData.UV_OFFSET + 1], 0f)
    }

    @Test
    fun tangentFrameMatchesIncreasingUvDirectionsAndIsRightHanded() {
        val rings = 12
        val sectors = 24
        val mesh = SphereMesh.create(radius = 1f, rings = rings, sectors = sectors)

        for ((ring, sector) in listOf(3 to 5, 6 to 12, 9 to 19)) {
            val offset = vertexOffset(ring, sector, sectors)
            val quaternion = vector(mesh, offset + SphereMeshData.TANGENT_OFFSET, 4)
            val tangent = rotate(quaternion, floatArrayOf(1f, 0f, 0f))
            val normal = rotate(quaternion, floatArrayOf(0f, 0f, 1f))
            val bitangent = cross(normal, tangent).map { it * sign(quaternion[3]) }.toFloatArray()

            val increasingU = subtract(
                position(mesh, ring, sector + 1, sectors),
                position(mesh, ring, sector - 1, sectors),
            )
            val increasingV = subtract(
                position(mesh, ring - 1, sector, sectors),
                position(mesh, ring + 1, sector, sectors),
            )

            assertTrue(dot(normalize(tangent), normalize(increasingU)) > 0.99f)
            assertTrue(dot(normalize(bitangent), normalize(increasingV)) > 0.99f)
            assertTrue(dot(normalize(cross(tangent, bitangent)), normalize(normal)) > 0.999f)
        }
    }

    @Test
    fun tangentQuaternionAlwaysCarriesPositiveHandednessIncludingEquator() {
        val mesh = SphereMesh.create(radius = 1f, rings = 12, sectors = 24)

        for (offset in mesh.vertices.indices step SphereMeshData.FLOATS_PER_VERTEX) {
            val w = mesh.vertices[offset + SphereMeshData.TANGENT_OFFSET + 3]
            assertTrue("Tangent quaternion w must encode positive handedness", w > 0f)
        }
    }

    @Test
    fun seamDuplicatesPositionNormalAndTangentFrameButNotU() {
        val rings = 8
        val sectors = 16
        val mesh = SphereMesh.create(radius = 1f, rings = rings, sectors = sectors)

        for (ring in 1 until rings) {
            val first = vertexOffset(ring, 0, sectors)
            val last = vertexOffset(ring, sectors, sectors)
            repeat(7) { component ->
                assertEquals(mesh.vertices[first + component], mesh.vertices[last + component], 0.000_001f)
            }
            assertEquals(0f, mesh.vertices[first + SphereMeshData.UV_OFFSET], 0f)
            assertEquals(1f, mesh.vertices[last + SphereMeshData.UV_OFFSET], 0f)
            assertEquals(
                mesh.vertices[first + SphereMeshData.UV_OFFSET + 1],
                mesh.vertices[last + SphereMeshData.UV_OFFSET + 1],
                0f,
            )
        }
    }

    private fun vertexOffset(ring: Int, sector: Int, sectors: Int): Int =
        (ring * (sectors + 1) + sector) * SphereMeshData.FLOATS_PER_VERTEX

    private fun position(mesh: SphereMeshData, ring: Int, sector: Int, sectors: Int): FloatArray =
        vector(mesh, vertexOffset(ring, sector, sectors) + SphereMeshData.POSITION_OFFSET, 3)

    private fun vector(mesh: SphereMeshData, offset: Int, count: Int): FloatArray =
        mesh.vertices.copyOfRange(offset, offset + count)

    private fun rotate(quaternion: FloatArray, vector: FloatArray): FloatArray {
        val xyz = quaternion.copyOfRange(0, 3)
        val twiceCross = cross(xyz, vector).map { 2f * it }.toFloatArray()
        return FloatArray(3) { index ->
            vector[index] + quaternion[3] * twiceCross[index] + cross(xyz, twiceCross)[index]
        }
    }

    private fun subtract(first: FloatArray, second: FloatArray): FloatArray =
        FloatArray(3) { first[it] - second[it] }

    private fun cross(first: FloatArray, second: FloatArray): FloatArray =
        floatArrayOf(
            first[1] * second[2] - first[2] * second[1],
            first[2] * second[0] - first[0] * second[2],
            first[0] * second[1] - first[1] * second[0],
        )

    private fun dot(first: FloatArray, second: FloatArray): Float =
        first.indices.sumOf { (first[it] * second[it]).toDouble() }.toFloat()

    private fun normalize(vector: FloatArray): FloatArray {
        val vectorLength = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        assertTrue(vectorLength > 0f && vectorLength.isFinite())
        return vector.map { it / vectorLength }.toFloatArray()
    }

    private fun length(values: FloatArray, offset: Int, count: Int): Float {
        var squaredLength = 0f
        repeat(count) { squaredLength += values[offset + it] * values[offset + it] }
        return sqrt(squaredLength)
    }
}
