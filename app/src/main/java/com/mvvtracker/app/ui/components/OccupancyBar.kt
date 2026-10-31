package com.mvvtracker.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mvvtracker.app.R
import kotlin.math.roundToInt

/**
 * The five occupancy levels, from many seats free (1, green) to very full (5, red).
 * The colours read in light and dark mode; every level also has its name, so the
 * bar never depends on colour alone.
 */
val OCCUPANCY_COLORS = listOf(
    Color(0xFF2E7D32),
    Color(0xFF7CB342),
    Color(0xFFF9A825),
    Color(0xFFEF6C00),
    Color(0xFFC62828)
)

fun occupancyColor(level: Int): Color = OCCUPANCY_COLORS[(level - 1).coerceIn(0, OCCUPANCY_COLORS.lastIndex)]

@StringRes
fun occupancyLabelRes(level: Int): Int = when (level) {
    1 -> R.string.occupancy_level_1
    2 -> R.string.occupancy_level_2
    3 -> R.string.occupancy_level_3
    4 -> R.string.occupancy_level_4
    else -> R.string.occupancy_level_5
}

/**
 * The small bar for the Feed and the train's card: [level] of five segments filled
 * in that level's colour. Read out as "Occupancy: standing only".
 */
@Composable
fun OccupancyMeter(level: Int, modifier: Modifier = Modifier, segmentWidth: Dp = 7.dp, height: Dp = 8.dp) {
    val description = stringResource(R.string.occupancy_meter_desc, stringResource(occupancyLabelRes(level)))
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.semantics { contentDescription = description }
    ) {
        for (i in 1..OCCUPANCY_COLORS.size) {
            Box(
                Modifier
                    .size(segmentWidth, height)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i <= level) occupancyColor(level) else empty)
            )
        }
    }
}

/**
 * The bar to pull in the report window: five stops from green (many seats free) to
 * red (very full), with a round knob in the level's colour, as on iOS. Pulling only
 * moves the knob; the card's Submit button sends it. Read out with the level's name.
 *
 * The slider's own thumb and track are replaced: the slider lays the track out so
 * that the knob's centre runs exactly from its start to its end, so the knob never
 * leaves the bar. Dots mark only the inner stops (2, 3 and 4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OccupancySlider(level: Int, onLevelChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(occupancyLabelRes(level))
    val count = OCCUPANCY_COLORS.size
    val knobColor = occupancyColor(level)
    Slider(
        value = level.toFloat(),
        onValueChange = { onLevelChange(it.roundToInt().coerceIn(1, count)) },
        valueRange = 1f..count.toFloat(),
        steps = count - 2,
        thumb = {
            Box(
                Modifier
                    .size(OCCUPANCY_KNOB)
                    .shadow(2.dp, CircleShape)
                    .background(Color.White, CircleShape)
                    .padding(3.dp)
                    .background(knobColor, CircleShape)
            )
        },
        track = {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            ) {
                val r = size.height / 2
                drawRoundRect(Brush.horizontalGradient(OCCUPANCY_COLORS), cornerRadius = CornerRadius(r, r))
                for (i in 1 until count - 1) {
                    drawCircle(
                        Color.White.copy(alpha = 0.85f),
                        radius = 2.dp.toPx(),
                        center = Offset(size.width * i / (count - 1), r)
                    )
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .semantics { stateDescription = label }
    )
}

/** Size of the knob; the bar starts and ends under its centre. */
val OCCUPANCY_KNOB = 24.dp
