package com.linux_core.ui.vpn

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun VpnCenterScreen(modifier: Modifier = Modifier) {
    var activeSubTab by remember { mutableStateOf("dashboard") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Futuristic mini sub-tab panel
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("dashboard" to "Panel", "traffic" to "Traffic", "security" to "Security", "mesh" to "Mesh").forEach { (tabId, label) ->
                val active = activeSubTab == tabId
                Button(
                    onClick = { activeSubTab = tabId },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (active) Color(0xCC008F11) else Color(0x771E2026)
                    ),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.weight(1f).height(32.dp)
                ) {
                    Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        when (activeSubTab) {
            "dashboard" -> VpnDashboardTab()
            "traffic" -> VpnTrafficTab()
            "security" -> VpnSecurityTab()
            "mesh" -> VpnMeshTab()
        }
    }
}
