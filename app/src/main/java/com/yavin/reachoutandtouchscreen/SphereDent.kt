package com.yavin.reachoutandtouchscreen

import kotlin.math.sqrt

internal const val DENT_ANGULAR_RADIUS_RADIANS = 0.12f
internal const val DENT_COMPRESSION_PER_TOUCH = 0.018f
internal const val MAX_DENT_DEPTH = 0.06f

internal enum class SphereMeshDensity(val rings: Int, val sectors: Int) {
    BASELINE(rings = 24, sectors = 48),
    DEFAULT_CANDIDATE(rings = 48, sectors = 96),
    HIGH_DENSITY_REFERENCE(rings = 96, sectors = 192),
}

// Change this single source-level selection to compare the three compile-time mesh densities.
internal val ACTIVE_SPHERE_MESH_DENSITY = SphereMeshDensity.HIGH_DENSITY_REFERENCE

/** CPU-owned, persistent radial compression for a fixed-topology unit sphere. */
internal class SphereDentState(
    baseUnitDirections: FloatArray,
    restoredAccumulatedCompression: FloatArray? = null,
) {
    private val baseUnitDirections = baseUnitDirections.copyOf()
    private val accumulatedCompression = restoredAccumulatedCompression
        ?.takeIf { it.size * COMPONENTS_PER_POSITION == baseUnitDirections.size }
        ?.copyOf()
        ?: FloatArray(baseUnitDirections.size / COMPONENTS_PER_POSITION)

    val vertexCount: Int = accumulatedCompression.size

    init {
        require(baseUnitDirections.size % COMPONENTS_PER_POSITION == 0) {
            "Base directions must contain complete XYZ positions"
        }
        for (vertexIndex in 0 until vertexCount) {
            val offset = vertexIndex * COMPONENTS_PER_POSITION
            val length = sqrt(
                baseUnitDirections[offset] * baseUnitDirections[offset] +
                    baseUnitDirections[offset + 1] * baseUnitDirections[offset + 1] +
                    baseUnitDirections[offset + 2] * baseUnitDirections[offset + 2],
            )
            require(length > 0f && length.isFinite()) { "Base directions must be finite and non-zero" }
            this.baseUnitDirections[offset] /= length
            this.baseUnitDirections[offset + 1] /= length
            this.baseUnitDirections[offset + 2] /= length
        }
    }

    fun applyDent(hitX: Float, hitY: Float, hitZ: Float) {
        val hitLength = sqrt(hitX * hitX + hitY * hitY + hitZ * hitZ)
        require(hitLength > 0f && hitLength.isFinite()) { "Hit direction must be finite and non-zero" }
        val centerX = hitX / hitLength
        val centerY = hitY / hitLength
        val centerZ = hitZ / hitLength
        val minimumDot = kotlin.math.cos(DENT_ANGULAR_RADIUS_RADIANS)

        for (vertexIndex in 0 until vertexCount) {
            val offset = vertexIndex * COMPONENTS_PER_POSITION
            val dot = (
                baseUnitDirections[offset] * centerX +
                    baseUnitDirections[offset + 1] * centerY +
                    baseUnitDirections[offset + 2] * centerZ
                ).coerceIn(-1f, 1f)
            if (dot <= minimumDot) continue

            val angularDistance = kotlin.math.acos(dot)
            val normalizedDistance = angularDistance / DENT_ANGULAR_RADIUS_RADIANS
            val inwardProgress = 1f - normalizedDistance
            val smoothContribution = inwardProgress * inwardProgress * (3f - 2f * inwardProgress)
            accumulatedCompression[vertexIndex] += DENT_COMPRESSION_PER_TOUCH * smoothContribution
        }
    }

    fun displacedPositions(): FloatArray {
        val positions = FloatArray(baseUnitDirections.size)
        for (vertexIndex in 0 until vertexCount) {
            val radius = 1f - depthAt(vertexIndex)
            val offset = vertexIndex * COMPONENTS_PER_POSITION
            positions[offset] = baseUnitDirections[offset] * radius
            positions[offset + 1] = baseUnitDirections[offset + 1] * radius
            positions[offset + 2] = baseUnitDirections[offset + 2] * radius
        }
        return positions
    }

    fun depthAt(vertexIndex: Int): Float {
        val compression = accumulatedCompression[vertexIndex]
        // Rational soft saturation is monotonic and remains below the limit for finite input.
        return MAX_DENT_DEPTH * compression / (MAX_DENT_DEPTH + compression)
    }

    fun snapshotAccumulatedCompression(): FloatArray = accumulatedCompression.copyOf()

    companion object {
        private const val COMPONENTS_PER_POSITION = 3

        fun fromMesh(
            mesh: SphereMeshData,
            restoredAccumulatedCompression: FloatArray? = null,
        ): SphereDentState {
            val directions = FloatArray(mesh.vertexCount * COMPONENTS_PER_POSITION)
            for (vertexIndex in 0 until mesh.vertexCount) {
                val sourceOffset = vertexIndex * SphereMeshData.FLOATS_PER_VERTEX
                val destinationOffset = vertexIndex * COMPONENTS_PER_POSITION
                mesh.vertices.copyInto(
                    destination = directions,
                    destinationOffset = destinationOffset,
                    startIndex = sourceOffset + SphereMeshData.POSITION_OFFSET,
                    endIndex = sourceOffset + SphereMeshData.POSITION_OFFSET + COMPONENTS_PER_POSITION,
                )
            }
            return SphereDentState(directions, restoredAccumulatedCompression)
        }
    }
}
