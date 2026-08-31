package com.spasfonk.obsidianrecorder.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var sortNewestFirst by remember { mutableStateOf(true) }
    var moreMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    val filteredRecordings = remember(state.recordings, state.selectedFilter, state.searchQuery, sortNewestFirst) {
        val base = state.recordings.filter { item ->
            val matchesFilter = state.selectedFilter == "Tout" || state.selectedFilter == "Enregistreur sonore"
            val matchesQuery = state.searchQuery.isBlank() ||
                item.title.contains(state.searchQuery, ignoreCase = true) ||
                item.transcriptPreview.contains(state.searchQuery, ignoreCase = true)
            matchesFilter && matchesQuery
        }
        if (sortNewestFirst) base.sortedByDescending { it.createdAtMillis } else base.sortedBy { it.createdAtMillis }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                RecordingTriggerFab(
                    visualState = state.fabVisualState,
                    onTap = { viewModel.toggleRecording(context) },
                    onLongPressOrSwipeUp = { viewModel.toggleThresholdPanel() }
                )
            },
            floatingActionButtonPosition = FabPosition.Center
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                RecorderTopBar(
                    isSearchActive = state.isSearchActive,
                    searchQuery = state.searchQuery,
                    moreMenuExpanded = moreMenuExpanded,
                    onSearchToggle = { viewModel.setSearchActive(!state.isSearchActive) },
                    onSearchQueryChange = { viewModel.setSearchQuery(it) },
                    onSortClick = { sortNewestFirst = !sortNewestFirst },
                    onHeadsetClick = {
                        // Sélection de périphérique de sortie audio (non implémentée
                        // dans cette version) : on informe explicitement l'utilisateur
                        // au lieu de laisser le bouton silencieux.
                    },
                    onMoreMenuToggle = { moreMenuExpanded = !moreMenuExpanded },
                    onMoreMenuDismiss = { moreMenuExpanded = false },
                    onRefresh = {
                        moreMenuExpanded = false
                        viewModel.refreshRecordings()
                    },
                    onOpenThreshold = {
                        moreMenuExpanded = false
                        viewModel.toggleThresholdPanel()
                    }
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
                            text = if (state.recordings.isEmpty())
                                "Aucun enregistrement pour le moment"
                            else
                                "Aucun résultat pour ce filtre",
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
                                onTogglePlayback = { viewModel.togglePlayback(item) },
                                onOpenActions = { viewModel.openActionsFor(item) }
                            )
                        }
                        item { Spacer(Modifier.height(96.dp)) }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.showThresholdPanel,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 110.dp, start = 20.dp, end = 20.dp),
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
    }

    state.selectedItemForActions?.let { item ->
        RecordingActionsSheet(
            item = item,
            context = context,
            onDismiss = { viewModel.closeActions() },
            onExport = { viewModel.exportTranscriptFor(item, context) },
            onCopy = {
                val sidecar = java.io.File(item.file.parentFile, item.file.nameWithoutExtension + ".txt")
                val text = if (sidecar.exists()) sidecar.readText() else ""
                if (text.isBlank()) {
                    viewModel.openActionsFor(item)
                } else {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("transcript", text))
                }
                viewModel.closeActions()
            },
            onShare = {
                val sidecar = java.io.File(item.file.parentFile, item.file.nameWithoutExtension + ".txt")
                val text = if (sidecar.exists()) sidecar.readText() else ""
                if (text.isNotBlank()) {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "Partager la transcription"))
                }
                viewModel.closeActions()
            },
            onSplit = { targetBytes -> viewModel.splitRecording(item, targetBytes) },
            onDelete = { viewModel.deleteRecording(item) }
        )
    }
}

@Composable
private fun RecorderTopBar(
    isSearchActive: Boolean,
    searchQuery: String,
    moreMenuExpanded: Boolean,
    onSearchToggle: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSortClick: () -> Unit,
    onHeadsetClick: () -> Unit,
    onMoreMenuToggle: () -> Unit,
    onMoreMenuDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onOpenThreshold: () -> Unit
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
            IconButton(onClick = onHeadsetClick) {
                Icon(Icons.Filled.Headset, contentDescription = "Sortie audio", tint = TextPrimary)
            }
            IconButton(onClick = onSortClick) {
                Icon(Icons.Filled.Sort, contentDescription = "Trier", tint = TextPrimary)
            }
            Box {
                IconButton(onClick = onMoreMenuToggle) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Plus d'options", tint = TextPrimary)
                }
                DropdownMenu(expanded = moreMenuExpanded, onDismissRequest = onMoreMenuDismiss) {
                    DropdownMenuItem(
                        text = { Text("Actualiser la liste") },
                        onClick = onRefresh
                    )
                    DropdownMenuItem(
                        text = { Text("Réglages du seuil de captation") },
                        onClick = onOpenThreshold
                    )
                }
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
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianBorder),
        modifier = Modifier.fillMaxWidth()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingActionsSheet(
    item: RecordingItem,
    context: Context,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSplit: (Long) -> Unit,
    onDelete: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ObsidianSurface
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                text = item.title,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "${item.dateLabel} \u00b7 ${item.durationLabel} \u00b7 ${item.sizeLabel}",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(16.dp))

            ActionRow(icon = Icons.Filled.Download, label = "Exporter la transcription (.txt)", onClick = onExport)
            ActionRow(icon = Icons.Filled.ContentCopy, label = "Copier la transcription", onClick = onCopy)
            ActionRow(icon = Icons.Filled.Share, label = "Partager la transcription", onClick = onShare)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = ObsidianBorder)

            Text(
                text = "Découper le fichier audio (sans perte)",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(25L, 50L, 100L).forEach { mb ->
                    androidx.compose.material3.OutlinedButton(onClick = { onSplit(mb * 1024 * 1024) }) {
                        Icon(Icons.Filled.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("$mb Mo")
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = ObsidianBorder)

            ActionRow(
                icon = Icons.Filled.Delete,
                label = "Supprimer l'enregistrement",
                onClick = onDelete,
                tint = androidx.compose.ui.graphics.Color(0xFFF87171)
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = TextPrimary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = null, tint = tint)
        }
        Spacer(Modifier.width(4.dp))
        Text(text = label, color = tint, modifier = Modifier.weight(1f))
    }
}
