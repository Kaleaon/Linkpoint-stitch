package com.example

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.linkpoint.client.ViewerSessionViewModel
import com.example.linkpoint.designsystem.LinkpointTheme
import com.example.linkpoint.store.ConnectionState
import com.example.linkpoint.ui.GatewayScreen
import com.example.linkpoint.ui.InWorldCockpitScreen

class MainActivity : ComponentActivity() {
    private var activeViewModel: ViewerSessionViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: ViewerSessionViewModel = viewModel()
            activeViewModel = viewModel
            val state by viewModel.state.collectAsStateWithLifecycle()

            LinkpointTheme(designState = state.design) {
                when (state.connection) {
                    is ConnectionState.Connected,
                    is ConnectionState.LocalSandbox -> {
                        InWorldCockpitScreen(
                            state = state,
                            renderer = viewModel.renderer,
                            client = viewModel
                        )
                    }
                    else -> {
                        GatewayScreen(
                            state = state,
                            client = viewModel
                        )
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val vm = activeViewModel
        if (vm != null && (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)) {
            if (event.repeatCount == 0 || event.action == KeyEvent.ACTION_UP) {
                val handled = vm.onKeyboardKeyEvent(
                    keyCode = event.keyCode,
                    isDown = event.action == KeyEvent.ACTION_DOWN
                )
                if (handled) return true
            } else if (vm.isMovementKeyActive(event.keyCode)) {
                // Consume key repeat events for held movement keys so Android focus traversal doesn't hijack Arrows/WASD
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            activeViewModel?.clearKeyboardMovement()
        }
    }
}
