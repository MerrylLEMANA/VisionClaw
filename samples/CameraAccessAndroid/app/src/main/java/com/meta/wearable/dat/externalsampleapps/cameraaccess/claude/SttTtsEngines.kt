package com.meta.wearable.dat.externalsampleapps.cameraaccess.claude

/**
 * Interfaces STT/TTS injectées dans ClaudeLiveService.
 *
 * Implémentations actuelles : AndroidSttEngine (SpeechRecognizer) + AndroidTtsEngine (TextToSpeech).
 * Alternative future (spec §10) : STT cloud ou ElevenLabs Flash pour latence réduite.
 *
 * Le TTS est appelé phrase par phrase (time-to-first-audio) — ne pas attendre la réponse
 * complète de Claude avant de commencer à synthétiser.
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
