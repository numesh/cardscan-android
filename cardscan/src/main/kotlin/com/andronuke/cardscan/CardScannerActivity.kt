package com.andronuke.cardscan

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** Serializable launch adapter. Embedded integrations use CardScanner directly. */
class CardScannerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val config = try {
            @Suppress("DEPRECATION")
            (intent.getSerializableExtra(CardScanContract.EXTRA_CONFIGURATION) as? CardScannerConfiguration ?: CardScannerConfiguration()).also { it.validate() }
        } catch (_: Exception) {
            finishWith(ScanOutcome.Failed(ScannerFailure.INVALID_CONFIGURATION)); return
        }
        setContent { CardScanner(configuration = config, onOutcome = ::finishWith) }
    }
    private fun finishWith(outcome: ScanOutcome) {
        val data = Intent().putExtra(CardScanContract.EXTRA_OUTCOME, when (outcome) {
            is ScanOutcome.Completed -> "completed"
            ScanOutcome.ManualEntryRequested -> "manual"
            ScanOutcome.TimedOut -> "timeout"
            is ScanOutcome.Failed -> outcome.error.name
            is ScanOutcome.Cancelled -> outcome.reason.name
        })
        if (outcome is ScanOutcome.Completed) {
            data.putExtra(CardScanContract.EXTRA_CARD_NUMBER, outcome.result.cardNumber)
            data.putExtra(CardScanContract.EXTRA_EXPIRY, outcome.result.expiry)
            data.putExtra(CardScanContract.EXTRA_VALIDATION, outcome.result.validationStatus.name)
        }
        setResult(if (outcome is ScanOutcome.Completed) Activity.RESULT_OK else Activity.RESULT_CANCELED, data)
        finish()
    }
}
