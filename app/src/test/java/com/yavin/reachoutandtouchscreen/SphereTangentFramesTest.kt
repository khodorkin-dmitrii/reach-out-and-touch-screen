package com.yavin.reachoutandtouchscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class SphereTangentFramesTest {
    @Test
    fun undeformedFramesMatchSphereAndRemainContinuousAtSeamAndPoles() {
        val rings = 12
        val sectors = 24
        val mesh = SphereMesh.create(radius = 1f, rings = rings, sectors = sectors)
        val positions = FloatArray(mesh.vertexCount * 3)
        SphereDentState.fromMesh(mesh).writeDisplacedPositions(positions)
        val frames = generateFrames(positions, mesh, rings, sectors)

        for (vertexIndex in 0 until mesh.vertexCount) {
            assertNormalizedFinite(frames.normals, vertexIndex * 3, 3)
            assertNormalizedFinite(frames.tangentQuaternions, vertexIndex * 4, 4)
            val positionOffset = vertexIndex * 3
            assertTrue(dot(frames.normals, positionOffset, positions, positionOffset, 3) > 0.999f)

            val meshQuaternionOffset = vertexIndex * SphereMeshData.FLOATS_PER_VERTEX +
                SphereMeshData.TANGENT_OFFSET
            val generatedQuaternionOffset = vertexIndex * 4
            assertTrue(
                abs(
                    dot(
                        mesh.vertices,
                        meshQuaternionOffset,
                        frames.tangentQuaternions,
                        generatedQuaternionOffset,
                        4,
                    ),
                ) > 0.999f,
            )
        }

        for (ring in 0..rings) {
            assertVectorsEqual(
                frames.normals,
                vertexIndex(ring, 0, sectors) * 3,
                vertexIndex(ring, sectors, sectors) * 3,
            )
        }
        for (sector in 1..sectors) {
            assertVectorsEqual(frames.normals, 0, vertexIndex(0, sector, sectors) * 3)
            assertVectorsEqual(
                frames.normals,
                vertexIndex(rings, 0, sectors) * 3,
                vertexIndex(rings, sector, sectors) * 3,
            )
        }
    }

    @Test
    fun accumulatedDentTiltsSlopeNormalsWithoutInvalidFrames() {
        val rings = 48
        val sectors = 96
        val mesh = SphereMesh.create(radius = 1f, rings = rings, sectors = sectors)
        val dentState = SphereDentState.fromMesh(mesh)
        dentState.applyDent(hitX = 0f, hitY = 0f, hitZ = 1f)
        val positions = FloatArray(mesh.vertexCount * 3)
        dentState.writeDisplacedPositions(positions)
        val frames = generateFrames(positions, mesh, rings, sectors)

        for (vertexIndex in 0 until mesh.vertexCount) {
            assertNormalizedFinite(frames.normals, vertexIndex * 3, 3)
            assertNormalizedFinite(frames.tangentQuaternions, vertexIndex * 4, 4)
            assertTrue(dot(frames.normals, vertexIndex * 3, positions, vertexIndex * 3, 3) > 0f)
        }

        val slopeVertex = vertexIndex(rings / 2, sectors / 2 + 1, sectors)
        val baseOffset = slopeVertex * SphereMeshData.FLOATS_PER_VERTEX
        assertTrue(
            dot(frames.normals, slopeVertex * 3, mesh.vertices, baseOffset, 3) < 0.999f,
        )
    }

    private fun assertNormalizedFinite(values: FloatArray, offset: Int, count: Int) {
        var lengthSquared = 0f
        repeat(count) {
            assertTrue(values[offset + it].isFinite())
            lengthSquared += values[offset + it] * values[offset + it]
        }
        assertEquals(1f, sqrt(lengthSquared), 0.0001f)
    }

    private fun generateFrames(
        positions: FloatArray,
        mesh: SphereMeshData,
        rings: Int,
        sectors: Int,
    ): Frames {
        val normals = FloatArray(mesh.vertexCount * 3)
        val tangentQuaternions = FloatArray(mesh.vertexCount * 4)
        SphereTangentFrames.generate(
            positions = positions,
            indices = mesh.indices,
            rings = rings,
            sectors = sectors,
            normals = normals,
            tangentQuaternions = tangentQuaternions,
        )
        return Frames(normals, tangentQuaternions)
    }

    private data class Frames(
        val normals: FloatArray,
        val tangentQuaternions: FloatArray,
    )

    private fun assertVectorsEqual(values: FloatArray, firstOffset: Int, secondOffset: Int) {
        repeat(3) { assertEquals(values[firstOffset + it], values[secondOffset + it], 0.000_001f) }
    }

    private fun dot(
        first: FloatArray,
        firstOffset: Int,
        second: FloatArray,
        secondOffset: Int,
        count: Int,
    ): Float {
        var result = 0f
        repeat(count) { result += first[firstOffset + it] * second[secondOffset + it] }
        return result
    }

    private fun vertexIndex(ring: Int, sector: Int, sectors: Int) = ring * (sectors + 1) + sector
}
