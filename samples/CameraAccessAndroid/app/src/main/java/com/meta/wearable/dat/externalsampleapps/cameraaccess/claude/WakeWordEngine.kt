package com.meta.wearable.dat.externalsampleapps.cameraaccess.claude

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Détection de wake-word via SpeechRecognizer Android (aucun SDK tiers, aucun compte).
 *
 * Principe : SpeechRecognizer écoute en boucle courte (~3 s). Si le transcript contient
 * un des mots-déclencheurs, onDetected() est appelé. Autrement, on relance immédiatement.
 *
 * Contrainte clé (piège #1 de CLAUDE.md) : SpeechRecognizer et la session Claude tiennent
 * tous les deux le micro en exclusivité. Ce moteur NE doit JAMAIS tourner en même temps
 * qu'une session Claude active — ClaudeSessionViewModel gère ce cycle stop/start.
 *
 * Mots-déclencheurs par défaut : "jarvis", "claude", "hey claude".
 * "Jarvis" est reconnu correctement par le STT fr-FR ; "claude" aussi.
 */
class WakeWordEngine(
    private val context: Context,
    private val keywords: List<String> = listOf("jarvis", "claude", "hey claude"),
    private val onDetected: () -> Unit,
) {
    companion object {
        private const val TAG = "WakeWordEngine"
        private const val RESTART_DELAY_MS = 150L
        private const val BUSY_DELAY_MS = 600L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    private var recognizer: SpeechRecognizer? = null

    fun start() {
        if (active) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "SpeechRecognizer non disponible — wake-word désactivé")
            return
        }
        active = true
        mainHandler.post { listenOnce() }
        Log.d(TAG, "Wake-word démarré (mots : $keywords)")
    }

    fun stop() {
        active = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            recognizer?.stopListening()
            recognizer?.destroy()
            recognizer = null
        }
        Log.d(TAG, "Wake-word arrêté")
    }

    private fun listenOnce() {
        if (!active) return

        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r

        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                if (!active) return
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val hit = matches?.any { result ->
                    keywords.any { kw -> result.lowercase().contains(kw) }
                } ?: false

                recognizer?.destroy()
                recognizer = null

                if (hit) {
                    Log.d(TAG, "Wake-word détecté dans : $matches")
                    onDetected()
                    // Ne pas relancer : ClaudeSessionViewModel reprendra après la session
                } else {
                    mainHandler.postDelayed({ listenOnce() }, RESTART_DELAY_MS)
                }
            }

            override fun onError(error: Int) {
                if (!active) return
                recognizer?.destroy()
                recognizer = null
                val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) BUSY_DELAY_MS
                            else RESTART_DELAY_MS
                mainHandler.postDelayed({ listenOnce() }, delay)
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Durée courte : on veut réagir vite, pas transcrire un long discours
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }
        r.startListening(intent)
    }
}
