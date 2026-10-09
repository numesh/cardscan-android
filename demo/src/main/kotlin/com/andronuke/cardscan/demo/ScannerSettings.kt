package com.andronuke.cardscan.demo

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import com.andronuke.cardscan.*

/** A small interactive sample of the public API; the configuration guide covers every option. */
@Composable internal fun ScannerSettings(c: CardScannerConfiguration, event: (AddCardEvent) -> Unit) {
    fun update(value: CardScannerConfiguration) { event(AddCardEvent.Configure(value)) }
    AlertDialog(onDismissRequest = { event(AddCardEvent.ShowSettings(false)) },title={Text("Scanner settings")},
        confirmButton={TextButton(onClick={event(AddCardEvent.ShowSettings(false))}) {Text("Done")}},
        text={Column(Modifier.verticalScroll(rememberScrollState())) {
            Row {
                TextButton(onClick={update(CardScannerConfiguration.balanced())}){Text("Balanced")}
                TextButton(onClick={update(CardScannerConfiguration.fast())}){Text("Fast")}
                TextButton(onClick={update(CardScannerConfiguration.strict())}){Text("Strict")}
            }
            SettingToggle("Torch",c.controls.torchVisible){update(c.copy(controls=c.controls.copy(torchVisible=it)))}
            SettingToggle("Tap to focus",c.controls.tapToFocusEnabled){update(c.copy(controls=c.controls.copy(tapToFocusEnabled=it)))}
            SettingToggle("Zoom controls",c.controls.zoomVisible){update(c.copy(controls=c.controls.copy(zoomVisible=it)))}
            SettingToggle("Retry",c.controls.retryVisible){update(c.copy(controls=c.controls.copy(retryVisible=it)))}
            SettingToggle("Require expiry",c.validation.expiryRequirement==ExpiryRequirement.REQUIRED){update(c.copy(validation=c.validation.copy(expiryRequirement=if(it) ExpiryRequirement.REQUIRED else ExpiryRequirement.OPTIONAL)))}
            SettingToggle("Confirm before returning",c.completionMode==CompletionMode.USER_CONFIRMATION){update(c.copy(completionMode=if(it) CompletionMode.USER_CONFIRMATION else CompletionMode.AUTOMATIC))}
            SettingToggle("Mask preview",c.appearance.numberDisplay==NumberDisplay.MASKED){update(c.copy(appearance=c.appearance.copy(numberDisplay=if(it) NumberDisplay.MASKED else NumberDisplay.FULL)))}
            SettingToggle("Merge partial numbers",c.partial.enabled){update(c.copy(partial=c.partial.copy(enabled=it)))}
            Text("Expiry wait: ${c.timing.expiryWaitTimeoutMs / 1000}s")
            Slider(value=c.timing.expiryWaitTimeoutMs.toFloat(),onValueChange={update(c.copy(timing=c.timing.copy(expiryWaitTimeoutMs=it.toLong())))},valueRange=2000f..30000f,steps=27)
        }})
}
@Composable private fun SettingToggle(label:String,value:Boolean,change:(Boolean)->Unit) {
    Row { Text(label,Modifier.weight(1f)); Switch(checked=value,onCheckedChange=change) }
}
