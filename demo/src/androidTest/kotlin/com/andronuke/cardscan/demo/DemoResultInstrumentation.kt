package com.andronuke.cardscan.demo

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import com.andronuke.cardscan.CardScanResult

/** Checks that a completed synthetic scan updates Compose form state. */
class DemoResultInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = Bundle()
        try {
            val activity = startActivitySync(Intent(targetContext, DemoActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DemoActivity
            val viewModel = ViewModelProvider(activity)[DemoViewModel::class.java]
            runOnMainSync {
                viewModel.onEvent(AddCardEvent.ScanCompleted(CardScanResult("4111111111111111", "12/30")))
            }
            check(viewModel.uiState.value.cardNumber == "4111 1111 1111 1111") { "card number not populated" }
            check(viewModel.uiState.value.month == "12") { "month not populated" }
            check(viewModel.uiState.value.year == "30") { "year not populated" }
            runOnMainSync { viewModel.onEvent(AddCardEvent.ScanCompleted(null)) }
            check(viewModel.uiState.value.month == "12") { "cancelled scan changed form" }
            activity.finish()
            report.putString(REPORT_KEY_STREAMRESULT, "PASS: scanner result fills Compose Add card form")
            finish(0, report)
        } catch (error: Throwable) {
            report.putString(REPORT_KEY_STREAMRESULT,
                "FAIL: ${error.javaClass.simpleName} ${error.message}")
            finish(1, report)
        }
    }
}
