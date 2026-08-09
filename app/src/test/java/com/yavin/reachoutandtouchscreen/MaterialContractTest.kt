package com.yavin.reachoutandtouchscreen

import com.google.android.filament.Texture
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
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
        assertTrue(source.contains("combinedWave += signedWave * timeDamping"))
        assertTrue(source.contains("tanh(combinedWave)"))
    }

    @Test
    fun lunarTextureParametersMatchKotlinContract() {
        val source = File("src/main/materials/sphere.mat").readText()

        assertTrue(source.contains("flipUV : false"))
        for (texture in listOf(LUNAR_BASE_COLOR_TEXTURE, LUNAR_NORMAL_TEXTURE)) {
            assertTrue(source.contains("name : ${texture.materialParameter}"))
            assertTrue(source.contains("materialParams_${texture.materialParameter}"))
        }
        assertEquals(TextureColorSpace.SRGB, LUNAR_BASE_COLOR_TEXTURE.colorSpace)
        assertEquals(TextureColorSpace.LINEAR, LUNAR_NORMAL_TEXTURE.colorSpace)
        assertEquals(Texture.InternalFormat.SRGB8_A8, internalFormatFor(TextureColorSpace.SRGB))
        assertEquals(Texture.InternalFormat.RGBA8, internalFormatFor(TextureColorSpace.LINEAR))
        assertTrue(source.contains("material.normal = normalize(tangentNormal)"))
    }

    @Test
    fun generatedLunarTexturesExistAtRuntimeDimensions() {
        for (texture in listOf(LUNAR_BASE_COLOR_TEXTURE, LUNAR_NORMAL_TEXTURE)) {
            val file = File("src/main/assets/${texture.assetPath}")
            assertTrue(file.isFile)
            assertTrue(file.length() > 0L)
            val image = ImageIO.read(file)
            assertEquals(2048, image.width)
            assertEquals(1024, image.height)
            assertEquals(3, image.colorModel.numColorComponents)
        }
    }

    @Test
    fun fullMipChainIncludesOneByOneLevel() {
        assertEquals(12, mipLevelCount(width = 2048, height = 1024))
        assertEquals(1, mipLevelCount(width = 1, height = 1))
    }
}
