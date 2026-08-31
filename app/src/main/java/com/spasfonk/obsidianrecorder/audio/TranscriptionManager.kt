package com.spasfonk.obsidianrecorder.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class TranscriptState(
    val finalizedText: String = "",
    val interimText: String = "",
    val isListening: Boolean = false,
    val lastError: String? = null
)

class TranscriptionManager(private val context: Context) {

    private val _state = MutableStateFlow(TranscriptState())
    val state: StateFlow<TranscriptState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var shouldRestart = false

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = _state.value.copy(lastError = "Reconnaissance vocale indisponible")
            return
        }
        shouldRestart = true
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
        launchListening()
    }

    fun stop() {
        shouldRestart = false
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
        _state.value = _state.value.copy(isListening = false, interimText = "")
    }

    private fun launchListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000)
        }
        recognizer?.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = _state.value.copy(isListening = true, lastError = null)
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            _state.value = _state.value.copy(isListening = false)
        }

        override fun onError(error: Int) {
            val message = when (error) {
                SpeechRecognizer.ERROR_NETWORK -> "Réseau indisponible"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Délai réseau dépassé"
                SpeechRecognizer.ERROR_NO_MATCH -> null
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Moteur occupé"
                SpeechRecognizer.ERROR_AUDIO -> "Erreur audio micro"
                else -> "Erreur reconnaissance ($error)"
            }
            _state.value = _state.value.copy(isListening = false, lastError = message)
            if (shouldRestart) launchListening()
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) {
                _state.value = _state.value.copy(
                    finalizedText = (_state.value.finalizedText + " " + text).trim(),
                    interimText = ""
                )
            }
            if (shouldRestart) launchListening()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            _state.value = _state.value.copy(interimText = text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
