package com.andronuke.cardscan

import android.app.Activity
import android.content.Context
import android.content.Intent

/** Public entry point and result parser for the card scanner. */
object CardScanContract {
    const val EXTRA_CARD_NUMBER = "com.andronuke.cardscan.CARD_NUMBER"
    const val EXTRA_EXPIRY = "com.andronuke.cardscan.EXPIRY"

    internal const val EXTRA_CONFIGURATION = "com.andronuke.cardscan.CONFIGURATION"
    internal const val EXTRA_OUTCOME = "com.andronuke.cardscan.OUTCOME"
    internal const val EXTRA_VALIDATION = "com.andronuke.cardscan.VALIDATION"

    @JvmStatic @JvmOverloads
    fun createIntent(context: Context, configuration: CardScannerConfiguration = CardScannerConfiguration()): Intent {
        configuration.validate()
        return Intent(context, CardScannerActivity::class.java).putExtra(EXTRA_CONFIGURATION, configuration.snapshot())
    }

    @JvmStatic
    fun parseOutcome(resultCode: Int, data: Intent?): ScanOutcome {
        parse(resultCode, data)?.let { return ScanOutcome.Completed(it) }
        return when (data?.getStringExtra(EXTRA_OUTCOME)) {
            "manual" -> ScanOutcome.ManualEntryRequested
            "timeout" -> ScanOutcome.TimedOut
            ScannerFailure.CAMERA_UNAVAILABLE.name -> ScanOutcome.Failed(ScannerFailure.CAMERA_UNAVAILABLE)
            ScannerFailure.INVALID_CONFIGURATION.name -> ScanOutcome.Failed(ScannerFailure.INVALID_CONFIGURATION)
            CancelReason.USER.name -> ScanOutcome.Cancelled()
            else -> ScanOutcome.Cancelled(CancelReason.SYSTEM_BACK)
        }
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    fun start(host: Activity, requestCode: Int) {
        host.startActivityForResult(createIntent(host), requestCode)
    }

    @JvmStatic
    fun parse(resultCode: Int, data: Intent?): CardScanResult? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        val number = data.getStringExtra(EXTRA_CARD_NUMBER)?.takeIf { it.isNotEmpty() } ?: return null
        return CardScanResult(number, data.getStringExtra(EXTRA_EXPIRY),
            CardValidationStatus.entries.firstOrNull { it.name == data.getStringExtra(EXTRA_VALIDATION) } ?: CardValidationStatus.VALID)
    }
}
