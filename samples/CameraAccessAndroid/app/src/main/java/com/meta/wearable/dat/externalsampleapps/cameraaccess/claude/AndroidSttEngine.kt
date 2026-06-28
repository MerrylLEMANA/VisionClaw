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
import java.util.Locale

/**
 * Implémentation par défaut de SttEngine avec android.speech.SpeechRecognizer.
 *
 * ⚠️ POINT D'ARCHITECTURE IMPORTANT (à ne pas découvrir au moment du bug en prod) :
 *
 * SpeechRecognizer gère LUI-MÊME l'accès au micro — il ne consomme pas de PCM qu'on lui
 * pousserait. Or AudioManager.kt (celui déjà présent côté Gemini) ouvre AUSSI le micro,
 * via AudioRecord, pour streamer vers Gemini Live. Les deux ne peuvent pas tenir le micro
 * en même temps.
 *
 * Donc : feedAudio() est un NO-OP volontaire dans cette implémentation. Quand on utilise
 * ClaudeLiveService + AndroidSttEngine, NE PAS démarrer AudioManager.startCapture() en
 * parallèle — c'est SpeechRecognizer qui doit avoir l'exclusivité du micro pendant l'écoute.
 * Voir la note de câblage dans StreamViewModel (INTEGRATION.md, section "Important").
 *
 * Alternative future (spec §10, "STT cloud") : un moteur cloud qui accepte du PCM en
 * streaming pourrait, lui, consommer feedAudio() normalement et laisser AudioManager
 * posséder le micro — c'est la voie à explorer si on veut éviter ce conflit.
 */
class AndroidSttEngine(private val context: Context) : SttEngine {

    companion object {
        private const val TAG = "AndroidSttEngine"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var paused = false

    override fun start(
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError("Reconnaissance vocale non disponible sur cet appareil")
                return@post
            }

            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle) {
                        val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val best = matches?.firstOrNull().orEmpty()
                        Log.d(TAG, "Résultat final: $best")
                        if (best.isNotBlank() && !paused) onFinalResult(best)
                        // Relance l'écoute pour le prochain tour (mode "toujours prêt").
                        if (!paused) startListeningInternal()
                    }

                    override fun onPartialResults(partialResults: Bundle) {
                        val matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        matches?.firstOrNull()?.let(onPartialResult)
                    }

                    override fun onError(error: Int) {
                        if (paused) return
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> startListeningInternal()
                            // Recognizer occupé après un stopListening/startListening rapide —
                            // on attend 500ms avant de relancer.
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                mainHandler.postDelayed({ if (!paused) startListeningInternal() }, 500)
                            else -> {
                                Log.e(TAG, "Erreur SpeechRecognizer: $error")
                                onError("Erreur reconnaissance vocale (code $error)")
                                mainHandler.postDelayed({ if (!paused) startListeningInternal() }, 500)
                            }
                        }
                    }

                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            startListeningInternal()
        }
    }

    private fun startListeningInternal() {
        // fr-FR préféré ; si non disponible, le recognizer utilise automatiquement
        // le locale système — mieux que fr-CA qui n'est pas installé sur tous les appareils.
        val lang = if (Locale.getAvailableLocales().any { it.toLanguageTag() == "fr-FR" }) "fr-FR"
                   else Locale.getDefault().toLanguageTag()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        mainHandler.post {
            try {
                recognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Impossible de démarrer l'écoute: ${e.message}")
            }
        }
    }

    override fun feedAudio(data: ByteArray) {
        // Volontairement no-op — voir le commentaire d'architecture en tête de fichier.
    }

    override fun endOfSpeech() {
        mainHandler.post { recognizer?.stopListening() }
    }

    // Suspend l'écoute pendant que le TTS joue, pour éviter que le STT capte la voix de Claude.
    override fun pause() {
        paused = true
        mainHandler.post { recognizer?.stopListening() }
    }

    // Reprend l'écoute après la fin du TTS. Délai court pour laisser l'audio se terminer.
    override fun resume() {
        paused = false
        mainHandler.postDelayed({ if (!paused) startListeningInternal() }, 300)
    }

    override fun stop() {
        mainHandler.post {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
        }
    }
}
