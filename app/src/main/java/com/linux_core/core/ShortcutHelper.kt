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
}
