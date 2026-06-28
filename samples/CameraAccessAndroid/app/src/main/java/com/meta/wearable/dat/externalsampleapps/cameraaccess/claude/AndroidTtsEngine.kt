package com.meta.wearable.dat.externalsampleapps.cameraaccess.claude

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Implémentation par défaut de TtsEngine avec android.speech.tts.TextToSpeech (gratuit, offline).
 *
 * ⚠️ NUANCE TECHNIQUE IMPORTANTE :
 * TextToSpeech.speak() ne donne pas accès à un flux PCM — il joue directement au système.
 * Pour respecter l'interface TtsEngine (qui doit retourner du PCM, afin de rester compatible
 * avec AudioManager.playAudio() côté glasses), on utilise synthesizeToFile() puis on lit le
 * fichier WAV produit et on en extrait les données PCM brutes.
 *
 * Le sample rate RÉEL du WAV dépend de la voix/locale installée sur l'appareil — ce n'est
 * PAS garanti d'être 24000 Hz (la constante OUTPUT_AUDIO_SAMPLE_RATE de Gemini). Cette
 * implémentation lit donc le sample rate exact depuis l'en-tête WAV et le retourne via
 * actualSampleRate plutôt que de prétendre respecter le paramètre [sampleRate] demandé.
 * Le code appelant (ClaudeLiveService / le pont vers AudioManager) doit configurer son
 * AudioTrack avec CE sample rate réel, pas une valeur supposée à l'avance — sinon la voix
 * sortira trop aiguë ou trop grave selon l'écart.
 *
 * Alternative future (spec §10) : ElevenLabs Flash en streaming donne directement du PCM
 * à un sample rate fixe et documenté, ce qui évite ce problème — à privilégier si la latence
 * ou la qualité de TextToSpeech système déçoit à l'usage.
 */
class AndroidTtsEngine(
    private val context: Context,
    /** Appelé une fois qu'on connaît le sample rate réel du WAV produit, avant le premier chunk. */
    private val onActualSampleRateKnown: ((Int) -> Unit)? = null,
) : TtsEngine {

    companion object {
        private const val TAG = "AndroidTtsEngine"
        private const val WAV_HEADER_SIZE = 44
    }

    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                // fr-FR préféré ; fr-CA n'est pas installé sur tous les appareils (cause silencieuse
                // de WAV vides). On vérifie la disponibilité avant de fixer la langue.
                val frFr = Locale.FRANCE
                val res = tts?.isLanguageAvailable(frFr)
                if (res == TextToSpeech.LANG_AVAILABLE ||
                    res == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                    res == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) {
                    tts?.language = frFr
                } else {
                    tts?.language = Locale.FRENCH // langue générique, laisse l'appareil choisir la voix
                    Log.w(TAG, "fr-FR indisponible (code $res), repli sur Locale.FRENCH")
                }
                selectBestFrenchVoice()
                tts?.setSpeechRate(0.70f) // plus lent = plus intelligible à l'oral
            } else {
                Log.e(TAG, "Échec d'initialisation de TextToSpeech")
            }
        }
    }

    /**
     * La voix par défaut du moteur TTS est souvent la plus basique (robotique). On cherche
     * parmi les voix françaises installées celle de meilleure qualité, en évitant les voix
     * réseau (latence/échecs hors-ligne) et en préférant la qualité la plus haute.
     */
    private fun selectBestFrenchVoice() {
        val engine = tts ?: return
        val frenchVoices = try {
            engine.voices?.filter {
                it.locale.language == "fr" && !it.isNetworkConnectionRequired && !it.features.contains(
                    android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Impossible de lister les voix: ${e.message}")
            null
        } ?: return

        // Log toutes les voix disponibles pour faciliter le choix (genre, qualité).
        frenchVoices.sortedByDescending { it.quality }.forEach {
            Log.d(TAG, "Voix disponible: ${it.name} locale=${it.locale} qualité=${it.quality} features=${it.features}")
        }

        // Noms de voix Google TTS connus pour être féminins sur Android (fr-CA et fr-FR).
        // Priorité : voix féminine fr-CA > féminine fr-FR > meilleure qualité disponible.
        val femaleNamePatterns = listOf("caa", "cac", "frc", "fra", "fre")
        val best = frenchVoices
            .sortedWith(
                compareByDescending<android.speech.tts.Voice> { v ->
                    femaleNamePatterns.any { v.name.contains(it) }
                }
                    .thenByDescending { it.locale.country == "CA" }
                    .thenByDescending { it.locale.country == "FR" }
                    .thenByDescending { it.quality }
            )
            .firstOrNull()
        if (best != null) {
            engine.voice = best
            Log.d(TAG, "Voix TTS sélectionnée: ${best.name} locale=${best.locale} qualité=${best.quality}")
        }
    }

    override fun synthesize(
        text: String,
        sampleRate: Int,
        onAudioChunk: (ByteArray) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val engine = tts
        if (engine == null || !ready) {
            onError("TextToSpeech non prêt")
            return
        }

        val thisUtteranceId = UUID.randomUUID().toString()
        val tempFile = File(context.cacheDir, "tts_$thisUtteranceId.wav")

        // L'engine TTS est partagé : si synthesize() est appelé par phrase (ClaudeLiveService),
        // setOnUtteranceProgressListener écrase le listener précédent. On NE peut donc PAS se fier
        // au tempFile capturé dans la closure — on reconstruit le fichier depuis l'utteranceId
        // passé au callback, qui identifie la phrase réellement terminée.
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                val doneFile = File(context.cacheDir, "tts_$utteranceId.wav")
                try {
                    val (actualSampleRate, pcmData) = extractPcmFromWav(doneFile)
                    onActualSampleRateKnown?.invoke(actualSampleRate)
                    if (pcmData.isNotEmpty()) onAudioChunk(pcmData)
                } catch (e: Exception) {
                    onError("Erreur lecture WAV: ${e.message}")
                } finally {
                    doneFile.delete()
                    onDone()
                }
            }

            @Deprecated("Deprecated in API, gardé pour compatibilité minSdk")
            override fun onError(utteranceId: String?) {
                onError("Erreur de synthèse TTS")
                File(context.cacheDir, "tts_$utteranceId.wav").delete()
            }
        })

        val result = engine.synthesizeToFile(text, null, tempFile, thisUtteranceId)
        if (result != TextToSpeech.SUCCESS) {
            onError("synthesizeToFile a échoué immédiatement")
        }
    }

    override fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    /**
     * Lit un fichier WAV PCM16 mono produit par TextToSpeech et retourne (sampleRate, donnéesPcm).
     * Suppose un en-tête WAV canonique de 44 octets (cas standard d'Android TextToSpeech).
     */
    private fun extractPcmFromWav(file: File): Pair<Int, ByteArray> {
        val bytes = file.readBytes()
        if (bytes.size <= WAV_HEADER_SIZE) return Pair(22050, ByteArray(0))

        // Le sample rate est encodé en little-endian aux octets 24-27 de l'en-tête WAV standard.
        val sampleRate = (bytes[24].toInt() and 0xFF) or
            ((bytes[25].toInt() and 0xFF) shl 8) or
            ((bytes[26].toInt() and 0xFF) shl 16) or
            ((bytes[27].toInt() and 0xFF) shl 24)

        val pcm = bytes.copyOfRange(WAV_HEADER_SIZE, bytes.size)
        return Pair(sampleRate, pcm)
    }
}
