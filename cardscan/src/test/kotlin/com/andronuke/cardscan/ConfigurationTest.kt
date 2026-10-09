package com.andronuke.cardscan

import org.junit.Assert.*
import org.junit.Test

class ConfigurationTest {
    private fun lines(number: String = "4111 1111 1111 1111", expiry: String? = "12/28") = buildList {
        add(CardTextExtractor.Line(number,100,0)); expiry?.let { add(CardTextExtractor.Line("EXP $it",400,0)) }
    }
    private fun read(engine: ScanEngine, lines: List<CardTextExtractor.Line> = lines(), count: Int = 3) {
        repeat(count) { engine.frame(lines,it*250L,2026,10) }
    }
    @Test fun defaultAndExactlyOnce() {
        val e=ScanEngine(CardScannerConfiguration()); read(e)
        val result=(e.outcome as ScanOutcome.Completed).result
        assertEquals("4111111111111111",result.cardNumber); assertEquals("12/28",result.expiry)
        e.frame(lines("5555 5555 5555 4444"),1000,2026,10)
        assertSame(result,(e.outcome as ScanOutcome.Completed).result)
    }
    @Test fun requiredExpiryNeverReturnsNumberOnly() {
        val e=ScanEngine(CardScannerConfiguration(validation=ValidationOptions(expiryRequirement=ExpiryRequirement.REQUIRED)))
        read(e,lines(expiry=null)); e.tick(20000); assertNull(e.outcome)
    }
    @Test fun optionalFallbackRunsWithoutFrames() {
        val e=ScanEngine(CardScannerConfiguration()); read(e,lines(expiry=null)); e.tick(13000); e.tick(13050)
        assertNull((e.outcome as ScanOutcome.Completed).result.expiry)
    }
    @Test fun timeoutWithoutFrames() {
        val e=ScanEngine(CardScannerConfiguration(timing=ScannerTiming(sessionTimeoutMs=1000)))
        e.tick(999); assertNull(e.outcome); e.tick(1000); assertEquals(ScanOutcome.TimedOut,e.outcome)
    }
    @Test fun numberOnlyAndUserConfirmation() {
        val e=ScanEngine(CardScannerConfiguration(validation=ValidationOptions(expiryRequirement=ExpiryRequirement.DISABLED),completionMode=CompletionMode.USER_CONFIRMATION))
        read(e); assertTrue(e.state.readyToConfirm); assertNull(e.outcome); e.confirm(750)
        assertNull((e.outcome as ScanOutcome.Completed).result.expiry)
    }
    @Test fun expiredAlwaysRequiresReview() {
        val e=ScanEngine(CardScannerConfiguration(validation=ValidationOptions(expiredCardPolicy=ExpiredCardPolicy.RETURN_FOR_REVIEW)))
        read(e,lines(expiry="01/20")); assertTrue(e.state.readyToConfirm); assertNull(e.outcome); e.confirm(800)
        assertEquals(CardValidationStatus.EXPIRED_REQUIRES_REVIEW,(e.outcome as ScanOutcome.Completed).result.validationStatus)
    }
    @Test fun brandAndFormatPolicies() {
        val options=ValidationOptions(supportedBrands=setOf(CardBrand.AMEX),expiryFormats=setOf(ExpiryFormat.LABELLED_COMPACT))
        val c=CardTextExtractor.extract(lines(),2026,10,options)
        assertNull(c.number); assertTrue(c.unsupportedNetwork); assertNull(c.expiry)
    }
    @Test fun customValidationAndMasking() {
        val e=ScanEngine(CardScannerConfiguration(appearance=ScannerAppearance(numberDisplay=NumberDisplay.LAST_FOUR))) { false }
        e.frame(lines(),0,2026,10); assertEquals("1111",e.state.numberPreview)
        read(e); assertNull(e.outcome); assertEquals(ScannerMessage.HOST_REJECTED,e.state.message)
    }
    @Test fun independentFragmentPolicy() {
        val tracker=PartialPanTracker(PartialNumberOptions(readsPerFragment=3))
        val left=listOf(CardTextExtractor.Line("45391488034",100,0)); val right=listOf(CardTextExtractor.Line("48803436467",100,0))
        repeat(3) { assertNull(tracker.observe(left,it*100L)) }
        repeat(2) { assertNull(tracker.observe(right,300+it*100L)) }
        assertEquals("4539148803436467",tracker.observe(right,500))
    }
    @Test fun invalidConfigurationFailsEarly() {
        try { CardScannerConfiguration(partial=PartialNumberOptions(minimumOverlap=1)).validate(); fail("Expected rejection") } catch (_:IllegalArgumentException) {}
        try { CardScannerConfiguration(recognition=RecognitionOptions(minimumConfidence=Float.NaN)).validate(); fail("Expected rejection") } catch (_:IllegalArgumentException) {}
    }
    @Test fun interruptedReadsDoNotCountAndRetryClearsState() {
        val e=ScanEngine(CardScannerConfiguration()); e.frame(lines(),0,2026,10); e.frame(lines(),4000,2026,10)
        assertNull(e.outcome); e.reset(); assertNull(e.state.numberPreview); assertNull(e.outcome)
    }
    @Test fun newCardCannotInheritExpiry() {
        val e=ScanEngine(CardScannerConfiguration(validation=ValidationOptions(expiryRequirement=ExpiryRequirement.REQUIRED)))
        e.frame(lines(),0,2026,10); e.frame(lines(),250,2026,10)
        repeat(3) { e.frame(lines("5555 5555 5555 4444",null),500+it*250L,2026,10) }
        assertNull(e.outcome)
    }
    @Test fun configuredMinimumStableDuration() {
        val e=ScanEngine(CardScannerConfiguration(timing=ScannerTiming(minimumStableDurationMs=1000)))
        read(e); assertNull(e.outcome); e.frame(lines(),1000,2026,10); assertTrue(e.outcome is ScanOutcome.Completed)
    }
    @Test fun mergedCandidateNeedsConfirmation() {
        val e=ScanEngine(CardScannerConfiguration(validation=ValidationOptions(expiryRequirement=ExpiryRequirement.DISABLED)))
        val left=lines("45391488034",null); val right=lines("48803436467",null)
        e.frame(left,0,2026,10); e.frame(left,100,2026,10); e.frame(right,200,2026,10); e.frame(right,300,2026,10)
        assertNull(e.outcome)
        e.frame(right,400,2026,10); e.frame(right,500,2026,10)
        assertEquals("4539148803436467",(e.outcome as ScanOutcome.Completed).result.cardNumber)
    }
    @Test fun configurationSerializationAndSnapshot() {
        val brands=mutableSetOf(CardBrand.VISA)
        val config=CardScannerConfiguration(validation=ValidationOptions(supportedBrands=brands),messages=mapOf(ScannerMessage.TITLE to "My scanner")).snapshot()
        brands.clear(); assertEquals(setOf(CardBrand.VISA),config.validation.supportedBrands)
        val bytes=java.io.ByteArrayOutputStream()
        java.io.ObjectOutputStream(bytes).use { it.writeObject(config) }
        val restored=java.io.ObjectInputStream(java.io.ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() as CardScannerConfiguration }
        restored.validate(); assertEquals(config,restored)
    }
    @Test fun invalidExpiryClearsEarlierDateEvidence() {
        val e=ScanEngine(CardScannerConfiguration(validation=ValidationOptions(expiryRequirement=ExpiryRequirement.REQUIRED)))
        e.frame(lines(),0,2026,10); e.frame(lines(),250,2026,10); e.frame(lines(expiry="13/29"),500,2026,10)
        assertNull(e.outcome); assertTrue(e.state.warningVisible)
    }
    @Test fun successDelayAndCancellation() {
        val e=ScanEngine(CardScannerConfiguration(timing=ScannerTiming(successDisplayMs=1000)))
        read(e); assertNull(e.outcome); e.tick(1499); assertNull(e.outcome)
        e.terminate(ScanOutcome.Cancelled()); e.tick(1500); assertTrue(e.outcome is ScanOutcome.Cancelled)
    }
}
