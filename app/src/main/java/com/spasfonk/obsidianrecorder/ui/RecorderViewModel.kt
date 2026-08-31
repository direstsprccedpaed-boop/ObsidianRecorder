package com.spasfonk.obsidianrecorder.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Environment
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spasfonk.obsidianrecorder.audio.AudioSplitter
import com.spasfonk.obsidianrecorder.audio.EngineState
import com.spasfonk.obsidianrecorder.service.RecordingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class FabVisualState { IDLE, LISTENING, RECORDING }

data class RecordingItem(
    val file: File,
    val title: String,
    val dateLabel: String,
    val durationLabel: String,
    val sizeLabel: String,
    val transcriptPreview: String,
    val createdAtMillis: Long
)

data class UiState(
    val isRecording: Boolean = false,
    val fabVisualState: FabVisualState = FabVisualState.IDLE,
    val currentDb: Float = -90f,
    val thresholdDb: Float = -45f,
    val waveform: List<Float> = emptyList(),
    val finalizedTranscript: String = "",
    val interimTranscript: String = "",
    val lastRecordingFile: File? = null,
    val splitOutputFiles: List<File> = emptyList(),
    val statusMessage: String? = null,
    val recordings: List<RecordingItem> = emptyList(),
    val selectedFilter: String = "Tout",
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val showThresholdPanel: Boolean = false,
    val playingFilePath: String? = null,
    val isFinalizingStop: Boolean = false,
    val selectedItemForActions: RecordingItem? = null
)

class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var boundService: RecordingService? = null
    private var isBound = false
    private var mediaPlayer: MediaPlayer? = null

    init {
        refreshRecordings()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.LocalBinder
            boundService = binder.getService()
            isBound = true
            observeService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            boundService = null
        }
    }

    private fun observeService() {
        val svc = boundService ?: return

        // C'est LA correction critique : on n'appelle refreshRecordings() ni
        // on ne débloque la lecture qu'après avoir reçu stopCompleted != null,
        // c'est-à-dire une fois que MediaMuxer a réellement écrit l'en-tête
        // final (moov atom) du fichier .m4a. Avant, refreshRecordings() était
        // appelé immédiatement après l'envoi de l'intent ACTION_STOP, sur un
        // fichier encore incomplet -> lecture d'un fichier vide garantie.
        viewModelScope.launch {
            svc.stopCompleted.collect { finishedFile ->
                if (finishedFile != null) {
                    val transcriptSnapshot = _uiState.value.finalizedTranscript.trim()
                    if (transcriptSnapshot.isNotBlank()) {
                        val sidecar = File(finishedFile.parentFile, finishedFile.nameWithoutExtension + ".txt")
                        try { sidecar.writeText(transcriptSnapshot) } catch (_: Exception) { }
                    }
                    _uiState.value = _uiState.value.copy(
                        isRecording = false,
                        isFinalizingStop = false,
                        lastRecordingFile = finishedFile,
                        fabVisualState = FabVisualState.IDLE,
                        finalizedTranscript = "",
                        interimTranscript = ""
                    )
                    refreshRecordings()
                    if (isBound) {
                        try { getApplication<Application>().unbindService(connection) } catch (_: Exception) { }
                        isBound = false
                    }
                }
            }
        }

        viewModelScope.launch {
            svc.engineReady.collect { ready ->
                if (!ready) return@collect
                val engine = svc.audioEngine ?: return@collect
                launch {
                    engine.level.collect { snap ->
                        _uiState.value = _uiState.value.copy(currentDb = snap.rmsDb)
                    }
                }
                launch {
                    engine.waveformHistory.collect { hist ->
                        _uiState.value = _uiState.value.copy(waveform = hist)
                    }
                }
                launch {
                    engine.state.collect { engineState ->
                        val fabState = when (engineState) {
                            EngineState.RECORDING -> FabVisualState.RECORDING
                            EngineState.PAUSED_SILENCE -> FabVisualState.LISTENING
                            EngineState.PAUSED_MANUAL -> FabVisualState.LISTENING
                            else -> if (_uiState.value.isRecording) FabVisualState.LISTENING else FabVisualState.IDLE
                        }
                        _uiState.value = _uiState.value.copy(fabVisualState = fabState)
                    }
                }
            }
        }
        viewModelScope.launch {
            svc.transcriptionManager.state.collect { t ->
                _uiState.value = _uiState.value.copy(
                    finalizedTranscript = t.finalizedText,
                    interimTranscript = t.interimText
                )
            }
        }
    }

    fun startRecording(context: Context) {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
        }
        context.startForegroundService(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        _uiState.value = _uiState.value.copy(
            isRecording = true,
            statusMessage = null,
            fabVisualState = FabVisualState.LISTENING
        )
    }

    fun stopRecording(context: Context) {
        // On ne fait plus refreshRecordings() ici : on se contente de signaler
        // l'arrêt et d'attendre l'événement stopCompleted (voir observeService).
        _uiState.value = _uiState.value.copy(isFinalizingStop = true, statusMessage = "Finalisation du fichier...")
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun toggleRecording(context: Context) {
        if (_uiState.value.isFinalizingStop) return
        if (_uiState.value.isRecording) {
            stopRecording(context)
        } else {
            startRecording(context)
        }
    }

    fun setThreshold(db: Float) {
        boundService?.audioEngine?.thresholdDb = db
        _uiState.value = _uiState.value.copy(thresholdDb = db)
    }

    fun toggleThresholdPanel() {
        _uiState.value = _uiState.value.copy(showThresholdPanel = !_uiState.value.showThresholdPanel)
    }

    fun dismissThresholdPanel() {
        _uiState.value = _uiState.value.copy(showThresholdPanel = false)
    }

    fun setFilter(filter: String) {
        _uiState.value = _uiState.value.copy(selectedFilter = filter)
    }

    fun setSearchActive(active: Boolean) {
        _uiState.value = _uiState.value.copy(isSearchActive = active, searchQuery = if (!active) "" else _uiState.value.searchQuery)
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun openActionsFor(item: RecordingItem) {
        _uiState.value = _uiState.value.copy(selectedItemForActions = item, lastRecordingFile = item.file)
    }

    fun closeActions() {
        _uiState.value = _uiState.value.copy(selectedItemForActions = null)
    }

    fun exportTranscriptFor(item: RecordingItem, context: Context): File? {
        val sidecar = File(item.file.parentFile, item.file.nameWithoutExtension + ".txt")
        if (!sidecar.exists()) {
            _uiState.value = _uiState.value.copy(statusMessage = "Aucune transcription associée à ce fichier")
            return null
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
        val outFile = File(dir, "${timestamp}_Transcript.txt")
        try {
            outFile.writeText(sidecar.readText())
            _uiState.value = _uiState.value.copy(statusMessage = "Transcript exporté : ${outFile.name}")
            return outFile
        } catch (_: Exception) {
            _uiState.value = _uiState.value.copy(statusMessage = "Erreur lors de l'export")
            return null
        }
    }

    fun exportTranscript(context: Context): File? {
        val text = (_uiState.value.finalizedTranscript + " " + _uiState.value.interimTranscript).trim()
        if (text.isBlank()) return null
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
        val file = File(dir, "${timestamp}_Transcript.txt")
        file.writeText(text)
        _uiState.value = _uiState.value.copy(statusMessage = "Transcript exporté : ${file.name}")
        return file
    }

    fun splitRecording(item: RecordingItem, targetSizeBytes: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val outputDir = item.file.parentFile ?: return@launch
            try {
                val parts = AudioSplitter.splitBySize(item.file, targetSizeBytes, outputDir)
                _uiState.value = _uiState.value.copy(
                    splitOutputFiles = parts,
                    statusMessage = "${parts.size} fichiers générés"
                )
                refreshRecordings()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(statusMessage = "Échec de la découpe : ${e.message}")
            }
        }
    }

    fun deleteRecording(item: RecordingItem) {
        try {
            if (item.file.exists()) item.file.delete()
            val sidecar = File(item.file.parentFile, item.file.nameWithoutExtension + ".txt")
            if (sidecar.exists()) sidecar.delete()
            _uiState.value = _uiState.value.copy(statusMessage = "Enregistrement supprimé", selectedItemForActions = null)
            refreshRecordings()
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(statusMessage = "Suppression impossible : ${e.message}")
        }
    }

    fun refreshRecordings() {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { scanRecordings() }
            _uiState.value = _uiState.value.copy(recordings = items)
        }
    }

    private fun scanRecordings(): List<RecordingItem> {
        val context = getApplication<Application>()
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_RECORDINGS) ?: return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("m4a", ignoreCase = true) }
            ?: return emptyList()

        val filenameDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
        val displayDateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val displayTitleFormat = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault())

        return files
            .filter { it.length() > 0L }
            .sortedByDescending { it.lastModified() }
            .map { file ->
                val rawTimestamp = file.nameWithoutExtension.removePrefix("REC_")
                val parsedDate: Date = try {
                    filenameDateFormat.parse(rawTimestamp) ?: Date(file.lastModified())
                } catch (_: Exception) {
                    Date(file.lastModified())
                }

                val title = "Enregistreur sonore \u2013 ${displayTitleFormat.format(parsedDate)}"
                val dateLabel = displayDateFormat.format(parsedDate)
                val durationLabel = extractDurationLabel(file)
                val sizeLabel = formatFileSize(file.length())
                val sidecar = File(file.parentFile, file.nameWithoutExtension + ".txt")
                val transcriptPreview = if (sidecar.exists()) {
                    try {
                        sidecar.readText().trim().replace("\n", " ").take(120)
                    } catch (_: Exception) {
                        "Aucune transcription disponible"
                    }
                } else {
                    "Aucune transcription disponible"
                }

                RecordingItem(
                    file = file,
                    title = title,
                    dateLabel = dateLabel,
                    durationLabel = durationLabel,
                    sizeLabel = sizeLabel,
                    transcriptPreview = transcriptPreview.ifBlank { "Aucune transcription disponible" },
                    createdAtMillis = parsedDate.time
                )
            }
    }

    private fun extractDurationLabel(file: File): String {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            formatDuration(durationMs)
        } catch (_: Exception) {
            "00:00:00"
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatFileSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format(Locale.getDefault(), "%.1f Mo", mb)
        } else {
            val kb = bytes / 1024.0
            String.format(Locale.getDefault(), "%.0f Ko", kb)
        }
    }

    fun togglePlayback(item: RecordingItem) {
        if (item.file.length() == 0L) {
            _uiState.value = _uiState.value.copy(statusMessage = "Fichier vide ou corrompu")
            return
        }
        val currentlyPlaying = _uiState.value.playingFilePath
        if (currentlyPlaying == item.file.absolutePath) {
            stopPlayback()
            return
        }
        stopPlayback()
        try {
            val player = MediaPlayer().apply {
                setDataSource(item.file.absolutePath)
                setOnCompletionListener {
                    _uiState.value = _uiState.value.copy(playingFilePath = null)
                    it.release()
                    mediaPlayer = null
                }
                setOnErrorListener { mp, _, _ ->
                    _uiState.value = _uiState.value.copy(
                        statusMessage = "Erreur de lecture : fichier illisible",
                        playingFilePath = null
                    )
                    mp.release()
                    mediaPlayer = null
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = player
            _uiState.value = _uiState.value.copy(playingFilePath = item.file.absolutePath)
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "Impossible de lire ce fichier : ${e.message}",
                playingFilePath = null
            )
        }
    }

    fun stopPlayback() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) { }
        mediaPlayer = null
        _uiState.value = _uiState.value.copy(playingFilePath = null)
    }

    override fun onCleared() {
        stopPlayback()
        super.onCleared()
    }
}
