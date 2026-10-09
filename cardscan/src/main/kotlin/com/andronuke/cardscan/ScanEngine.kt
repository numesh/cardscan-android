package com.andronuke.cardscan

/** Deterministic policy engine. Its clock counts active session time, never wall time. */
internal class ScanEngine(
    val config: CardScannerConfiguration,
    private val validator: ((CardScanResult) -> Boolean)? = null
) {
    init { config.validate() }
    var state = ScannerUiState(); private set
    var outcome: ScanOutcome? = null; private set
    private var tracker = PartialPanTracker(config.partial, config.validation)
    private var number: String? = null
    private var numberReads = 0
    private var firstNumber = 0L
    private var lastNumber = 0L
    private var confirmedAt: Long? = null
    private var expiry: String? = null
    private var expiryReads = 0
    private var lastExpiry = 0L
    private var lastNumberPreview = 0L
    private var lastExpiryPreview = 0L
    private var warningUntil = 0L
    private var pending: CardScanResult? = null
    private var deliverAt: Long? = null
    private var expired = false

    fun reset() {
        tracker.clear(); number = null; numberReads = 0; confirmedAt = null
        expiry = null; expiryReads = 0; pending = null; deliverAt = null; expired = false
        warningUntil = 0; state = ScannerUiState(); outcome = null
    }
    fun terminate(value: ScanOutcome) { if (outcome == null) { outcome = value; pending = null; tracker.clear(); number = null; expiry = null; state = ScannerUiState() } }
    fun status(key: ScannerMessage, warning: Boolean, now: Long) {
        if (outcome != null) return
        if (warning && state.message != key) warningUntil = now + config.timing.warningDisplayMs
        if (warning || now >= warningUntil) state = state.copy(message = key, warningVisible = warning)
    }
    fun confirm(now: Long) { pending?.let { if (deliverAt == null) deliverAt = now + config.timing.successDisplayMs }; tick(now) }

    fun frame(lines: List<CardTextExtractor.Line>, now: Long, year: Int, month: Int) {
        if (outcome != null || pending != null) return
        val t = config.timing
        var c = CardTextExtractor.extract(lines, year, month, config.validation)
        // A new conflicting candidate invalidates expiry evidence from the previous card.
        if (c.number != null && c.number != number) {
            number = c.number; numberReads = 0; firstNumber = now; confirmedAt = null
            expiry = null; expiryReads = 0; tracker.clear()
        }
        if (c.number == null && (c.detectedNumber?.length ?: 0) >= 15) tracker.clear()
        if (c.number == null && confirmedAt == null && (c.detectedNumber?.length ?: 0) < 15) {
            tracker.observe(lines, now)?.let { c = c.copy(number = it, detectedNumber = it, invalidNumber = false, unsupportedNetwork = false) }
        }
        c.detectedNumber?.let { state = state.copy(numberPreview = displayNumber(it)); lastNumberPreview = now }
        if (c.detectedNumber == null && number == null && config.partial.previewVisible) {
            tracker.bestFragment?.let { state = state.copy(numberPreview = displayNumber(it)?.let { value -> config.text(ScannerMessage.PARTIAL_PREFIX) + " " + value + " …" }); lastNumberPreview = now }
        }
        if (config.validation.expiryRequirement != ExpiryRequirement.DISABLED && c.detectedExpiry != null) {
            state = state.copy(expiryPreview = c.detectedExpiry, expiryPreviewInvalid = c.expiryIssue != CardTextExtractor.EXPIRY_OK)
            lastExpiryPreview = now
        }
        if (c.number != null) {
            if (number != c.number || (confirmedAt == null && now - lastNumber > t.confirmationWindowMs)) {
                number = c.number; numberReads = 0; firstNumber = now; expiry = null; expiryReads = 0
            }
            numberReads++; lastNumber = now
            if (numberReads >= config.recognition.numberConfirmationReads && now - firstNumber >= t.minimumStableDurationMs && confirmedAt == null) confirmedAt = now
        }
        if (number != null && config.validation.expiryRequirement != ExpiryRequirement.DISABLED) {
            if (c.expiry != null) {
                expiryReads = if (c.expiry == expiry && now - lastExpiry <= t.confirmationWindowMs) expiryReads + 1 else 1
                expiry = c.expiry; lastExpiry = now
                val parts = c.expiry!!.split("/").map { it.toInt() }
                expired = 2000 + parts[1] < year || (2000 + parts[1] == year && parts[0] < month)
                state = state.copy(expiryPreviewInvalid = expired)
            } else if (c.expiryIssue != CardTextExtractor.EXPIRY_OK || now - lastExpiry > t.candidateResetDelayMs) { expiry = null; expiryReads = 0 }
        }
        when {
            c.unsupportedNetwork -> status(ScannerMessage.UNSUPPORTED_BRAND, true, now)
            c.invalidNumber && tracker.bestFragment == null -> status(ScannerMessage.INVALID_NUMBER, true, now)
            config.validation.expiryRequirement != ExpiryRequirement.DISABLED && c.expiryIssue != CardTextExtractor.EXPIRY_OK -> status(when (c.expiryIssue) {
                CardTextExtractor.EXPIRY_EXPIRED -> ScannerMessage.EXPIRED
                CardTextExtractor.EXPIRY_AMBIGUOUS -> ScannerMessage.AMBIGUOUS_EXPIRY
                else -> ScannerMessage.INVALID_EXPIRY
            }, true, now)
            confirmedAt != null -> status(if (expiry == null) ScannerMessage.LOOKING_FOR_EXPIRY else ScannerMessage.CONFIRMING_EXPIRY, false, now)
            number != null -> status(ScannerMessage.CONFIRMING_NUMBER, false, now)
            tracker.bestFragment != null -> status(ScannerMessage.PARTIAL_NUMBER, false, now)
            else -> status(if (c.hasText) ScannerMessage.NO_NUMBER else ScannerMessage.NO_TEXT, false, now)
        }
        if (confirmedAt != null) {
            if (config.validation.expiryRequirement == ExpiryRequirement.DISABLED) prepare(null, now)
            else if (expiry != null && expiryReads >= config.recognition.expiryConfirmationReads) prepare(expiry, now)
        }
        tick(now)
    }
    /** Called by a lifecycle-aware timer even when the camera produces no OCR frames. */
    fun tick(now: Long) {
        if (outcome != null) return
        deliverAt?.let { if (now >= it) pending?.let { result -> terminate(ScanOutcome.Completed(result)) } }
        if (outcome != null) return
        config.timing.sessionTimeoutMs?.let { if (now >= it) { terminate(ScanOutcome.TimedOut); return } }
        if (pending != null) return
        val t = config.timing
        if (number != null && confirmedAt == null && now - lastNumber > t.candidateResetDelayMs) { number = null; numberReads = 0; expiry = null; expiryReads = 0 }
        confirmedAt?.let { at ->
            if (now - at >= t.confirmedNumberRetentionMs) { number = null; confirmedAt = null; expiry = null; expiryReads = 0; tracker.clear(); state = ScannerUiState(); return }
            if (now - at >= t.expiryWaitTimeoutMs && now >= warningUntil) {
                when (config.validation.missingExpiryAction) {
                    MissingExpiryAction.RETURN_NUMBER -> if (config.validation.expiryRequirement == ExpiryRequirement.OPTIONAL) prepare(null, now)
                    MissingExpiryAction.TIMEOUT -> terminate(ScanOutcome.TimedOut)
                    MissingExpiryAction.KEEP_SCANNING -> Unit
                }
            }
        }
        if (now - lastNumberPreview > t.previewRetentionMs) state = state.copy(numberPreview = if (confirmedAt != null) number?.let(::displayNumber) else null)
        if (now - lastExpiryPreview > t.expiryPreviewRetentionMs) state = state.copy(expiryPreview = null)
    }
    private fun prepare(date: String?, now: Long) {
        if (pending != null || number == null) return
        val result = CardScanResult(number!!, date, if (date != null && expired) CardValidationStatus.EXPIRED_REQUIRES_REVIEW else CardValidationStatus.VALID)
        if (validator != null && !validator.invoke(result)) { reset(); status(ScannerMessage.HOST_REJECTED, true, now); return }
        pending = result
        state = state.copy(numberPreview = displayNumber(number!!), expiryPreview = date)
        state = state.copy(readyToConfirm = config.completionMode == CompletionMode.USER_CONFIRMATION || result.validationStatus != CardValidationStatus.VALID,
            message = ScannerMessage.REVIEW, warningVisible = result.validationStatus != CardValidationStatus.VALID)
        if (!state.readyToConfirm) { state = state.copy(message = ScannerMessage.SUCCESS); deliverAt = now + config.timing.successDisplayMs }
    }
    private fun displayNumber(digits: String): String? = when (config.appearance.numberDisplay) {
        NumberDisplay.HIDDEN -> null
        NumberDisplay.LAST_FOUR -> digits.takeLast(4)
        NumberDisplay.MASKED -> "•".repeat((digits.length - 4).coerceAtLeast(0)) + digits.takeLast(4)
        NumberDisplay.FULL -> if (digits.length == 15) digits.take(4) + " " + digits.substring(4,10) + " " + digits.drop(10) else digits.chunked(4).joinToString(" ")
    }
}
