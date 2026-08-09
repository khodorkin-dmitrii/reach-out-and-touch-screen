package com.yavin.reachoutandtouchscreen

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Choreographer
import android.view.Surface
import androidx.annotation.MainThread
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance.FloatElement
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.google.android.filament.Viewport
import com.google.android.filament.android.TextureHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

/**
 * Main-thread facade around a single-thread-owned Filament scene.
 *
 * Surface destruction and final cleanup are synchronous because Android may release the native
 * surface as soon as its callback returns. All other work is queued to keep engine and asset
 * creation off the UI thread while preserving Filament's single-thread access requirement.
 */
@MainThread
internal class FilamentRenderer(
    assets: AssetManager,
    initialRippleSnapshot: RippleSnapshot,
    private val onFpsChanged: (Int) -> Unit,
) {
    private val renderThread = HandlerThread("FilamentRenderer").apply { start() }
    private val handler = Handler(renderThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var state: RenderState
    @Volatile
    private var acceptingCalls = true

    init {
        handler.post { state = RenderState(assets, initialRippleSnapshot, ::publishFps) }
    }

    fun attachSurface(surface: Surface, width: Int, height: Int) {
        post { attachSurface(surface, width, height) }
    }

    fun resize(width: Int, height: Int) {
        post { resize(width, height) }
    }

    fun detachSurface(surface: Surface) {
        runSynchronously { detachSurface(surface) }
    }

    fun setResumed(resumed: Boolean) {
        post { setResumed(resumed) }
    }

    fun onTouch(touch: TouchInput) {
        post { onTouch(touch) }
    }

    fun snapshotAndDestroy(): RippleSnapshot {
        checkMainThread()
        if (!acceptingCalls) return RippleSnapshot.Empty
        acceptingCalls = false
        var snapshot = RippleSnapshot.Empty
        runSynchronously(allowAfterClose = true) {
            snapshot = captureRippleSnapshot(System.nanoTime())
            destroy()
        }
        renderThread.quitSafely()
        return snapshot
    }

    private fun post(block: RenderState.() -> Unit) {
        checkMainThread()
        if (!acceptingCalls) return
        handler.post {
            state.block()
        }
    }

    private fun runSynchronously(
        allowAfterClose: Boolean = false,
        block: RenderState.() -> Unit,
    ) {
        checkMainThread()
        if (!acceptingCalls && !allowAfterClose) return
        val completion = CountDownLatch(1)
        handler.post {
            try {
                state.block()
            } finally {
                completion.countDown()
            }
        }
        completion.await()
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "FilamentRenderer facade must be accessed from the main thread"
        }
    }

    private fun publishFps(fps: Int) {
        mainHandler.post {
            if (acceptingCalls) onFpsChanged(fps)
        }
    }

    private class RenderState(
        private val assets: AssetManager,
        initialRippleSnapshot: RippleSnapshot,
        private val publishFps: (Int) -> Unit,
    ) : Choreographer.FrameCallback {
        private val engine = Engine.create()
        private val renderer = engine.createRenderer()
        private val scene = engine.createScene()
        private val view = engine.createView()
        private val entityManager = EntityManager.get()
        private val cameraEntity = entityManager.create()
        private val camera = engine.createCamera(cameraEntity)
        private val sphereEntity = entityManager.create()
        private val lightEntity = entityManager.create()
        private val meshData = SphereMesh.create(
            radius = 1f,
            rings = SPHERE_RINGS,
            sectors = SPHERE_SECTORS,
        )
        private val vertexData = createVertexBufferData(meshData)
        private val indexData = createIndexBufferData(meshData)
        private val vertexBuffer = createVertexBuffer()
        private val indexBuffer = createIndexBuffer()
        private val materialPackage = readAsset(MATERIAL_ASSET)
        private val material = Material.Builder()
            .payload(materialPackage, materialPackage.remaining())
            .build(engine)
        private val materialInstance = material.createInstance()
        private val choreographer = Choreographer.getInstance()
        private val uploadHandler = Handler(checkNotNull(Looper.myLooper()))
        private val textureSampler = TextureSampler(
            TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
            TextureSampler.MagFilter.LINEAR,
            TextureSampler.WrapMode.REPEAT,
            TextureSampler.WrapMode.CLAMP_TO_EDGE,
            TextureSampler.WrapMode.CLAMP_TO_EDGE,
        )
        private val baseColorTexture = loadTexture(LUNAR_BASE_COLOR_TEXTURE)
        private val normalTexture = loadTexture(LUNAR_NORMAL_TEXTURE)

        private var surface: Surface? = null
        private var swapChain: SwapChain? = null
        private var resumed = false
        private var frameScheduled = false
        private var destroyed = false
        private var width = 0
        private var height = 0
        private val initialRippleTimeNanos = System.nanoTime()
        private val rippleStore = RippleStore().apply {
            restore(initialRippleSnapshot, initialRippleTimeNanos)
        }
        private val rippleEpochNanos = rippleEpochForRestore(
            nowNanos = initialRippleTimeNanos,
            earliestActiveStartTimeNanos = rippleStore.earliestActiveStartTimeNanos(
                initialRippleTimeNanos,
            ),
        )
        private val rippleParameters = FloatArray(MAX_ACTIVE_RIPPLES * RIPPLE_PARAMETER_COMPONENTS)
        private var rippleClockNeedsUpdate = false
        private val projectionMatrix = DoubleArray(16)
        private val viewMatrix = DoubleArray(16)
        private var fpsSampleStartNanos = 0L
        private var renderedFramesInSample = 0
        private var lastPublishedFps = 0

        init {
            renderer.clearOptions = Renderer.ClearOptions().apply {
                clear = true
                clearColor = doubleArrayOf(0.018, 0.025, 0.045, 1.0)
            }

            view.scene = scene
            view.camera = camera
            camera.setExposure(16f, 1f / 125f, 100f)

            materialInstance.setParameter(
                "baseColor",
                Colors.RgbaType.SRGB,
                1f,
                1f,
                1f,
                1f,
            )
            materialInstance.setParameter("roughness", 0.72f)
            materialInstance.setParameter("metallic", 0f)
            materialInstance.setParameter(
                LUNAR_BASE_COLOR_TEXTURE.materialParameter,
                baseColorTexture,
                textureSampler,
            )
            materialInstance.setParameter(
                LUNAR_NORMAL_TEXTURE.materialParameter,
                normalTexture,
                textureSampler,
            )
            for (slot in 0 until MAX_ACTIVE_RIPPLES) {
                rippleParameters[slot * RIPPLE_PARAMETER_COMPONENTS + RIPPLE_START_COMPONENT] =
                    INACTIVE_RIPPLE_START_SECONDS
            }
            materialInstance.setParameter(
                "ripples",
                FloatElement.FLOAT4,
                rippleParameters,
                0,
                MAX_ACTIVE_RIPPLES,
            )
            materialInstance.setParameter("rippleClock", 0f)

            if (rippleStore.hasActive(initialRippleTimeNanos)) {
                uploadRippleParameters(initialRippleTimeNanos)
                updateRippleClock(initialRippleTimeNanos)
                rippleClockNeedsUpdate = true
            }

            RenderableManager.Builder(1)
                .boundingBox(Box(0f, 0f, 0f, 1f, 1f, 1f))
                .material(0, materialInstance)
                .geometry(
                    0,
                    RenderableManager.PrimitiveType.TRIANGLES,
                    vertexBuffer,
                    indexBuffer,
                    0,
                    meshData.indices.size,
                )
                .build(engine, sphereEntity)

            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1f, 0.94f, 0.86f)
                .intensity(95_000f)
                .direction(-0.55f, -0.8f, -0.65f)
                .castShadows(false)
                .build(engine, lightEntity)

            scene.addEntity(sphereEntity)
            scene.addEntity(lightEntity)
        }

        fun attachSurface(newSurface: Surface, width: Int, height: Int) {
            if (destroyed) return
            if (surface !== newSurface) {
                destroySwapChain()
                surface = newSurface
                swapChain = engine.createSwapChain(newSurface)
                resetFpsSampling()
            }
            resize(width, height)
            updateFrameScheduling()
        }

        fun resize(width: Int, height: Int) {
            if (destroyed || width <= 0 || height <= 0) return
            this.width = width
            this.height = height
            val aspectRatio = width.toDouble() / height.toDouble()
            view.viewport = Viewport(0, 0, width, height)
            camera.setProjection(
                VERTICAL_FOV_DEGREES,
                aspectRatio,
                0.1,
                100.0,
                Camera.Fov.VERTICAL,
            )
            val cameraDistance = CameraFraming.distanceForSphere(
                radius = 1.0,
                verticalFovDegrees = VERTICAL_FOV_DEGREES,
                aspectRatio = aspectRatio,
                margin = FRAMING_MARGIN,
            )
            camera.lookAt(0.0, 0.1, cameraDistance, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
            updateFrameScheduling()
        }

        fun detachSurface(detachedSurface: Surface) {
            if (destroyed || surface !== detachedSurface) return
            stopFrameLoop()
            destroySwapChain()
            surface = null
            width = 0
            height = 0
        }

        fun setResumed(resumed: Boolean) {
            if (destroyed) return
            if (this.resumed == resumed) return
            this.resumed = resumed
            resetFpsSampling()
            updateFrameScheduling()
        }

        fun onTouch(touch: TouchInput) {
            if (!canRender()) return
            camera.getProjectionMatrix(projectionMatrix)
            camera.getViewMatrix(viewMatrix)
            val ray = ScreenRay.create(
                touch = touch,
                viewportWidth = width,
                viewportHeight = height,
                projectionMatrix = projectionMatrix,
                viewMatrix = viewMatrix,
            ) ?: return

            // The sphere renderable currently has an identity transform, so its unit object-space
            // sphere and API-level world-space sphere are identical. A future transform must first
            // move this ray into object space before using the same intersection routine.
            val hit = RaySphereIntersection.nearestHit(
                ray = ray,
                sphereCenter = SPHERE_CENTER,
                sphereRadius = SPHERE_RADIUS,
            ) ?: return
            val rippleDirection = (hit - SPHERE_CENTER).normalized() ?: return
            val nowNanos = System.nanoTime()
            rippleStore.add(rippleDirection, nowNanos)
            uploadRippleParameters(nowNanos)
            updateRippleClock(nowNanos)
            rippleClockNeedsUpdate = true
        }

        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            val currentSwapChain = swapChain
            if (!canRender() || currentSwapChain == null) return

            updateRipples(frameTimeNanos)
            if (renderer.beginFrame(currentSwapChain, frameTimeNanos)) {
                renderer.render(view)
                renderer.endFrame()
                recordRenderedFrame(frameTimeNanos)
            }
            updateFrameScheduling()
        }

        fun captureRippleSnapshot(nowNanos: Long): RippleSnapshot = rippleStore.snapshot(nowNanos)

        fun destroy() {
            if (destroyed) return
            destroyed = true
            stopFrameLoop()
            destroySwapChain()
            surface = null
            engine.flushAndWait()

            scene.removeEntity(sphereEntity)
            scene.removeEntity(lightEntity)
            engine.destroyEntity(sphereEntity)
            engine.destroyEntity(lightEntity)
            engine.destroyMaterialInstance(materialInstance)
            engine.destroyTexture(baseColorTexture)
            engine.destroyTexture(normalTexture)
            engine.destroyMaterial(material)
            engine.destroyVertexBuffer(vertexBuffer)
            engine.destroyIndexBuffer(indexBuffer)
            engine.destroyCameraComponent(cameraEntity)
            engine.destroyView(view)
            engine.destroyScene(scene)
            engine.destroyRenderer(renderer)
            entityManager.destroy(sphereEntity)
            entityManager.destroy(lightEntity)
            entityManager.destroy(cameraEntity)
            engine.destroy()
        }

        private fun createVertexBuffer() = VertexBuffer.Builder()
            .vertexCount(meshData.vertexCount)
            .bufferCount(1)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION,
                0,
                VertexBuffer.AttributeType.FLOAT3,
                0,
                VERTEX_SIZE_BYTES,
            )
            .attribute(
                VertexBuffer.VertexAttribute.TANGENTS,
                0,
                VertexBuffer.AttributeType.FLOAT4,
                SphereMeshData.TANGENT_OFFSET * Float.SIZE_BYTES,
                VERTEX_SIZE_BYTES,
            )
            .attribute(
                VertexBuffer.VertexAttribute.UV0,
                0,
                VertexBuffer.AttributeType.FLOAT2,
                SphereMeshData.UV_OFFSET * Float.SIZE_BYTES,
                VERTEX_SIZE_BYTES,
            )
            .build(engine)
            .also { it.setBufferAt(engine, 0, vertexData) }

        private fun createIndexBuffer() = IndexBuffer.Builder()
            .indexCount(meshData.indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
            .also { it.setBuffer(engine, indexData) }

        private fun destroySwapChain() {
            swapChain?.let {
                engine.destroySwapChain(it)
                engine.flushAndWait()
            }
            swapChain = null
            resetFpsSampling()
        }

        private fun uploadRippleParameters(nowNanos: Long) {
            for (slot in 0 until MAX_ACTIVE_RIPPLES) {
                val parameterOffset = slot * RIPPLE_PARAMETER_COMPONENTS
                if (rippleStore.isActive(slot, nowNanos)) {
                    rippleParameters[parameterOffset] = rippleStore.originX(slot)
                    rippleParameters[parameterOffset + 1] = rippleStore.originY(slot)
                    rippleParameters[parameterOffset + 2] = rippleStore.originZ(slot)
                    rippleParameters[parameterOffset + RIPPLE_START_COMPONENT] =
                        secondsSinceRippleEpoch(rippleStore.startTimeNanos(slot))
                } else {
                    rippleParameters[parameterOffset + RIPPLE_START_COMPONENT] =
                        INACTIVE_RIPPLE_START_SECONDS
                }
            }
            materialInstance.setParameter(
                "ripples",
                FloatElement.FLOAT4,
                rippleParameters,
                0,
                MAX_ACTIVE_RIPPLES,
            )
        }

        private fun updateRipples(frameTimeNanos: Long) {
            if (!rippleClockNeedsUpdate) return
            updateRippleClock(frameTimeNanos)
            if (!rippleStore.hasActive(frameTimeNanos)) rippleClockNeedsUpdate = false
        }

        private fun updateRippleClock(nowNanos: Long) {
            materialInstance.setParameter("rippleClock", secondsSinceRippleEpoch(nowNanos))
        }

        private fun secondsSinceRippleEpoch(timeNanos: Long) =
            rippleSecondsSinceEpoch(timeNanos, rippleEpochNanos)

        private fun recordRenderedFrame(frameTimeNanos: Long) {
            if (fpsSampleStartNanos == 0L) {
                fpsSampleStartNanos = frameTimeNanos
                renderedFramesInSample = 0
                return
            }

            renderedFramesInSample++
            val elapsedNanos = frameTimeNanos - fpsSampleStartNanos
            if (elapsedNanos < FPS_SAMPLE_INTERVAL_NANOS) return

            val fps = ((renderedFramesInSample * NANOS_PER_SECOND + elapsedNanos / 2) /
                elapsedNanos).toInt()
            if (fps != lastPublishedFps) {
                lastPublishedFps = fps
                publishFps(fps)
            }
            fpsSampleStartNanos = frameTimeNanos
            renderedFramesInSample = 0
        }

        private fun resetFpsSampling() {
            fpsSampleStartNanos = 0L
            renderedFramesInSample = 0
            if (lastPublishedFps != 0) {
                lastPublishedFps = 0
                publishFps(0)
            }
        }

        private fun updateFrameScheduling() {
            if (canRender() && !frameScheduled) {
                frameScheduled = true
                choreographer.postFrameCallback(this)
            } else if (!canRender()) {
                stopFrameLoop()
            }
        }

        private fun stopFrameLoop() {
            if (frameScheduled) {
                choreographer.removeFrameCallback(this)
                frameScheduled = false
            }
        }

        private fun canRender() =
            !destroyed && resumed && swapChain != null && width > 0 && height > 0

        private fun readAsset(path: String): ByteBuffer {
            val bytes = assets.open(path).use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
                .put(bytes)
                .apply { flip() }
        }

        private fun loadTexture(asset: LunarTextureAsset): Texture {
            val bitmap = assets.open(asset.assetPath).use { stream ->
                BitmapFactory.decodeStream(
                    stream,
                    null,
                    BitmapFactory.Options().apply {
                        inScaled = false
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                )
            } ?: error("Unable to decode texture asset ${asset.assetPath}")
            check(bitmap.width == LUNAR_TEXTURE_WIDTH && bitmap.height == LUNAR_TEXTURE_HEIGHT) {
                "Expected ${LUNAR_TEXTURE_WIDTH}x$LUNAR_TEXTURE_HEIGHT texture ${asset.assetPath}, " +
                    "got ${bitmap.width}x${bitmap.height}"
            }

            val texture = Texture.Builder()
                .width(bitmap.width)
                .height(bitmap.height)
                .levels(mipLevelCount(bitmap.width, bitmap.height))
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(internalFormatFor(asset.colorSpace))
                .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
                .build(engine)
            try {
                TextureHelper.setBitmap(
                    engine,
                    texture,
                    0,
                    bitmap,
                    uploadHandler,
                    Runnable { bitmap.recycle() },
                )
                texture.generateMipmaps(engine)
            } catch (failure: Throwable) {
                bitmap.recycle()
                engine.destroyTexture(texture)
                throw failure
            }
            return texture
        }

        private companion object {
            const val SPHERE_RINGS = 24
            const val SPHERE_SECTORS = 48
            const val VERTEX_SIZE_BYTES = SphereMeshData.FLOATS_PER_VERTEX * Float.SIZE_BYTES
            const val MATERIAL_ASSET = "materials/sphere.filamat"
            const val LUNAR_TEXTURE_WIDTH = 2048
            const val LUNAR_TEXTURE_HEIGHT = 1024
            const val VERTICAL_FOV_DEGREES = 45.0
            const val FRAMING_MARGIN = 1.18
            const val SPHERE_RADIUS = 1.0
            const val RIPPLE_PARAMETER_COMPONENTS = 4
            const val RIPPLE_START_COMPONENT = 3
            const val INACTIVE_RIPPLE_START_SECONDS = -10f
            const val NANOS_PER_SECOND = 1_000_000_000L
            const val FPS_SAMPLE_INTERVAL_NANOS = 1_000_000_000L
            val SPHERE_CENTER = Vector3(0.0, 0.0, 0.0)

            fun createVertexBufferData(mesh: SphereMeshData): ByteBuffer =
                ByteBuffer.allocateDirect(mesh.vertices.size * Float.SIZE_BYTES)
                    .order(ByteOrder.nativeOrder())
                    .apply {
                        asFloatBuffer().put(mesh.vertices)
                        limit(capacity())
                        position(0)
                    }

            fun createIndexBufferData(mesh: SphereMeshData): ByteBuffer =
                ByteBuffer.allocateDirect(mesh.indices.size * Short.SIZE_BYTES)
                    .order(ByteOrder.nativeOrder())
                    .apply {
                        asShortBuffer().put(mesh.indices)
                        limit(capacity())
                        position(0)
                    }
        }
    }
}

internal fun mipLevelCount(width: Int, height: Int): Int {
    require(width > 0 && height > 0)
    return Int.SIZE_BITS - Integer.numberOfLeadingZeros(maxOf(width, height))
}

internal fun internalFormatFor(colorSpace: TextureColorSpace): Texture.InternalFormat =
    when (colorSpace) {
        TextureColorSpace.SRGB -> Texture.InternalFormat.SRGB8_A8
        TextureColorSpace.LINEAR -> Texture.InternalFormat.RGBA8
    }

internal fun rippleSecondsSinceEpoch(timeNanos: Long, epochNanos: Long): Float =
    ((timeNanos - epochNanos).coerceAtLeast(0L) / 1_000_000_000.0).toFloat()

internal fun rippleEpochForRestore(
    nowNanos: Long,
    earliestActiveStartTimeNanos: Long?,
): Long = minOf(nowNanos, earliestActiveStartTimeNanos ?: nowNanos)
