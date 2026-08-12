package com.yavin.reachoutandtouchscreen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FilamentScene(
    rippleStateViewModel: RippleStateViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var fps by remember { mutableIntStateOf(0) }
    var rippleEffectsEnabled by rememberSaveable { mutableStateOf(true) }
    var moonTextureEnabled by rememberSaveable { mutableStateOf(true) }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsInteraction by remember { mutableIntStateOf(0) }
    val renderer = remember {
        FilamentRenderer(
            assets = context.assets,
            initialRippleSnapshot = rippleStateViewModel.snapshot(),
            onFpsChanged = { fps = it },
        )
    }
    val touchAreaSize = remember { mutableStateOf(IntSize.Zero) }
    val currentRippleEffectsEnabled by rememberUpdatedState(rippleEffectsEnabled)
    val moonTextureBlend by animateFloatAsState(
        targetValue = if (moonTextureEnabled) 1f else 0f,
        animationSpec = tween(durationMillis = MOON_TEXTURE_TRANSITION_MILLIS),
        label = "Moon texture blend",
    )

    fun showControls() {
        controlsVisible = true
        controlsInteraction++
    }

    LaunchedEffect(controlsInteraction) {
        delay(CONTROLS_VISIBLE_MILLIS)
        controlsVisible = false
    }

    LaunchedEffect(renderer, moonTextureBlend) {
        renderer.setMoonTextureBlend(moonTextureBlend)
    }

    DisposableEffect(renderer, lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> renderer.setResumed(true)
                Lifecycle.Event.ON_PAUSE -> renderer.setResumed(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        renderer.setResumed(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))

        onDispose {
            lifecycle.removeObserver(observer)
            rippleStateViewModel.retain(renderer.snapshotAndDestroy())
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidExternalSurface(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { touchAreaSize.value = it }
                .pointerInput(renderer) {
                    awaitEachGesture {
                        var controllerId: Long? = null
                        var controllerWasChosen = false
                        try {
                            do {
                                val event = awaitPointerEvent()
                                val size = touchAreaSize.value
                                for (change in event.changes) {
                                    val touch = TouchInput(
                                        x = change.position.x.toDouble(),
                                        y = change.position.y.toDouble(),
                                        touchAreaWidth = size.width,
                                        touchAreaHeight = size.height,
                                    )
                                    when {
                                        !change.previousPressed && change.pressed -> {
                                            val controlsRotation = !controllerWasChosen
                                            if (controlsRotation) {
                                                controllerId = change.id.value
                                                controllerWasChosen = true
                                            }
                                            renderer.onPointerDown(
                                                touch = touch,
                                                controlsRotation = controlsRotation,
                                                createsRipple = currentRippleEffectsEnabled,
                                                eventTimeNanos = change.uptimeMillis * NANOS_PER_MILLISECOND,
                                            )
                                        }

                                        change.id.value == controllerId &&
                                            change.previousPressed && change.pressed -> {
                                            renderer.onRotationMove(
                                                touch = touch,
                                                eventTimeNanos = change.uptimeMillis * NANOS_PER_MILLISECOND,
                                            )
                                        }

                                        change.id.value == controllerId &&
                                            change.previousPressed && !change.pressed -> {
                                            renderer.onRotationEnd(
                                                touch = touch,
                                                eventTimeNanos = change.uptimeMillis * NANOS_PER_MILLISECOND,
                                            )
                                            controllerId = null
                                        }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            if (controllerId != null) {
                                renderer.onRotationCancel()
                            }
                        }
                    }
                },
            isOpaque = true,
        ) {
            onSurface { surface, width, height ->
                renderer.attachSurface(surface, width, height)
                surface.onChanged { newWidth, newHeight ->
                    renderer.resize(newWidth, newHeight)
                }
                surface.onDestroyed {
                    renderer.detachSurface(surface)
                }

                try {
                    awaitCancellation()
                } finally {
                    renderer.detachSurface(surface)
                }
            }
        }

        Text(
            text = if (fps > 0) "FPS: $fps" else "FPS: --",
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(16.dp)
                .width(CONTROLS_WIDTH)
                .height(CONTROLS_HEIGHT)
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = ::showControls,
                ),
        ) {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(animationSpec = tween(CONTROLS_FADE_MILLIS)),
                exit = fadeOut(animationSpec = tween(CONTROLS_FADE_MILLIS)),
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.62f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        ToggleRow(
                            label = stringResource(R.string.ripple_effects),
                            checked = rippleEffectsEnabled,
                            onCheckedChange = {
                                rippleEffectsEnabled = it
                                showControls()
                            },
                        )
                        ToggleRow(
                            label = stringResource(R.string.moon_texture),
                            checked = moonTextureEnabled,
                            onCheckedChange = {
                                moonTextureEnabled = it
                                showControls()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(52.dp)
            .clickable { onCheckedChange(!checked) },
    ) {
        Text(
            text = label,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val CONTROLS_VISIBLE_MILLIS = 5_000L
private const val CONTROLS_FADE_MILLIS = 250
private const val MOON_TEXTURE_TRANSITION_MILLIS = 500
private val CONTROLS_WIDTH = 240.dp
private val CONTROLS_HEIGHT = 120.dp

internal inline fun <T> forEachNewPointerDown(
    changes: List<T>,
    wasPressed: (T) -> Boolean,
    isPressed: (T) -> Boolean,
    onDown: (T) -> Unit,
) {
    for (index in changes.indices) {
        val change = changes[index]
        if (!wasPressed(change) && isPressed(change)) onDown(change)
    }
}
