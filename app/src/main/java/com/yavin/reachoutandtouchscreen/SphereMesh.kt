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
        const val FLOATS_PER_VERTEX = 7
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
            val horizontalRadius = cos(latitude).toFloat()
            val y = sin(latitude).toFloat()

            for (sector in 0..sectors) {
                val longitude = 2.0 * PI * sector / sectors
                val x = horizontalRadius * cos(longitude).toFloat()
                val z = horizontalRadius * sin(longitude).toFloat()

                vertices[vertexOffset++] = x * radius
                vertices[vertexOffset++] = y * radius
                vertices[vertexOffset++] = z * radius

                val tangentX = -sin(longitude).toFloat()
                val tangentZ = cos(longitude).toFloat()
                val bitangentX = y * tangentZ
                val bitangentY = z * tangentX - x * tangentZ
                val bitangentZ = -y * tangentX
                val tangentFrame = tangentFrameQuaternion(
                    tangentX, 0f, tangentZ,
                    bitangentX, bitangentY, bitangentZ,
                    x, y, z,
                )
                tangentFrame.copyInto(vertices, vertexOffset)
                vertexOffset += tangentFrame.size
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

    private fun tangentFrameQuaternion(
        tangentX: Float,
        tangentY: Float,
        tangentZ: Float,
        bitangentX: Float,
        bitangentY: Float,
        bitangentZ: Float,
        normalX: Float,
        normalY: Float,
        normalZ: Float,
    ): FloatArray {
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

        val quaternion = when {
            trace > 0f -> {
                val scale = 2f * sqrt(trace + 1f)
                floatArrayOf(
                    (m21 - m12) / scale,
                    (m02 - m20) / scale,
                    (m10 - m01) / scale,
                    scale / 4f,
                )
            }
            m00 > m11 && m00 > m22 -> {
                val scale = 2f * sqrt(1f + m00 - m11 - m22)
                floatArrayOf(
                    scale / 4f,
                    (m01 + m10) / scale,
                    (m02 + m20) / scale,
                    (m21 - m12) / scale,
                )
            }
            m11 > m22 -> {
                val scale = 2f * sqrt(1f + m11 - m00 - m22)
                floatArrayOf(
                    (m01 + m10) / scale,
                    scale / 4f,
                    (m12 + m21) / scale,
                    (m02 - m20) / scale,
                )
            }
            else -> {
                val scale = 2f * sqrt(1f + m22 - m00 - m11)
                floatArrayOf(
                    (m02 + m20) / scale,
                    (m12 + m21) / scale,
                    scale / 4f,
                    (m10 - m01) / scale,
                )
            }
        }

        val length = sqrt(quaternion.sumOf { (it * it).toDouble() }).toFloat()
        return quaternion.apply {
            for (index in indices) this[index] /= length
            if (this[3] < 0f) for (index in indices) this[index] = -this[index]
        }
    }
}
