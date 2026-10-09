package com.andronuke.cardscan

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Camera-independent state owner; the host drives the active-time clock. */
internal class ScannerViewModel(config: CardScannerConfiguration = CardScannerConfiguration(), validator: ((CardScanResult) -> Boolean)? = null) : ViewModel() {
    val engine = ScanEngine(config, validator)
    private val mutable = MutableStateFlow(engine.state)
    val state = mutable.asStateFlow()
    fun refresh() { mutable.value = engine.state.copy(torchEnabled = mutable.value.torchEnabled, torchAvailable = mutable.value.torchAvailable, focusPoint = mutable.value.focusPoint) }
    fun hardware(torch: Boolean = mutable.value.torchEnabled, available: Boolean = mutable.value.torchAvailable, focus: ScanPoint? = mutable.value.focusPoint) {
        mutable.value = mutable.value.copy(torchEnabled = torch, torchAvailable = available, focusPoint = focus)
    }
    override fun onCleared() { engine.reset(); mutable.value = ScannerUiState() }
}
