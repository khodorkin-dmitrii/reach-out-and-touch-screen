package com.yavin.reachoutandtouchscreen

internal enum class TextureColorSpace {
    SRGB,
    LINEAR,
}

internal data class LunarTextureAsset(
    val assetPath: String,
    val materialParameter: String,
    val colorSpace: TextureColorSpace,
)

internal val LUNAR_BASE_COLOR_TEXTURE = LunarTextureAsset(
    assetPath = "textures/moon_base_color.png",
    materialParameter = "baseColorTexture",
    colorSpace = TextureColorSpace.SRGB,
)

internal val LUNAR_NORMAL_TEXTURE = LunarTextureAsset(
    assetPath = "textures/moon_normal.png",
    materialParameter = "normalTexture",
    colorSpace = TextureColorSpace.LINEAR,
)
