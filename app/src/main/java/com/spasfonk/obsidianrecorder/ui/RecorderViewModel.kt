package com.spasfonk.obsidianrecorder.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spasfonk.obsidianrecorder.audio.AudioSplitter
import com.spasfonk.obsidianrecorder.service.RecordingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiState(
    val isRecording: Boolean = false,
    val currentDb: Float = -90f,
    val thresholdDb: Float = -45f,
    val waveform: List<Float> = emptyList(),
    val finalizedTranscript: String = "",
    val interimTranscript: String = "",
    val lastRecordingFile: File? = null,
    val splitOutputFiles: List<File> = emptyList(),
    val statusMessage: String? = null
)

class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var boundService: RecordingService? = null
    private var isBound = false

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
        _uiState.value = _uiState.value.copy(isRecording = true, statusMessage = null)
    }

    fun stopRecording(context: Context) {
        val outputFile = boundService?.currentOutputFile
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        context.startService(intent)
        if (isBound) {
            context.unbindService(connection)
            isBound = false
        }
        _uiState.value = _uiState.value.copy(isRecording = false, lastRecordingFile = outputFile)
    }

    fun setThreshold(db: Float) {
        boundService?.audioEngine?.thresholdDb = db
        _uiState.value = _uiState.value.copy(thresholdDb = db)
    }

    fun exportTranscript(context: Context): File? {
        val text = (_uiState.value.finalizedTranscript + " " + _uiState.value.interimTranscript).trim()
        if (text.isBlank()) return null
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
        val file = File(dir, "${timestamp}_Transcript.txt")
        file.writeText(text)
        _uiState.value = _uiState.value.copy(statusMessage = "Transcript exporté : ${file.name}")
        return file
    }

    fun splitRecording(targetSizeBytes: Long) {
        val file = _uiState.value.lastRecordingFile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val outputDir = file.parentFile ?: return@launch
            val parts = AudioSplitter.splitBySize(file, targetSizeBytes, outputDir)
            _uiState.value = _uiState.value.copy(
                splitOutputFiles = parts,
                statusMessage = "${parts.size} fichiers générés"
            )
        }
    }
}
