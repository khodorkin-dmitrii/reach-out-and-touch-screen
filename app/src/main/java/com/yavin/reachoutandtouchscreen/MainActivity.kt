package com.yavin.reachoutandtouchscreen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.google.android.filament.Filament
import com.yavin.reachoutandtouchscreen.ui.theme.ReachOutAndTouchscreenTheme

class MainActivity : ComponentActivity() {
    companion object {
        init {
            Filament.init()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReachOutAndTouchscreenTheme {
                FilamentScene()
            }
        }
    }
}
