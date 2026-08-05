package com.yavin.reachoutandtouchscreen

import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.awaitCancellation

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilamentScene(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val renderer = remember { FilamentRenderer(context.assets) }
    val touchAreaSize = remember { mutableStateOf(IntSize.Zero) }

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
            renderer.destroy()
        }
    }

    AndroidExternalSurface(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { touchAreaSize.value = it }
            .pointerInput(renderer) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val size = touchAreaSize.value
                    renderer.onTouch(
                        TouchInput(
                            x = down.position.x.toDouble(),
                            y = down.position.y.toDouble(),
                            touchAreaWidth = size.width,
                            touchAreaHeight = size.height,
                        ),
                    )
                    waitForUpOrCancellation()
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
}
