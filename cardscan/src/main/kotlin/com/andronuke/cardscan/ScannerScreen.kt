package com.andronuke.cardscan

import android.graphics.RectF
import android.view.MotionEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView

/** Optional replacements receive state and typed actions; camera ownership stays with the library. */
class ScannerSlots(
    val header: (@Composable (ScannerUiState, (ScannerAction) -> Unit) -> Unit)? = null,
    val controls: (@Composable (ScannerUiState, (ScannerAction) -> Unit) -> Unit)? = null,
    val status: (@Composable (ScannerUiState) -> Unit)? = null,
    val detectedValues: (@Composable (ScannerUiState) -> Unit)? = null
)
internal fun scannerGuide(width: Float, height: Float, density: Float, style: ScannerAppearance): RectF {
    val w = minOf(width * style.guideWidthFraction, (height * 0.40f) * style.guideAspectRatio).coerceAtLeast(0f)
    val h = w / style.guideAspectRatio
    val top = minOf(height * style.guideVerticalPosition, (height - h).coerceAtLeast(0f))
    return RectF((width-w)/2, top, (width+w)/2, top+h)
}

@Composable
internal fun ScannerScreen(state: ScannerUiState, config: CardScannerConfiguration,
    action: (ScannerAction) -> Unit, onPreview: (PreviewView) -> Unit, focus: (ScanPoint) -> Unit,
    slots: ScannerSlots, modifier: Modifier = Modifier) {
    val a = config.appearance
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize().background(Color(a.backgroundColor)).statusBarsPadding().navigationBarsPadding()) {
        val guide = scannerGuide(maxWidth.value, maxHeight.value, 1f, a)
        AndroidView(factory = { context -> PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            @Suppress("ClickableViewAccessibility")
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP && config.controls.tapToFocusEnabled) {
                    focus(ScanPoint(event.x, event.y)); view.performClick()
                }
                false
            }
            onPreview(this)
        } }, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val rect = scannerGuide(size.width, size.height, density.density, a)
            val shade = Color(a.backgroundColor).copy(alpha = a.shadeOpacity)
            drawRect(shade, size = Size(size.width, rect.top))
            drawRect(shade, topLeft = Offset(0f,rect.bottom), size = Size(size.width,(size.height-rect.bottom).coerceAtLeast(0f)))
            drawRect(shade, topLeft = Offset(0f,rect.top), size = Size(rect.left,rect.height()))
            drawRect(shade, topLeft = Offset(rect.right,rect.top), size = Size((size.width-rect.right).coerceAtLeast(0f),rect.height()))
            if (a.guideVisible && a.guideStyle == GuideStyle.CORNERS) {
                val segment = minOf(28.dp.toPx(), rect.width()/4, rect.height()/4)
                listOf(Offset(rect.left,rect.top) to Offset(1f,1f), Offset(rect.right,rect.top) to Offset(-1f,1f),
                    Offset(rect.left,rect.bottom) to Offset(1f,-1f), Offset(rect.right,rect.bottom) to Offset(-1f,-1f)).forEach { (corner,direction) ->
                    drawLine(Color(a.guideColor),corner,corner+Offset(segment*direction.x,0f),a.guideStrokeDp.dp.toPx())
                    drawLine(Color(a.guideColor),corner,corner+Offset(0f,segment*direction.y),a.guideStrokeDp.dp.toPx())
                }
            }
            if (a.guideVisible && a.guideStyle == GuideStyle.OUTLINE) drawRoundRect(Color(a.guideColor), Offset(rect.left,rect.top),Size(rect.width(),rect.height()),
                androidx.compose.ui.geometry.CornerRadius(a.cornerRadiusDp.dp.toPx()), style = Stroke(a.guideStrokeDp.dp.toPx()))
            if (config.controls.focusMarkerVisible) state.focusPoint?.let { drawCircle(Color(a.focusColor), 24.dp.toPx(),Offset(it.x,it.y),style=Stroke(2.dp.toPx())) }
        }
        Column(Modifier.fillMaxWidth().padding(a.spacingDp.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (slots.header != null) slots.header.invoke(state,action) else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (config.controls.closeVisible) ScannerButton(config.text(ScannerMessage.CLOSE), a.closeIcon, "×", config) { action(ScannerAction.CLOSE) }
                    Text(config.text(ScannerMessage.TITLE), color=Color(a.textColor),fontSize=a.titleSizeSp.sp)
                }
                Text(config.text(ScannerMessage.INSTRUCTIONS),color=Color(a.textColor),fontSize=a.bodySizeSp.sp,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth())
            }
            if (config.controls.placement == ControlPlacement.TOP) Controls(state,config,action,slots)
        }
        Column(Modifier.offset(y=(guide.bottom - 104).coerceAtLeast(guide.top).dp).fillMaxWidth().padding(a.spacingDp.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
            if (slots.detectedValues != null) slots.detectedValues.invoke(state) else {
                state.numberPreview?.let { PreviewChip(it,config,false) }
                if (a.expiryPreviewVisible) state.expiryPreview?.let { PreviewChip(config.text(ScannerMessage.EXPIRY_PREFIX)+" "+it,config,state.expiryPreviewInvalid) }
            }
        }
        Box(Modifier.offset(y=guide.bottom.dp).fillMaxWidth().padding(a.spacingDp.dp)) {
            if (slots.status != null) slots.status.invoke(state) else Text(config.text(state.message), color=Color(a.textColor),fontSize=a.bodySizeSp.sp,textAlign=TextAlign.Center,
                modifier=Modifier.fillMaxWidth().clip(RoundedCornerShape(a.cornerRadiusDp.dp)).background(Color(if(state.warningVisible) a.warningColor else if(state.message==ScannerMessage.SUCCESS) a.successColor else a.backgroundColor)).padding(8.dp)
                    .semantics { if(a.announceStatus) liveRegion=LiveRegionMode.Polite })
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(a.spacingDp.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if (config.controls.placement == ControlPlacement.BOTTOM) Controls(state,config,action,slots)
            if (state.readyToConfirm) ScannerButton(config.text(ScannerMessage.CONFIRM),0,null,config) { action(ScannerAction.CONFIRM) }
            if (config.controls.manualEntryVisible) ScannerButton(config.text(ScannerMessage.MANUAL),a.manualIcon,null,config) { action(ScannerAction.MANUAL_ENTRY) }
            if(a.privacyVisible) Row(verticalAlignment=Alignment.CenterVertically) {
                Icon(painterResource(a.privacyIcon.takeIf { it!=0 } ?: R.drawable.ic_privacy),null,tint=Color(a.textColor),modifier=Modifier.size(18.dp))
                Text(config.text(ScannerMessage.PRIVACY),color=Color(a.textColor),fontSize=12.sp)
            }
        }
    }
}
@Composable private fun Controls(state:ScannerUiState,c:CardScannerConfiguration,action:(ScannerAction)->Unit,slots:ScannerSlots) {
    if(slots.controls!=null) { slots.controls.invoke(state,action); return }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
        if(c.controls.torchVisible && (!c.controls.hideTorchWhenUnavailable || state.torchAvailable)) ScannerButton(
            c.text(if(state.torchEnabled) ScannerMessage.TORCH_OFF else ScannerMessage.TORCH_ON),
            if(state.torchEnabled) c.appearance.torchOnIcon.takeIf { it!=0 } ?: R.drawable.ic_flashlight else c.appearance.torchOffIcon.takeIf { it!=0 } ?: R.drawable.ic_flashlight,null,c,true) { action(ScannerAction.TOGGLE_TORCH) }
        if(c.controls.retryVisible) ScannerButton(c.text(ScannerMessage.RETRY),0,null,c) { action(ScannerAction.RETRY) }
        if(c.controls.zoomVisible) {
            ScannerButton(c.text(ScannerMessage.ZOOM_OUT),0,"−",c) { action(ScannerAction.ZOOM_OUT) }
            ScannerButton(c.text(ScannerMessage.ZOOM_IN),0,"+",c) { action(ScannerAction.ZOOM_IN) }
        }
    }
}
@Composable private fun ScannerButton(label:String,icon:Int,glyph:String?,c:CardScannerConfiguration,large:Boolean=false,onClick:()->Unit) {
    val a=c.appearance
    TextButton(onClick=onClick,modifier=Modifier.defaultMinSize(minWidth=48.dp,minHeight=48.dp).then(if(large) Modifier.size(a.buttonSizeDp.dp) else Modifier)
        .semantics { contentDescription=label }, colors=ButtonDefaults.textButtonColors(contentColor=Color(a.textColor),containerColor=Color(a.buttonColor)),shape=if(large) RoundedCornerShape(50) else RoundedCornerShape(a.cornerRadiusDp.dp)) {
        if(icon!=0) Icon(painterResource(icon),null,modifier=Modifier.size(a.iconSizeDp.dp))
        else Text(glyph?:label,fontSize=(if(glyph!=null) a.iconSizeDp else a.buttonSizeSp).sp,textAlign=TextAlign.Center)
    }
}
@Composable private fun PreviewChip(value:String,c:CardScannerConfiguration,invalid:Boolean) {
    Text(value,color=Color(c.appearance.textColor),fontSize=c.appearance.previewSizeSp.sp,modifier=Modifier
        .clip(RoundedCornerShape(c.appearance.cornerRadiusDp.dp)).background(Color(if(invalid) c.appearance.warningColor else c.appearance.previewColor)).padding(6.dp))
}
