package com.andronuke.cardscan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.*
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.concurrent.Executor

/**
 * Embedded scanner. Callbacks and slots stay in-process; configuration is fixed for this composition.
 * Host owns presentation and must dismiss on terminal outcome. Apply FLAG_SECURE to the host window.
 */
@Composable
fun CardScanner(
    configuration: CardScannerConfiguration = CardScannerConfiguration(),
    onOutcome: (ScanOutcome) -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    onProgress: (ScannerProgress) -> Unit = {},
    additionalValidator: ((CardScanResult) -> Boolean)? = null,
    slots: ScannerSlots = ScannerSlots()
) {
    val config = remember { configuration.snapshot().also { it.validate() } }
    val callback by rememberUpdatedState(onOutcome)
    val progress by rememberUpdatedState(onProgress)
    val validator by rememberUpdatedState(additionalValidator)
    val model = remember { ScannerViewModel(config) { validator?.invoke(it) ?: true } }
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val hostView = LocalView.current
    val controller = remember { LifecycleCameraController(context) }
    var preview by remember { mutableStateOf<PreviewView?>(null) }
    var permitted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var active by remember { mutableStateOf(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var elapsed by remember { mutableLongStateOf(0) }
    var delivered by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        if (!granted) { model.engine.status(ScannerMessage.PERMISSION_DENIED, true, elapsed); model.refresh() }
    }
    LaunchedEffect(Unit) { if (!permitted) permission.launch(Manifest.permission.CAMERA) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            active = lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_RESUME) permitted = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (event == Lifecycle.Event.ON_PAUSE) controller.enableTorch(false)
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); model.engine.reset(); model.refresh() }
    }
    // Active time excludes backgrounding and permission dialogs; no OCR callback is required.
    LaunchedEffect(active, permitted, delivered) {
        if (active && permitted && !delivered) {
            var previous = SystemClock.elapsedRealtime()
            while (true) {
                delay(50)
                val now = SystemClock.elapsedRealtime(); elapsed += now - previous; previous = now
                model.engine.tick(elapsed); model.refresh()
                model.engine.outcome?.let { delivered = true; controller.clearImageAnalysisAnalyzer(); controller.unbind(); callback(it); return@LaunchedEffect }
            }
        }
    }
    LaunchedEffect(state.message, state.warningVisible) {
        progress(ScannerProgress(state.message, state.warningVisible))
        if (config.controls.hapticsEnabled && (state.warningVisible || state.message == ScannerMessage.SUCCESS)) hostView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
    }
    val dispatch: (ScannerAction) -> Unit = { action ->
        when (action) {
            ScannerAction.CLOSE -> model.engine.terminate(ScanOutcome.Cancelled())
            ScannerAction.MANUAL_ENTRY -> model.engine.terminate(ScanOutcome.ManualEntryRequested)
            ScannerAction.RETRY -> { elapsed = 0; model.engine.reset() }
            ScannerAction.CONFIRM -> model.engine.confirm(elapsed)
            ScannerAction.TOGGLE_TORCH -> {
                if (controller.cameraInfo?.hasFlashUnit() == true) controller.enableTorch(!state.torchEnabled)
                else model.engine.status(ScannerMessage.TORCH_UNAVAILABLE, false, elapsed)
            }
            ScannerAction.ZOOM_IN, ScannerAction.ZOOM_OUT -> controller.cameraInfo?.zoomState?.value?.let { z ->
                controller.setZoomRatio((z.zoomRatio + if (action == ScannerAction.ZOOM_IN) 0.5f else -0.5f).coerceIn(z.minZoomRatio,z.maxZoomRatio))
            }
        }
        model.refresh()
        // Cancellation must work even when camera permission was refused.
        model.engine.outcome?.let { if (!delivered) { delivered = true; controller.unbind(); callback(it) } }
    }
    BackHandler { model.engine.terminate(ScanOutcome.Cancelled(CancelReason.SYSTEM_BACK)); dispatch(ScannerAction.CLOSE) }
    DisposableEffect(preview, permitted, lifecycle) {
        var mounted = true
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val executor = Executor { hostView.post(it) }
        if (preview != null && permitted) try {
            controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            controller.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            controller.isTapToFocusEnabled = config.controls.tapToFocusEnabled
            controller.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            controller.imageAnalysisResolutionSelector = ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(
                Size(config.recognition.resolutionWidth, config.recognition.resolutionHeight), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
            val analyzer = MlKitAnalyzer(listOf(recognizer), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, executor) { result ->
                if (mounted && active && !delivered && !state.readyToConfirm) {
                    if (result.getThrowable(recognizer) != null) model.engine.status(ScannerMessage.ANALYSIS_FAILED, false, elapsed)
                    result.getValue(recognizer)?.let { text ->
                        val view = preview!!
                        val density = view.resources.displayMetrics.density
                        val guide = scannerGuide(view.width.toFloat(), view.height.toFloat(), density, config.appearance)
                        val pad = config.appearance.analysisPaddingDp * density
                        guide.inset(-pad, -pad)
                        val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                            val bounds = line.boundingBox
                            if (bounds != null && guide.contains(bounds.exactCenterX(), bounds.exactCenterY()) &&
                                bounds.height() >= config.recognition.minimumTextHeight * view.height &&
                                (config.recognition.minimumConfidence == 0f || line.confidence >= config.recognition.minimumConfidence))
                                CardTextExtractor.Line(line.text, bounds.top, bounds.left) else null
                        }
                        val calendar = Calendar.getInstance()
                        model.engine.frame(lines, elapsed, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH)+1)
                    }
                    model.refresh()
                }
            }
            controller.setImageAnalysisAnalyzer(executor, object : ImageAnalysis.Analyzer {
                var last = Long.MIN_VALUE / 2
                override fun getTargetCoordinateSystem() = analyzer.targetCoordinateSystem
                override fun updateTransform(matrix: Matrix?) = analyzer.updateTransform(matrix)
                override fun analyze(image: ImageProxy) {
                    val now = SystemClock.elapsedRealtime()
                    if (!active || delivered || now - last < config.timing.analysisIntervalMs) image.close()
                    else { last = now; analyzer.analyze(image) }
                }
            })
            preview!!.controller = controller
            controller.bindToLifecycle(lifecycle)
            controller.torchState.observe(lifecycle) { model.hardware(torch = it == TorchState.ON) }
            controller.initializationFuture.addListener({
                if (!mounted) return@addListener
                model.hardware(available = controller.cameraInfo?.hasFlashUnit() == true)
                controller.cameraInfo?.zoomState?.value?.let { z -> controller.setZoomRatio(config.controls.initialZoom.coerceIn(z.minZoomRatio,z.maxZoomRatio)) }
                if (active && config.controls.initialTorchEnabled && controller.cameraInfo?.hasFlashUnit() == true) controller.enableTorch(true)
            }, executor)
        } catch (_: Exception) {
            model.engine.terminate(ScanOutcome.Failed(ScannerFailure.CAMERA_UNAVAILABLE))
        }
        onDispose { mounted = false; controller.torchState.removeObservers(lifecycle); controller.clearImageAnalysisAnalyzer(); controller.unbind(); recognizer.close() }
    }
    ScannerScreen(state, config, dispatch, { preview = it }, { point ->
        if (config.controls.tapToFocusEnabled) model.hardware(focus = point)
    }, slots, modifier)
    LaunchedEffect(state.focusPoint) { if (state.focusPoint != null) { delay(config.timing.focusMarkerMs); model.hardware(focus = null) } }
}
