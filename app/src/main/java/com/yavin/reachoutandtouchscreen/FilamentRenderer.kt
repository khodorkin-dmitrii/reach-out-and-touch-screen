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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

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
    initialIdleRotationEnabled: Boolean,
    private val onFpsChanged: (Int) -> Unit,
) {
    private val renderThread = HandlerThread("FilamentRenderer").apply { start() }
    private val handler = Handler(renderThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var state: RenderState
    @Volatile
    private var acceptingCalls = true

    init {
        handler.post {
            state = RenderState(
                assets = assets,
                initialRippleSnapshot = initialRippleSnapshot,
                initialIdleRotationEnabled = initialIdleRotationEnabled,
                publishFps = ::publishFps,
            )
        }
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

    fun onPointerDown(
        touch: TouchInput,
        controlsRotation: Boolean,
        createsRipple: Boolean,
        eventTimeNanos: Long,
    ) {
        post { onPointerDown(touch, controlsRotation, createsRipple, eventTimeNanos) }
    }

    fun setMoonTextureBlend(blend: Float) {
        post { setMoonTextureBlend(blend) }
    }

    fun setIdleRotationEnabled(enabled: Boolean) {
        post { setIdleRotationEnabled(enabled) }
    }

    fun onDoubleTap(touch: TouchInput) {
        post { onDoubleTap(touch) }
    }

    fun onRotationMove(touch: TouchInput, eventTimeNanos: Long) {
        post { onRotationMove(touch, eventTimeNanos) }
    }

    fun onRotationEnd(touch: TouchInput, eventTimeNanos: Long) {
        post { onRotationEnd(touch, eventTimeNanos) }
    }

    fun onRotationCancel() {
        post { onRotationCancel() }
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
        initialIdleRotationEnabled: Boolean,
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
        private var overviewCameraDistance = 0.0
        private var cameraFocusQuadrant = initialRippleSnapshot.cameraFocusQuadrant
        private var cameraVerticalFovDegrees = verticalFovFor(cameraFocusQuadrant)
        private var cameraTargetX = targetXFor(cameraFocusQuadrant)
        private var cameraTargetY = targetYFor(cameraFocusQuadrant)
        private var cameraAnimationStartNanos = 0L
        private var cameraAnimationStartFovDegrees = cameraVerticalFovDegrees
        private var cameraAnimationStartTargetX = cameraTargetX
        private var cameraAnimationStartTargetY = cameraTargetY
        private var cameraAnimationEndFovDegrees = cameraVerticalFovDegrees
        private var cameraAnimationEndTargetX = cameraTargetX
        private var cameraAnimationEndTargetY = cameraTargetY
        private val sphereTransform = FloatArray(16)
        private val rotationAxisFrame = SphereRotationConfiguration.axisFrame
        private var sphereAngleRadians = initialRippleSnapshot.sphereAngleRadians
            .takeIf { it.isFinite() }
            ?.let(::wrapRadians)
            ?: 0.0
        private var angularVelocityRadiansPerSecond = 0.0
        private var idleRotationEnabled = initialIdleRotationEnabled
        private var lastSphereInteractionNanos = initialRippleTimeNanos
        private var lastRotationFrameTimeNanos = 0L
        private var rotationDragActive = false
        private var grabbedLocalLongitudeRadians = 0.0
        private var lastControllerX = 0.0
        private var usingRotationFallback = false
        private val angularVelocityTracker = RecentAngularVelocityTracker(
            windowNanos = ROTATION_VELOCITY_WINDOW_NANOS,
        )
        private var rippleParametersNeedUpload = false
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
                0.08f,
                0.42f,
                0.95f,
                1f,
            )
            materialInstance.setParameter("roughness", 0.28f)
            materialInstance.setParameter("metallic", 0.05f)
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
            materialInstance.setParameter("moonTextureBlend", 1f)
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
            applySphereTransform()

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
            overviewCameraDistance = CameraFraming.distanceForSphere(
                radius = 1.0,
                verticalFovDegrees = VERTICAL_FOV_DEGREES,
                aspectRatio = aspectRatio,
                margin = FRAMING_MARGIN,
            )
            applyCameraPose()
            updateFrameScheduling()
        }

        fun detachSurface(detachedSurface: Surface) {
            if (destroyed || surface !== detachedSurface) return
            stopFrameLoop()
            destroySwapChain()
            surface = null
            width = 0
            height = 0
            lastRotationFrameTimeNanos = 0L
        }

        fun setResumed(resumed: Boolean) {
            if (destroyed) return
            if (this.resumed == resumed) return
            this.resumed = resumed
            lastRotationFrameTimeNanos = 0L
            if (resumed) markSphereInteraction()
            resetFpsSampling()
            updateFrameScheduling()
        }

        fun onPointerDown(
            touch: TouchInput,
            controlsRotation: Boolean,
            createsRipple: Boolean,
            eventTimeNanos: Long,
        ) {
            if (!canRender()) return
            val localHit = localSphereHit(touch) ?: return
            markSphereInteraction()
            if (controlsRotation) {
                angularVelocityRadiansPerSecond = 0.0
                lastRotationFrameTimeNanos = 0L
                rotationDragActive = false
            }
            if (createsRipple) {
                val rippleDirection = localHit.normalized() ?: return
                val nowNanos = System.nanoTime()
                rippleStore.add(rippleDirection, nowNanos)
                uploadRippleParameters(nowNanos)
                updateRippleClock(nowNanos)
                rippleClockNeedsUpdate = true
            }

            if (controlsRotation) {
                rotationDragActive = true
                grabbedLocalLongitudeRadians = longitudeRadians(localHit, rotationAxisFrame)
                lastControllerX = touch.x
                usingRotationFallback = false
                angularVelocityTracker.reset(sphereAngleRadians, eventTimeNanos)
            }
        }

        fun setMoonTextureBlend(blend: Float) {
            if (destroyed) return
            materialInstance.setParameter("moonTextureBlend", blend.coerceIn(0f, 1f))
        }

        fun setIdleRotationEnabled(enabled: Boolean) {
            if (destroyed || idleRotationEnabled == enabled) return
            idleRotationEnabled = enabled
            markSphereInteraction()
            lastRotationFrameTimeNanos = 0L
        }

        fun onDoubleTap(touch: TouchInput) {
            if (!canRender() || localSphereHit(touch) != null) return
            val tappedQuadrant = cameraFocusQuadrantFor(
                x = touch.x,
                y = touch.y,
                viewportWidth = width,
                viewportHeight = height,
            )
            cameraFocusQuadrant = nextCameraFocus(cameraFocusQuadrant, tappedQuadrant)
            cameraAnimationStartFovDegrees = cameraVerticalFovDegrees
            cameraAnimationStartTargetX = cameraTargetX
            cameraAnimationStartTargetY = cameraTargetY
            cameraAnimationEndFovDegrees = verticalFovFor(cameraFocusQuadrant)
            cameraAnimationEndTargetX = targetXFor(cameraFocusQuadrant)
            cameraAnimationEndTargetY = targetYFor(cameraFocusQuadrant)
            cameraAnimationStartNanos = System.nanoTime()
        }

        fun onRotationMove(touch: TouchInput, eventTimeNanos: Long) {
            if (!rotationDragActive || !canRender()) return
            markSphereInteraction()
            updateRotationDrag(touch, eventTimeNanos)
        }

        fun onRotationEnd(touch: TouchInput, eventTimeNanos: Long) {
            if (!rotationDragActive) return
            markSphereInteraction()
            if (canRender()) updateRotationDrag(touch, eventTimeNanos)
            angularVelocityRadiansPerSecond = angularVelocityTracker
                .velocityRadiansPerSecond(eventTimeNanos)
                .coerceIn(-MAX_ANGULAR_VELOCITY, MAX_ANGULAR_VELOCITY)
                .takeUnless { abs(it) < STOP_ANGULAR_VELOCITY } ?: 0.0
            rotationDragActive = false
            lastRotationFrameTimeNanos = 0L
        }

        fun onRotationCancel() {
            if (rotationDragActive) markSphereInteraction()
            rotationDragActive = false
            angularVelocityRadiansPerSecond = 0.0
            lastRotationFrameTimeNanos = 0L
        }

        private fun localSphereHit(touch: TouchInput): Vector3? {
            camera.getProjectionMatrix(projectionMatrix)
            camera.getViewMatrix(viewMatrix)
            val worldRay = ScreenRay.create(
                touch = touch,
                viewportWidth = width,
                viewportHeight = height,
                projectionMatrix = projectionMatrix,
                viewMatrix = viewMatrix,
            ) ?: return null
            val localRay = inverseRotateRayAroundAxis(
                ray = worldRay,
                unitAxis = rotationAxisFrame.axis,
                angleRadians = sphereAngleRadians,
            )
            return RaySphereIntersection.nearestHit(
                ray = localRay,
                sphereCenter = SPHERE_CENTER,
                sphereRadius = SPHERE_RADIUS,
            )
        }

        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            val currentSwapChain = swapChain
            if (!canRender() || currentSwapChain == null) return

            updateCamera(frameTimeNanos)
            updateRotation(frameTimeNanos)
            updateRipples(frameTimeNanos)
            if (renderer.beginFrame(currentSwapChain, frameTimeNanos)) {
                renderer.render(view)
                renderer.endFrame()
                recordRenderedFrame(frameTimeNanos)
            }
            updateFrameScheduling()
        }

        fun captureRippleSnapshot(nowNanos: Long): RippleSnapshot {
            val rippleSnapshot = rippleStore.snapshot(nowNanos)
            return RippleSnapshot(
                entries = rippleSnapshot.entries,
                sphereAngleRadians = sphereAngleRadians,
                cameraFocusQuadrant = cameraFocusQuadrant,
            )
        }

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

        private fun updateRotationDrag(touch: TouchInput, eventTimeNanos: Long) {
            val localHit = localSphereHit(touch)
            if (localHit != null) {
                val pointerLocalLongitude = longitudeRadians(localHit, rotationAxisFrame)
                if (usingRotationFallback) {
                    // Rebase at the re-entry point so switching mappings cannot introduce a jump.
                    grabbedLocalLongitudeRadians = pointerLocalLongitude
                    usingRotationFallback = false
                } else {
                    setSphereAngle(
                        anchoredAxisRotation(
                            currentAngleRadians = sphereAngleRadians,
                            grabbedLocalLongitudeRadians = grabbedLocalLongitudeRadians,
                            pointerLocalLongitudeRadians = pointerLocalLongitude,
                        ),
                    )
                }
            } else {
                val projectedRadius = minOf(
                    touch.touchAreaWidth,
                    touch.touchAreaHeight,
                ) / (2.0 * FRAMING_MARGIN) * cameraZoomScale() * rotationAxisFrame.axis.y
                if (projectedRadius > 0.0) {
                    setSphereAngle(
                        sphereAngleRadians + (touch.x - lastControllerX) / projectedRadius,
                    )
                }
                usingRotationFallback = true
            }
            lastControllerX = touch.x
            angularVelocityTracker.add(sphereAngleRadians, eventTimeNanos)
        }

        private fun updateCamera(frameTimeNanos: Long) {
            if (cameraAnimationStartNanos == 0L) return
            val progress = (
                (frameTimeNanos - cameraAnimationStartNanos).coerceAtLeast(0L).toDouble() /
                    CAMERA_FOCUS_ANIMATION_NANOS.toDouble()
                ).coerceAtMost(1.0)
            val easedProgress = progress * progress * (3.0 - 2.0 * progress)
            cameraVerticalFovDegrees = lerp(
                cameraAnimationStartFovDegrees,
                cameraAnimationEndFovDegrees,
                easedProgress,
            )
            cameraTargetX = lerp(
                cameraAnimationStartTargetX,
                cameraAnimationEndTargetX,
                easedProgress,
            )
            cameraTargetY = lerp(
                cameraAnimationStartTargetY,
                cameraAnimationEndTargetY,
                easedProgress,
            )
            applyCameraPose()
            if (progress >= 1.0) cameraAnimationStartNanos = 0L
        }

        private fun applyCameraPose() {
            if (width <= 0 || height <= 0 || overviewCameraDistance <= 0.0) return
            camera.setProjection(
                cameraVerticalFovDegrees,
                width.toDouble() / height.toDouble(),
                0.1,
                100.0,
                Camera.Fov.VERTICAL,
            )
            camera.lookAt(
                0.0,
                0.1,
                overviewCameraDistance,
                cameraTargetX,
                cameraTargetY,
                0.0,
                0.0,
                1.0,
                0.0,
            )
        }

        private fun cameraZoomScale(): Double {
            val overviewHalfFov = VERTICAL_FOV_DEGREES * PI / 360.0
            val currentHalfFov = cameraVerticalFovDegrees * PI / 360.0
            return tan(overviewHalfFov) / tan(currentHalfFov)
        }

        private fun updateRotation(frameTimeNanos: Long) {
            if (rotationDragActive) {
                lastRotationFrameTimeNanos = 0L
                return
            }
            val usesInertia = angularVelocityRadiansPerSecond != 0.0
            val usesIdleRotation = shouldUseIdleRotation(
                enabled = idleRotationEnabled,
                lastInteractionNanos = lastSphereInteractionNanos,
                nowNanos = frameTimeNanos,
                delayNanos = IDLE_ROTATION_DELAY_NANOS,
            )
            if (!usesInertia && !usesIdleRotation) {
                lastRotationFrameTimeNanos = 0L
                return
            }
            if (lastRotationFrameTimeNanos == 0L) {
                lastRotationFrameTimeNanos = frameTimeNanos
                return
            }
            val deltaTimeSeconds = (
                (frameTimeNanos - lastRotationFrameTimeNanos).coerceAtLeast(0L) /
                    NANOS_PER_SECOND.toDouble()
                ).coerceAtMost(MAX_ROTATION_DELTA_TIME_SECONDS)
            lastRotationFrameTimeNanos = frameTimeNanos
            if (deltaTimeSeconds == 0.0) return

            setSphereAngle(
                sphereAngleRadians + if (usesInertia) {
                    angularVelocityRadiansPerSecond * deltaTimeSeconds
                } else {
                    IDLE_ROTATION_SPEED_RADIANS_PER_SECOND * deltaTimeSeconds
                },
            )
            if (!usesInertia) return
            angularVelocityRadiansPerSecond = decayedAngularVelocity(
                angularVelocityRadiansPerSecond,
                ROTATION_FRICTION_PER_SECOND,
                deltaTimeSeconds,
            )
            if (abs(angularVelocityRadiansPerSecond) < STOP_ANGULAR_VELOCITY) {
                angularVelocityRadiansPerSecond = 0.0
                lastRotationFrameTimeNanos = 0L
            }
        }

        private fun markSphereInteraction() {
            lastSphereInteractionNanos = System.nanoTime()
        }

        private fun setSphereAngle(angleRadians: Double) {
            val wrappedAngle = wrapRadians(angleRadians)
            if (wrappedAngle == sphereAngleRadians) return
            sphereAngleRadians = wrappedAngle
            applySphereTransform()
            rippleParametersNeedUpload = true
        }

        private fun applySphereTransform() {
            val cosine = cos(sphereAngleRadians).toFloat()
            val sine = sin(sphereAngleRadians).toFloat()
            val oneMinusCosine = 1f - cosine
            val axisX = rotationAxisFrame.axis.x.toFloat()
            val axisY = rotationAxisFrame.axis.y.toFloat()
            val axisZ = rotationAxisFrame.axis.z.toFloat()
            sphereTransform.fill(0f)
            sphereTransform[0] = cosine + axisX * axisX * oneMinusCosine
            sphereTransform[1] = axisY * axisX * oneMinusCosine + axisZ * sine
            sphereTransform[2] = axisZ * axisX * oneMinusCosine - axisY * sine
            sphereTransform[4] = axisX * axisY * oneMinusCosine - axisZ * sine
            sphereTransform[5] = cosine + axisY * axisY * oneMinusCosine
            sphereTransform[6] = axisZ * axisY * oneMinusCosine + axisX * sine
            sphereTransform[8] = axisX * axisZ * oneMinusCosine + axisY * sine
            sphereTransform[9] = axisY * axisZ * oneMinusCosine - axisX * sine
            sphereTransform[10] = cosine + axisZ * axisZ * oneMinusCosine
            sphereTransform[15] = 1f
            val transformManager = engine.transformManager
            transformManager.setTransform(
                transformManager.getInstance(sphereEntity),
                sphereTransform,
            )
        }

        private fun uploadRippleParameters(nowNanos: Long) {
            val cosine = cos(sphereAngleRadians).toFloat()
            val sine = sin(sphereAngleRadians).toFloat()
            val oneMinusCosine = 1f - cosine
            val axisX = rotationAxisFrame.axis.x.toFloat()
            val axisY = rotationAxisFrame.axis.y.toFloat()
            val axisZ = rotationAxisFrame.axis.z.toFloat()
            for (slot in 0 until MAX_ACTIVE_RIPPLES) {
                val parameterOffset = slot * RIPPLE_PARAMETER_COMPONENTS
                if (rippleStore.isActive(slot, nowNanos)) {
                    val localX = rippleStore.originX(slot)
                    val localY = rippleStore.originY(slot)
                    val localZ = rippleStore.originZ(slot)
                    val axisDotOrigin = axisX * localX + axisY * localY + axisZ * localZ
                    rippleParameters[parameterOffset] = cosine * localX +
                        sine * (axisY * localZ - axisZ * localY) +
                        oneMinusCosine * axisX * axisDotOrigin
                    rippleParameters[parameterOffset + 1] = cosine * localY +
                        sine * (axisZ * localX - axisX * localZ) +
                        oneMinusCosine * axisY * axisDotOrigin
                    rippleParameters[parameterOffset + 2] = cosine * localZ +
                        sine * (axisX * localY - axisY * localX) +
                        oneMinusCosine * axisZ * axisDotOrigin
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
            if (rippleParametersNeedUpload) {
                uploadRippleParameters(frameTimeNanos)
                rippleParametersNeedUpload = false
            }
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
            const val FOCUSED_VERTICAL_FOV_DEGREES = 22.0
            const val CAMERA_FOCUS_TARGET_OFFSET = 0.48
            const val CAMERA_FOCUS_ANIMATION_NANOS = 450_000_000L
            const val FRAMING_MARGIN = 1.18
            const val SPHERE_RADIUS = 1.0
            const val RIPPLE_PARAMETER_COMPONENTS = 4
            const val RIPPLE_START_COMPONENT = 3
            const val INACTIVE_RIPPLE_START_SECONDS = -10f
            const val NANOS_PER_SECOND = 1_000_000_000L
            const val FPS_SAMPLE_INTERVAL_NANOS = 1_000_000_000L
            const val ROTATION_VELOCITY_WINDOW_NANOS = 120_000_000L
            const val ROTATION_FRICTION_PER_SECOND = 4.5
            const val IDLE_ROTATION_SPEED_RADIANS_PER_SECOND = 0.15
            const val MAX_ANGULAR_VELOCITY = 8.0
            const val STOP_ANGULAR_VELOCITY = 0.025
            const val MAX_ROTATION_DELTA_TIME_SECONDS = 0.05
            val SPHERE_CENTER = Vector3(0.0, 0.0, 0.0)

            fun verticalFovFor(quadrant: CameraFocusQuadrant?) =
                if (quadrant == null) VERTICAL_FOV_DEGREES else FOCUSED_VERTICAL_FOV_DEGREES

            fun targetXFor(quadrant: CameraFocusQuadrant?) =
                quadrant?.horizontalSign?.times(CAMERA_FOCUS_TARGET_OFFSET) ?: 0.0

            fun targetYFor(quadrant: CameraFocusQuadrant?) =
                quadrant?.verticalSign?.times(CAMERA_FOCUS_TARGET_OFFSET) ?: 0.0

            fun lerp(start: Double, end: Double, progress: Double) =
                start + (end - start) * progress

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

internal const val IDLE_ROTATION_DELAY_NANOS = 10_000_000_000L

internal fun shouldUseIdleRotation(
    enabled: Boolean,
    lastInteractionNanos: Long,
    nowNanos: Long,
    delayNanos: Long,
): Boolean = enabled &&
    delayNanos >= 0L &&
    nowNanos - lastInteractionNanos >= delayNanos

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
