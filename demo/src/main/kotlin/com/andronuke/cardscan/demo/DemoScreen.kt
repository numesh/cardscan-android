package com.andronuke.cardscan.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val fieldShape = RoundedCornerShape(6.dp)
private val fieldBackground = Color(0xFFF5F5F5)
private val labelColor = Color(0xFF555555)

/** Pure Compose view: renders state and forwards input as events. */
@Composable
internal fun AddCardScreen(state: AddCardUiState, onEvent: (AddCardEvent) -> Unit) {
    val scanned = state.scanNote != null
    Column(
        Modifier.fillMaxSize().background(Color.White).statusBarsPadding().navigationBarsPadding()
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            // The touch target is 48 dp; the arrow itself is the intended 22 dp size.
            Box(
                Modifier.padding(start = 8.dp).size(48.dp)
                    .clickable { onEvent(AddCardEvent.BackPressed) }
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center
            ) {
                BackIcon()
            }
            Text("Add card", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.Black)
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = if (scanned) 8.dp else 24.dp)
        ) {
            if (scanned) {
                ScanSuccessBanner(state.scanNote.orEmpty())
                Spacer(Modifier.height(22.dp))
            }

            FieldLabel("Card number", required = true)
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).height(52.dp)
                    .background(if (scanned) fieldBackground else Color.White, fieldShape)
                    .border(1.dp, if (scanned) Color(0xFFC8CCD1) else Color.Black, fieldShape)
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CardIcon(Modifier.size(19.dp, 15.dp))
                CardInput(
                    value = state.cardNumber,
                    onValueChange = { onEvent(AddCardEvent.CardNumberChanged(it)) },
                    hint = "Enter card number",
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                    numeric = true
                )
                // The camera graphic is 24 dp inside a 44 dp touch target.
                Box(
                    Modifier.size(44.dp).clickable { onEvent(AddCardEvent.ScanRequested) }
                        .semantics { contentDescription = "Scan card with camera" },
                    contentAlignment = Alignment.Center
                ) {
                    CameraIcon()
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ExpiryField(state.month, state.year, onEvent, Modifier.weight(1f))
                SmallField("CVV", if (scanned) "CVV" else "123", state.cvv,
                    { onEvent(AddCardEvent.CvvChanged(it)) }, Modifier.weight(1f), password = true)
            }

            Spacer(Modifier.height(18.dp))
            FieldLabel("Nickname", required = false)
            Box(
                Modifier.fillMaxWidth().padding(top = 4.dp).height(52.dp)
                    .background(fieldBackground, fieldShape).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                CardInput(state.nickname, { onEvent(AddCardEvent.NicknameChanged(it)) },
                    "Enter a nickname for your card (Optional)", Modifier.fillMaxWidth(), numeric = false)
            }

            if (scanned) {
                Text(
                    if (state.month.isEmpty() || state.year.isEmpty())
                        "Enter expiry date and CVV to continue."
                    else "Enter CVV to continue.",
                    color = Color(0xFF71809A), fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
                )
            }
        }

        Box(
            Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 16.dp, vertical = 14.dp)
                .background(Color(0xFFFFE797), RoundedCornerShape(56.dp))
                .semantics { contentDescription = "Add card unavailable in demo" },
            contentAlignment = Alignment.Center
        ) {
            Text("Add card", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                color = Color(0xFF5E6671))
        }
    }
}

@Composable
private fun ScanSuccessBanner(note: String) {
    Row(
        Modifier.fillMaxWidth().height(66.dp)
            .background(Color(0xFFE2F8E9), fieldShape).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(30.dp).background(Color(0xFF08A83B), CircleShape),
            contentAlignment = Alignment.Center) {
            Text("✓", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(if (note.startsWith("Card details")) "Card details scanned" else "Card number scanned",
                color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Check your details before continuing.", color = labelColor, fontSize = 13.sp)
        }
    }
}

@Composable
private fun FieldLabel(label: String, required: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = labelColor)
        if (required) Text(" *", fontSize = 14.sp, fontWeight = FontWeight.Bold,
            color = Color(0xFFE72D2D))
    }
}

/** Month and year remain separate inputs for simple event handling, inside one visual field. */
@Composable
private fun ExpiryField(month: String, year: String, onEvent: (AddCardEvent) -> Unit, modifier: Modifier) {
    Column(modifier) {
        FieldLabel("Expiry date", required = true)
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp).height(52.dp)
                .background(fieldBackground, fieldShape).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CardInput(month, { onEvent(AddCardEvent.MonthChanged(it)) }, "MM",
                Modifier.width(if (month.isEmpty()) 30.dp else 23.dp), numeric = true)
            Text(" / ", color = if (month.isEmpty() && year.isEmpty()) Color(0xFFBFC2C7) else Color.Black,
                fontSize = 16.sp)
            CardInput(year, { onEvent(AddCardEvent.YearChanged(it)) }, "YY", Modifier.width(30.dp), numeric = true)
        }
    }
}

@Composable
private fun SmallField(
    label: String, hint: String, value: String, onChange: (String) -> Unit,
    modifier: Modifier, password: Boolean = false
) {
    Column(modifier) {
        FieldLabel(label, required = true)
        Box(
            Modifier.fillMaxWidth().padding(top = 4.dp).height(52.dp)
                .background(fieldBackground, fieldShape).padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            CardInput(value, onChange, hint, Modifier.fillMaxWidth(), numeric = true, password = password)
        }
    }
}

@Composable
private fun CardInput(
    value: String, onValueChange: (String) -> Unit, hint: String,
    modifier: Modifier, numeric: Boolean, password: Boolean = false
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.semantics { contentDescription = hint },
        singleLine = true,
        textStyle = TextStyle(color = Color(0xFF1F1F1F), fontSize = 16.sp),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, color = Color(0xFFBFC2C7),
                    fontSize = 16.sp, maxLines = 1)
                inner()
            }
        }
    )
}

@Composable
private fun CardIcon(modifier: Modifier) {
    Canvas(modifier) {
        val grey = Color(0xFFBFC2C7)
        drawRoundRect(grey, cornerRadius = CornerRadius(2.dp.toPx()),
            style = Stroke(width = 1.5.dp.toPx()))
        drawRect(grey, topLeft = Offset(0f, 5.dp.toPx()),
            size = Size(size.width, 4.dp.toPx()))
    }
}

/** Draw at display density so the toolbar arrow stays sharp at any screen scale. */
@Composable
private fun BackIcon() {
    Canvas(Modifier.size(22.dp)) {
        val ink = Color.Black
        val stroke = 2.dp.toPx()
        val middle = size.height / 2
        drawLine(ink, Offset(size.width - 2.dp.toPx(), middle), Offset(2.dp.toPx(), middle), stroke)
        drawLine(ink, Offset(2.dp.toPx(), middle), Offset(10.dp.toPx(), 3.dp.toPx()), stroke)
        drawLine(ink, Offset(2.dp.toPx(), middle), Offset(10.dp.toPx(), size.height - 3.dp.toPx()), stroke)
    }
}

/** Outlined camera from the reference, with a 24 dp graphic inside a 44 dp target. */
@Composable
private fun CameraIcon() {
    Canvas(Modifier.size(24.dp)) {
        val ink = Color.Black
        val stroke = 2.dp.toPx()
        drawRoundRect(ink, topLeft = Offset(2.dp.toPx(), 7.dp.toPx()),
            size = Size(20.dp.toPx(), 14.dp.toPx()),
            cornerRadius = CornerRadius(2.dp.toPx()), style = Stroke(stroke))
        drawLine(ink, Offset(7.dp.toPx(), 7.dp.toPx()), Offset(9.dp.toPx(), 4.dp.toPx()), stroke)
        drawLine(ink, Offset(9.dp.toPx(), 4.dp.toPx()), Offset(15.dp.toPx(), 4.dp.toPx()), stroke)
        drawLine(ink, Offset(15.dp.toPx(), 4.dp.toPx()), Offset(17.dp.toPx(), 7.dp.toPx()), stroke)
        drawCircle(ink, radius = 3.2.dp.toPx(), center = Offset(12.dp.toPx(), 14.dp.toPx()),
            style = Stroke(stroke))
    }
}
