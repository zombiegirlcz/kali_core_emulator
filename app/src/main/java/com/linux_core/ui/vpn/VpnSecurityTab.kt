package com.linux_core.ui.vpn

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linux_core.core.VpnFirewallManager
import com.linux_core.core.VpnLogManager
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun VpnSecurityTab() {
    val context = LocalContext.current
    var activeFilter by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }
    
    val auditLogs = remember { mutableStateOf(VpnLogManager.getLogs()) }
    val blockedIps = remember { 
        mutableStateListOf<String>().apply { 
            addAll(VpnFirewallManager.getBlockedIps()) 
        } 
    }
    
    var showFirewallConfig by remember { mutableStateOf(false) }

    // Periodic logger updater
    LaunchedEffect(Unit) {
        while (true) {
            auditLogs.value = VpnLogManager.getLogs()
            delay(1000)
        }
    }

    val filteredLogs = remember(activeFilter, searchQuery, auditLogs.value) {
        auditLogs.value.filter { entry ->
            val matchesFilter = if (activeFilter == "ALL") true else entry.category.name == activeFilter
            val matchesSearch = if (searchQuery.isEmpty()) true else {
                entry.dstIp.contains(searchQuery) || entry.srcIp.contains(searchQuery) || entry.detail.contains(searchQuery, ignoreCase = true)
            }
            matchesFilter && matchesSearch
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header with Export and Firewall toggle
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("NETWORK ANALYSIS", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Live connection auditing & defense", fontSize = 10.sp, color = Color.Gray)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = { showFirewallConfig = !showFirewallConfig },
                    modifier = Modifier.size(32.dp).background(if (showFirewallConfig) Color(0xFF8F0011) else Color(0x33FFFFFF), RoundedCornerShape(4.dp))
                ) {
                    Icon(
                        imageVector = if (showFirewallConfig) Icons.Default.Shield else Icons.Default.Shield,
                        contentDescription = "Firewall",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Button(
                    onClick = {
                        val path = VpnLogManager.exportLogsToDownloads(context)
                        if (path != null) {
                            Toast.makeText(context, "Logs exported to Downloads", Toast.LENGTH_LONG).show()
                        }
                    },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC008F11)),
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("EXPORT", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (showFirewallConfig) {
            FirewallController(blockedIps)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Search and Filter Bar
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0C0E14)),
            border = BorderStroke(1.dp, Color(0x22FFFFFF))
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter by IP or keyword...", color = Color.DarkGray, fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00FF41),
                        unfocusedBorderColor = Color(0x11FFFFFF)
                    ),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp)) }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("ALL" to "All", "ALLOWED" to "Ok", "SUSPICIOUS" to "Warn", "CRITICAL" to "Alert").forEach { (filterId, label) ->
                        val active = activeFilter == filterId
                        FilterChip(
                            selected = active,
                            onClick = { activeFilter = filterId },
                            label = { Text(label, fontSize = 9.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF008F11),
                                containerColor = Color(0x11FFFFFF),
                                labelColor = Color.Gray,
                                selectedLabelColor = Color.White
                            ),
                            border = null,
                            modifier = Modifier.weight(1f).height(24.dp)
                        )
                    }
                }
            }
        }

        // Live Log List
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF1E2026)),
            colors = CardDefaults.cardColors(containerColor = Color(0xCC08090D))
        ) {
            if (filteredLogs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No matching connection logs", color = Color.DarkGray, fontSize = 12.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(filteredLogs) { entry ->
                        LogEntryItem(entry, blockedIps)
                    }
                }
            }
        }
    }
}

@Composable
fun FirewallController(blockedIps: SnapshotStateList<String>) {
    val context = LocalContext.current
    var newIpToBlock by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF8F0011)),
        colors = CardDefaults.cardColors(containerColor = Color(0xCC1A0A0C))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFFFF3333), modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Active Firewall Controls", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newIpToBlock,
                    onValueChange = { newIpToBlock = it },
                    placeholder = { Text("Enter IP to block...", color = Color.DarkGray, fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFF3333),
                        unfocusedBorderColor = Color(0x33FFFFFF)
                    ),
                    modifier = Modifier.weight(1f).height(48.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val ip = newIpToBlock.trim()
                        if (ip.isNotEmpty()) {
                            VpnFirewallManager.blockIp(ip)
                            if (!blockedIps.contains(ip)) {
                                blockedIps.add(ip)
                            }
                            newIpToBlock = ""
                            Toast.makeText(context, "IP Blocked", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8F0011)),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("BLOCK", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (blockedIps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Blocked List:", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    items(blockedIps.toList()) { ip ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0x33FF3333),
                            border = BorderStroke(1.dp, Color(0x66FF3333)),
                            modifier = Modifier.clickable {
                                VpnFirewallManager.unblockIp(ip)
                                blockedIps.remove(ip)
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(ip, color = Color(0xFFFF3333), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(Icons.Default.Close, contentDescription = null, tint = Color(0xFFFF3333), modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogEntryItem(entry: VpnLogManager.LogEntry, blockedIps: SnapshotStateList<String>) {
    val context = LocalContext.current
    val categoryColor = when (entry.category) {
        VpnLogManager.AuditCategory.CRITICAL -> Color(0xFFFF3333)
        VpnLogManager.AuditCategory.SUSPICIOUS -> Color(0xFFFF9900)
        VpnLogManager.AuditCategory.BLOCKED -> Color(0xFFFFDD00)
        VpnLogManager.AuditCategory.ALLOWED -> Color(0xFF00FF41)
        else -> Color.Gray
    }
    
    val isIpBlocked = blockedIps.contains(entry.dstIp)
    var isExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded }
            .background(if (isExpanded) Color(0x11FFFFFF) else Color.Transparent, RoundedCornerShape(4.dp))
            .padding(vertical = 4.dp, horizontal = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(categoryColor, RoundedCornerShape(50))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = entry.protocol,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = categoryColor,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = entry.dstIp,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isIpBlocked) Color(0xFFFF3333) else Color.White,
                    fontFamily = FontFamily.Monospace
                )
            }
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
            Text(timeStr, fontSize = 9.sp, color = Color.DarkGray)
        }
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 22.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Port: ${entry.dstPort}  •  Size: ${entry.size}B",
                fontSize = 10.sp,
                color = Color.Gray
            )
            
            if (!isExpanded) {
                Text(
                    text = entry.category.name,
                    fontSize = 8.sp,
                    color = categoryColor.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (isExpanded) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(modifier = Modifier.padding(start = 22.dp, end = 8.dp)) {
                if (entry.detail.isNotEmpty()) {
                    Text(
                        text = "DETAILS: ${entry.detail}",
                        fontSize = 10.sp,
                        color = Color.LightGray,
                        fontWeight = FontWeight.Normal
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            // Placeholder for IP Info
                            Toast.makeText(context, "Whois lookup for ${entry.dstIp}", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("WHOIS", fontSize = 9.sp, color = Color(0xFF00E5FF))
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            if (isIpBlocked) {
                                VpnFirewallManager.unblockIp(entry.dstIp)
                                blockedIps.remove(entry.dstIp)
                                Toast.makeText(context, "Unblocked", Toast.LENGTH_SHORT).show()
                            } else {
                                VpnFirewallManager.blockIp(entry.dstIp)
                                if (!blockedIps.contains(entry.dstIp)) {
                                    blockedIps.add(entry.dstIp)
                                }
                                Toast.makeText(context, "Blocked", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isIpBlocked) Color(0xFF008F11) else Color(0xFF8F0011)
                        ),
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text(
                            text = if (isIpBlocked) "UNBLOCK" else "BLOCK IP",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(color = Color(0x0AFFFFFF), thickness = 0.5.dp)
    }
}
