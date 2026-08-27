package com.yavin.reachoutandtouchscreen

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Reconstructs a smooth UV-compatible frame from the complete displaced latitude/longitude grid. */
internal object SphereTangentFrames {
    fun generate(
        positions: FloatArray,
        indices: ShortArray,
        rings: Int,
        sectors: Int,
        normals: FloatArray,
        tangentQuaternions: FloatArray,
    ) {
        val vertexCount = (rings + 1) * (sectors + 1)
        require(rings >= 2 && sectors >= 3)
        require(positions.size == vertexCount * POSITION_COMPONENTS)
        require(indices.size == rings * sectors * INDICES_PER_QUAD)
        require(normals.size == positions.size)
        require(tangentQuaternions.size == vertexCount * TANGENT_COMPONENTS)

        val firstScratch = FloatArray(POSITION_COMPONENTS)
        val secondScratch = FloatArray(POSITION_COMPONENTS)
        for (ring in 1 until rings) {
            for (sector in 0..sectors) {
                val canonicalSector = canonicalSector(sector, sectors)
                val previousSector = (canonicalSector + sectors - 1) % sectors
                val nextSector = (canonicalSector + 1) % sectors
                difference(
                    positions,
                    vertexIndex(ring, nextSector, sectors),
                    vertexIndex(ring, previousSector, sectors),
                    firstScratch,
                )
                difference(
                    positions,
                    vertexIndex(ring - 1, canonicalSector, sectors),
                    vertexIndex(ring + 1, canonicalSector, sectors),
                    secondScratch,
                )
                val vertexIndex = vertexIndex(ring, sector, sectors)
                writeNormalizedOutwardCross(
                    first = firstScratch,
                    second = secondScratch,
                    positions = positions,
                    vertexIndex = vertexIndex,
                    destination = normals,
                )
            }
        }

        val southNormal = FloatArray(POSITION_COMPONENTS)
        poleNormal(
            positions = positions,
            indices = indices,
            firstIndexOffset = 0,
            endIndexOffset = sectors * INDICES_PER_QUAD,
            poleVertexIndex = 0,
            destination = southNormal,
        )
        val northNormal = FloatArray(POSITION_COMPONENTS)
        poleNormal(
            positions = positions,
            indices = indices,
            firstIndexOffset = (rings - 1) * sectors * INDICES_PER_QUAD,
            endIndexOffset = indices.size,
            poleVertexIndex = rings * (sectors + 1),
            destination = northNormal,
        )
        for (sector in 0..sectors) {
            writeVector(normals, vertexIndex(0, sector, sectors), southNormal)
            writeVector(normals, vertexIndex(rings, sector, sectors), northNormal)
        }

        val tangentCandidate = FloatArray(POSITION_COMPONENTS)
        val tangent = FloatArray(POSITION_COMPONENTS)
        for (ring in 0..rings) {
            val tangentSourceRing = ring.coerceIn(1, rings - 1)
            for (sector in 0..sectors) {
                val canonicalSector = canonicalSector(sector, sectors)
                val previousSector = (canonicalSector + sectors - 1) % sectors
                val nextSector = (canonicalSector + 1) % sectors
                val vertexIndex = vertexIndex(ring, sector, sectors)
                val normalOffset = vertexIndex * POSITION_COMPONENTS
                val normalX = normals[normalOffset]
                val normalY = normals[normalOffset + 1]
                val normalZ = normals[normalOffset + 2]
                difference(
                    positions,
                    vertexIndex(tangentSourceRing, nextSector, sectors),
                    vertexIndex(tangentSourceRing, previousSector, sectors),
                    tangentCandidate,
                )
                orthogonalizedTangent(
                    candidate = tangentCandidate,
                    normalX = normalX,
                    normalY = normalY,
                    normalZ = normalZ,
                    sector = canonicalSector,
                    sectors = sectors,
                    destination = tangent,
                )
                // U increases east and V increases south, so the UV frame has negative
                // handedness: B = -(N x T).
                val bitangentX = normalZ * tangent[1] - normalY * tangent[2]
                val bitangentY = normalX * tangent[2] - normalZ * tangent[0]
                val bitangentZ = normalY * tangent[0] - normalX * tangent[1]
                SphereMesh.writeTangentFrameQuaternion(
                    tangent[0],
                    tangent[1],
                    tangent[2],
                    bitangentX,
                    bitangentY,
                    bitangentZ,
                    normalX,
                    normalY,
                    normalZ,
                    destination = tangentQuaternions,
                    destinationOffset = vertexIndex * TANGENT_COMPONENTS,
                )
            }
        }
    }

    private fun poleNormal(
        positions: FloatArray,
        indices: ShortArray,
        firstIndexOffset: Int,
        endIndexOffset: Int,
        poleVertexIndex: Int,
        destination: FloatArray,
    ) {
        var accumulatedX = 0f
        var accumulatedY = 0f
        var accumulatedZ = 0f
        val firstToSecond = FloatArray(POSITION_COMPONENTS)
        val firstToThird = FloatArray(POSITION_COMPONENTS)
        for (indexOffset in firstIndexOffset until endIndexOffset step INDICES_PER_TRIANGLE) {
            val first = indices[indexOffset].toUShort().toInt()
            val second = indices[indexOffset + 1].toUShort().toInt()
            val third = indices[indexOffset + 2].toUShort().toInt()
            difference(positions, second, first, firstToSecond)
            difference(positions, third, first, firstToThird)
            val faceX = firstToSecond[1] * firstToThird[2] - firstToSecond[2] * firstToThird[1]
            val faceY = firstToSecond[2] * firstToThird[0] - firstToSecond[0] * firstToThird[2]
            val faceZ = firstToSecond[0] * firstToThird[1] - firstToSecond[1] * firstToThird[0]
            val lengthSquared = faceX * faceX + faceY * faceY + faceZ * faceZ
            if (lengthSquared <= MIN_VECTOR_LENGTH_SQUARED || !lengthSquared.isFinite()) continue
            accumulatedX += faceX
            accumulatedY += faceY
            accumulatedZ += faceZ
        }
        normalizedOutward(
            x = accumulatedX,
            y = accumulatedY,
            z = accumulatedZ,
            positions = positions,
            vertexIndex = poleVertexIndex,
            destination = destination,
        )
    }

    private fun writeNormalizedOutwardCross(
        first: FloatArray,
        second: FloatArray,
        positions: FloatArray,
        vertexIndex: Int,
        destination: FloatArray,
    ) {
        val destinationOffset = vertexIndex * POSITION_COMPONENTS
        normalizedOutward(
            x = first[1] * second[2] - first[2] * second[1],
            y = first[2] * second[0] - first[0] * second[2],
            z = first[0] * second[1] - first[1] * second[0],
            positions = positions,
            vertexIndex = vertexIndex,
            destination = destination,
            destinationOffset = destinationOffset,
        )
    }

    private fun normalizedOutward(
        x: Float,
        y: Float,
        z: Float,
        positions: FloatArray,
        vertexIndex: Int,
        destination: FloatArray,
        destinationOffset: Int = 0,
    ) {
        val positionOffset = vertexIndex * POSITION_COMPONENTS
        var normalX = x
        var normalY = y
        var normalZ = z
        var lengthSquared = normalX * normalX + normalY * normalY + normalZ * normalZ
        if (lengthSquared <= MIN_VECTOR_LENGTH_SQUARED || !lengthSquared.isFinite()) {
            normalX = positions[positionOffset]
            normalY = positions[positionOffset + 1]
            normalZ = positions[positionOffset + 2]
            lengthSquared = normalX * normalX + normalY * normalY + normalZ * normalZ
        }
        require(lengthSquared > MIN_VECTOR_LENGTH_SQUARED && lengthSquared.isFinite())
        val inverseLength = 1f / sqrt(lengthSquared)
        normalX *= inverseLength
        normalY *= inverseLength
        normalZ *= inverseLength
        val outwardDot = normalX * positions[positionOffset] +
            normalY * positions[positionOffset + 1] + normalZ * positions[positionOffset + 2]
        if (outwardDot < 0f) {
            normalX = -normalX
            normalY = -normalY
            normalZ = -normalZ
        }
        destination[destinationOffset] = normalX
        destination[destinationOffset + 1] = normalY
        destination[destinationOffset + 2] = normalZ
    }

    private fun orthogonalizedTangent(
        candidate: FloatArray,
        normalX: Float,
        normalY: Float,
        normalZ: Float,
        sector: Int,
        sectors: Int,
        destination: FloatArray,
    ) {
        val normalProjection = candidate[0] * normalX + candidate[1] * normalY + candidate[2] * normalZ
        var tangentX = candidate[0] - normalProjection * normalX
        var tangentY = candidate[1] - normalProjection * normalY
        var tangentZ = candidate[2] - normalProjection * normalZ
        var lengthSquared = tangentX * tangentX + tangentY * tangentY + tangentZ * tangentZ
        if (lengthSquared <= MIN_VECTOR_LENGTH_SQUARED || !lengthSquared.isFinite()) {
            val longitude = 2.0 * PI * (sector.toDouble() / sectors - 0.5)
            val fallbackX = cos(longitude).toFloat()
            val fallbackZ = -sin(longitude).toFloat()
            val fallbackProjection = fallbackX * normalX + fallbackZ * normalZ
            tangentX = fallbackX - fallbackProjection * normalX
            tangentY = -fallbackProjection * normalY
            tangentZ = fallbackZ - fallbackProjection * normalZ
            lengthSquared = tangentX * tangentX + tangentY * tangentY + tangentZ * tangentZ
        }
        if (lengthSquared <= MIN_VECTOR_LENGTH_SQUARED || !lengthSquared.isFinite()) {
            val referenceX = if (abs(normalY) > 0.9f) 1f else 0f
            val referenceY = if (abs(normalY) > 0.9f) 0f else 1f
            tangentX = referenceY * normalZ
            tangentY = -referenceX * normalZ
            tangentZ = referenceX * normalY - referenceY * normalX
            lengthSquared = tangentX * tangentX + tangentY * tangentY + tangentZ * tangentZ
        }
        require(lengthSquared > MIN_VECTOR_LENGTH_SQUARED && lengthSquared.isFinite())
        val inverseLength = 1f / sqrt(lengthSquared)
        destination[0] = tangentX * inverseLength
        destination[1] = tangentY * inverseLength
        destination[2] = tangentZ * inverseLength
    }

    private fun difference(
        values: FloatArray,
        firstVertex: Int,
        secondVertex: Int,
        destination: FloatArray,
    ) {
        val firstOffset = firstVertex * POSITION_COMPONENTS
        val secondOffset = secondVertex * POSITION_COMPONENTS
        destination[0] = values[firstOffset] - values[secondOffset]
        destination[1] = values[firstOffset + 1] - values[secondOffset + 1]
        destination[2] = values[firstOffset + 2] - values[secondOffset + 2]
    }

    private fun writeVector(destination: FloatArray, vertexIndex: Int, vector: FloatArray) {
        val offset = vertexIndex * POSITION_COMPONENTS
        destination[offset] = vector[0]
        destination[offset + 1] = vector[1]
        destination[offset + 2] = vector[2]
    }

    private fun canonicalSector(sector: Int, sectors: Int) = if (sector == sectors) 0 else sector

    private fun vertexIndex(ring: Int, sector: Int, sectors: Int) = ring * (sectors + 1) + sector

    private const val POSITION_COMPONENTS = 3
    private const val TANGENT_COMPONENTS = 4
    private const val INDICES_PER_TRIANGLE = 3
    private const val INDICES_PER_QUAD = 6
    private const val MIN_VECTOR_LENGTH_SQUARED = 1e-20f
}
