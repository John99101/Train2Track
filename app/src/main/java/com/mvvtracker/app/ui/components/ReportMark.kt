package com.mvvtracker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The red of a reported journey, the same in the Feed, on the Map and on the report screen. */
val ReportRed = Color(0xFFDC2828)

/** The red "!" that marks a journey with a current report. */
@Composable
fun ReportMark(size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(ReportRed),
        contentAlignment = Alignment.Center
    ) {
        Text("!", color = Color.White, fontSize = (size.value * 0.62f).sp, fontWeight = FontWeight.Bold)
    }
}
