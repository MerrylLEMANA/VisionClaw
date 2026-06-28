package com.meta.wearable.dat.externalsampleapps.cameraaccess.claude

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ClaudeUiState(
    val isClaudeActive: Boolean = false,
    val connectionState: ClaudeConnectionState = ClaudeConnectionState.Disconnected,
    val isModelSpeaking: Boolean = false,
    val errorMessage: String? = null,
    val userTranscript: String = "",
    val aiTranscript: String = "",
)

class ClaudeSessionViewModel : ViewModel() {

    companion object {
        private const val TAG = "ClaudeSessionVM"
        private const val VIDEO_FRAME_INTERVAL_MS = 5000L
    }

    private val _uiState = MutableStateFlow(ClaudeUiState())
    val uiState: StateFlow<ClaudeUiState> = _uiState.asStateFlow()

    private var claudeService: ClaudeLiveService? = null
    private var journal: JournalManager? = null
    private var lastVideoFrameTime = 0L
    private var stateObservationJob: Job? = null

    private var wakeWordEngine: WakeWordEngine? = null
    private var wakeWordContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // AudioTrack dédié TTS Claude. USAGE_MEDIA → haut-parleur par défaut.
    // L'écho est évité en coupant le SpeechRecognizer (pause/resume) pendant le TTS,
    // pas via AEC matériel — plus simple et plus fiable sur Pixel 3.
    private var playbackTrack: AudioTrack? = null
    private var playbackSampleRate = 0

    /**
     * Initialise le wake-word engine et démarre l'écoute. Appeler une seule fois quand l'écran
     * s'affiche. Le wake word est suspendu automatiquement pendant les sessions Claude (conflit micro)
     * et reprend dès que la session se termine.
     */
    fun startWakeWord(context: Context) {
        val appCtx = context.applicationContext
        wakeWordContext = appCtx
        if (wakeWordEngine == null) {
            wakeWordEngine = WakeWordEngine(appCtx) {
                // Appelé depuis le thread AudioRecord — repasser sur le main thread
                mainHandler.post {
                    if (!_uiState.value.isClaudeActive) startSession(appCtx)
                }
            }
        }
        if (!_uiState.value.isClaudeActive) {
            wakeWordEngine?.start()
        }
    }

    fun stopWakeWord() {
        wakeWordEngine?.stop()
        wakeWordEngine = null
        wakeWordContext = null
    }

    fun startSession(context: Context) {
        if (_uiState.value.isClaudeActive) return
        wakeWordEngine?.stop() // libère le micro avant que SpeechRecognizer démarre

        if (!ClaudeConfig.isConfigured) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Clé API Claude non configurée. Ouvrez les Paramètres."
            )
            return
        }

        // Le TTS système ne produit pas forcément du 24kHz : on lit le sample rate réel du WAV
        // et on (re)crée l'AudioTrack à ce rate, sinon la voix sort trop aiguë ou trop grave.
        val ttsEngine = AndroidTtsEngine(context) { actualSampleRate ->
            ensurePlaybackTrack(actualSampleRate)
        }

        val service = ClaudeLiveService(
            sttEngine = AndroidSttEngine(context),
            ttsEngine = ttsEngine,
        )
        claudeService = service
        journal = JournalManager(context.applicationContext)
        _uiState.value = _uiState.value.copy(isClaudeActive = true)

        // TTS output → AudioTrack dédié. PAS de capture micro (SpeechRecognizer gère le sien).
        service.onAudioReceived = { data -> playbackTrack?.write(data, 0, data.size) }
        service.onInterrupted = {
            playbackTrack?.pause()
            playbackTrack?.flush()
            playbackTrack?.play()
        }

        service.onTurnComplete = {
            _uiState.value = _uiState.value.copy(userTranscript = "")
        }

        service.onInputTranscription = { text ->
            journal?.logUser(text)
            _uiState.value = _uiState.value.copy(
                userTranscript = _uiState.value.userTranscript + text,
                aiTranscript = ""
            )
        }

        service.onOutputTranscription = { text ->
            journal?.logAssistant(text)
            _uiState.value = _uiState.value.copy(
                aiTranscript = _uiState.value.aiTranscript + text
            )
        }

        service.onDisconnected = { reason ->
            if (_uiState.value.isClaudeActive) {
                stopSession()
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Déconnexion Claude : ${reason ?: "erreur inconnue"}"
                )
            }
        }

        stateObservationJob = viewModelScope.launch {
            while (isActive) {
                delay(100)
                claudeService?.let { svc ->
                    _uiState.value = _uiState.value.copy(
                        connectionState = svc.connectionState.value,
                        isModelSpeaking = svc.isModelSpeaking.value,
                    )
                }
            }
        }

        service.connect { success ->
            if (!success) {
                val msg = when (val s = service.connectionState.value) {
                    is ClaudeConnectionState.Error -> s.message
                    else -> "Échec de connexion à Claude"
                }
                stopSession()
                _uiState.value = _uiState.value.copy(errorMessage = msg)
            }
            // SpeechRecognizer démarré dans connect() via sttEngine.start() — pas de startCapture()
        }
    }

    /** (Re)crée l'AudioTrack de lecture si le sample rate change. Sortie speaker (USAGE_MEDIA). */
    private fun ensurePlaybackTrack(sampleRate: Int) {
        if (playbackTrack != null && playbackSampleRate == sampleRate) return
        playbackTrack?.release()
        playbackSampleRate = sampleRate
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        playbackTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf * 4) // buffer large : absorbe les gaps entre phrases (anti-saccade)
            .build()
            .also { it.play() }
        Log.d(TAG, "AudioTrack de lecture créé à $sampleRate Hz")
    }

    fun stopSession() {
        stateObservationJob?.cancel()
        stateObservationJob = null
        val service = claudeService
        claudeService = null  // null avant disconnect pour briser la récursion onDisconnected → stopSession
        service?.disconnect()
        playbackTrack?.release()
        playbackTrack = null
        playbackSampleRate = 0
        _uiState.value = ClaudeUiState()
        // Relancer le wake word (STT libéré, micro disponible à nouveau)
        if (wakeWordEngine != null) {
            wakeWordEngine?.start()
        }
    }

    fun attachPhoto(bitmap: Bitmap) {
        claudeService?.attachPhoto(bitmap)
    }

    fun sendVideoFrameIfThrottled(bitmap: Bitmap) {
        if (!_uiState.value.isClaudeActive) return
        val now = System.currentTimeMillis()
        if (now - lastVideoFrameTime < VIDEO_FRAME_INTERVAL_MS) return
        lastVideoFrameTime = now
        claudeService?.sendVideoFrame(bitmap)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        stopWakeWord()
        stopSession()
    }
}
