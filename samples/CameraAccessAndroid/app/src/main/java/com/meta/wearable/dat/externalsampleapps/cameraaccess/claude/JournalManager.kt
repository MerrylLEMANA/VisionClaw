package com.meta.wearable.dat.externalsampleapps.cameraaccess.claude

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal local des échanges Claude (décision actée : double sortie obligatoire — une réponse
 * parlée concise + cette version écrite complète en notation réelle).
 *
 * Format Markdown horodaté, append-only, dans le stockage privé de l'app (filesDir). Lisible
 * et exportable. Les écritures viennent de threads différents (STT côté main handler,
 * streaming réponse côté sendExecutor) -> synchronisé.
 */
class JournalManager(context: Context) {

    companion object {
        private const val TAG = "JournalManager"
        private const val FILE_NAME = "claude_journal.md"
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE)

    val path: String get() = file.absolutePath

    fun logUser(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        append("\n## ${timeFormat.format(Date())}\n**Vous :** $t\n")
    }

    fun logAssistant(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        append("**Claude :** $t\n")
    }

    fun readAll(): String = synchronized(lock) {
        if (file.exists()) file.readText() else ""
    }

    private fun append(s: String) {
        synchronized(lock) {
            try {
                file.appendText(s)
            } catch (e: Exception) {
                Log.e(TAG, "Échec écriture journal: ${e.message}")
            }
        }
    }
}
