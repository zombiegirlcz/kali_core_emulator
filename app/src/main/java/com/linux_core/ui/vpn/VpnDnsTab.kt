package com.linux_core.ui.vpn

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linux_core.core.VpnLogManager
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnDnsTab() {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var newRuleText by remember { mutableStateOf("") }

    var dnsLogs by remember { mutableStateOf(VpnLogManager.getDnsLogs()) }
    var topDomains by remember { mutableStateOf(VpnLogManager.getTopDomains()) }
    var blocklistRules by remember { mutableStateOf(VpnLogManager.getBlocklistRules()) }

    // Periodic statistics & logs refresher loop
    LaunchedEffect(Unit) {
        VpnLogManager.loadCustomBlocklist(context)
        blocklistRules = VpnLogManager.getBlocklistRules()
        while (true) {
            dnsLogs = VpnLogManager.getDnsLogs()
            topDomains = VpnLogManager.getTopDomains()
            delay(1000)
        }
    }

    val filteredDnsLogs = remember(searchQuery, dnsLogs) {
        if (searchQuery.trim().isEmpty()) {
            dnsLogs
        } else {
            dnsLogs.filter {
                it.domain.contains(searchQuery, ignoreCase = true) ||
                        it.type.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // TOP 10 DOMAINS CARD
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF1E2026)),
                colors = CardDefaults.cardColors(containerColor = Color(0x990B0D13))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "TOP 10 REQUESTED DOMAINS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00FF41),
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    if (topDomains.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No active DNS queries recorded yet.",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    } else {
                        val maxCount = topDomains.firstOrNull()?.second ?: 1
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            topDomains.forEach { (domain, count) ->
                                val progress = (count.toFloat() / maxCount).coerceIn(0f, 1f)
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = domain,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "$count requests",
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color(0xFF00E5FF)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = progress,
                                        color = Color(0xFF00FF41),
                                        trackColor = Color(0x11FFFFFF),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // CUSTOM BLOCKLIST RULES EDITOR
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF1E2026)),
                colors = CardDefaults.cardColors(containerColor = Color(0x990B0D13))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "CUSTOM AD BLOCKLIST & FIREWALL RULES",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00FF41),
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Text(
                        text = "Add specific hostnames or wildcards (e.g. *.ads.com)",
                        fontSize = 10.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newRuleText,
                            onValueChange = { newRuleText = it },
                            placeholder = { Text("Enter domain name...", color = Color.DarkGray, fontSize = 11.sp) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00FF41),
                                unfocusedBorderColor = Color(0xFF1E2026),
                                focusedContainerColor = Color(0xFF08090D),
                                unfocusedContainerColor = Color(0xFF08090D)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val cleanRule = newRuleText.trim()
                                if (cleanRule.isNotEmpty()) {
                                    VpnLogManager.addBlocklistRule(context, cleanRule)
                                    blocklistRules = VpnLogManager.getBlocklistRules()
                                    newRuleText = ""
                                    Toast.makeText(context, "Added blocklist rule: $cleanRule", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF008F11)),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("ADD", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (blocklistRules.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 140.dp)
                        ) {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(blocklistRules) { rule ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0x11FFFFFF), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = rule,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color.LightGray
                                        )
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete Rule",
                                            tint = Color(0xFFFF3333),
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clickable {
                                                    VpnLogManager.removeBlocklistRule(context, rule)
                                                    blocklistRules = VpnLogManager.getBlocklistRules()
                                                    Toast.makeText(context, "Removed rule: $rule", Toast.LENGTH_SHORT).show()
                                                }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // LIVE DNS QUERIES LOG
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF1E2026)),
                colors = CardDefaults.cardColors(containerColor = Color(0x990B0D13))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "LIVE DNS QUERIES LOG",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00FF41),
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Filter queries by domain...", color = Color.DarkGray, fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00FF41),
                            unfocusedBorderColor = Color(0xFF1E2026),
                            focusedContainerColor = Color(0xFF08090D),
                            unfocusedContainerColor = Color(0xFF08090D)
                        ),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(bottom = 12.dp)
                    )

                    if (filteredDnsLogs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No matching DNS queries logs.",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    } else {
                        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                        ) {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(filteredDnsLogs) { entry ->
                                    val isBlocked = entry.category == VpnLogManager.AuditCategory.BLOCKED
                                    val timeStr = timeFormat.format(Date(entry.timestamp))

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0x08FFFFFF), RoundedCornerShape(6.dp))
                                            .border(1.dp, Color(0x11FFFFFF), RoundedCornerShape(6.dp))
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = entry.domain,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = timeStr,
                                                    fontSize = 9.sp,
                                                    color = Color.Gray,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                                Text(
                                                    text = entry.type,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF00E5FF),
                                                    fontFamily = FontFamily.Monospace
                                                )
                                                Text(
                                                    text = if (isBlocked) "BLOCKED" else "ALLOWED",
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Black,
                                                    color = if (isBlocked) Color(0xFFFF3333) else Color(0xFF00FF41),
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        Button(
                                            onClick = {
                                                if (isBlocked) {
                                                    VpnLogManager.removeBlocklistRule(context, entry.domain)
                                                    Toast.makeText(context, "Allowed domain: ${entry.domain}", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    VpnLogManager.addBlocklistRule(context, entry.domain)
                                                    Toast.makeText(context, "Blocked domain: ${entry.domain}", Toast.LENGTH_SHORT).show()
                                                }
                                                blocklistRules = VpnLogManager.getBlocklistRules()
                                            },
                                            shape = RoundedCornerShape(4.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isBlocked) Color(0x3300FF41) else Color(0x33FF3333)
                                            ),
                                            border = BorderStroke(
                                                1.dp,
                                                if (isBlocked) Color(0xFF00FF41) else Color(0xFFFF3333)
                                            ),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Text(
                                                text = if (isBlocked) "ALLOW" else "BLOCK",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isBlocked) Color(0xFF00FF41) else Color(0xFFFF3333)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
