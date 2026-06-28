package com.meta.wearable.dat.externalsampleapps.cameraaccess.claude

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Miroir de GeminiLiveService.kt, pour Claude.
 *
 * Différence structurelle majeure : Gemini Live est un flux temps réel bidirectionnel
 * (un seul WebSocket ouvert). Claude fonctionne par tours requête/réponse en streaming SSE
 * sur l'API Messages REST. Donc ce service simule la même API externe (mêmes états,
 * mêmes callbacks) mais orchestre en interne : STT -> accumulation -> appel Claude -> TTS.
 *
 * Ce fichier est un SQUELETTE fonctionnel, pas une version finale :
 *   - sttEngine et ttsEngine sont injectés (voir SttTtsEngines.kt) -> à implémenter concrètement.
 *   - Le tool-use Claude (function calling, ex. OpenClaw / calculatrice du "mode solveur",
 *     voir spec §6.5) n'est pas encore branché -> TODO marqué plus bas.
 *   - Pas de gestion d'image multi-frame (spec §6.6) -> une seule image par tour pour l'instant.
 */

sealed class ClaudeConnectionState {
    data object Disconnected : ClaudeConnectionState()
    data object Ready : ClaudeConnectionState() // pas de "handshake" réseau comme Gemini : prêt dès que la clé est valide
    data object Thinking : ClaudeConnectionState() // appel en cours
    data class Error(val message: String) : ClaudeConnectionState()
}

class ClaudeLiveService(
    private val sttEngine: SttEngine,
    private val ttsEngine: TtsEngine,
    private val outputSampleRate: Int = 24000, // garder cohérent avec AudioManager / OUTPUT_AUDIO_SAMPLE_RATE
) {
    companion object {
        private const val TAG = "ClaudeLiveService"
    }

    private val _connectionState = MutableStateFlow<ClaudeConnectionState>(ClaudeConnectionState.Disconnected)
    val connectionState: StateFlow<ClaudeConnectionState> = _connectionState.asStateFlow()

    private val _isModelSpeaking = MutableStateFlow(false)
    val isModelSpeaking: StateFlow<Boolean> = _isModelSpeaking.asStateFlow()

    // Mêmes callbacks que GeminiLiveService, pour rester interchangeable côté StreamViewModel.
    var onAudioReceived: ((ByteArray) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null
    var onInterrupted: (() -> Unit)? = null
    var onDisconnected: ((String?) -> Unit)? = null
    var onInputTranscription: ((String) -> Unit)? = null
    var onOutputTranscription: ((String) -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // streaming SSE : pas de timeout de lecture
        .build()

    private val sendExecutor = Executors.newSingleThreadExecutor()

    // Historique de conversation minimal pour le multi-tour (spec §11 — backlog "conversation multi-tours").
    private val conversationHistory = mutableListOf<JSONObject>()

    private var pendingImageBase64: String? = null
    private var activeCall: Call? = null
    private var bargeInRequested = false

    // File d'attente TTS sérialisée : on ne lance JAMAIS la synthèse d'une phrase tant que la
    // précédente n'a pas fini. Synthétiser en rafale écrase le listener pendant qu'une synthèse
    // est en cours, ce qui tue le processus moteur TTS (DeadObjectException). Voir piège #6.
    private val ttsQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private var ttsBusy = false

    fun connect(callback: (Boolean) -> Unit) {
        if (!ClaudeConfig.isConfigured) {
            _connectionState.value = ClaudeConnectionState.Error("Clé API Claude non configurée")
            callback(false)
            return
        }
        _connectionState.value = ClaudeConnectionState.Ready
        sttEngine.start(
            onPartialResult = { /* TODO: afficher en transcription provisoire si besoin */ },
            onFinalResult = { transcript -> handleUserUtterance(transcript) },
            onError = { err -> Log.e(TAG, "Erreur STT: $err") },
        )
        callback(true)
    }

    fun disconnect() {
        activeCall?.cancel()
        sttEngine.stop()
        ttsEngine.stop()
        _connectionState.value = ClaudeConnectionState.Disconnected
        _isModelSpeaking.value = false
        onDisconnected?.invoke(null)
    }

    /** Equivalent de sendAudio() côté Gemini : on alimente le STT plutôt qu'un WebSocket direct. */
    fun sendAudio(data: ByteArray) {
        if (_connectionState.value == ClaudeConnectionState.Disconnected) return
        sttEngine.feedAudio(data)
    }

    /**
     * Equivalent de sendVideoFrame() côté Gemini. Différence importante (spec §6.6) :
     * Claude reçoit une image par message, pas un flux continu à 1fps. On garde donc la
     * dernière frame en attente et on l'attache au prochain message utilisateur.
     */
    fun sendVideoFrame(bitmap: Bitmap) {
        sendExecutor.execute {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            pendingImageBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        }
    }

    /**
     * Attache une photo haute résolution au prochain message (spec §6.6 — capture déclenchée
     * manuellement, qualité supérieure à une frame du flux vidéo).
     */
    fun attachPhoto(bitmap: Bitmap) {
        sendExecutor.execute {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, baos)
            pendingImageBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        }
    }

    /** Permet d'envoyer du texte directement (debug, ou commande "détaille"/"répète" déjà transcrite). */
    fun sendTextMessage(text: String) {
        handleUserUtterance(text)
    }

    /** Barge-in (spec §6.7) : coupe le TTS en cours et annule l'appel Claude actif s'il y en a un. */
    fun interrupt() {
        bargeInRequested = true
        ttsEngine.stop()
        activeCall?.cancel()
        ttsQueue.clear()
        ttsBusy = false
        _isModelSpeaking.value = false
        sttEngine.resume() // relance l'écoute si elle avait été suspendue pour le TTS
        onInterrupted?.invoke()
    }

    // --- Privé ---

    private fun handleUserUtterance(transcript: String) {
        if (transcript.isBlank()) return
        onInputTranscription?.invoke(transcript)
        bargeInRequested = false

        val userContent = JSONArray()
        pendingImageBase64?.let { img ->
            userContent.put(JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", "image/jpeg")
                    put("data", img)
                })
            })
            pendingImageBase64 = null
        }
        userContent.put(JSONObject().apply {
            put("type", "text")
            put("text", transcript)
        })

        val userMessage = JSONObject().apply {
            put("role", "user")
            put("content", userContent)
        }
        conversationHistory.add(userMessage)

        streamClaudeResponse()
    }

    private fun streamClaudeResponse() {
        _connectionState.value = ClaudeConnectionState.Thinking

        val body = JSONObject().apply {
            put("model", ClaudeConfig.selectedModel)
            put("max_tokens", ClaudeConfig.MAX_TOKENS)
            put("system", ClaudeConfig.systemPrompt)
            put("stream", true)
            put("messages", JSONArray(conversationHistory))
            // Mode solveur : outil d'exécution de code côté serveur Anthropic. Claude écrit et
            // exécute du Python dans le sandbox Anthropic ; le résultat revient dans CETTE réponse
            // streamée, sans tool_result à renvoyer.
            put("tools", JSONArray().put(JSONObject().apply {
                put("type", ClaudeConfig.CODE_EXECUTION_TOOL_TYPE)
                put("name", "code_execution")
            }))
        }

        val request = Request.Builder()
            .url(ClaudeConfig.MESSAGES_URL)
            .addHeader("x-api-key", ClaudeConfig.apiKey)
            .addHeader("anthropic-version", ClaudeConfig.ANTHROPIC_VERSION)
            .addHeader("anthropic-beta", ClaudeConfig.CODE_EXECUTION_BETA)
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        activeCall = call

        // Note : okhttp ne fournit pas nativement un parsing SSE événement-par-événement aussi
        // propre qu'un client SSE dédié. Pour un vrai streaming robuste, envisager OkHttp-SSE ou
        // une lecture ligne-à-ligne du corps de réponse comme ci-dessous (suffisant pour un skeleton).
        sendExecutor.execute {
            try {
                val response = call.execute()
                if (!response.isSuccessful) {
                    val errBody = response.body?.string()
                    Log.e(TAG, "Erreur API Claude: ${response.code} $errBody")
                    _connectionState.value = ClaudeConnectionState.Error("HTTP ${response.code}")
                    onDisconnected?.invoke("Erreur API Claude (${response.code})")
                    return@execute
                }

                val reader = BufferedReader(InputStreamReader(response.body?.byteStream()))
                val sentenceBuffer = StringBuilder()
                val fullResponseBuffer = StringBuilder()

                reader.useLines { lines ->
                    for (line in lines) {
                        if (bargeInRequested) break
                        if (!line.startsWith("data: ")) continue
                        val payload = line.removePrefix("data: ").trim()
                        if (payload.isEmpty()) continue

                        val event = try { JSONObject(payload) } catch (e: Exception) { continue }
                        when (event.optString("type")) {
                            "content_block_start" -> {
                                // Le mode solveur ouvre un bloc server_tool_use quand Claude lance
                                // du code. On reste en "Thinking" (l'overlay affiche déjà "Réflechit").
                                val block = event.optJSONObject("content_block")
                                when (block?.optString("type")) {
                                    "server_tool_use" ->
                                        Log.d(TAG, "Exécution de code (mode solveur) en cours")
                                    "code_execution_tool_result" ->
                                        Log.d(TAG, "Résultat du code reçu")
                                }
                            }
                            "content_block_delta" -> {
                                val delta = event.optJSONObject("delta") ?: continue
                                // IMPORTANT : ne traiter QUE le texte de Claude (text_delta). Le code
                                // arrive en input_json_delta et les résultats en blocs séparés — il ne
                                // faut ni les lire à voix haute ni les mettre dans la réponse.
                                if (delta.optString("type") != "text_delta") continue
                                val text = delta.optString("text")
                                if (text.isEmpty()) continue
                                sentenceBuffer.append(text)
                                fullResponseBuffer.append(text)

                                // Stratégie time-to-first-audio (spec §8) : on envoie au TTS dès
                                // qu'on a une phrase complète, pas en attendant tout le message.
                                if (text.contains(Regex("[.!?]"))) {
                                    flushSentenceToTts(sentenceBuffer.toString())
                                    sentenceBuffer.clear()
                                }
                            }
                            "message_delta" -> {
                                // pause_turn : l'API a suspendu un tour long (rare pour un calcul court,
                                // limite 90s par cellule). On le journalise ; la continuation n'est pas
                                // implémentée (réponse renvoyée telle quelle, cf. doc).
                                val stop = event.optJSONObject("delta")?.optString("stop_reason")
                                if (stop == "pause_turn") Log.w(TAG, "pause_turn reçu (tour long suspendu)")
                            }
                            "message_stop" -> {
                                if (sentenceBuffer.isNotEmpty()) {
                                    flushSentenceToTts(sentenceBuffer.toString())
                                    sentenceBuffer.clear()
                                }
                                onOutputTranscription?.invoke(fullResponseBuffer.toString())
                                conversationHistory.add(JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", fullResponseBuffer.toString())
                                })
                                _connectionState.value = ClaudeConnectionState.Ready
                                onTurnComplete?.invoke()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (!bargeInRequested) {
                    Log.e(TAG, "Erreur streaming Claude: ${e.message}")
                    _connectionState.value = ClaudeConnectionState.Error(e.message ?: "Erreur inconnue")
                    onDisconnected?.invoke(e.message)
                }
            } finally {
                activeCall = null
            }
        }
    }

    /**
     * Nettoie le texte avant synthèse vocale : retire le markdown (gras, titres, puces,
     * code) et remplace les symboles mathématiques courants par leur forme parlée. Le TTS
     * Android lit sinon "**", "##" ou "²" littéralement, ce qui rend la sortie incompréhensible.
     * Le journal, lui, conserve le markdown original (cette fonction n'agit que sur le TTS).
     */
    private fun sanitizeForTts(text: String): String {
        var s = text
        s = s.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1") // **gras**
        s = s.replace(Regex("\\*(.+?)\\*"), "$1")        // *italique*
        s = s.replace(Regex("`(.+?)`"), "$1")            // `code`
        s = s.replace(Regex("(?m)^#{1,6}\\s*"), "")      // titres
        s = s.replace(Regex("(?m)^\\s*[-*]\\s+"), "")    // puces
        // Différentielles : le TTS lit "dr" comme "docteur", "dx"/"dz" mal. On les oralise.
        // (dθ avant θ pour ne pas casser le remplacement.)
        s = s.replace("dθ", " dé thêta ")
        s = s.replace(Regex("\\bdx\\b"), "dé x")
        s = s.replace(Regex("\\bdy\\b"), "dé i grec")
        s = s.replace(Regex("\\bdz\\b"), "dé z")
        s = s.replace(Regex("\\bdr\\b"), "dé r")
        s = s.replace(Regex("\\bdt\\b"), "dé t")
        s = s.replace("θ", " thêta ")
        s = s.replace("²", " au carré")
        s = s.replace("³", " au cube")
        s = s.replace("√", " racine de ")
        s = s.replace("∫", " intégrale de ")
        s = s.replace("π", " pi ")
        s = s.replace("×", " fois ")
        s = s.replace("÷", " divisé par ")
        s = s.replace("≈", " environ ")
        s = s.replace("→", " donne ")
        s = s.replace(Regex("[#*_>`~|]"), "")            // symboles markdown résiduels
        return s.trim()
    }

    @Synchronized
    private fun flushSentenceToTts(rawSentence: String) {
        val sentence = sanitizeForTts(rawSentence)
        if (sentence.isBlank() || bargeInRequested) return
        ttsQueue.add(sentence)
        _isModelSpeaking.value = true
        if (!ttsBusy) {
            sttEngine.pause() // couper le STT pour qu'il ne capte pas la voix de Claude
            speakNext()
        }
    }

    /** Dépile et synthétise la phrase suivante. Appelé en série via onDone — jamais en parallèle. */
    @Synchronized
    private fun speakNext() {
        if (bargeInRequested) {
            ttsQueue.clear()
            ttsBusy = false
            finishSpeaking()
            return
        }
        val next = ttsQueue.poll()
        if (next == null) {
            ttsBusy = false
            finishSpeaking()
            return
        }
        ttsBusy = true
        ttsEngine.synthesize(
            text = next,
            sampleRate = outputSampleRate,
            onAudioChunk = { audio -> onAudioReceived?.invoke(audio) },
            onDone = { speakNext() },
            onError = { err ->
                Log.e(TAG, "Erreur TTS: $err")
                speakNext()
            },
        )
    }

    private fun finishSpeaking() {
        _isModelSpeaking.value = false
        sttEngine.resume()
    }
}
