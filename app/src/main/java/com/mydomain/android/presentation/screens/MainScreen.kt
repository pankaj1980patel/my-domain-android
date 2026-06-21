package com.mydomain.android.presentation.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mydomain.android.LogEntry
import com.mydomain.android.Peer
import com.mydomain.android.RustNet
import com.mydomain.android.core.NetworkingUtils.parsePeers
import com.mydomain.android.presentation.components.SettingsDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(prefs: SharedPreferences, onLogout: () -> Unit) {
    var peers by remember { mutableStateOf<List<Peer>>(emptyList()) }
    var connected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val log = remember { mutableStateListOf<LogEntry>() }
    var selected by remember { mutableStateOf<String?>(null) }
    var protocol by remember { mutableStateOf("WS") }
    var message by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val msgJson = intent?.getStringExtra("msg") ?: return
                runCatching {
                    val o = JSONObject(msgJson)
                    val text = o.optString("text")
                    try {
                        val json = JSONObject(text)
                        if (json.optString("type") == "clipboard") {
                            log.add(0, LogEntry("in", o.optString("from"), o.optString("ip"), o.optString("protocol"), "📋 Clipboard Synced", o.optBoolean("ok", true)))
                            return@runCatching
                        }
                    } catch (_: Exception) { }
                    log.add(0, LogEntry("in", o.optString("from"), o.optString("ip"), o.optString("protocol"), text, o.optBoolean("ok", true)))
                }
            }
        }
        val filter = IntentFilter("com.mydomain.android.NEW_MESSAGE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val p = withContext(Dispatchers.IO) { runCatching { parsePeers(RustNet.nativeGetPeers()) }.getOrDefault(emptyList()) }
            val conn = withContext(Dispatchers.IO) { runCatching { RustNet.nativeConnectedPeers() }.getOrDefault("[]") }
            peers = p
            connected = runCatching {
                val a = JSONArray(conn)
                (0 until a.length()).map { a.getString(it) }.toSet()
            }.getOrDefault(emptySet())
            if (selected == null && p.isNotEmpty()) selected = p.first().nodeId
            delay(2000)
        }
    }

    val sel = peers.firstOrNull { it.nodeId == selected }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("LAN Messenger", fontWeight = FontWeight.Bold) },
            actions = {
                TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { RustNet.nativeRefresh() } } }) { Text("Refresh") }
                TextButton(onClick = { RustNet.nativeScanLan() }) { Text("Scan") }
                IconButton(onClick = { showSettings = true }) { Text("⚙") }
            },
        )
    }) { inner ->
        // Entire screen content structure migrated into a unified LazyColumn
        LazyColumn(
            modifier = Modifier
                .padding(inner)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // --- PEERS SECTION HEADER ---
            item {
                Text("Peers (${peers.size})", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
            }

            // --- PEERS LIST ---
            items(peers) { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${p.name}  [${p.source}]", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("${p.ip} · tcp ${p.tcpPort} · udp ${p.udpPort} · ws ${p.wsPort}", fontSize = 11.sp)
                    }
                    if (p.wsPort != 0) {
                        val isConnected = connected.contains(p.nodeId)
                        OutlinedButton(
                            enabled = !isConnected,
                            onClick = {
                                scope.launch { val e = withContext(Dispatchers.IO) { RustNet.nativeConnectWs(p.nodeId) }; if (e.isNotEmpty()) status = e }
                            },
                        ) { Text(if (isConnected) "WS ✓" else "WS", fontSize = 12.sp) }
                    }
                }
            }

            // --- SEND UI FORM ---
            item {
                Spacer(Modifier.height(12.dp))
                Text("Send", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))

                ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = !menu }) {
                    OutlinedTextField(
                        value = sel?.let { "${it.name} (${it.ip})" } ?: "No peers", onValueChange = {},
                        readOnly = true, label = { Text("To") }, modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menu) },
                    )
                    ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        peers.forEach { p ->
                            DropdownMenuItem(text = { Text("${p.name} (${p.ip})") }, onClick = { selected = p.nodeId; menu = false })
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    listOf("UDP", "TCP", "WS").forEach { pr ->
                        FilterChip(selected = protocol == pr, onClick = { protocol = pr }, label = { Text(pr) })
                    }
                }

                OutlinedTextField(message, { message = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())

                Button(onClick = {
                    val node = selected ?: return@Button
                    val text = message.trim()
                    if (text.isEmpty()) { status = "Message is empty"; return@Button }
                    val peerName = sel?.name ?: "peer"
                    val peerIp = sel?.ip ?: ""
                    scope.launch {
                        val e = withContext(Dispatchers.IO) {
                            if (protocol == "WS") { RustNet.nativeConnectWs(node); delay(400) }
                            RustNet.nativeSend(node, protocol, text)
                        }
                        if (e.isEmpty()) {
                            log.add(0, LogEntry("out", peerName, peerIp, protocol, text, true)); message = ""; status = "Sent over $protocol"
                        } else status = e.removePrefix("ERROR: ")
                    }
                }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Send") }

                if (status.isNotEmpty()) {
                    Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                }
            }

            // --- MESSAGES SECTION HEADER ---
            item {
                Spacer(Modifier.height(12.dp))
                Text("Messages", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
            }

            // --- INBOUND / OUTBOUND MESSAGES LOG ---
            items(log) { e ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("[${e.protocol}] ${if (e.dir == "in") "from" else "to"} ${e.peer}  ${e.ip}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(e.text, color = if (e.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(onDismiss = { showSettings = false }, onLogout = {
            RustNet.nativeLogout()
            showSettings = false; onLogout()
        })
    }
}

