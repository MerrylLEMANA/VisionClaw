package com.meta.wearable.dat.externalsampleapps.cameraaccess.claude

import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager

/**
 * Miroir de GeminiConfig.kt, pour Claude.
 *
 * Différence clé avec Gemini Live : Claude n'a pas d'API audio native bidirectionnelle.
 * On parle donc à l'API Messages (REST, streaming SSE), pas à un WebSocket temps réel.
 * Le pipeline complet est : audio -> STT -> texte -> Claude (streaming) -> texte -> TTS -> audio.
 */
object ClaudeConfig {

    // Endpoint Anthropic Messages API
    const val MESSAGES_URL = "https://api.anthropic.com/v1/messages"
    const val ANTHROPIC_VERSION = "2023-06-01"

    // Outil d'exécution de code côté serveur Anthropic (mode solveur). S'exécute dans le
    // sandbox Anthropic, le résultat revient dans la même réponse streamée (pas de tool_result
    // à renvoyer). Voir doc "Code execution tool".
    const val CODE_EXECUTION_BETA = "code-execution-2025-08-25"
    const val CODE_EXECUTION_TOOL_TYPE = "code_execution_20250825"

    // Modèles disponibles — voir spec §6.4 pour la logique de routage.
    const val MODEL_HAIKU = "claude-haiku-4-5-20251001"
    const val MODEL_SONNET = "claude-sonnet-4-6"
    const val MODEL_OPUS = "claude-opus-4-8"

    // Modèle par défaut pour ce skeleton — à affiner avec le routage plus tard.
    const val DEFAULT_MODEL = MODEL_SONNET

    // 4096 : laisse de la place au code exécuté + résultat + narration. La sortie PARLÉE reste
    // bornée à 3 phrases par le system prompt (ce n'est qu'un plafond, pas une cible).
    const val MAX_TOKENS = 4096

    val apiKey: String
        get() = SettingsManager.claudeAPIKey

    val systemPrompt: String
        get() = SettingsManager.claudeSystemPrompt

    val selectedModel: String
        get() = SettingsManager.claudeModel

    val isConfigured: Boolean
        get() = apiKey != "YOUR_ANTHROPIC_API_KEY" && apiKey.isNotEmpty()

    // Prompt par défaut — reprend les règles "langage naturel simplifié" de la spec §6.7 (v1.2).
    const val DEFAULT_SYSTEM_PROMPT = """Tu es un assistant vocal pour quelqu'un qui porte des lunettes intelligentes Ray-Ban Meta. Tu peux voir à travers sa caméra quand une image t'est fournie. Ta réponse sera lue à voix haute par synthèse vocale.

RÈGLES ABSOLUES POUR LA SORTIE PARLÉE :
- Maximum 3 phrases à l'oral, quelle que soit la complexité. Toujours.
- Pas de markdown : jamais d'étoiles, de dièses, de tirets de liste, de backticks. Texte pur uniquement.
- Jamais de symboles mathématiques bruts : écris "x au carré" et non "x²", "racine de" et non "√", "l'intégrale de" et non "∫".
- La variable y se dit "i grec" : écris toujours "i grec" et jamais la lettre y seule, sinon elle est mal prononcée.
- Les différentielles s'écrivent en toutes lettres : "dé x", "dé i grec", "dé r", "dé thêta" (jamais "dx", "dr", "dθ" qui sont mal lus).
- Pas de listes à puces à l'oral.

CALCULS NUMÉRIQUES (IMPORTANT) :
- Tu disposes d'un outil d'exécution de code Python (code_execution). Utilise-le pour TOUT calcul non trivial : intégrales, équations, grandes opérations, résultats sensibles à la précision.
- N'invente JAMAIS un résultat de tête : exécute le code et donne le résultat vérifié.
- À l'oral, donne uniquement le résultat final en une phrase ("le volume vaut environ 7,2 unités cubes"), pas le code ni les étapes intermédiaires.
- Le code et le détail restent côté écrit ; la voix ne dit que le résultat et, si utile, une phrase de méthode.

Réponds toujours en français."""
}
