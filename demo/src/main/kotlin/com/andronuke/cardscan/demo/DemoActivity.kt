package com.andronuke.cardscan.demo

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import com.andronuke.cardscan.CardScanContract
import kotlinx.coroutines.flow.collect

/** Platform host: navigation and secure window policy. Form decisions live in DemoViewModel. */
class DemoActivity : ComponentActivity() {
    private val viewModel by lazy { ViewModelProvider(this)[DemoViewModel::class.java] }

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onEvent(AddCardEvent.ScanCompleted(CardScanContract.parse(result.resultCode, result.data)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = android.graphics.Color.WHITE
        window.navigationBarColor = android.graphics.Color.WHITE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContent {
            val state by viewModel.uiState.collectAsState()
            LaunchedEffect(viewModel) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        AddCardEffect.LaunchScanner -> scanLauncher.launch(CardScanContract.createIntent(this@DemoActivity))
                        AddCardEffect.Close -> finish()
                    }
                }
            }
            AddCardScreen(state, viewModel::onEvent)
        }
    }

}
