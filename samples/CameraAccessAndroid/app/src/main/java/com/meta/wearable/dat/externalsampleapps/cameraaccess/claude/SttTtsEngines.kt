package com.meta.wearable.dat.externalsampleapps.cameraaccess.claude

/**
 * Gemini Live parle nativement audio <-> audio. Claude ne le fait pas (au moment de l'écriture) :
 * on doit donc transcrire la voix en texte (STT), envoyer le texte à Claude, puis synthétiser
 * sa réponse texte en audio (TTS). Ces deux interfaces isolent ce choix pour qu'on puisse changer
 * de moteur (Android natif <-> cloud) sans toucher à ClaudeLiveService.
 *
 * TODO (à brancher au moment de l'intégration, voir spec §10 — Pile technique) :
 *   - SttEngine  : implémentation par défaut avec android.speech.SpeechRecognizer
 *                  (streaming partiel via setRecognitionListener) ou un STT cloud.
 *   - TtsEngine  : implémentation par défaut avec android.speech.tts.TextToSpeech
 *                  (offline/gratuit) ou ElevenLabs Flash (latence plus faible, payant).
 *
 * Important (spec §7/§8) : le levier de latence le plus rentable est le streaming TTS phrase
 * par phrase (time-to-first-audio), pas d'attendre la réponse complète de Claude avant de
 * commencer à synthétiser. synthesizeStreaming() est conçu pour ça.
 */
interface SttEngine {
    /** Démarre l'écoute. onPartialResult est appelé en continu (transcription provisoire). */
    fun start(onPartialResult: (String) -> Unit, onFinalResult: (String) -> Unit, onError: (String) -> Unit)

    /** Pousse un chunk audio brut (PCM16 mono) si le moteur consomme l'audio plutôt que le micro directement. */
    fun feedAudio(data: ByteArray)

    /** Signale explicitement la fin de la parole (endpointing) — voir spec §8 sur la latence. */
    fun endOfSpeech()

    /** Suspend l'écoute sans détruire le recognizer (utilisé pendant que le TTS joue). */
    fun pause() {}

    /** Reprend l'écoute après un pause(). */
    fun resume() {}

    fun stop()
}

interface TtsEngine {
    /**
     * Synthétise un segment de texte et retourne l'audio en PCM16 mono à [sampleRate] Hz,
     * via callback pour permettre un streaming par chunks plutôt qu'un bloc complet.
     */
    fun synthesize(
        text: String,
        sampleRate: Int,
        onAudioChunk: (ByteArray) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    )

    fun stop()
}
