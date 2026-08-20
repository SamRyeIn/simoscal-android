package com.simoscal.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.simoscal.android.ui.SimoscalApp
import com.simoscal.android.ui.SimoscalTheme

/**
 * The app's single activity.
 *
 * Everything below it is Compose. The activity itself holds no calibration
 * state: the [EditorViewModel] survives configuration changes, and anything
 * that must survive *process death* is persisted through [RecoveryStore] after
 * every state-changing engine call.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SimoscalTheme {
                SimoscalApp(viewModel = viewModel)
            }
        }
    }
}
