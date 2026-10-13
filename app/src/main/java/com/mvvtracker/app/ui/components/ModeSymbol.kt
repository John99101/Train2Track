package com.mvvtracker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Our own mode badges (not the operators' trademarked logos): a coloured tile with
// a letter for S-/U-Bahn and a transit glyph for tram/bus. Colours match the map.
private val METRO_GREEN = Color(0xFF009944)   // S-Bahn
private val SUBWAY_BLUE = Color(0xFF00629B)   // U-Bahn
private val TRAM_ORANGE = Color(0xFFD65A14)
private val BUS_VIOLET = Color(0xFF7C3AAD)
private val OTHER_GREY = Color(0xFF787878)

/**
 * A small symbol for a transport mode, sized to sit inline with text.
 * METRO = S-Bahn (green "S" circle), SUBWAY = U-Bahn (blue "U" square),
 * TRAM (orange tram glyph), BUS (violet bus glyph).
 */
@Composable
fun ModeSymbol(mode: String, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    when (mode) {
        "BUS" -> IconBadge(BUS_VIOLET, size, modifier) {
            Icon(Icons.Filled.DirectionsBus, contentDescription = "Bus", tint = Color.White,
                modifier = Modifier.size(size * 0.66f))
        }
        "TRAM" -> IconBadge(TRAM_ORANGE, size, modifier) {
            Icon(Icons.Filled.Tram, contentDescription = "Tram", tint = Color.White,
                modifier = Modifier.size(size * 0.66f))
        }
        "SUBWAY" -> LetterBadge("U", SUBWAY_BLUE, RoundedCornerShape(4.dp), size, modifier)
        "METRO" -> LetterBadge("S", METRO_GREEN, CircleShape, size, modifier)
        else -> LetterBadge("•", OTHER_GREY, CircleShape, size, modifier)
    }
}

@Composable
private fun LetterBadge(letter: String, color: Color, shape: Shape, size: Dp, modifier: Modifier) {
    Box(
        modifier = modifier.size(size).clip(shape).background(color),
        contentAlignment = Alignment.Center
    ) {
        // Line height = font size: the inherited 24 sp line would push the letter
        // out of very small badges (e.g. the Feed filter's corner symbols).
        Text(
            text = letter,
            color = Color.White,
            fontSize = (size.value * 0.6f).sp,
            fontWeight = FontWeight.Bold,
            style = TextStyle(
                lineHeight = (size.value * 0.6f).sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            )
        )
    }
}

@Composable
private fun IconBadge(color: Color, size: Dp, modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center
    ) { content() }
}
