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

// The colours of the "!", by how many riders report the journey (see [reportColor]).
// The same in the Feed, on the Map, on the train page and at the stations; iOS has
// the same values in MVVTrackerCore (ReportColor.swift).
val ReportOrange = Color(0xFFE65100)
val ReportLightRed = Color(0xFFEF5350)
val ReportRed = Color(0xFFDC2828)
val ReportBrightRed = Color(0xFFFF1744)

/** 1 rider: orange (not confirmed yet); 2 to 5: light red; 6 to 10: red; 11 or more: bright red. */
fun reportColor(riders: Int): Color = when {
    riders <= 1 -> ReportOrange
    riders <= 5 -> ReportLightRed
    riders <= 10 -> ReportRed
    else -> ReportBrightRed
}

/** The "!" that marks a journey with a current report, coloured by how many [riders] report it. */
@Composable
fun ReportMark(riders: Int, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(reportColor(riders)),
        contentAlignment = Alignment.Center
    ) {
        Text("!", color = Color.White, fontSize = (size.value * 0.62f).sp, fontWeight = FontWeight.Bold)
    }
}
