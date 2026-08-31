package com.spasfonk.obsidianrecorder.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.spasfonk.obsidianrecorder.ui.theme.ElectricBlue
import com.spasfonk.obsidianrecorder.ui.theme.EmeraldAccent
import com.spasfonk.obsidianrecorder.ui.theme.ObsidianBorder
import kotlin.math.roundToInt

@Composable
fun VuMeterWithThreshold(
    currentDb: Float,
    thresholdDb: Float,
    minDb: Float = -90f,
    maxDb: Float = 0f,
    onThresholdChange: (Float) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var lastStep by remember { mutableStateOf(thresholdDb.roundToInt()) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    val newDb = minDb + fraction * (maxDb - minDb)
                    onThresholdChange(newDb)
                    val step = newDb.roundToInt()
                    if (step != lastStep) {
                        lastStep = step
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(72.dp)) {
            val width = size.width
            val height = size.height
            val levelFraction = ((currentDb - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
            val thresholdFraction = ((thresholdDb - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)

            drawRoundRect(
                color = ObsidianBorder,
                cornerRadius = CornerRadius(16f, 16f)
            )

            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(ElectricBlue, EmeraldAccent)),
                size = Size(width * levelFraction, height),
                cornerRadius = CornerRadius(16f, 16f)
            )

            val thresholdX = width * thresholdFraction
            drawLine(
                color = Color.White,
                start = Offset(thresholdX, 0f),
                end = Offset(thresholdX, height),
                strokeWidth = 4f
            )
            drawCircle(
                color = Color.White,
                radius = 14f,
                center = Offset(thresholdX, height / 2f)
            )
        }
    }
}
