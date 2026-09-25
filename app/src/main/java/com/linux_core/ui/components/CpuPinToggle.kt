package com.linux_core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Malý přepínač CPU pinu v rohu karty distra. Zapnuto = proot + guest na
 * jednom rychlém jádru (rychlejší shell/apt/git, pomalejší paralelní výpočty).
 */
@Composable
fun CpuPinToggle(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = Color(0xFFFFB300)
    Surface(
        onClick = { onToggle(!enabled) },
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = if (enabled) accent.copy(alpha = 0.18f) else Color(0xFF12131A),
        border = BorderStroke(1.dp, if (enabled) accent else Color(0xFF333333)),
    ) {
        Icon(
            imageVector = Icons.Filled.Memory,
            contentDescription = if (enabled) "CPU pin zapnutý (1 jádro)" else "CPU pin vypnutý",
            tint = if (enabled) accent else Color.Gray,
            modifier = Modifier.padding(3.dp).size(16.dp),
        )
    }
}
