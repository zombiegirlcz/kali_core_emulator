package com.linux_core.ui.terminal

import android.content.Intent
import android.util.Log
import com.linux_core.core.RootfsManager
import com.linux_core.core.TerminalService
import java.io.File

/**
 * Zpracovani "open with" / share intentu (ACTION_VIEW / ACTION_EDIT) —
 * zkopiruje sdileny soubor do rootfs tmp a otevre ho v nano v aktivni session.
 */
internal fun TerminalActivity.handleFileIntent(intent: Intent) {
    val action = intent.action
    if (Intent.ACTION_VIEW == action || Intent.ACTION_EDIT == action) {
        val uri = intent.data ?: return
        val fileName = getFileNameFromUri(uri)

        // Ensure layout migration before resolving rootfs paths
        RootfsManager.ensureMigrated(applicationContext)

        // Determine rootfs directory name
        var rootfsDirName = intent.getStringExtra("rootfsDirName")
        if (rootfsDirName == null) {
            val kaliSetup = File(filesDir, "nh/distro/kali/root/.setup_done")
            val parrotSetup = File(filesDir, "nh/distro/parrot/root/.setup_done")
            rootfsDirName =
                when {
                    kaliSetup.exists() -> "nh/distro/kali"
                    parrotSetup.exists() -> "nh/distro/parrot"
                    else -> "nh/distro/kali"
                }
        }

        val copiedFile = copyUriToChrootTmp(uri, fileName, rootfsDirName)
        if (copiedFile != null) {
            val command = "nano /tmp/nethunter_edit_$fileName"

            // If GUI is active, automatically switch to CLI so they see the editor
            if (activeViewMode != "CLI") {
                switchViewMode("CLI")
            }

            val activeSession = currentSession ?: TerminalService.sessions.firstOrNull()
            if (activeSession != null) {
                // Send command to active session
                switchToSession(activeSession)
                terminalView.post {
                    terminalView.postDelayed({
                        activeSession.write("\u0003\u0015$command\r")
                    }, 500)
                }
            } else {
                // Save for when the session starts
                pendingNanoCommand = command
            }
        }
    }
}

internal fun TerminalActivity.getFileNameFromUri(uri: android.net.Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx != -1) {
                    result = cursor.getString(idx)
                }
            }
        } catch (e: Exception) {
            Log.e(TerminalActivity.TAG, "Failed to query displayName: ${e.message}")
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    // Sanitize filename to avoid weird shell characters
    return (result ?: "unnamed_file").replace(Regex("[^a-zA-Z0-9._-]"), "_")
}

internal fun TerminalActivity.copyUriToChrootTmp(
    uri: android.net.Uri,
    fileName: String,
    rootfsDirName: String,
): File? {
    try {
        val destDir = File(filesDir, "$rootfsDirName/tmp")
        if (!destDir.exists()) {
            destDir.mkdirs()
        }
        val destFile = File(destDir, "nethunter_edit_$fileName")
        contentResolver.openInputStream(uri)?.use { inputStream ->
            destFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        Log.i(TerminalActivity.TAG, "Successfully copied $uri to ${destFile.absolutePath}")
        return destFile
    } catch (e: Exception) {
        Log.e(TerminalActivity.TAG, "Failed to copy URI to chroot tmp: ${e.message}")
        return null
    }
}
