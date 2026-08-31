package com.spasfonk.obsidianrecorder.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.spasfonk.obsidianrecorder.ui.FabVisualState

private val IdleColor = Color(0xFFE5E7EB)
private val ListeningColor = Color(0xFFF59E0B)
private val RecordingColor = Color(0xFF10B981)
private val IdleDotColor = Color(0xFFEF4444)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingTriggerFab(
    visualState: FabVisualState,
    onTap: () -> Unit,
    onLongPressOrSwipeUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    val targetColor = when (visualState) {
        FabVisualState.IDLE -> IdleColor
        FabVisualState.LISTENING -> ListeningColor
        FabVisualState.RECORDING -> RecordingColor
    }
    val fabColor by animateColorAsState(targetValue = targetColor, label = "fabColor")

    val infiniteTransition = rememberInfiniteTransition(label = "fabPulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (visualState == FabVisualState.RECORDING) 1.06f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fabScale"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = if (visualState == FabVisualState.LISTENING) 0.6f else 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fabGlow"
    )

    Box(
        modifier = modifier.size(80.dp),
        contentAlignment = Alignment.Center
    ) {
        if (visualState == FabVisualState.LISTENING) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(ListeningColor.copy(alpha = glowAlpha), CircleShape)
            )
        }

        Box(
            modifier = Modifier
                .size(64.dp)
                .graphicsLayer {
                    val s = if (visualState == FabVisualState.RECORDING) pulseScale else 1.0f
                    scaleX = s
                    scaleY = s
                }
                .shadow(elevation = 10.dp, shape = CircleShape, clip = false)
                .background(fabColor, CircleShape)
                // Un seul détecteur de gestes combiné (tap + appui long) au lieu
                // de deux pointerInput concurrents : élimine le conflit qui
                // rendait le panneau de seuil inaccessible via appui long.
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onTap()
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPressOrSwipeUp()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            when (visualState) {
                FabVisualState.IDLE -> {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(IdleDotColor, CircleShape)
                    )
                }
                FabVisualState.LISTENING -> {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "En attente de voix",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
                FabVisualState.RECORDING -> {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "Arrêter l'enregistrement",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}
