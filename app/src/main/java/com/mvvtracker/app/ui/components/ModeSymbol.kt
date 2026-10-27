package com.mvvtracker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Subway
import androidx.compose.material.icons.filled.Train
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

// Our own mode badges: a coloured circle with a vehicle glyph for every mode. No
// letter "S" in a green circle or "U" in a blue square: those are the logos of the
// Deutsche Bahn and the U-Bahn operators. Colours match the map.
private val METRO_GREEN = Color(0xFF009944)   // S-Bahn
private val SUBWAY_BLUE = Color(0xFF00629B)   // U-Bahn
private val TRAM_ORANGE = Color(0xFFD65A14)
private val BUS_VIOLET = Color(0xFF7C3AAD)
private val OTHER_GREY = Color(0xFF787878)

/**
 * A small symbol for a transport mode, sized to sit inline with text.
 * METRO = S-Bahn (green, train), SUBWAY = U-Bahn (blue, train in a tunnel),
 * TRAM (orange tram glyph), BUS (violet bus glyph), other trains (grey train).
 */
@Composable
fun ModeSymbol(mode: String, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    when (mode) {
        "METRO" -> IconBadge(METRO_GREEN, size, modifier) {
            Icon(Icons.Filled.Train, contentDescription = "S-Bahn", tint = Color.White,
                modifier = Modifier.size(size * 0.66f))
        }
        "SUBWAY" -> IconBadge(SUBWAY_BLUE, size, modifier) {
            Icon(Icons.Filled.Subway, contentDescription = "U-Bahn", tint = Color.White,
                modifier = Modifier.size(size * 0.7f))
        }
        "BUS" -> IconBadge(BUS_VIOLET, size, modifier) {
            Icon(Icons.Filled.DirectionsBus, contentDescription = "Bus", tint = Color.White,
                modifier = Modifier.size(size * 0.66f))
        }
        "TRAM" -> IconBadge(TRAM_ORANGE, size, modifier) {
            Icon(Icons.Filled.Tram, contentDescription = "Tram", tint = Color.White,
                modifier = Modifier.size(size * 0.66f))
        }
        "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "RAIL" ->
            IconBadge(OTHER_GREY, size, modifier) {
                Icon(Icons.Filled.Train, contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(size * 0.66f))
            }
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
