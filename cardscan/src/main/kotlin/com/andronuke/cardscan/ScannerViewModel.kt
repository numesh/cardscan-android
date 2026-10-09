package com.andronuke.cardscan

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The screen is a projection of this state; no camera or Activity references are retained. */
internal data class ScannerUiState(
    val status: String = "Hold steady and avoid reflections.",
    val warningVisible: Boolean = false,
    val numberPreview: String? = null,
    val expiryPreview: String? = null,
    val expiryPreviewInvalid: Boolean = false,
    val torchEnabled: Boolean = false,
    val focusPoint: ScanPoint? = null
)

internal data class ScanPoint(val x: Float, val y: Float)

/** Camera results, hardware feedback, and user actions enter one reducer. */
internal sealed interface ScannerEvent {
    data class Frame(
        val lines: List<CardTextExtractor.Line>,
        val now: Long,
        val year: Int,
        val month: Int
    ) : ScannerEvent
    data class TorchStateChanged(val enabled: Boolean) : ScannerEvent
    data class FocusTapped(val point: ScanPoint) : ScannerEvent
    data class FocusCleared(val point: ScanPoint) : ScannerEvent
    data object TorchPressed : ScannerEvent
    data object ClosePressed : ScannerEvent
    data object CameraReady : ScannerEvent
    data object CameraUnavailable : ScannerEvent
    data object CameraStarting : ScannerEvent
    data object TorchUnavailable : ScannerEvent
    data object AnalysisFailed : ScannerEvent
    data object PermissionDenied : ScannerEvent
}

/** Effects are consumed once by the Activity; they are never replayed as UI state. */
internal sealed interface ScannerEffect {
    data object Close : ScannerEffect
    data object ToggleTorch : ScannerEffect
    data class Finished(val number: String, val expiry: String?) : ScannerEffect
}

internal class ScannerViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()
    private val effectChannel = Channel<ScannerEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    private val partialPanTracker = PartialPanTracker()
    private var completed = false
    private var recentWarning: String? = null
    private var warningUntil = 0L
    private var lastNumberSeenAt = 0L
    private var lastExpiryPreviewSeenAt = 0L
    private var observedNumber: String? = null
    private var numberReadCount = 0
    private var numberFirstSeenAt = 0L
    private var lastValidNumberSeenAt = 0L
    private var observedExpiry: String? = null
    private var expiryReadCount = 0
    private var lastExpirySeenAt = 0L
    private var firstExpiryIssueAt = 0L

    fun onEvent(event: ScannerEvent) {
        when (event) {
            is ScannerEvent.Frame -> if (!completed) processFrame(event)
            is ScannerEvent.TorchStateChanged -> _uiState.update { it.copy(torchEnabled = event.enabled) }
            is ScannerEvent.FocusTapped -> _uiState.update { it.copy(focusPoint = event.point) }
            is ScannerEvent.FocusCleared -> _uiState.update {
                if (it.focusPoint == event.point) it.copy(focusPoint = null) else it
            }
            ScannerEvent.TorchPressed -> emit(ScannerEffect.ToggleTorch)
            ScannerEvent.ClosePressed -> emit(ScannerEffect.Close)
            ScannerEvent.CameraReady -> showStatus("Hold steady and avoid reflections.")
            ScannerEvent.CameraUnavailable -> showWarning("Camera unavailable. Enter details manually.")
            ScannerEvent.CameraStarting -> showStatus("Camera is starting. Try the flash again in a moment.")
            ScannerEvent.TorchUnavailable -> showStatus("Flash is unavailable on this camera.")
            ScannerEvent.AnalysisFailed -> showStatus("Text could not be analyzed. Try more light or enter manually.")
            ScannerEvent.PermissionDenied -> showWarning(
                "Camera permission denied. Enter details manually or enable it in Settings.")
        }
    }

    private fun processFrame(frame: ScannerEvent.Frame) {
        val now = frame.now
        var candidate = CardTextExtractor.extract(frame.lines, frame.year, frame.month)
        var mergedFromFragments = false
        if (candidate.number == null && observedNumber == null) {
            val merged = partialPanTracker.observe(frame.lines, now)
            if (merged != null) {
                candidate = candidate.copy(number = merged, detectedNumber = merged,
                    hasText = true, unsupportedNetwork = false, invalidNumber = false)
                mergedFromFragments = true
                partialPanTracker.clear()
            } else if ((candidate.detectedNumber?.length ?: 0) >= 15) partialPanTracker.clear()
        } else if (candidate.number != null) partialPanTracker.clear()

        // Preview OCR immediately; acceptance still requires the checks below.
        when {
            candidate.detectedNumber != null -> showDetectedNumber(candidate.detectedNumber, now)
            observedNumber != null && numberReadCount >= 3 -> showDetectedNumber(observedNumber, now)
            observedNumber == null && partialPanTracker.bestFragment != null ->
                showPartialNumber(partialPanTracker.bestFragment!!, now)
            else -> showDetectedNumber(null, now)
        }
        showDetectedExpiry(candidate.detectedExpiry,
            candidate.expiryIssue != CardTextExtractor.EXPIRY_OK, now)

        if (candidate.expiryIssue != CardTextExtractor.EXPIRY_OK && firstExpiryIssueAt == 0L)
            firstExpiryIssueAt = now
        if (candidate.expiry != null) firstExpiryIssueAt = 0

        if (candidate.number == null) {
            handleFrameWithoutNumber(candidate, now)
            return
        }

        lastValidNumberSeenAt = now
        if (candidate.number == observedNumber) numberReadCount++
        else {
            observedNumber = candidate.number
            // Two repeated overlapping fragments already provide multi-frame confirmation.
            numberReadCount = if (mergedFromFragments) 3 else 1
            numberFirstSeenAt = now
            observedExpiry = null
            expiryReadCount = 0
            firstExpiryIssueAt = if (candidate.expiryIssue == CardTextExtractor.EXPIRY_OK) 0 else now
        }
        observeExpiry(candidate.expiry, now)
        if (candidate.expiry != null) {
            recentWarning = null
            warningUntil = 0
        }
        if (candidate.expiryIssue != CardTextExtractor.EXPIRY_OK && observedExpiry == null) {
            warnForExpiry(candidate.expiryIssue, now)
            if (numberReadCount >= 3 && now - numberFirstSeenAt >= 12000 &&
                now - firstExpiryIssueAt >= 1600) finishWithNumber(observedNumber!!, null)
            return
        }
        when {
            numberReadCount < 3 -> showStatus("Card number found. Hold steady to confirm.", now)
            observedExpiry != null && expiryReadCount >= 2 ->
                finishWithNumber(observedNumber!!, observedExpiry)
            now - numberFirstSeenAt >= 12000 &&
                (firstExpiryIssueAt == 0L || now - firstExpiryIssueAt >= 1600) ->
                finishWithNumber(observedNumber!!, null)
            else -> showStatus(if (observedExpiry == null)
                "Number confirmed. Keep the expiry date inside the frame."
                else "Expiry found. Hold steady to confirm.", now)
        }
    }

    private fun handleFrameWithoutNumber(candidate: CardTextExtractor.Scan, now: Long) {
        if (observedNumber != null && numberReadCount >= 3) {
            // Expiry may be printed elsewhere; continue after OCR stops seeing the PAN.
            observeExpiry(candidate.expiry, now)
            if (observedExpiry != null && expiryReadCount >= 2) {
                finishWithNumber(observedNumber!!, observedExpiry)
                return
            }
            if (now - numberFirstSeenAt >= 12000 &&
                (firstExpiryIssueAt == 0L || now - firstExpiryIssueAt >= 1600)) {
                finishWithNumber(observedNumber!!, null)
                return
            }
        } else if (now - lastValidNumberSeenAt > 2000) {
            observedNumber = null
            numberReadCount = 0
            observedExpiry = null
            expiryReadCount = 0
        }
        when {
            candidate.unsupportedNetwork && ((candidate.detectedNumber?.length ?: 0) >= 15 ||
                partialPanTracker.bestFragment == null) ->
                showWarning("Warning: Only Visa, Mastercard and Amex can be scanned.", now)
            candidate.invalidNumber && ((candidate.detectedNumber?.length ?: 0) >= 15 ||
                partialPanTracker.bestFragment == null) ->
                showWarning("Warning: Card number failed validation. Check the card or enter manually.", now)
            candidate.expiryIssue != CardTextExtractor.EXPIRY_OK && observedExpiry == null ->
                warnForExpiry(candidate.expiryIssue, now)
            observedNumber != null && candidate.expiry != null ->
                showStatus("Expiry found. Hold steady to confirm.", now)
            observedNumber != null && numberReadCount >= 3 ->
                showStatus("Number confirmed. Keep the expiry date inside the frame.", now)
            partialPanTracker.bestFragment != null ->
                showStatus("Part of the number captured. Tilt the card to reveal the rest.", now)
            candidate.hasText -> showStatus("No valid card number yet. Hold steady and avoid reflections.", now)
            else -> showStatus("No readable text yet. Move closer or improve lighting.", now)
        }
    }

    private fun observeExpiry(expiry: String?, now: Long) {
        if (expiry == null) {
            if (now - lastExpirySeenAt > 2000) {
                observedExpiry = null
                expiryReadCount = 0
            }
            return
        }
        expiryReadCount = if (expiry == observedExpiry && now - lastExpirySeenAt <= 3000)
            expiryReadCount + 1 else 1
        observedExpiry = expiry
        lastExpirySeenAt = now
    }

    private fun showDetectedNumber(digits: String?, now: Long) {
        if (digits != null) {
            lastNumberSeenAt = now
            val formatted = buildString {
                digits.forEachIndexed { index, char ->
                    val boundary = if (digits.length == 15) index == 4 || index == 10 else index % 4 == 0
                    if (index > 0 && boundary) append(' ')
                    append(char)
                }
            }
            _uiState.update { it.copy(numberPreview = formatted) }
        } else if (now - lastNumberSeenAt > 1000) _uiState.update { it.copy(numberPreview = null) }
    }

    private fun showPartialNumber(digits: String, now: Long) {
        lastNumberSeenAt = now
        _uiState.update { it.copy(numberPreview = "Part captured: $digits …") }
    }

    private fun showDetectedExpiry(expiry: String?, invalid: Boolean, now: Long) {
        when {
            expiry != null -> {
                lastExpiryPreviewSeenAt = now
                _uiState.update { it.copy(expiryPreview = expiry, expiryPreviewInvalid = invalid) }
            }
            observedExpiry != null && numberReadCount >= 3 ->
                _uiState.update { it.copy(expiryPreview = observedExpiry, expiryPreviewInvalid = false) }
            now - lastExpiryPreviewSeenAt > 1500 -> _uiState.update { it.copy(expiryPreview = null) }
        }
    }

    private fun showStatus(message: String, now: Long = SystemClock.elapsedRealtime()) {
        val warning = recentWarning?.takeIf { now < warningUntil }
        _uiState.update { it.copy(status = warning ?: message, warningVisible = warning != null) }
    }

    private fun showWarning(message: String, now: Long = SystemClock.elapsedRealtime()) {
        recentWarning = message
        warningUntil = now + 1600
        _uiState.update { it.copy(status = message, warningVisible = true) }
    }

    private fun warnForExpiry(issue: Int, now: Long) {
        when (issue) {
            CardTextExtractor.EXPIRY_EXPIRED ->
                showWarning("Warning: This card has expired. Enter expiry manually.", now)
            CardTextExtractor.EXPIRY_AMBIGUOUS ->
                showWarning("Warning: Conflicting expiry dates detected. Enter expiry manually.", now)
            CardTextExtractor.EXPIRY_INVALID ->
                showWarning("Warning: Expiry date is invalid. Check the card or enter manually.", now)
        }
    }

    private fun finishWithNumber(number: String, expiry: String?) {
        completed = true // An additional analyzer callback must not deliver a second result.
        emit(ScannerEffect.Finished(number, expiry))
    }

    private fun emit(effect: ScannerEffect) {
        viewModelScope.launch { effectChannel.send(effect) }
    }

    override fun onCleared() {
        partialPanTracker.clear()
        observedNumber = null
        observedExpiry = null
        _uiState.value = ScannerUiState()
        effectChannel.close()
    }
}
