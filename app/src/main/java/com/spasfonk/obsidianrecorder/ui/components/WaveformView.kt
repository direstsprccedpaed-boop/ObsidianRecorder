package com.spasfonk.obsidianrecorder.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.spasfonk.obsidianrecorder.ui.theme.EmeraldAccent

@Composable
fun WaveformCanvas(history: List<Float>, minDb: Float = -90f, maxDb: Float = 0f) {
    Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
        if (history.isEmpty()) return@Canvas
        val barWidth = size.width / history.size
        history.forEachIndexed { index, db ->
            val fraction = ((db - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
            val barHeight = size.height * fraction
            drawRect(
                color = EmeraldAccent.copy(alpha = 0.85f),
                topLeft = Offset(index * barWidth, size.height - barHeight),
                size = Size(barWidth * 0.7f, barHeight)
            )
        }
    }
}
