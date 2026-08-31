package com.spasfonk.obsidianrecorder.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.spasfonk.obsidianrecorder.MainActivity
import com.spasfonk.obsidianrecorder.audio.AudioRecordEngine
import com.spasfonk.obsidianrecorder.audio.TranscriptionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    companion object {
        const val CHANNEL_ID = "obsidian_recorder_channel"
        const val NOTIFICATION_ID = 42
        const val ACTION_START = "action_start"
        const val ACTION_STOP = "action_stop"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var audioEngine: AudioRecordEngine? = null
        private set
    lateinit var transcriptionManager: TranscriptionManager
    var currentOutputFile: File? = null
        private set

    private val _engineReady = MutableStateFlow(false)
    val engineReady: StateFlow<Boolean> = _engineReady.asStateFlow()

    // Emet le fichier finalisé uniquement une fois que MediaMuxer a réellement
    // écrit l'en-tête (moov atom) sur disque via stop(). Tant que cette valeur
    // reste null, le fichier .m4a est incomplet et NE DOIT PAS être lu, scanné
    // ou exposé dans la liste : c'est la cause du "fichier vide à la lecture".
    private val _stopCompleted = MutableStateFlow<File?>(null)
    val stopCompleted: StateFlow<File?> = _stopCompleted.asStateFlow()

    @Volatile
    private var isStopping = false

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        transcriptionManager = TranscriptionManager(applicationContext)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> beginRecording()
            ACTION_STOP -> endRecording()
        }
        return START_STICKY
    }

    private fun beginRecording() {
        if (audioEngine != null) return

        _stopCompleted.value = null
        isStopping = false

        startForeground(NOTIFICATION_ID, buildNotification("Enregistrement en cours"))
        acquireWakeLock()
        requestAudioFocus()

        val dir = getExternalFilesDir(Environment.DIRECTORY_RECORDINGS) ?: filesDir
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        val outputFile = File(dir, "REC_$timestamp.m4a")
        currentOutputFile = outputFile

        val engine = AudioRecordEngine(outputFile = outputFile)
        audioEngine = engine
        engine.start()
        transcriptionManager.start()
        _engineReady.value = true
    }

    private fun endRecording() {
        if (isStopping) return
        val engine = audioEngine ?: return
        val finishedFile = currentOutputFile
        isStopping = true

        transcriptionManager.stop()

        // audioEngine.stop() est bloquant : il attend la fin du thread de capture
        // puis le drainage complet de l'encodeur (jusqu'à ~2s). Cette opération ne
        // doit JAMAIS tourner sur le thread principal du Service (risque d'ANR et
        // retard de finalisation). On l'exécute sur un thread dédié, puis on
        // revient sur le thread principal pour terminer proprement le cycle de vie
        // du service et notifier la finalisation réelle du fichier.
        Thread {
            try {
                engine.stop()
            } catch (_: Exception) { }

            mainHandler.post {
                _engineReady.value = false
                audioEngine = null
                releaseWakeLock()
                abandonAudioFocus()
                stopForeground(STOP_FOREGROUND_REMOVE)
                isStopping = false
                _stopCompleted.value = finishedFile
                stopSelf()
            }
        }.start()
    }

    private fun requestAudioFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> audioEngine?.setManualPause(true)
                    AudioManager.AUDIOFOCUS_GAIN -> audioEngine?.setManualPause(false)
                }
            }
            .build()
        audioManager?.requestAudioFocus(focusRequest!!)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ObsidianRecorder:WakeLock").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Enregistrement audio", NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Obsidian Recorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        try { audioEngine?.stop() } catch (_: Exception) { }
        _engineReady.value = false
        releaseWakeLock()
        abandonAudioFocus()
        super.onDestroy()
    }
}
