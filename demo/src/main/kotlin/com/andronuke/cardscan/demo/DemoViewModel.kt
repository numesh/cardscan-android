package com.andronuke.cardscan.demo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andronuke.cardscan.CardScanResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Values rendered by the Add Card screen. Nothing here is written to SavedStateHandle or disk. */
internal data class AddCardUiState(
    val cardNumber: String = "",
    val month: String = "",
    val year: String = "",
    val cvv: String = "",
    val nickname: String = "",
    val scanNote: String? = null
)

/** User actions and external results are the only inputs to the demo reducer. */
internal sealed interface AddCardEvent {
    data class CardNumberChanged(val value: String) : AddCardEvent
    data class MonthChanged(val value: String) : AddCardEvent
    data class YearChanged(val value: String) : AddCardEvent
    data class CvvChanged(val value: String) : AddCardEvent
    data class NicknameChanged(val value: String) : AddCardEvent
    data class ScanCompleted(val result: CardScanResult?) : AddCardEvent
    data object ScanRequested : AddCardEvent
    data object BackPressed : AddCardEvent
}

/** One-time navigation commands stay separate from persistent form state. */
internal sealed interface AddCardEffect {
    data object LaunchScanner : AddCardEffect
    data object Close : AddCardEffect
}

internal class DemoViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(AddCardUiState())
    val uiState: StateFlow<AddCardUiState> = _uiState.asStateFlow()

    private val effectChannel = Channel<AddCardEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    fun onEvent(event: AddCardEvent) {
        when (event) {
            is AddCardEvent.CardNumberChanged -> _uiState.update { it.copy(cardNumber = event.value.take(23)) }
            is AddCardEvent.MonthChanged -> _uiState.update { it.copy(month = event.value.take(2)) }
            is AddCardEvent.YearChanged -> _uiState.update { it.copy(year = event.value.take(2)) }
            is AddCardEvent.CvvChanged -> _uiState.update { it.copy(cvv = event.value.take(4)) }
            is AddCardEvent.NicknameChanged -> _uiState.update { it.copy(nickname = event.value.take(48)) }
            is AddCardEvent.ScanCompleted -> applyScanResult(event.result)
            AddCardEvent.ScanRequested -> emit(AddCardEffect.LaunchScanner)
            AddCardEvent.BackPressed -> emit(AddCardEffect.Close)
        }
    }

    private fun applyScanResult(result: CardScanResult?) {
        if (result == null) return // Cancelled scans must leave a user's existing edits intact.
        val expiry = result.expiry?.takeIf { it.matches(Regex("[0-9]{2}/[0-9]{2}")) }
        _uiState.update { current ->
            current.copy(
                cardNumber = formatNumber(result.cardNumber),
                month = expiry?.substring(0, 2).orEmpty(),
                year = expiry?.substring(3, 5).orEmpty(),
                scanNote = if (expiry == null) "Card number scanned. Enter the expiry date manually."
                    else "Card details scanned. Check them before continuing."
            )
        }
    }

    private fun emit(effect: AddCardEffect) {
        viewModelScope.launch { effectChannel.send(effect) }
    }

    private fun formatNumber(digits: String): String = buildString {
        digits.forEachIndexed { index, char ->
            val groupBoundary = if (digits.length == 15) index == 4 || index == 10 else index % 4 == 0
            if (index > 0 && groupBoundary) append(' ')
            append(char)
        }
    }
}
