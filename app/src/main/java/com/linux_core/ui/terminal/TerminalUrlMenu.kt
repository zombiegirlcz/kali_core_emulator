package com.linux_core.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.linux_core.core.terminal.UrlExtractor

/**
 * Kontextové menu terminálu — položka „📋 Kopírovat URL".
 *
 * Termux `TextSelectionCursorController` dává po podržení prstu plovoucí lištu
 * `copy | paste | more`, kde `more` volá `stopTextSelectionMode()` a hned
 * `terminalView.showContextMenu()`. Selekce v tu chvíli **už neexistuje**,
 * takže odkazy se nehledají ve vybraném textu, ale v celém bufferu terminálu
 * (viditelná obrazovka + scrollback) — `getTranscriptTextWithFullLinesJoined()`
 * spojuje zalomené řádky, takže URL rozlámaná šířkou okna přijde v jednom kusu.
 *
 * Menu funguje jen díky `registerForContextMenu(terminalView)` v `onCreate`.
 */
internal const val MENU_ITEM_COPY_URL = 1001

/** Seznam odkazů z celého bufferu → dialog → klik kopíruje do schránky. */
internal fun TerminalActivity.showUrlPicker() {
    val urls =
        try {
            val transcript =
                currentSession?.emulator?.screen?.getTranscriptTextWithFullLinesJoined().orEmpty()
            UrlExtractor.extract(transcript)
        } catch (e: Exception) {
            Log.w(TerminalActivity.TAG, "showUrlPicker: transcript nedostupný: ${e.message}")
            emptyList()
        }

    if (urls.isEmpty()) {
        Toast.makeText(this, "V terminálu není žádný http/https odkaz", Toast.LENGTH_SHORT).show()
        return
    }

    val labels = urls.toTypedArray<CharSequence>()
    android.app.AlertDialog.Builder(this)
        .setTitle("Odkazy v terminálu (${urls.size})")
        .setItems(labels) { _, which ->
            copyToClipboard(urls[which])
        }
        .setNegativeButton("Zrušit", null)
        .show()
}

/** Kopie do systémové schránky (stejná cesta jako `nh clip`, jen z UI). */
internal fun TerminalActivity.copyToClipboard(text: String) {
    try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("nethunter_url", text))
        Toast.makeText(this, "📋 Zkopírováno: ${text.take(60)}", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Log.e(TerminalActivity.TAG, "copyToClipboard failed: ${e.message}")
        Toast.makeText(this, "[-] Kopírování selhalo: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
