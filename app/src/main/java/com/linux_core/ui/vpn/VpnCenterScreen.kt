package com.linux_core.ui.vpn

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
            .padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Futuristic Cyber Glassmorphic Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp)
                .background(Color(0xE608090D), RoundedCornerShape(10.dp))
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0x3300FF41), // Neon green transparent
                            Color(0x11FFFFFF),
                            Color(0x3300E5FF)  // Neon cyan transparent
                        )
                    ),
                    shape = RoundedCornerShape(10.dp)
                )
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tabs = listOf(
                Triple("dashboard", "Panel", Icons.Default.Dashboard),
                Triple("traffic", "Traffic", Icons.Default.ShowChart),
                Triple("security", "Security", Icons.Default.Shield),
                Triple("dns", "DNS", Icons.Default.Language),
                Triple("mesh", "Mesh", Icons.Default.DeviceHub),
                Triple("settings", "Settings", Icons.Default.Settings)
            )

            tabs.forEach { (tabId, label, icon) ->
                val active = activeSubTab == tabId
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { activeSubTab = tabId }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = if (active) Color(0xFF00FF41) else Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = label,
                            color = if (active) Color.White else Color.Gray,
                            fontSize = 10.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        // Neon active indicator bar
                        Box(
                            modifier = Modifier
                                .width(20.dp)
                                .height(2.dp)
                                .background(
                                    if (active) Color(0xFF00FF41) else Color.Transparent,
                                    RoundedCornerShape(1.dp)
                                )
                        )
                    }
                }
            }
        }

        when (activeSubTab) {
            "dashboard" -> VpnDashboardTab()
            "traffic" -> VpnTrafficTab()
            "security" -> VpnSecurityTab()
            "dns" -> VpnDnsTab()
            "mesh" -> VpnMeshTab()
            "settings" -> VpnSettingsTab()
        }
    }
}

