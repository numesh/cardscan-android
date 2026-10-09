package com.andronuke.cardscan

import android.view.MotionEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/** Compose view: renders scanner state and forwards user input as events. */
@Composable
internal fun ScannerScreen(
    state: ScannerUiState,
    onEvent: (ScannerEvent) -> Unit,
    onPreviewReady: (PreviewView) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black)
            .statusBarsPadding().navigationBarsPadding()
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val guideWidth = maxWidth - 48.dp
            // This 1.5:1 guide leaves room for a physical card and its printed expiry.
            val guideHeight = guideWidth * 0.67f
            val guideTop = minOf(maxHeight * 0.25f, maxHeight - guideHeight - 280.dp)
                .coerceAtLeast(0.dp)

        // CameraX needs a real PreviewView for the stream and its built-in tap-to-focus.
        AndroidView(
                factory = { context ->
                    PreviewView(context).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        @Suppress("ClickableViewAccessibility")
                        setOnTouchListener { view, event ->
                            if (event.actionMasked == MotionEvent.ACTION_UP) {
                                val point = ScanPoint(event.x, event.y)
                                onEvent(ScannerEvent.FocusTapped(point))
                                view.postDelayed({ onEvent(ScannerEvent.FocusCleared(point)) }, 900)
                                view.performClick()
                            }
                            false // PreviewView lets CameraX handle tap-to-focus.
                        }
                        onPreviewReady(this)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            GuideShade(guideTop, guideWidth, guideHeight)
            GuideCorners(guideTop, guideWidth, guideHeight)
            FocusMarker(state.focusPoint)

            Box(Modifier.fillMaxWidth().height(52.dp)) {
                Row(Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clickable { onEvent(ScannerEvent.ClosePressed) }
                        .semantics { contentDescription = "Close scanner" },
                        contentAlignment = Alignment.Center) {
                        CloseIcon()
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("Scan card", color = Color.White, fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold)
                }
            }

            Text("Position your card number and expiry date\ninside the frame.",
                color = Color.White, fontSize = 15.sp, lineHeight = 22.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 70.dp, start = 16.dp, end = 16.dp))

            state.numberPreview?.let { digits ->
                ScanChip(
                    text = digits,
                    modifier = Modifier.offset(y = guideTop + guideHeight - 102.dp)
                        .fillMaxWidth().padding(horizontal = 40.dp).height(44.dp),
                    fontSize = 17
                )
            }
            state.expiryPreview?.let { expiry ->
                ScanChip(
                    text = "EXP $expiry",
                    modifier = Modifier.offset(x = 40.dp, y = guideTop + guideHeight - 52.dp)
                        .size(width = 120.dp, height = 40.dp),
                    fontSize = 16,
                    red = state.expiryPreviewInvalid
                )
            }

            Text(
                state.status,
                color = Color.White,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.offset(y = guideTop + guideHeight + 10.dp)
                    .fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (state.warningVisible) Color(0xFFAE2525) else Color.Transparent)
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            )

            // Flash has its own prominent control below the live card preview.
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 128.dp)
                    .size(72.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = if (state.torchEnabled) 0.27f else 0.13f))
                    .border(2.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                    .clickable { onEvent(ScannerEvent.TorchPressed) }
                    .semantics { contentDescription = if (state.torchEnabled) "Turn torch off" else "Turn torch on" },
                contentAlignment = Alignment.Center
            ) { FlashlightIcon() }

            Box(
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 64.dp)
                    .fillMaxWidth().height(52.dp).clip(RoundedCornerShape(50))
                    .border(2.dp, Color.White, RoundedCornerShape(50))
                    .clickable { onEvent(ScannerEvent.ClosePressed) },
                contentAlignment = Alignment.Center
            ) {
                Text("Enter details manually", color = Color.White,
                    fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
                verticalAlignment = Alignment.CenterVertically) {
                PrivacyShield()
                Spacer(Modifier.width(7.dp))
                Text("Your card image is not saved.", color = Color(0xFFDEE2E8), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun GuideShade(top: Dp, width: Dp, height: Dp) {
    val density = LocalDensity.current
    // Offscreen compositing lets the clear rounded rectangle reveal the camera preview.
    Canvas(Modifier.fillMaxSize().graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
    }) {
        drawRect(Color.Black.copy(alpha = 0.67f))
        val left = with(density) { 24.dp.toPx() }
        val y = with(density) { top.toPx() }
        val w = with(density) { width.toPx() }
        val h = with(density) { height.toPx() }
        drawRoundRect(Color.Transparent, topLeft = Offset(left, y),
            size = ComposeSize(w, h), cornerRadius = CornerRadius(with(density) { 16.dp.toPx() }),
            blendMode = BlendMode.Clear)
        // The reference has a bounded camera band. Keep controls on solid black.
        drawRect(Color.Black, size = ComposeSize(size.width, (y - 28.dp.toPx()).coerceAtLeast(0f)))
        val previewBottom = y + h + 60.dp.toPx()
        if (previewBottom < size.height) {
            drawRect(Color.Black, topLeft = Offset(0f, previewBottom),
                size = ComposeSize(size.width, size.height - previewBottom))
        }
    }
}

/** Four short brackets stay visible even when the camera points at a dark card. */
@Composable
private fun GuideCorners(top: Dp, width: Dp, height: Dp) {
    Canvas(Modifier.fillMaxSize()) {
        val left = 24.dp.toPx()
        val right = left + width.toPx()
        val upper = top.toPx()
        val lower = upper + height.toPx()
        val segment = 28.dp.toPx()
        val stroke = 3.dp.toPx()
        val color = Color(0xFFFFD522)
        listOf(
            Offset(left + segment, upper) to Offset(left, upper),
            Offset(left, upper) to Offset(left, upper + segment),
            Offset(right - segment, upper) to Offset(right, upper),
            Offset(right, upper) to Offset(right, upper + segment),
            Offset(left + segment, lower) to Offset(left, lower),
            Offset(left, lower) to Offset(left, lower - segment),
            Offset(right - segment, lower) to Offset(right, lower),
            Offset(right, lower) to Offset(right, lower - segment)
        ).forEach { (start, end) -> drawLine(color, start, end, strokeWidth = stroke) }
    }
}

@Composable
private fun CloseIcon() {
    Canvas(Modifier.size(22.dp)) {
        val inset = 2.dp.toPx()
        drawLine(Color.White, Offset(inset, inset), Offset(size.width - inset, size.height - inset),
            strokeWidth = 2.3.dp.toPx())
        drawLine(Color.White, Offset(size.width - inset, inset), Offset(inset, size.height - inset),
            strokeWidth = 2.3.dp.toPx())
    }
}

@Composable
private fun FlashlightIcon() {
    Canvas(Modifier.size(28.dp)) {
        val u = size.width / 28f
        val shape = Path().apply {
            moveTo(7f * u, 3f * u); lineTo(21f * u, 3f * u)
            lineTo(21f * u, 9f * u); lineTo(18f * u, 12f * u)
            lineTo(18f * u, 25f * u); lineTo(10f * u, 25f * u)
            lineTo(10f * u, 12f * u); lineTo(7f * u, 9f * u); close()
        }
        drawPath(shape, Color.White, style = Stroke(width = 2f * u))
        drawLine(Color.White, Offset(10f * u, 12f * u), Offset(18f * u, 12f * u), 2f * u)
    }
}

@Composable
private fun PrivacyShield() {
    Canvas(Modifier.size(17.dp, 19.dp)) {
        val x = size.width / 17f
        val y = size.height / 19f
        val shape = Path().apply {
            moveTo(8.5f * x, 1f * y); lineTo(15f * x, 3f * y)
            lineTo(14f * x, 12f * y); quadraticTo(12f * x, 16f * y, 8.5f * x, 18f * y)
            quadraticTo(5f * x, 16f * y, 3f * x, 12f * y)
            lineTo(2f * x, 3f * y); close()
        }
        drawPath(shape, Color(0xFFBEC8D5))
        drawCircle(Color(0xFF222830), radius = 1.2f * x, center = Offset(8.5f * x, 7f * y))
        drawLine(Color(0xFF222830), Offset(8.5f * x, 8f * y), Offset(8.5f * x, 12f * y), 1.5f * x)
    }
}

@Composable
private fun FocusMarker(point: ScanPoint?) {
    Canvas(Modifier.fillMaxSize()) {
        if (point == null) return@Canvas
        val radius = 24.dp.toPx()
        val color = Color(0xFFFFC83D)
        drawCircle(color, radius, Offset(point.x, point.y), style = Stroke(width = 2.dp.toPx()))
        drawLine(color, Offset(point.x - 5.dp.toPx(), point.y),
            Offset(point.x + 5.dp.toPx(), point.y), strokeWidth = 2.dp.toPx())
        drawLine(color, Offset(point.x, point.y - 5.dp.toPx()),
            Offset(point.x, point.y + 5.dp.toPx()), strokeWidth = 2.dp.toPx())
    }
}

@Composable
private fun ScanChip(text: String, modifier: Modifier, fontSize: Int, red: Boolean = false) {
    Box(modifier.clip(RoundedCornerShape(10.dp))
        .background(if (red) Color(0xFFAE2525) else Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center, maxLines = 1)
    }
}
