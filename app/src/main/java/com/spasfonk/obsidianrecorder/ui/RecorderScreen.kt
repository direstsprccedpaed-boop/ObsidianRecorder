package com.spasfonk.obsidianrecorder.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spasfonk.obsidianrecorder.ui.components.RecordingCard
import com.spasfonk.obsidianrecorder.ui.components.RecordingTriggerFab
import com.spasfonk.obsidianrecorder.ui.components.VuMeterWithThreshold
import com.spasfonk.obsidianrecorder.ui.components.WaveformCanvas
import com.spasfonk.obsidianrecorder.ui.theme.EmeraldAccent
import com.spasfonk.obsidianrecorder.ui.theme.ObsidianBorder
import com.spasfonk.obsidianrecorder.ui.theme.ObsidianSurface
import com.spasfonk.obsidianrecorder.ui.theme.TextPrimary
import com.spasfonk.obsidianrecorder.ui.theme.TextSecondary

private val categoryFilters = listOf("Tout", "Enregistreur sonore", "Appels", "Applications")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderScreen(viewModel: RecorderViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    val filteredRecordings = remember(state.recordings, state.selectedFilter, state.searchQuery) {
        state.recordings.filter { item ->
            val matchesFilter = state.selectedFilter == "Tout" || state.selectedFilter == "Enregistreur sonore"
            val matchesQuery = state.searchQuery.isBlank() ||
                item.title.contains(state.searchQuery, ignoreCase = true) ||
                item.transcriptPreview.contains(state.searchQuery, ignoreCase = true)
            matchesFilter && matchesQuery
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedVisibility(
                    visible = state.showThresholdPanel,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    ThresholdPanel(
                        currentDb = state.currentDb,
                        thresholdDb = state.thresholdDb,
                        waveform = state.waveform,
                        onThresholdChange = { viewModel.setThreshold(it) },
                        onDismiss = { viewModel.dismissThresholdPanel() }
                    )
                }
                Spacer(Modifier.height(12.dp))
                RecordingTriggerFab(
                    visualState = state.fabVisualState,
                    onTap = { viewModel.toggleRecording(context) },
                    onLongPressOrSwipeUp = { viewModel.toggleThresholdPanel() }
                )
            }
        },
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            RecorderTopBar(
                isSearchActive = state.isSearchActive,
                searchQuery = state.searchQuery,
                onSearchToggle = { viewModel.setSearchActive(!state.isSearchActive) },
                onSearchQueryChange = { viewModel.setSearchQuery(it) }
            )

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categoryFilters.forEach { filter ->
                    val selected = state.selectedFilter == filter
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.setFilter(filter) },
                        label = { Text(filter) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmeraldAccent,
                            selectedLabelColor = androidx.compose.ui.graphics.Color.Black,
                            containerColor = ObsidianSurface,
                            labelColor = TextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = ObsidianBorder,
                            selectedBorderColor = EmeraldAccent
                        )
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (filteredRecordings.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Aucun enregistrement pour le moment",
                        color = TextSecondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredRecordings, key = { it.file.absolutePath }) { item ->
                        RecordingCard(
                            item = item,
                            isPlaying = state.playingFilePath == item.file.absolutePath,
                            onTogglePlayback = { viewModel.togglePlayback(item) }
                        )
                    }
                    item {
                        Spacer(Modifier.height(96.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecorderTopBar(
    isSearchActive: Boolean,
    searchQuery: String,
    onSearchToggle: () -> Unit,
    onSearchQueryChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (isSearchActive) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Rechercher un enregistrement") },
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onSearchToggle) {
                Icon(Icons.Filled.Close, contentDescription = "Fermer la recherche", tint = TextPrimary)
            }
        } else {
            Text(
                text = "Enregistreur sonore",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onSearchToggle) {
                Icon(Icons.Filled.Search, contentDescription = "Rechercher", tint = TextPrimary)
            }
            IconButton(onClick = { }) {
                Icon(Icons.Filled.Headset, contentDescription = "Sortie audio", tint = TextPrimary)
            }
            IconButton(onClick = { }) {
                Icon(Icons.Filled.Sort, contentDescription = "Trier", tint = TextPrimary)
            }
            IconButton(onClick = { }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Plus d'options", tint = TextPrimary)
            }
        }
    }
}

@Composable
private fun ThresholdPanel(
    currentDb: Float,
    thresholdDb: Float,
    waveform: List<Float>,
    onThresholdChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.Card(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, ObsidianBorder),
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Seuil de déclenchement",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Fermer", tint = TextSecondary)
                }
            }
            Spacer(Modifier.height(8.dp))
            VuMeterWithThreshold(
                currentDb = currentDb,
                thresholdDb = thresholdDb,
                onThresholdChange = onThresholdChange
            )
            Spacer(Modifier.height(8.dp))
            WaveformCanvas(history = waveform)
        }
    }
}
