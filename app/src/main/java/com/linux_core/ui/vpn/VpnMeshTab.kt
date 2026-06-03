package com.linux_core.ui.vpn

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linux_core.core.VpnPeerManager
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnMeshTab() {
    val context = LocalContext.current
    val clipboardManager: ClipboardManager = LocalClipboardManager.current

    var isP2PEnabled by remember { mutableStateOf(VpnPeerManager.isEnabled()) }
    var localIdText by remember { mutableStateOf(VpnPeerManager.getLocalPeerId().toString()) }
    var nodeNameText by remember { mutableStateOf(VpnPeerManager.getNodeName()) }
    var peerConnectionStringInput by remember { mutableStateOf("") }
    
    // Trigger periodic list/WAN status refresh
    var refreshTrigger by remember { mutableStateOf(0) }
    var localWanEndpoint by remember { mutableStateOf(VpnPeerManager.getLocalWanAddress()) }
    var peerList by remember { mutableStateOf(VpnPeerManager.peers.values.toList()) }

    LaunchedEffect(refreshTrigger) {
        while (true) {
            localWanEndpoint = VpnPeerManager.getLocalWanAddress()
            peerList = VpnPeerManager.peers.values.toList()
            delay(1500)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Service Controller Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF00FF41)),
                colors = CardDefaults.cardColors(containerColor = Color(0xDD0C0E14))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DeviceHub, contentDescription = null, tint = Color(0xFF00FF41), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("P2P Mesh Network", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text(
                                    text = if (isP2PEnabled) "● status: active overlay" else "○ status: offline",
                                    fontSize = 11.sp,
                                    color = if (isP2PEnabled) Color(0xFF00FF66) else Color.Gray
                                )
                            }
                        }
                        Switch(
                            checked = isP2PEnabled,
                            onCheckedChange = { checked ->
                                isP2PEnabled = checked
                                VpnPeerManager.setEnabled(checked)
                                refreshTrigger++
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00FF41),
                                checkedTrackColor = Color(0x8800FF41)
                            )
                        )
                    }
                }
            }
        }

        if (isP2PEnabled) {
            // 2. Node Config Settings Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E2026)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xBB0B0D13))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Local Node Configuration", fontSize = 12.sp, color = Color.LightGray, fontWeight = FontWeight.Bold)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = localIdText,
                                onValueChange = {
                                    localIdText = it
                                    it.toIntOrNull()?.let { id ->
                                        VpnPeerManager.setLocalPeerId(id)
                                    }
                                },
                                label = { Text("Peer ID (1-254)", fontSize = 10.sp, color = Color.Gray) },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF00FF41),
                                    unfocusedBorderColor = Color(0xFF1E2026)
                                ),
                                modifier = Modifier.weight(1f).height(50.dp)
                            )

                            OutlinedTextField(
                                value = nodeNameText,
                                onValueChange = {
                                    nodeNameText = it
                                    VpnPeerManager.setNodeName(it)
                                },
                                label = { Text("Node Name", fontSize = 10.sp, color = Color.Gray) },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF00FF41),
                                    unfocusedBorderColor = Color(0xFF1E2026)
                                ),
                                modifier = Modifier.weight(1.5f).height(50.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // WAN IP / Connection Info Box
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(6.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0x331E2026))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Resolved Public IP (STUN):", fontSize = 9.sp, color = Color.Gray)
                                Text(
                                    text = localWanEndpoint,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00FF41)
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Connection String (Share this):", fontSize = 9.sp, color = Color.Gray)
                                        Text(
                                            text = VpnPeerManager.getLocalConnectionString(),
                                            fontSize = 9.sp,
                                            color = Color.LightGray,
                                            maxLines = 1
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            val connStr = VpnPeerManager.getLocalConnectionString()
                                            clipboardManager.setText(AnnotatedString(connStr))
                                            Toast.makeText(context, "Copied connection string!", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color(0xFF00FF41), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Add Peer Form Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E2026)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xBB0B0D13))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Connect to a New Peer", fontSize = 12.sp, color = Color.LightGray, fontWeight = FontWeight.Bold)
                        }

                        OutlinedTextField(
                            value = peerConnectionStringInput,
                            onValueChange = { peerConnectionStringInput = it },
                            placeholder = { Text("Paste peer's connection string...", color = Color.DarkGray, fontSize = 11.sp) },
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 11.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00FF41),
                                unfocusedBorderColor = Color(0xFF1E2026)
                            ),
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                if (VpnPeerManager.addPeerFromConnectionString(peerConnectionStringInput)) {
                                    peerConnectionStringInput = ""
                                    Toast.makeText(context, "Peer successfully added!", Toast.LENGTH_SHORT).show()
                                    peerList = VpnPeerManager.peers.values.toList()
                                } else {
                                    Toast.makeText(context, "Invalid connection string format", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF008F11)),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth().height(36.dp)
                        ) {
                            Text("ADD MESH PEER", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }

            // 4. Peer List Nodes Card
            item {
                Text(
                    text = "Connected Overlay Mesh Nodes (${peerList.size})",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            if (peerList.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0x331E2026))
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text("No peers connected yet. Add one above!", color = Color.DarkGray, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                items(peerList) { peer ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                        colors = CardDefaults.cardColors(containerColor = Color(0x11FFFFFF))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = peer.nodeName,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "[10.9.0.${peer.peerId}]",
                                        fontSize = 11.sp,
                                        color = Color(0xFF00E5FF),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "WAN IP: ${peer.wanIp}:${peer.wanPort}",
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                                Text(
                                    text = "Key: ${peer.publicKeyBase64.take(16)}...",
                                    fontSize = 8.sp,
                                    color = Color.DarkGray
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Ping indicator
                                val ping = peer.pingMs
                                val pingColor = when {
                                    ping < 0 -> Color.Gray
                                    ping < 100 -> Color(0xFF00FF66)
                                    ping < 300 -> Color(0xFFFFCC00)
                                    else -> Color(0xFFFF3333)
                                }
                                val pingText = if (ping < 0) "connecting" else "${ping}ms"
                                
                                Text(
                                    text = pingText,
                                    fontSize = 9.sp,
                                    color = pingColor,
                                    fontWeight = FontWeight.Bold
                                )

                                IconButton(
                                    onClick = {
                                        VpnPeerManager.removePeer(peer.peerId)
                                        peerList = VpnPeerManager.peers.values.toList()
                                        Toast.makeText(context, "Peer removed", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFF8F0011), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
