package com.yavin.reachoutandtouchscreen

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
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
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.TextureHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

private data class LightArcballGesture(
    val arcball: ArcballGesture,
    val startSourceDirection: Vector3,
)

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
    initialRotateLightEnabled: Boolean,
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
                initialRotateLightEnabled = initialRotateLightEnabled,
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
        createsTouchReaction: Boolean,
        eventTimeNanos: Long,
    ) {
        post { onPointerDown(touch, controlsRotation, createsTouchReaction, eventTimeNanos) }
    }

    fun setMoonTextureBlend(blend: Float) {
        post { setMoonTextureBlend(blend) }
    }

    fun setIdleRotationEnabled(enabled: Boolean) {
        post { setIdleRotationEnabled(enabled) }
    }

    fun setRotateLightEnabled(enabled: Boolean) {
        post { setRotateLightEnabled(enabled) }
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
        initialRotateLightEnabled: Boolean,
        private val publishFps: (Int) -> Unit,
    ) : Choreographer.FrameCallback {
        private val engine = Engine.create()
        private val renderer = engine.createRenderer()
        private val scene = engine.createScene()
        private val skybox = Skybox.Builder()
            .color(BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE, BACKGROUND_ALPHA)
            .showSun(true)
            .build(engine)
        private val view = engine.createView()
        private val entityManager = EntityManager.get()
        private val cameraEntity = entityManager.create()
        private val camera = engine.createCamera(cameraEntity)
        private val sphereEntity = entityManager.create()
        private val lightEntity = entityManager.create()
        private val meshDensity = ACTIVE_SPHERE_MESH_DENSITY
        private val meshData = SphereMesh.create(
            radius = 1f,
            rings = meshDensity.rings,
            sectors = meshDensity.sectors,
        )
        private val dentState = SphereDentState.fromMesh(
            mesh = meshData,
            restoredAccumulatedCompression = initialRippleSnapshot.copyAccumulatedDentCompression(),
        )
        private val uploadHandler = Handler(checkNotNull(Looper.myLooper()))
        private val displacedPositions = FloatArray(meshData.vertexCount * POSITION_COMPONENTS)
        private val geometricNormals = FloatArray(meshData.vertexCount * POSITION_COMPONENTS)
        private val tangentQuaternions = FloatArray(meshData.vertexCount * TANGENT_COMPONENTS)
        private val dynamicUploadSlots = Array(DYNAMIC_UPLOAD_SLOT_COUNT) {
            DynamicUploadSlot(meshData.vertexCount)
        }
        private val dentRebuildState = DentRebuildState(DYNAMIC_UPLOAD_SLOT_COUNT)
        private val dentRebuildRunnable = Runnable { runScheduledDentRebuild() }
        private val uvVertexData = createUvVertexBufferData(meshData)
        private val indexData = createIndexBufferData(meshData)
        private val vertexBuffer = createVertexBuffer()
        private val indexBuffer = createIndexBuffer()
        private val materialPackage = readAsset(MATERIAL_ASSET)
        private val material = Material.Builder()
            .payload(materialPackage, materialPackage.remaining())
            .build(engine)
        private val materialInstance = material.createInstance()
        private val choreographer = Choreographer.getInstance()
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
        private var cameraProjection: CameraFraming.ProjectionPolicy? = null
        private var cameraFocusQuadrant = initialRippleSnapshot.cameraFocusQuadrant
        private var cameraFovDegrees = fovFor(cameraFocusQuadrant)
        private var cameraTargetX = targetXFor(cameraFocusQuadrant)
        private var cameraTargetY = targetYFor(cameraFocusQuadrant)
        private var cameraAnimationStartNanos = 0L
        private var cameraAnimationStartFovDegrees = cameraFovDegrees
        private var cameraAnimationStartTargetX = cameraTargetX
        private var cameraAnimationStartTargetY = cameraTargetY
        private var cameraAnimationEndFovDegrees = cameraFovDegrees
        private var cameraAnimationEndTargetX = cameraTargetX
        private var cameraAnimationEndTargetY = cameraTargetY
        private val sphereTransform = FloatArray(16)
        private var sphereOrientation = initialRippleSnapshot.sphereOrientation
            .normalizedOrIdentity()
        private var idleRotationEnabled = initialIdleRotationEnabled
        private var rotateLightEnabled = initialRotateLightEnabled
        private var lightSourceDirection = normalizedLightSourceDirection(
            initialRippleSnapshot.lightSourceDirection,
        )
        private var lastSphereInteractionNanos = initialRippleTimeNanos
        private var lastRotationFrameTimeNanos = 0L
        private var arcballGesture: ArcballGesture? = null
        private var lightArcballGesture: LightArcballGesture? = null
        private var arcballPointerInside = false
        private val orientationVelocityTracker = RecentOrientationVelocityTracker(
            capacity = ORIENTATION_SAMPLE_CAPACITY,
            windowNanos = ORIENTATION_SAMPLE_WINDOW_NANOS,
            staleReleaseNanos = STALE_RELEASE_NANOS,
            minimumIntervalNanos = MINIMUM_SAMPLE_INTERVAL_NANOS,
            minimumSpeedRadiansPerSecond = MINIMUM_INERTIA_LAUNCH_SPEED,
            maximumSpeedRadiansPerSecond = MAXIMUM_INERTIA_LAUNCH_SPEED,
        )
        private var inertiaWorldAxis = Vector3(0.0, 1.0, 0.0)
        private var inertiaSpeedRadiansPerSecond = 0.0
        private var rippleParametersNeedUpload = false
        private var fpsSampleStartNanos = 0L
        private var renderedFramesInSample = 0
        private var lastPublishedFps = 0
        private var pendingLogicalDentNanos = 0L

        init {
            renderer.clearOptions = Renderer.ClearOptions().apply {
                clear = true
                clearColor = doubleArrayOf(
                    BACKGROUND_RED.toDouble(),
                    BACKGROUND_GREEN.toDouble(),
                    BACKGROUND_BLUE.toDouble(),
                    BACKGROUND_ALPHA.toDouble(),
                )
            }

            scene.skybox = skybox
            view.scene = scene
            view.camera = camera
            view.dithering = View.Dithering.TEMPORAL
            view.bloomOptions = View.BloomOptions().apply {
                enabled = true
                strength = BLOOM_STRENGTH
                lensFlare = true
                starburst = true
            }
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
            setMaterialLightDirection()
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

            rebuildDynamicGeometry()
            val initialSlotIndex = dentRebuildState.reserveInitialUploadSlot()
            dynamicUploadSlots[initialSlotIndex].write(displacedPositions, tangentQuaternions)
            dentRebuildState.markInitialUploadSubmitted(initialSlotIndex)
            submitDynamicUpload(initialSlotIndex)

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

            val filamentLightDirection = filamentDirectionForSource(lightSourceDirection)
            LightManager.Builder(LightManager.Type.SUN)
                .color(1f, 0.94f, 0.86f)
                .intensity(95_000f)
                .direction(
                    filamentLightDirection.x.toFloat(),
                    filamentLightDirection.y.toFloat(),
                    filamentLightDirection.z.toFloat(),
                )
                .sunAngularRadius(SUN_ANGULAR_RADIUS_DEGREES)
                .sunHaloSize(SUN_HALO_SIZE)
                .sunHaloFalloff(SUN_HALO_FALLOFF)
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
            val projection = CameraFraming.projectionForViewport(width, height) ?: return
            this.width = width
            this.height = height
            cameraProjection = projection
            view.viewport = Viewport(0, 0, width, height)
            overviewCameraDistance = CameraFraming.distanceForSphere(
                radius = 1.0,
                shortSideFovDegrees = OVERVIEW_SHORT_SIDE_FOV_DEGREES,
                projection = projection,
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
            createsTouchReaction: Boolean,
            eventTimeNanos: Long,
        ) {
            val logicalDentStartNanos = if (DENT_REBUILD_TIMING_ENABLED) System.nanoTime() else 0L
            if (!canRender()) return
            val localHit = localSphereHit(touch) ?: return
            val hitDirection = localHit.normalized() ?: return
            if (rotateLightEnabled) {
                if (controlsRotation) {
                    lightArcballGesture = createArcballGesture(
                        touch = touch,
                        startOrientation = Quaternion.Identity,
                    )?.let { arcball ->
                        LightArcballGesture(
                            arcball = arcball,
                            startSourceDirection = lightSourceDirection,
                        )
                    }
                }
                return
            }
            val successfulHitNanos = timingSince(logicalDentStartNanos)
            val nowNanos = System.nanoTime()
            val pointerDownTimeNanos = eventTimeNanos.takeIf { it in 1..nowNanos } ?: nowNanos
            markSphereInteraction()
            if (controlsRotation) {
                inertiaSpeedRadiansPerSecond = 0.0
                lastRotationFrameTimeNanos = 0L
                arcballGesture = createArcballGesture(touch, sphereOrientation)
                arcballPointerInside = arcballGesture?.projection?.contains(touch.x, touch.y) == true
                orientationVelocityTracker.clear()
                if (arcballGesture != null) {
                    orientationVelocityTracker.reset(sphereOrientation, eventTimeNanos)
                }
            }
            if (!createsTouchReaction) return

            rippleStore.add(hitDirection, pointerDownTimeNanos)
            uploadRippleParameters(nowNanos)
            updateRippleClock(nowNanos)
            rippleClockNeedsUpdate = true

            val dentAccumulationStartNanos = timingNow()
            dentState.applyDent(
                hitX = hitDirection.x.toFloat(),
                hitY = hitDirection.y.toFloat(),
                hitZ = hitDirection.z.toFloat(),
            )
            if (DENT_REBUILD_TIMING_ENABLED) {
                pendingLogicalDentNanos +=
                    successfulHitNanos + timingSince(dentAccumulationStartNanos)
            }
            if (dentRebuildState.onDentUpdated()) {
                uploadHandler.post(dentRebuildRunnable)
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

        fun setRotateLightEnabled(enabled: Boolean) {
            if (destroyed || rotateLightEnabled == enabled) return
            rotateLightEnabled = enabled
            lightArcballGesture = null
            if (!enabled) return

            val interruptedMoonMotion =
                arcballGesture != null || inertiaSpeedRadiansPerSecond != 0.0
            arcballGesture = null
            arcballPointerInside = false
            orientationVelocityTracker.clear()
            inertiaSpeedRadiansPerSecond = 0.0
            if (interruptedMoonMotion) lastRotationFrameTimeNanos = 0L
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
            cameraAnimationStartFovDegrees = cameraFovDegrees
            cameraAnimationStartTargetX = cameraTargetX
            cameraAnimationStartTargetY = cameraTargetY
            cameraAnimationEndFovDegrees = fovFor(cameraFocusQuadrant)
            cameraAnimationEndTargetX = targetXFor(cameraFocusQuadrant)
            cameraAnimationEndTargetY = targetYFor(cameraFocusQuadrant)
            cameraAnimationStartNanos = System.nanoTime()
        }

        fun onRotationMove(touch: TouchInput, eventTimeNanos: Long) {
            if (lightArcballGesture != null) {
                if (canRender()) updateLightDrag(touch)
                return
            }
            val gesture = arcballGesture ?: return
            if (!canRender()) return
            markSphereInteraction()
            val wasInside = arcballPointerInside
            val isInside = gesture.projection.contains(touch.x, touch.y)
            updateRotationDrag(touch, eventTimeNanos)
            arcballPointerInside = isInside
            if (wasInside && !isInside) finishArcballGesture(eventTimeNanos)
        }

        fun onRotationEnd(touch: TouchInput, eventTimeNanos: Long) {
            if (lightArcballGesture != null) {
                if (canRender()) updateLightDrag(touch)
                lightArcballGesture = null
                return
            }
            if (arcballGesture == null) return
            markSphereInteraction()
            if (canRender()) updateRotationDrag(touch, eventTimeNanos)
            finishArcballGesture(eventTimeNanos)
        }

        fun onRotationCancel() {
            if (lightArcballGesture != null) {
                lightArcballGesture = null
                return
            }
            if (arcballGesture == null) return
            markSphereInteraction()
            arcballGesture = null
            arcballPointerInside = false
            orientationVelocityTracker.clear()
            inertiaSpeedRadiansPerSecond = 0.0
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
            val localRay = inverseRotateRay(worldRay, sphereOrientation)
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
                sphereOrientation = sphereOrientation,
                lightSourceDirection = lightSourceDirection,
                cameraFocusQuadrant = cameraFocusQuadrant,
                accumulatedDentCompression = dentState.snapshotAccumulatedCompression(),
            )
        }

        fun destroy() {
            if (destroyed) return
            destroyed = true
            stopFrameLoop()
            uploadHandler.removeCallbacks(dentRebuildRunnable)
            dentRebuildState.cancelPendingTask()
            destroySwapChain()
            surface = null
            engine.flushAndWait()

            scene.removeEntity(sphereEntity)
            scene.removeEntity(lightEntity)
            scene.skybox = null
            engine.destroyEntity(sphereEntity)
            engine.destroyEntity(lightEntity)
            engine.destroySkybox(skybox)
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
            .bufferCount(3)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION,
                0,
                VertexBuffer.AttributeType.FLOAT3,
                0,
                POSITION_VERTEX_SIZE_BYTES,
            )
            .attribute(
                VertexBuffer.VertexAttribute.TANGENTS,
                TANGENT_BUFFER_INDEX,
                VertexBuffer.AttributeType.FLOAT4,
                0,
                TANGENT_VERTEX_SIZE_BYTES,
            )
            .attribute(
                VertexBuffer.VertexAttribute.UV0,
                UV_BUFFER_INDEX,
                VertexBuffer.AttributeType.FLOAT2,
                0,
                UV_VERTEX_SIZE_BYTES,
            )
            .build(engine)
            .also {
                it.setBufferAt(engine, UV_BUFFER_INDEX, uvVertexData)
            }

        private fun runScheduledDentRebuild() {
            if (destroyed) return
            val request = dentRebuildState.beginPendingRebuild() ?: return
            val totalStartNanos = timingNow()
            val logicalDentNanos = pendingLogicalDentNanos
            pendingLogicalDentNanos = 0L

            val positionsStartNanos = timingNow()
            dentState.writeDisplacedPositions(displacedPositions)
            val positionsNanos = timingSince(positionsStartNanos)

            val tangentFramesStartNanos = timingNow()
            SphereTangentFrames.generate(
                positions = displacedPositions,
                indices = meshData.indices,
                rings = meshDensity.rings,
                sectors = meshDensity.sectors,
                normals = geometricNormals,
                tangentQuaternions = tangentQuaternions,
            )
            val tangentFramesNanos = timingSince(tangentFramesStartNanos)

            val copyStartNanos = timingNow()
            dynamicUploadSlots[request.slotIndex].write(displacedPositions, tangentQuaternions)
            val copyNanos = timingSince(copyStartNanos)

            val needsFollowUp = dentRebuildState.markSubmitted(request)
            val submissionStartNanos = timingNow()
            submitDynamicUpload(request.slotIndex)
            val submissionNanos = timingSince(submissionStartNanos)
            val totalNanos = timingSince(totalStartNanos)

            if (DENT_REBUILD_TIMING_ENABLED) {
                Log.d(
                    TAG,
                    "dent authoritative=${dentRebuildState.authoritativeVersion} " +
                        "rebuilt=${request.version} " +
                        "submitted=${dentRebuildState.submittedVersion} " +
                        "coalesced=${request.coalescedDentUpdates} " +
                        "waitedForSlot=${request.waitedForFreeSlot} " +
                        "inFlight=${dentRebuildState.inFlightSlotCount()} " +
                        "hitAndLogicalMs=${logicalDentNanos.toMilliseconds()} " +
                        "positionsMs=${positionsNanos.toMilliseconds()} " +
                        "tangentFramesMs=${tangentFramesNanos.toMilliseconds()} " +
                        "copyMs=${copyNanos.toMilliseconds()} " +
                        "setBufferAtCpuMs=${submissionNanos.toMilliseconds()} " +
                        "totalMs=${totalNanos.toMilliseconds()} gpuAsync=not-measured",
                )
            }
            if (needsFollowUp) uploadHandler.post(dentRebuildRunnable)
        }

        private fun rebuildDynamicGeometry() {
            dentState.writeDisplacedPositions(displacedPositions)
            SphereTangentFrames.generate(
                positions = displacedPositions,
                indices = meshData.indices,
                rings = meshDensity.rings,
                sectors = meshDensity.sectors,
                normals = geometricNormals,
                tangentQuaternions = tangentQuaternions,
            )
        }

        private fun submitDynamicUpload(slotIndex: Int) {
            val slot = dynamicUploadSlots[slotIndex]
            submitDynamicUploadPart(POSITION_BUFFER_INDEX, slot.positions, slotIndex)
            submitDynamicUploadPart(TANGENT_BUFFER_INDEX, slot.tangents, slotIndex)
        }

        private fun submitDynamicUploadPart(
            bufferIndex: Int,
            upload: ByteBuffer,
            slotIndex: Int,
        ) {
            vertexBuffer.setBufferAt(
                engine,
                bufferIndex,
                upload,
                0,
                upload.remaining(),
                uploadHandler,
                Runnable { onDynamicUploadPartCompleted(slotIndex) },
            )
        }

        private fun onDynamicUploadPartCompleted(slotIndex: Int) {
            if (destroyed) return
            if (dentRebuildState.onUploadPartCompleted(slotIndex)) {
                uploadHandler.post(dentRebuildRunnable)
            }
        }

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

        private fun createArcballGesture(
            touch: TouchInput,
            startOrientation: Quaternion,
        ): ArcballGesture? {
            camera.getProjectionMatrix(projectionMatrix)
            camera.getViewMatrix(viewMatrix)
            val cameraBasis = CameraBasis.fromViewMatrix(viewMatrix) ?: return null
            val arcballProjection = arcballProjectionForSphere(
                sphereCenter = SPHERE_CENTER,
                sphereRadius = SPHERE_RADIUS,
                cameraBasis = cameraBasis,
                projectionMatrix = projectionMatrix,
                viewMatrix = viewMatrix,
                touchAreaWidth = touch.touchAreaWidth,
                touchAreaHeight = touch.touchAreaHeight,
            ) ?: return null
            val startVector = mapPointerToArcball(
                pointerX = touch.x,
                pointerY = touch.y,
                projection = arcballProjection,
            ) ?: return null
            // Freeze camera/projection reference data so focus animation cannot bend one drag.
            return ArcballGesture(
                startVectorView = startVector,
                startOrientation = startOrientation,
                cameraBasis = cameraBasis,
                projection = arcballProjection,
            )
        }

        private fun updateRotationDrag(touch: TouchInput, eventTimeNanos: Long) {
            val gesture = arcballGesture ?: return
            val orientation = gesture.orientationAt(touch.x, touch.y) ?: return
            if (setSphereOrientation(orientation)) {
                orientationVelocityTracker.add(orientation, eventTimeNanos)
            }
        }

        private fun updateCamera(frameTimeNanos: Long) {
            if (cameraAnimationStartNanos == 0L) return
            val progress = (
                (frameTimeNanos - cameraAnimationStartNanos).coerceAtLeast(0L).toDouble() /
                    CAMERA_FOCUS_ANIMATION_NANOS.toDouble()
                ).coerceAtMost(1.0)
            val easedProgress = progress * progress * (3.0 - 2.0 * progress)
            cameraFovDegrees = lerp(
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
            val projection = cameraProjection ?: return
            if (width <= 0 || height <= 0 || overviewCameraDistance <= 0.0) return
            camera.setProjection(
                cameraFovDegrees,
                projection.aspectRatio,
                0.1,
                100.0,
                when (projection.fovAxis) {
                    CameraFraming.FovAxis.HORIZONTAL -> Camera.Fov.HORIZONTAL
                    CameraFraming.FovAxis.VERTICAL -> Camera.Fov.VERTICAL
                },
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

        private fun updateRotation(frameTimeNanos: Long) {
            if (arcballGesture != null) {
                lastRotationFrameTimeNanos = 0L
                return
            }
            val usesInertia = inertiaSpeedRadiansPerSecond >= INERTIA_STOP_SPEED
            if (!usesInertia && inertiaSpeedRadiansPerSecond != 0.0) {
                inertiaSpeedRadiansPerSecond = 0.0
            }
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

            if (usesInertia) {
                setSphereOrientation(
                    orientationAfterWorldInertia(
                        orientation = sphereOrientation,
                        worldAxis = inertiaWorldAxis,
                        speedRadiansPerSecond = inertiaSpeedRadiansPerSecond,
                        deltaTimeSeconds = deltaTimeSeconds,
                    ),
                )
                inertiaSpeedRadiansPerSecond = decayedAngularSpeed(
                    speedRadiansPerSecond = inertiaSpeedRadiansPerSecond,
                    dampingRatePerSecond = INERTIA_DAMPING_RATE_PER_SECOND,
                    deltaTimeSeconds = deltaTimeSeconds,
                )
                if (inertiaSpeedRadiansPerSecond < INERTIA_STOP_SPEED) {
                    inertiaSpeedRadiansPerSecond = 0.0
                    lastRotationFrameTimeNanos = 0L
                }
            } else {
                setSphereOrientation(
                    sphereOrientation * Quaternion.fromAxisAngle(
                        LOCAL_Y_AXIS,
                        IDLE_ROTATION_SPEED_RADIANS_PER_SECOND * deltaTimeSeconds,
                    ),
                )
            }
        }

        private fun updateLightDrag(touch: TouchInput) {
            val gesture = lightArcballGesture ?: return
            val worldDelta = gesture.arcball.worldDeltaAt(touch.x, touch.y) ?: return
            val sourceDirection = rotatedLightSourceDirection(
                startDirection = gesture.startSourceDirection,
                worldDelta = worldDelta,
            )
            if (sourceDirection == lightSourceDirection) return
            lightSourceDirection = sourceDirection
            setMaterialLightDirection()
            val filamentDirection = filamentDirectionForSource(sourceDirection)
            val lightManager = engine.lightManager
            lightManager.setDirection(
                lightManager.getInstance(lightEntity),
                filamentDirection.x.toFloat(),
                filamentDirection.y.toFloat(),
                filamentDirection.z.toFloat(),
            )
        }

        private fun setMaterialLightDirection() {
            materialInstance.setParameter(
                LIGHT_DIRECTION_TO_SOURCE_PARAMETER,
                lightSourceDirection.x.toFloat(),
                lightSourceDirection.y.toFloat(),
                lightSourceDirection.z.toFloat(),
            )
        }

        private fun finishArcballGesture(releaseTimeNanos: Long) {
            if (arcballGesture == null) return
            val launchVelocity = orientationVelocityTracker.estimate(releaseTimeNanos)
            if (launchVelocity != null) {
                inertiaWorldAxis = launchVelocity.axis
                inertiaSpeedRadiansPerSecond = launchVelocity.speedRadiansPerSecond
            } else {
                inertiaSpeedRadiansPerSecond = 0.0
            }
            arcballGesture = null
            arcballPointerInside = false
            orientationVelocityTracker.clear()
            lastRotationFrameTimeNanos = 0L
        }

        private fun markSphereInteraction() {
            lastSphereInteractionNanos = System.nanoTime()
        }

        private fun setSphereOrientation(orientation: Quaternion): Boolean {
            val normalized = orientation.normalizedOrIdentity()
            if (normalized == sphereOrientation) return false
            sphereOrientation = normalized
            applySphereTransform()
            rippleParametersNeedUpload = true
            return true
        }

        private fun applySphereTransform() {
            sphereOrientation.writeColumnMajorRotationMatrix(sphereTransform)
            val transformManager = engine.transformManager
            transformManager.setTransform(
                transformManager.getInstance(sphereEntity),
                sphereTransform,
            )
        }

        private fun uploadRippleParameters(nowNanos: Long) {
            for (slot in 0 until MAX_ACTIVE_RIPPLES) {
                val parameterOffset = slot * RIPPLE_PARAMETER_COMPONENTS
                if (rippleStore.isActive(slot, nowNanos)) {
                    val localX = rippleStore.originX(slot)
                    val localY = rippleStore.originY(slot)
                    val localZ = rippleStore.originZ(slot)
                    rippleParameters[parameterOffset] =
                        sphereTransform[0] * localX + sphereTransform[4] * localY +
                            sphereTransform[8] * localZ
                    rippleParameters[parameterOffset + 1] =
                        sphereTransform[1] * localX + sphereTransform[5] * localY +
                            sphereTransform[9] * localZ
                    rippleParameters[parameterOffset + 2] =
                        sphereTransform[2] * localX + sphereTransform[6] * localY +
                            sphereTransform[10] * localZ
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

        private fun timingNow(): Long =
            if (DENT_REBUILD_TIMING_ENABLED) System.nanoTime() else 0L

        private fun timingSince(startNanos: Long): Long =
            if (DENT_REBUILD_TIMING_ENABLED) System.nanoTime() - startNanos else 0L

        private fun Long.toMilliseconds(): Double = this / 1_000_000.0

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
            const val TAG = "FilamentRenderer"
            const val DENT_REBUILD_TIMING_ENABLED = false
            const val DYNAMIC_UPLOAD_SLOT_COUNT = 2
            const val POSITION_BUFFER_INDEX = 0
            const val TANGENT_BUFFER_INDEX = 1
            const val UV_BUFFER_INDEX = 2
            const val POSITION_COMPONENTS = 3
            const val TANGENT_COMPONENTS = 4
            const val UV_COMPONENTS = 2
            const val POSITION_VERTEX_SIZE_BYTES = POSITION_COMPONENTS * Float.SIZE_BYTES
            const val TANGENT_VERTEX_SIZE_BYTES = TANGENT_COMPONENTS * Float.SIZE_BYTES
            const val UV_VERTEX_SIZE_BYTES = UV_COMPONENTS * Float.SIZE_BYTES
            const val MATERIAL_ASSET = "materials/sphere.filamat"
            const val LIGHT_DIRECTION_TO_SOURCE_PARAMETER = "lightDirectionToSource"
            const val BACKGROUND_RED = 0.018f
            const val BACKGROUND_GREEN = 0.025f
            const val BACKGROUND_BLUE = 0.045f
            const val BACKGROUND_ALPHA = 1.0f
            const val SUN_ANGULAR_RADIUS_DEGREES = 0.545f
            const val SUN_HALO_SIZE = 25.0f
            const val SUN_HALO_FALLOFF = 5.0f
            const val BLOOM_STRENGTH = 0.10f
            const val LUNAR_TEXTURE_WIDTH = 2048
            const val LUNAR_TEXTURE_HEIGHT = 1024
            const val OVERVIEW_SHORT_SIDE_FOV_DEGREES = 45.0
            const val FOCUSED_SHORT_SIDE_FOV_DEGREES = 22.0
            const val CAMERA_FOCUS_TARGET_OFFSET = 0.48
            const val CAMERA_FOCUS_ANIMATION_NANOS = 450_000_000L
            const val FRAMING_MARGIN = 1.18
            const val SPHERE_RADIUS = 1.0
            const val RIPPLE_PARAMETER_COMPONENTS = 4
            const val RIPPLE_START_COMPONENT = 3
            const val INACTIVE_RIPPLE_START_SECONDS = -10f
            const val NANOS_PER_SECOND = 1_000_000_000L
            const val FPS_SAMPLE_INTERVAL_NANOS = 1_000_000_000L
            const val ORIENTATION_SAMPLE_CAPACITY = 12
            const val ORIENTATION_SAMPLE_WINDOW_NANOS = 100_000_000L
            const val STALE_RELEASE_NANOS = 120_000_000L
            const val MINIMUM_SAMPLE_INTERVAL_NANOS = 16_000_000L
            const val MINIMUM_INERTIA_LAUNCH_SPEED = 0.35
            const val MAXIMUM_INERTIA_LAUNCH_SPEED = 6.0
            const val INERTIA_DAMPING_RATE_PER_SECOND = 3.5
            const val INERTIA_STOP_SPEED = 0.03
            const val IDLE_ROTATION_SPEED_RADIANS_PER_SECOND = 0.15
            const val MAX_ROTATION_DELTA_TIME_SECONDS = 0.05
            val SPHERE_CENTER = Vector3(0.0, 0.0, 0.0)
            val LOCAL_Y_AXIS = Vector3(0.0, 1.0, 0.0)

            fun fovFor(quadrant: CameraFocusQuadrant?) =
                if (quadrant == null) {
                    OVERVIEW_SHORT_SIDE_FOV_DEGREES
                } else {
                    FOCUSED_SHORT_SIDE_FOV_DEGREES
                }

            fun targetXFor(quadrant: CameraFocusQuadrant?) =
                quadrant?.horizontalSign?.times(CAMERA_FOCUS_TARGET_OFFSET) ?: 0.0

            fun targetYFor(quadrant: CameraFocusQuadrant?) =
                quadrant?.verticalSign?.times(CAMERA_FOCUS_TARGET_OFFSET) ?: 0.0

            fun lerp(start: Double, end: Double, progress: Double) =
                start + (end - start) * progress

            fun createFloatVertexBufferData(values: FloatArray): ByteBuffer =
                ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                    .order(ByteOrder.nativeOrder())
                    .apply {
                        asFloatBuffer().put(values)
                        limit(capacity())
                        position(0)
                    }

            fun createUvVertexBufferData(mesh: SphereMeshData): ByteBuffer {
                val uvs = FloatArray(mesh.vertexCount * UV_COMPONENTS)
                for (vertexIndex in 0 until mesh.vertexCount) {
                    val sourceOffset = vertexIndex * SphereMeshData.FLOATS_PER_VERTEX +
                        SphereMeshData.UV_OFFSET
                    val destinationOffset = vertexIndex * UV_COMPONENTS
                    uvs[destinationOffset] = mesh.vertices[sourceOffset]
                    uvs[destinationOffset + 1] = mesh.vertices[sourceOffset + 1]
                }
                return createFloatVertexBufferData(uvs)
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

        private class DynamicUploadSlot(vertexCount: Int) {
            val positions: ByteBuffer = allocateFloatBuffer(vertexCount * POSITION_COMPONENTS)
            val tangents: ByteBuffer = allocateFloatBuffer(vertexCount * TANGENT_COMPONENTS)
            private val positionFloats: FloatBuffer = positions.asFloatBuffer()
            private val tangentFloats: FloatBuffer = tangents.asFloatBuffer()

            fun write(positionValues: FloatArray, tangentValues: FloatArray) {
                require(positionValues.size == positionFloats.capacity())
                require(tangentValues.size == tangentFloats.capacity())
                positionFloats.clear()
                positionFloats.put(positionValues)
                tangentFloats.clear()
                tangentFloats.put(tangentValues)
                positions.position(0)
                positions.limit(positions.capacity())
                tangents.position(0)
                tangents.limit(tangents.capacity())
            }

            private companion object {
                fun allocateFloatBuffer(componentCount: Int): ByteBuffer =
                    ByteBuffer.allocateDirect(componentCount * Float.SIZE_BYTES)
                        .order(ByteOrder.nativeOrder())
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
