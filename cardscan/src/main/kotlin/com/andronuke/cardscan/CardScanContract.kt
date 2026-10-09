package com.andronuke.cardscan

import android.app.Activity
import android.content.Context
import android.content.Intent

/** Public entry point and result parser for the card scanner. */
object CardScanContract {
    const val EXTRA_CARD_NUMBER = "com.andronuke.cardscan.CARD_NUMBER"
    const val EXTRA_EXPIRY = "com.andronuke.cardscan.EXPIRY"

    @JvmStatic
    fun createIntent(context: Context): Intent = Intent(context, CardScannerActivity::class.java)

    @JvmStatic
    @Suppress("DEPRECATION")
    fun start(host: Activity, requestCode: Int) {
        host.startActivityForResult(createIntent(host), requestCode)
    }

    @JvmStatic
    fun parse(resultCode: Int, data: Intent?): CardScanResult? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        val number = data.getStringExtra(EXTRA_CARD_NUMBER)?.takeIf { it.isNotEmpty() } ?: return null
        return CardScanResult(number, data.getStringExtra(EXTRA_EXPIRY))
    }
}
