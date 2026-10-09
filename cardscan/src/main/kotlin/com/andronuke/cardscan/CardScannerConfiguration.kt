package com.andronuke.cardscan

import java.io.Serializable

/** Stable message keys. Overrides localize all built-in copy without changing scan decisions. */
enum class ScannerMessage(val defaultText: String) {
    READY("Hold steady and avoid reflections."),
    CONFIRMING_NUMBER("Card number found. Hold steady to confirm."),
    LOOKING_FOR_EXPIRY("Number confirmed. Keep the expiry date inside the frame."),
    CONFIRMING_EXPIRY("Expiry found. Hold steady to confirm."),
    PARTIAL_NUMBER("Part of the number captured. Tilt the card to reveal the rest."),
    NO_NUMBER("No valid card number yet. Hold steady and avoid reflections."),
    NO_TEXT("No readable text yet. Move closer or improve lighting."),
    INVALID_NUMBER("Card number failed validation. Check the card or enter manually."),
    UNSUPPORTED_BRAND("This card type is not supported."),
    INVALID_EXPIRY("Expiry date is invalid. Check the card or enter manually."),
    EXPIRED("This card has expired. Enter expiry manually."),
    AMBIGUOUS_EXPIRY("Conflicting expiry dates detected. Enter expiry manually."),
    PERMISSION_DENIED("Camera permission denied. Enter details manually or enable it in Settings."),
    CAMERA_UNAVAILABLE("Camera unavailable. Enter details manually."),
    CAMERA_STARTING("Camera is starting. Try the flash again in a moment."),
    TORCH_UNAVAILABLE("Flash is unavailable on this camera."),
    ANALYSIS_FAILED("Text could not be analyzed. Try more light or enter manually."),
    FOCUS_FAILED("Could not focus. Hold the card steady."),
    REVIEW("Check the scanned details, then confirm."),
    SUCCESS("Card details scanned."),
    HOST_REJECTED("These card details cannot be used. Try another card."),
    TITLE("Scan card"),
    INSTRUCTIONS("Position your card number and expiry date inside the frame."),
    CLOSE("Close scanner"),
    MANUAL("Enter details manually"),
    RETRY("Scan again"),
    CONFIRM("Use scanned details"),
    TORCH_ON("Turn torch on"),
    TORCH_OFF("Turn torch off"),
    PRIVACY("Your card image is not saved."),
    EXPIRY_PREFIX("EXP"),
    PARTIAL_PREFIX("Part captured:"),
    ZOOM_IN("Zoom in"),
    ZOOM_OUT("Zoom out"),
    NUMBER_DETECTED("Card number detected"),
    EXPIRY_DETECTED("Expiry detected");
}
enum class CardBrand { VISA, MASTERCARD, AMEX }
enum class ExpiryRequirement { DISABLED, OPTIONAL, REQUIRED }
enum class MissingExpiryAction { RETURN_NUMBER, KEEP_SCANNING, TIMEOUT }
enum class ExpiredCardPolicy { REJECT, RETURN_FOR_REVIEW }
enum class CompletionMode { AUTOMATIC, USER_CONFIRMATION }
enum class NumberDisplay { FULL, MASKED, LAST_FOUR, HIDDEN }
enum class GuideStyle { CORNERS, OUTLINE }
enum class ControlPlacement { BOTTOM, TOP }
enum class ExpiryFormat { SEPARATED, SPACED, LABELLED_COMPACT }

/** Immutable per-session options. Timing values use milliseconds; dimensions use dp/sp. */
data class CardScannerConfiguration(
    val controls: ScannerControls = ScannerControls(),
    val recognition: RecognitionOptions = RecognitionOptions(),
    val validation: ValidationOptions = ValidationOptions(),
    val timing: ScannerTiming = ScannerTiming(),
    val partial: PartialNumberOptions = PartialNumberOptions(),
    val appearance: ScannerAppearance = ScannerAppearance(),
    val messages: Map<ScannerMessage, String> = emptyMap(),
    val completionMode: CompletionMode = CompletionMode.AUTOMATIC
) : Serializable {
    internal fun snapshot() = copy(validation = validation.copy(supportedBrands = validation.supportedBrands.toSet(), expiryFormats = validation.expiryFormats.toSet(), expiryLabels = validation.expiryLabels.toList()), messages = messages.toMap())
    fun text(key: ScannerMessage): String = messages[key] ?: key.defaultText
    fun validate() {
        require(recognition.numberConfirmationReads in 2..20 && recognition.expiryConfirmationReads in 2..20) { "Confirmation reads must be 2..20" }
        require(recognition.minimumConfidence in 0f..1f && recognition.minimumTextHeight in 0f..0.2f)
        require(recognition.resolutionWidth in 640..3840 && recognition.resolutionHeight in 480..2160)
        require(validation.supportedBrands.isNotEmpty() && validation.maximumFutureYears in 1..50)
        require(validation.expiryLabels.all { it.isNotBlank() } && validation.expiryFormats.isNotEmpty())
        require(timing.analysisIntervalMs in 0..5000 && timing.minimumStableDurationMs in 0..10000)
        require(timing.confirmationWindowMs in 100..30000 && timing.candidateResetDelayMs in 100..30000)
        require(timing.expiryWaitTimeoutMs in 0..120000 && (timing.sessionTimeoutMs == null || timing.sessionTimeoutMs in 1000..600000))
        require(timing.warningDisplayMs in 0..10000 && timing.previewRetentionMs in 0..10000 && timing.expiryPreviewRetentionMs in 0..10000)
        require(timing.successDisplayMs in 0..5000 && timing.focusMarkerMs in 0..5000)
        require(timing.confirmedNumberRetentionMs in 1000..600000)
        require(partial.minimumOverlap in 6..13 && partial.readsPerFragment in 2..20 && partial.maxFragments in 2..32 && partial.retentionMs in 500..30000)
        require(controls.initialZoom in 1f..10f)
        require(appearance.guideWidthFraction in 0.4f..0.98f && appearance.guideAspectRatio in 1f..2.5f && appearance.guideVerticalPosition in 0.15f..0.6f)
        require(appearance.analysisPaddingDp in 0f..40f && appearance.shadeOpacity in 0f..1f)
        require(appearance.iconSizeDp in 16f..48f && appearance.buttonSizeDp in 48f..96f && appearance.spacingDp in 0f..32f)
        require(appearance.cornerRadiusDp in 0f..48f && appearance.guideStrokeDp in 1f..8f)
        require(listOf(appearance.titleSizeSp, appearance.bodySizeSp, appearance.buttonSizeSp, appearance.previewSizeSp).all { it in 12f..32f })
    }
    companion object {
        fun balanced() = CardScannerConfiguration()
        fun fast() = CardScannerConfiguration(recognition = RecognitionOptions(numberConfirmationReads = 2), timing = ScannerTiming(analysisIntervalMs = 150))
        fun strict() = CardScannerConfiguration(recognition = RecognitionOptions(numberConfirmationReads = 5, expiryConfirmationReads = 3), timing = ScannerTiming(minimumStableDurationMs = 600), validation = ValidationOptions(expiryRequirement = ExpiryRequirement.REQUIRED))
    }
}
data class ScannerControls(
    val torchVisible: Boolean = true, val initialTorchEnabled: Boolean = false,
    val hideTorchWhenUnavailable: Boolean = false, val tapToFocusEnabled: Boolean = true,
    val focusMarkerVisible: Boolean = true, val manualEntryVisible: Boolean = true,
    val closeVisible: Boolean = true, val retryVisible: Boolean = false,
    val zoomVisible: Boolean = false, val initialZoom: Float = 1f,
    val hapticsEnabled: Boolean = false, val placement: ControlPlacement = ControlPlacement.BOTTOM
) : Serializable
/** ML Kit has no fast/accurate switch. Confidence is an OCR score, never a correctness guarantee. */
data class RecognitionOptions(
    val numberConfirmationReads: Int = 3, val expiryConfirmationReads: Int = 2,
    val minimumConfidence: Float = 0f, val minimumTextHeight: Float = 0f,
    val resolutionWidth: Int = 1280, val resolutionHeight: Int = 720
) : Serializable
data class ValidationOptions(
    val supportedBrands: Set<CardBrand> = CardBrand.entries.toSet(),
    val expiryRequirement: ExpiryRequirement = ExpiryRequirement.OPTIONAL,
    val missingExpiryAction: MissingExpiryAction = MissingExpiryAction.RETURN_NUMBER,
    val expiredCardPolicy: ExpiredCardPolicy = ExpiredCardPolicy.REJECT,
    val maximumFutureYears: Int = 20,
    val expiryFormats: Set<ExpiryFormat> = ExpiryFormat.entries.toSet(),
    val expiryLabels: List<String> = listOf("EXP", "VALID", "THRU", "GOOD")
) : Serializable
data class ScannerTiming(
    val analysisIntervalMs: Long = 250, val minimumStableDurationMs: Long = 0,
    val confirmationWindowMs: Long = 3000, val candidateResetDelayMs: Long = 2000,
    val expiryWaitTimeoutMs: Long = 12000, val sessionTimeoutMs: Long? = null,
    val warningDisplayMs: Long = 1600, val previewRetentionMs: Long = 1000,
    val expiryPreviewRetentionMs: Long = 1500, val focusMarkerMs: Long = 900,
    val successDisplayMs: Long = 0, val confirmedNumberRetentionMs: Long = 60000
) : Serializable
data class PartialNumberOptions(
    val enabled: Boolean = true, val previewVisible: Boolean = true, val readsPerFragment: Int = 2,
    val minimumOverlap: Int = 6, val retentionMs: Long = 5000, val maxFragments: Int = 12
) : Serializable
/** ARGB colors; drawable resource IDs resolve in the host application. Zero selects built-in icons. */
data class ScannerAppearance(
    val backgroundColor: Long = 0xFF000000, val textColor: Long = 0xFFFFFFFF,
    val guideColor: Long = 0xFFFFD522, val warningColor: Long = 0xFFAE2525,
    val successColor: Long = 0xFF16883B, val focusColor: Long = 0xFFFFC83D,
    val buttonColor: Long = 0xFF222222, val previewColor: Long = 0xDD000000,
    val titleSizeSp: Float = 20f, val bodySizeSp: Float = 15f,
    val buttonSizeSp: Float = 15f, val previewSizeSp: Float = 17f,
    val iconSizeDp: Float = 28f, val buttonSizeDp: Float = 72f,
    val spacingDp: Float = 12f, val cornerRadiusDp: Float = 16f,
    val guideWidthFraction: Float = 0.88f, val guideAspectRatio: Float = 1.5f,
    val guideVerticalPosition: Float = 0.25f, val guideStrokeDp: Float = 3f,
    val analysisPaddingDp: Float = 24f, val shadeOpacity: Float = 0.67f,
    val guideStyle: GuideStyle = GuideStyle.CORNERS,
    val guideVisible: Boolean = true, val numberDisplay: NumberDisplay = NumberDisplay.FULL,
    val expiryPreviewVisible: Boolean = true, val privacyVisible: Boolean = true,
    val announceStatus: Boolean = true,
    val closeIcon: Int = 0, val torchOnIcon: Int = 0, val torchOffIcon: Int = 0,
    val manualIcon: Int = 0, val privacyIcon: Int = 0
) : Serializable

internal fun cardBrand(digits: String): CardBrand? {
    if (digits.startsWith("4") && digits.length in setOf(13,16,19)) return CardBrand.VISA
    val two = digits.take(2).toIntOrNull() ?: return null
    val four = digits.take(4).toIntOrNull() ?: return null
    if ((two in 51..55 || four in 2221..2720) && digits.length == 16) return CardBrand.MASTERCARD
    if ((two == 34 || two == 37) && digits.length == 15) return CardBrand.AMEX
    return null
}
