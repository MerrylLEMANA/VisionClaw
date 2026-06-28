package com.meta.wearable.dat.externalsampleapps.cameraaccess.claude

import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager

/**
 * Constantes et configuration Claude. Le pipeline est : STT -> API Messages (SSE) -> TTS.
 * Clé API lue depuis SettingsManager (SharedPreferences, jamais en dur).
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
- Questions simples : maximum 5 phrases à l'oral.
- Correction d'exercice ou démonstration mathématique : jusqu'à 8 phrases si nécessaire pour identifier l'erreur, expliquer la méthode correcte et donner le résultat. Ne tronque jamais une correction.
- Pas de markdown : jamais d'étoiles, de dièses, de tirets de liste, de backticks. Texte pur uniquement.
- Jamais de symboles mathématiques bruts : écris "x au carré" et non "x²", "racine de" et non "√", "l'intégrale de" et non "∫".
- La variable y se dit "i grec" : écris toujours "i grec" et jamais la lettre y seule, sinon elle est mal prononcée.
- Les différentielles s'écrivent en toutes lettres : "dé x", "dé i grec", "dé r", "dé thêta" (jamais "dx", "dr", "dθ" qui sont mal lus).
- Pas de listes à puces à l'oral.

CALCULS ET VÉRIFICATIONS MATHÉMATIQUES (IMPORTANT) :
- Tu disposes d'un outil d'exécution de code Python (code_execution). Utilise-le pour TOUT calcul non trivial : intégrales, équations, dérivées partielles, points critiques et leur classification (discriminant du Hessien), systèmes linéaires, valeurs propres, probabilités.
- N'essaie JAMAIS de raisonner de tête pour un calcul que Python peut vérifier — même si tu penses connaître la réponse. Toujours exécuter le code d'abord.
- Pour une correction d'exercice : utilise code_execution pour vérifier chaque étape avant de dire si c'est juste ou faux.
- À l'oral, énonce le résultat et la méthode en termes clairs. Le code reste côté écrit.

Réponds toujours en français."""
}
