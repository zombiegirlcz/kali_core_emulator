package com.linux_core.core

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import java.io.File

/**
 * ShareReceiverActivity — „Open with" / share příjemce (Termux-style).
 *
 * Objeví se v systémovém dialogu „Otevřít v aplikaci" i ve share sheetu
 * (ACTION_VIEW / ACTION_SEND / ACTION_SEND_MULTIPLE). Přijaté soubory se
 * zkopírují do filesDir/share/, která je v guestu bindnutá jako /root/share
 * (viz boot skript build_binds) — stejný vzor jako Termux ~/storage/shared.
 *
 * Po kopii otevře TerminalActivity v aktivním distro s `cd /root/share`,
 * aby uživatel rovnou viděl přijaté soubory.
 *
 * Bezpečnost: content:// se čte přes ContentResolver (žádné přímé cesty),
 * jména souborů se sanitizují proti path traversalu.
 */
class ShareReceiverActivity : Activity() {

    companion object {
        private const val TAG = "ShareReceiver"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val uris = collectUris(intent)
            if (uris.isEmpty()) {
                Toast.makeText(this, "Žádný soubor k uložení", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            val shareDir = File(filesDir, "share")
            if (!shareDir.exists()) shareDir.mkdirs()

            val saved = mutableListOf<String>()
            for (uri in uris) {
                val name = sanitizeName(queryDisplayName(uri) ?: uri.lastPathSegment ?: "shared_file")
                val target = uniqueFile(shareDir, name)
                val ok = copyUri(uri, target)
                if (ok) {
                    saved.add(target.name)
                    Log.i(TAG, "Saved shared file: ${target.absolutePath}")
                } else {
                    Log.w(TAG, "Failed to copy shared file: $uri")
                }
            }

            if (saved.isEmpty()) {
                Toast.makeText(this, "[-] Nepodařilo se uložit soubor(y)", Toast.LENGTH_LONG).show()
                finish()
                return
            }

            Toast.makeText(
                this,
                "✓ Uloženo do ~/share: ${saved.joinToString(", ")}",
                Toast.LENGTH_LONG
            ).show()

            // Otevři terminál v aktivním distro přímo v /root/share
            openTerminalInShare()
        } catch (e: Exception) {
            Log.e(TAG, "Share receive failed", e)
            Toast.makeText(this, "[-] Chyba při přijímání souboru: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            finish()
        }
    }

    /** Sbírá URI z VIEW / SEND / SEND_MULTIPLE intentů. */
    private fun collectUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val out = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { out.add(it) }
            }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) out.add(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                list?.filterNotNull()?.let { out.addAll(it) }
            }
        }
        return out
    }

    /** Zobrazované jméno souboru přes ContentResolver (OPENABLE_COLUMNS). */
    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryDisplayName failed for $uri: ${e.message}")
            null
        }
    }

    /** Sanitizace jména: jen basename, bez path separatorů a řídicích znaků. */
    private fun sanitizeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[\\x00-\\x1f<>:\"|?*]"), "_").trim()
        return cleaned.ifEmpty { "shared_file" }.take(200)
    }

    /** Kolize jmen: name.ext → name (1).ext → name (2).ext ... */
    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        var i = 1
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        while (candidate.exists()) {
            candidate = File(dir, "$stem ($i)$ext")
            i++
        }
        return candidate
    }

    /** Kopie content:// nebo file:// URI do cílového souboru. */
    private fun copyUri(uri: Uri, target: File): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return false
            target.length() > 0 || true
        } catch (e: Exception) {
            Log.e(TAG, "copyUri failed: ${e.message}")
            false
        }
    }

    /** Aktivní distro z markeru nh/.active_distro (kali|parrot|docker:<image>). */
    private fun activeDistroRootfsDirName(): String {
        val marker = File(filesDir, "nh/.active_distro")
        val spec = try {
            if (marker.exists()) marker.readText().trim() else ""
        } catch (e: Exception) { "" }
        return when {
            spec.startsWith("docker:") -> "nh/distro/docker/${spec.removePrefix("docker:")}"
            spec == "kali" || spec == "parrot" -> "nh/distro/$spec"
            File(filesDir, "nh/distro/parrot").exists() -> "nh/distro/parrot"
            File(filesDir, "nh/distro/kali").exists() -> "nh/distro/kali"
            else -> "nh/distro/kali"
        }
    }

    /** Otevře TerminalActivity s cd do /root/share (guest cesta bindu). */
    private fun openTerminalInShare() {
        val rootfsDirName = activeDistroRootfsDirName()
        val isDocker = rootfsDirName.startsWith("nh/distro/docker/")
        // customCommand běží přes entrypoint: exec $ENTRY_SHELL -c "<cmd>"
        // → cd do share, výpis, a interaktivní login shell (zsh preferován).
        val cmd = "cd /root/share && ls -la && exec \$(command -v zsh || command -v bash) --login"
        val intent = Intent(this, com.linux_core.ui.terminal.TerminalActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("rootfsDirName", rootfsDirName)
            putExtra("mountStorage", false)
            putExtra("customCommand", cmd)
            if (isDocker) putExtra("isDockerImage", true)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open terminal: ${e.message}")
        }
    }
}
