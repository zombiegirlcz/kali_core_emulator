package com.linux_core.core

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import com.linux_core.ui.terminal.TerminalActivity

object ShortcutHelper {

    fun registerShortcuts(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return

        val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return

        // 1. Kali Shortcut
        val kaliIntent = Intent(context, TerminalActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("rootfsDirName", "kali-arm64")
            putExtra("mountStorage", false)
        }
        val kaliShortcut = ShortcutInfo.Builder(context, "shortcut_kali")
            .setShortLabel("Launch Kali")
            .setLongLabel("Launch Kali NetHunter Terminal")
            .setIcon(Icon.createWithResource(context, android.R.drawable.ic_menu_slideshow))
            .setIntent(kaliIntent)
            .build()

        // 2. Parrot Shortcut
        val parrotIntent = Intent(context, TerminalActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("rootfsDirName", "parrot-arm64")
            putExtra("mountStorage", false)
        }
        val parrotShortcut = ShortcutInfo.Builder(context, "shortcut_parrot")
            .setShortLabel("Launch Parrot")
            .setLongLabel("Launch ParrotOS Terminal")
            .setIcon(Icon.createWithResource(context, android.R.drawable.ic_menu_slideshow))
            .setIntent(parrotIntent)
            .build()

        try {
            shortcutManager.dynamicShortcuts = listOf(kaliShortcut, parrotShortcut)
        } catch (_: Exception) {}
    }

    fun pinShortcut(context: Context, distroId: String, customCommand: String?, mountStorage: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return
        if (!shortcutManager.isRequestPinShortcutSupported) return

        val isKali = distroId.contains("kali")
        val rootfsDirName = if (isKali) "kali-arm64" else "parrot-arm64"

        // Build a unique shortcut ID based on the custom command or just static launch
        val uniqueId = if (!customCommand.isNullOrEmpty()) {
            "pin_${distroId}_${customCommand.hashCode()}"
        } else {
            "pin_${distroId}_default"
        }

        val launchIntent = Intent(context, TerminalActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("rootfsDirName", rootfsDirName)
            putExtra("mountStorage", mountStorage)
            if (!customCommand.isNullOrEmpty()) {
                putExtra("customCommand", customCommand)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        // Display text: either standard Boot or shortened custom command
        val shortLabel = if (!customCommand.isNullOrEmpty()) {
            val cmd = customCommand.trim()
            if (cmd.length > 10) cmd.take(8) + ".." else cmd
        } else {
            if (isKali) "Kali" else "Parrot"
        }

        val longLabel = if (!customCommand.isNullOrEmpty()) {
            "Run '$customCommand' on ${if (isKali) "Kali" else "Parrot"}"
        } else {
            "Launch ${if (isKali) "Kali" else "Parrot"} Terminal"
        }

        val shortcut = ShortcutInfo.Builder(context, uniqueId)
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel)
            .setIcon(Icon.createWithResource(context, android.R.drawable.ic_menu_compass))
            .setIntent(launchIntent)
            .build()

        try {
            shortcutManager.requestPinShortcut(shortcut, null)
        } catch (_: Exception) {}
    }
}
