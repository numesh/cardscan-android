package com.andronuke.cardscan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Calendar
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.collect

/** Platform adapter: camera, permission, and result delivery. Scan policy lives in ScannerViewModel. */
class CardScannerActivity : ComponentActivity() {
    private var previewView: PreviewView? = null
    private var cameraController: LifecycleCameraController? = null
    private lateinit var recognizer: TextRecognizer
    private val viewModel by lazy { ViewModelProvider(this)[ScannerViewModel::class.java] }
    private var active = false
    private var permissionRequested = false
    private var resultReturned = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startIfPermitted()
        else viewModel.onEvent(ScannerEvent.PermissionDenied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        setContent {
            val state by viewModel.uiState.collectAsState()
            LaunchedEffect(viewModel) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        ScannerEffect.Close -> finish()
                        ScannerEffect.ToggleTorch -> toggleTorch()
                        is ScannerEffect.Finished -> returnResult(effect.number, effect.expiry)
                    }
                }
            }
            ScannerScreen(state, viewModel::onEvent, ::onPreviewReady)
        }
    }

    override fun onResume() {
        super.onResume()
        active = true
        startIfPermitted()
    }

    override fun onPause() {
        active = false
        super.onPause()
    }

    override fun onDestroy() {
        cameraController?.clearImageAnalysisAnalyzer()
        recognizer.close()
        super.onDestroy()
    }

    /** CameraX owns PreviewView; Compose supplies it after the view enters composition. */
    private fun onPreviewReady(view: PreviewView) {
        previewView = view
        cameraController?.let { view.controller = it }
        view.post { startIfPermitted() }
    }

    private fun toggleTorch() {
        val controller = cameraController
        val info = controller?.cameraInfo
        when {
            info == null -> viewModel.onEvent(ScannerEvent.CameraStarting)
            !info.hasFlashUnit() -> viewModel.onEvent(ScannerEvent.TorchUnavailable)
            else -> controller.enableTorch(!viewModel.uiState.value.torchEnabled)
        }
    }

    private fun startIfPermitted() {
        if (!active || cameraController != null || previewView == null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!permissionRequested) {
                permissionRequested = true
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
            return
        }
        try {
            val controller = LifecycleCameraController(this)
            controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            controller.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            controller.isTapToFocusEnabled = true
            controller.setTapToFocusAutoCancelDuration(3, TimeUnit.SECONDS)
            controller.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            controller.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val uiExecutor = Executor { runOnUiThread(it) }
            controller.setImageAnalysisAnalyzer(uiExecutor, MlKitAnalyzer(
                listOf(recognizer), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
                uiExecutor
            ) { result ->
                if (!active || resultReturned) return@MlKitAnalyzer
                if (result.getThrowable(recognizer) != null) {
                    viewModel.onEvent(ScannerEvent.AnalysisFailed)
                    return@MlKitAnalyzer
                }
                result.getValue(recognizer)?.let(::handleRecognizedText)
            })
            previewView?.controller = controller
            controller.bindToLifecycle(this)
            cameraController = controller
            controller.torchState.observe(this) { state ->
                viewModel.onEvent(ScannerEvent.TorchStateChanged(state == TorchState.ON))
            }
            viewModel.onEvent(ScannerEvent.CameraReady)
        } catch (_: RuntimeException) {
            viewModel.onEvent(ScannerEvent.CameraUnavailable)
        }
    }

    /** ML Kit data enters the ViewModel only after the preview guide filters the lines. */
    private fun handleRecognizedText(recognized: com.google.mlkit.vision.text.Text) {
        if (!active || resultReturned) return
        val lines = buildList {
            for (block in recognized.textBlocks) for (line in block.lines) {
                val bounds = line.boundingBox
                if (bounds != null && insideGuide(bounds)) {
                    add(CardTextExtractor.Line(line.text, bounds.top, bounds.left))
                }
            }
        }
        val today = Calendar.getInstance()
        viewModel.onEvent(ScannerEvent.Frame(lines, SystemClock.elapsedRealtime(),
            today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1))
    }

    private fun insideGuide(box: Rect): Boolean {
        val view = previewView ?: return false
        if (view.width == 0 || view.height == 0) return false
        val density = resources.displayMetrics.density
        val side = 24f * density
        // Match ScannerScreen's guide exactly so OCR uses the visible card area.
        val guideHeight = (view.width - side * 2) * 0.67f
        val top = minOf(view.height * 0.25f, view.height - guideHeight - 280 * density)
            .coerceAtLeast(0f)
        val allowance = 24f * density
        return box.centerX() >= side - allowance && box.centerX() <= view.width - side + allowance &&
            box.centerY() >= top - allowance && box.centerY() <= top + guideHeight + allowance
    }

    private fun returnResult(number: String, expiry: String?) {
        resultReturned = true
        val data = Intent().putExtra(CardScanContract.EXTRA_CARD_NUMBER, number)
        if (expiry != null) data.putExtra(CardScanContract.EXTRA_EXPIRY, expiry)
        setResult(Activity.RESULT_OK, data)
        finish()
    }
}
