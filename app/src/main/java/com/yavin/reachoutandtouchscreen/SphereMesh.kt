package com.yavin.reachoutandtouchscreen

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class SphereMeshData(
    val vertices: FloatArray,
    val indices: ShortArray,
) {
    val vertexCount: Int get() = vertices.size / FLOATS_PER_VERTEX

    companion object {
        const val POSITION_OFFSET = 0
        const val TANGENT_OFFSET = 3
        const val UV_OFFSET = 7
        const val FLOATS_PER_VERTEX = 9
    }
}

object SphereMesh {
    fun create(radius: Float, rings: Int, sectors: Int): SphereMeshData {
        require(radius > 0f) { "Radius must be positive" }
        require(rings >= 2) { "At least two rings are required" }
        require(sectors >= 3) { "At least three sectors are required" }

        val vertexCount = (rings + 1) * (sectors + 1)
        require(vertexCount <= UShort.MAX_VALUE.toInt() + 1) {
            "Sphere exceeds the unsigned-short index range"
        }

        val vertices = FloatArray(vertexCount * SphereMeshData.FLOATS_PER_VERTEX)
        val indices = ShortArray(rings * sectors * 6)
        var vertexOffset = 0

        for (ring in 0..rings) {
            val latitude = -PI / 2.0 + PI * ring / rings
            // V=0 is lunar north and V=1 is lunar south, matching top-to-bottom image rows.
            val v = 1.0 - ring.toDouble() / rings
            val horizontalRadius = cos(latitude).toFloat()
            val y = sin(latitude).toFloat()

            for (sector in 0..sectors) {
                val u = sector.toDouble() / sectors
                // NASA's map has 0 degrees longitude at U=0.5. Keep it on the camera-facing +Z
                // hemisphere and place the duplicated equirectangular seam on the back (-Z).
                val longitude = -PI / 2.0 + 2.0 * PI * u
                val x = horizontalRadius * cos(longitude).toFloat()
                val z = horizontalRadius * sin(longitude).toFloat()

                vertices[vertexOffset++] = x * radius
                vertices[vertexOffset++] = y * radius
                vertices[vertexOffset++] = z * radius

                // T follows increasing U/east, B follows increasing V/south, and T x B = N.
                val tangentX = -sin(longitude).toFloat()
                val tangentZ = cos(longitude).toFloat()
                val bitangentX = y * tangentZ
                val bitangentY = z * tangentX - x * tangentZ
                val bitangentZ = -y * tangentX
                writeTangentFrameQuaternion(
                    tangentX, 0f, tangentZ,
                    bitangentX, bitangentY, bitangentZ,
                    x, y, z,
                    destination = vertices,
                    destinationOffset = vertexOffset,
                )
                vertexOffset += TANGENT_FRAME_COMPONENTS
                vertices[vertexOffset++] = u.toFloat()
                vertices[vertexOffset++] = v.toFloat()
            }
        }

        var indexOffset = 0
        for (ring in 0 until rings) {
            for (sector in 0 until sectors) {
                val topLeft = ring * (sectors + 1) + sector
                val topRight = topLeft + 1
                val bottomLeft = topLeft + sectors + 1
                val bottomRight = bottomLeft + 1

                indices[indexOffset++] = topLeft.toShort()
                indices[indexOffset++] = bottomLeft.toShort()
                indices[indexOffset++] = topRight.toShort()
                indices[indexOffset++] = topRight.toShort()
                indices[indexOffset++] = bottomLeft.toShort()
                indices[indexOffset++] = bottomRight.toShort()
            }
        }

        return SphereMeshData(vertices, indices)
    }

    internal fun writeTangentFrameQuaternion(
        tangentX: Float,
        tangentY: Float,
        tangentZ: Float,
        bitangentX: Float,
        bitangentY: Float,
        bitangentZ: Float,
        normalX: Float,
        normalY: Float,
        normalZ: Float,
        destination: FloatArray,
        destinationOffset: Int,
    ) {
        val m00 = tangentX
        val m01 = bitangentX
        val m02 = normalX
        val m10 = tangentY
        val m11 = bitangentY
        val m12 = normalY
        val m20 = tangentZ
        val m21 = bitangentZ
        val m22 = normalZ
        val trace = m00 + m11 + m22

        var quaternionX: Float
        var quaternionY: Float
        var quaternionZ: Float
        var quaternionW: Float
        when {
            trace > 0f -> {
                val scale = 2f * sqrt(trace + 1f)
                quaternionX = (m21 - m12) / scale
                quaternionY = (m02 - m20) / scale
                quaternionZ = (m10 - m01) / scale
                quaternionW = scale / 4f
            }
            m00 > m11 && m00 > m22 -> {
                val scale = 2f * sqrt(1f + m00 - m11 - m22)
                quaternionX = scale / 4f
                quaternionY = (m01 + m10) / scale
                quaternionZ = (m02 + m20) / scale
                quaternionW = (m21 - m12) / scale
            }
            m11 > m22 -> {
                val scale = 2f * sqrt(1f + m11 - m00 - m22)
                quaternionX = (m01 + m10) / scale
                quaternionY = scale / 4f
                quaternionZ = (m12 + m21) / scale
                quaternionW = (m02 - m20) / scale
            }
            else -> {
                val scale = 2f * sqrt(1f + m22 - m00 - m11)
                quaternionX = (m02 + m20) / scale
                quaternionY = (m12 + m21) / scale
                quaternionZ = scale / 4f
                quaternionW = (m10 - m01) / scale
            }
        }

        val inverseLength = 1f / sqrt(
            quaternionX * quaternionX + quaternionY * quaternionY +
                quaternionZ * quaternionZ + quaternionW * quaternionW,
        )
        quaternionX *= inverseLength
        quaternionY *= inverseLength
        quaternionZ *= inverseLength
        quaternionW *= inverseLength
        if (quaternionW < 0f) {
            quaternionX = -quaternionX
            quaternionY = -quaternionY
            quaternionZ = -quaternionZ
            quaternionW = -quaternionW
        }
        if (quaternionW < TANGENT_FRAME_W_BIAS) {
            quaternionW = TANGENT_FRAME_W_BIAS
            val xyzScale = sqrt(1f - TANGENT_FRAME_W_BIAS * TANGENT_FRAME_W_BIAS)
            quaternionX *= xyzScale
            quaternionY *= xyzScale
            quaternionZ *= xyzScale
        }

        destination[destinationOffset] = quaternionX
        destination[destinationOffset + 1] = quaternionY
        destination[destinationOffset + 2] = quaternionZ
        destination[destinationOffset + 3] = quaternionW
    }

    private const val TANGENT_FRAME_COMPONENTS = 4
    private const val TANGENT_FRAME_W_BIAS = 1f / Int.MAX_VALUE
}
