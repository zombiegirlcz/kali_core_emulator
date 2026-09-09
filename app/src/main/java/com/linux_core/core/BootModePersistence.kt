package com.linux_core.core

import android.content.Context

fun saveBootMode(context: Context, distroId: String, mode: String) {
    context.getSharedPreferences("boot_modes", Context.MODE_PRIVATE)
        .edit()
        .putString("mode_$distroId", mode)
        .apply()
}

fun loadBootMode(context: Context, distroId: String, default: String = "M"): String {
    return context.getSharedPreferences("boot_modes", Context.MODE_PRIVATE)
        .getString("mode_$distroId", default) ?: default
}
