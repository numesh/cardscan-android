package com.andronuke.cardscan

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** In-memory synthetic OCR, parsing, and partial-scan checks. */
class ScannerPipelineInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = Bundle()
        try {
            checkPipeline()
            report.putString(REPORT_KEY_STREAMRESULT, "PASS: bundled OCR and card parser")
            finish(0, report)
        } catch (error: Throwable) {
            report.putString(REPORT_KEY_STREAMRESULT,
                "FAIL: ${error.javaClass.simpleName} ${error.message}")
            finish(1, report)
        }
    }

    private fun checkPipeline() {
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(3).apply {
            color = Color.BLACK
            textSize = 72f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        canvas.drawText("4111 1111 1111 1111", 90f, 310f, paint)
        val today = Calendar.getInstance()
        val futureYear = today.get(Calendar.YEAR) + 2
        val expiry = "12/%02d".format(Locale.US, futureYear % 100)
        paint.textSize = 60f
        canvas.drawText("VALID THRU $expiry", 90f, 430f, paint)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                20, TimeUnit.SECONDS)
            val lines = text.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    line.boundingBox?.let { CardTextExtractor.Line(line.text, it.top, it.left) }
                }
            }
            val result = CardTextExtractor.extract(lines, today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1)
            check(result.number == "4111111111111111") { "card number" }
            check(result.detectedNumber == result.number) { "preview number" }
            check(result.expiry == expiry) { "expiry" }
            val data = Intent().putExtra(CardScanContract.EXTRA_CARD_NUMBER, result.number)
                .putExtra(CardScanContract.EXTRA_EXPIRY, result.expiry)
            val parsed = CardScanContract.parse(Activity.RESULT_OK, data)
            check(parsed?.cardNumber == result.number && parsed.expiry == result.expiry) {
                "scanner result handoff"
            }

            assertSupported("5555 5555 5555 4444", today, "Mastercard 51-55")
            assertSupported("2223 0000 4840 0011", today, "Mastercard 2221-2720")
            assertSupported("3782 822463 10005", today, "Amex")
            assertPartialMerge("4539148803436467", "Visa")
            assertPartialMerge("2223000048400011", "Mastercard")
            assertPartialMerge("378282246310005", "Amex")

            val rejected = extract(listOf("4691 0000 1234 5678"), today)
            check(rejected.number == null && rejected.invalidNumber &&
                rejected.detectedNumber == "4691000012345678") { "invalid sample number" }
            val splitDate = listOf(
                CardTextExtractor.Line("4111 1111 1111 1111", 100, 0),
                CardTextExtractor.Line("VALID THRU", 600, 0),
                CardTextExtractor.Line("12", 602, 130),
                CardTextExtractor.Line("/", 599, 165),
                CardTextExtractor.Line("%02d".format(Locale.US, futureYear % 100), 601, 190)
            )
            check(CardTextExtractor.extract(splitDate, today.get(Calendar.YEAR),
                today.get(Calendar.MONTH) + 1).expiry == expiry) { "split expiry" }
            val separate = extract(listOf("12 %02d".format(Locale.US, futureYear % 100)), today)
            check(separate.number == null && separate.expiry == expiry) { "expiry on separate frame" }
            val expired = extract(listOf("4111 1111 1111 1111", "VALID THRU 08/2019"), today)
            check(expired.expiry == null && expired.expiryIssue == CardTextExtractor.EXPIRY_EXPIRED &&
                expired.detectedExpiry == "08/19") { "expired date warning" }
            val invalidDate = extract(listOf("4111 1111 1111 1111", "EXP 13/29"), today)
            check(invalidDate.expiry == null && invalidDate.expiryIssue == CardTextExtractor.EXPIRY_INVALID &&
                invalidDate.detectedExpiry == "13/29") { "invalid date warning" }
            val unsupported = extract(listOf("6011 1111 1111 1117"), today)
            check(unsupported.number == null && unsupported.unsupportedNetwork) { "unsupported card warning" }
            checkScannerViewModel(today, expiry)
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    private fun extract(texts: List<String>, today: Calendar): CardTextExtractor.Scan =
        CardTextExtractor.extract(texts.mapIndexed { index, text ->
            CardTextExtractor.Line(text, index * 100, 0)
        }, today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1)

    private fun assertSupported(printedNumber: String, today: Calendar, brand: String) {
        check(extract(listOf(printedNumber), today).number == printedNumber.replace(" ", "")) {
            "$brand should be accepted"
        }
    }

    private fun assertPartialMerge(number: String, brand: String) {
        val tracker = PartialPanTracker()
        val left = listOf(CardTextExtractor.Line(number.substring(0, 11), 100, 0))
        val right = listOf(CardTextExtractor.Line(number.substring(5), 100, 0))
        check(tracker.observe(left, 1000) == null && tracker.observe(left, 1100) == null &&
            tracker.observe(right, 1200) == null) { "$brand merged without repeated reads" }
        check(tracker.observe(right, 1300) == number) { "$brand partial merge" }
        tracker.clear()
        check(tracker.bestFragment == null) { "partial data not cleared" }
        tracker.observe(left, 10000)
        tracker.observe(left, 10100)
        tracker.observe(right, 17000)
        check(tracker.observe(right, 17100) == null) { "fragments merged across scan timeout" }
    }

    private fun checkScannerViewModel(today: Calendar, expiry: String) {
        val engine = ScanEngine(CardScannerConfiguration())
        val lines = listOf(CardTextExtractor.Line("4111 1111 1111 1111",100,0), CardTextExtractor.Line("EXP $expiry",400,0))
        repeat(3) { engine.frame(lines,it*250L,today.get(Calendar.YEAR),today.get(Calendar.MONTH)+1) }
        val result = (engine.outcome as? ScanOutcome.Completed)?.result
        check(result?.cardNumber == "4111111111111111" && result.expiry == expiry)
        val invalid = ScanEngine(CardScannerConfiguration())
        invalid.frame(listOf(CardTextExtractor.Line("EXP 13/29",400,0)),0,today.get(Calendar.YEAR),today.get(Calendar.MONTH)+1)
        check(invalid.state.warningVisible && invalid.state.message == ScannerMessage.INVALID_EXPIRY)
    }
}
