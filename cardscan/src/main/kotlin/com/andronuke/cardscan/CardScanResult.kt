package com.andronuke.cardscan

/** Transient card data. Never persist or log. Expired results always require explicit review. */
class CardScanResult @JvmOverloads constructor(
    @JvmField val cardNumber: String, @JvmField val expiry: String?,
    @JvmField val validationStatus: CardValidationStatus = CardValidationStatus.VALID
) {
    val brand: CardBrand? get() = cardBrand(cardNumber)
    val requiresManualExpiry: Boolean get() = expiry == null
}
enum class CardValidationStatus { VALID, EXPIRED_REQUIRES_REVIEW }
enum class ScannerFailure { CAMERA_UNAVAILABLE, INVALID_CONFIGURATION }
enum class CancelReason { USER, SYSTEM_BACK }
sealed interface ScanOutcome {
    data class Completed(val result: CardScanResult) : ScanOutcome
    data object ManualEntryRequested : ScanOutcome
    data class Cancelled(val reason: CancelReason = CancelReason.USER) : ScanOutcome
    data object TimedOut : ScanOutcome
    data class Failed(val error: ScannerFailure) : ScanOutcome
}
/** Diagnostic codes never carry PAN, expiry, images, or raw OCR. */
data class ScannerProgress(val message: ScannerMessage, val warning: Boolean)
enum class ScannerAction { CLOSE, MANUAL_ENTRY, RETRY, CONFIRM, TOGGLE_TORCH, ZOOM_IN, ZOOM_OUT }
data class ScannerUiState(
    val message: ScannerMessage = ScannerMessage.READY, val warningVisible: Boolean = false,
    val numberPreview: String? = null, val expiryPreview: String? = null,
    val expiryPreviewInvalid: Boolean = false, val readyToConfirm: Boolean = false,
    val torchEnabled: Boolean = false, val torchAvailable: Boolean = false,
    val focusPoint: ScanPoint? = null
)
data class ScanPoint(val x: Float, val y: Float)
