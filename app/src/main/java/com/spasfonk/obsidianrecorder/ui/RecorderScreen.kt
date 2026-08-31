package com.spasfonk.obsidianrecorder.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.spasfonk.obsidianrecorder.ui.components.VuMeterWithThreshold
import com.spasfonk.obsidianrecorder.ui.components.WaveformCanvas
import com.spasfonk.obsidianrecorder.ui.theme.EmeraldAccent
import com.spasfonk.obsidianrecorder.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderScreen(viewModel: RecorderViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("Obsidian Recorder", style = MaterialTheme.typography.headlineSmall)

            VuMeterWithThreshold(
                currentDb = state.currentDb,
                thresholdDb = state.thresholdDb,
                onThresholdChange = { viewModel.setThreshold(it) }
            )

            WaveformCanvas(history = state.waveform)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!state.isRecording) {
                    Button(
                        onClick = { viewModel.startRecording(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Démarrer")
                    }
                } else {
                    Button(
                        onClick = { viewModel.stopRecording(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Arrêter")
                    }
                }
            }

            HorizontalDivider()

            Text("Transcription", style = MaterialTheme.typography.titleMedium)
            Text(
                text = state.finalizedTranscript,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = state.interimTranscript,
                color = TextSecondary
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    val file = viewModel.exportTranscript(context)
                    scope.launch {
                        snackbarHostState.showSnackbar(if (file != null) "Transcript exporté" else "Aucun texte")
                    }
                }) { Text("Exporter .txt") }

                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("transcript", state.finalizedTranscript))
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch { snackbarHostState.showSnackbar("Copié dans le presse-papiers") }
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                }

                OutlinedButton(onClick = {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, state.finalizedTranscript)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "Partager la transcription"))
                }) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                }
            }

            HorizontalDivider()

            Text("Découpe du fichier final", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(25L, 50L, 100L).forEach { mb ->
                    OutlinedButton(onClick = { viewModel.splitRecording(mb * 1024 * 1024) }) {
                        Text("${mb} Mo")
                    }
                }
            }

            state.statusMessage?.let {
                Text(it, color = EmeraldAccent)
            }
        }
    }
}
