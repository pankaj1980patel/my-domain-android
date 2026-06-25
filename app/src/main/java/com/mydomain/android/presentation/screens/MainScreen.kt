package com.mydomain.android.presentation.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mydomain.android.LogEntry
import com.mydomain.android.NetService
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

private val ONLINE = Color(0xFF3ECF8E)
private val CONNECTED = Color(0xFF5B8CFF)

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
    var notifTitle by remember { mutableStateOf("") }
    var notifBody by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var clipSync by remember { mutableStateOf(NetService.clipboardSyncEnabled) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
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
                        when (json.optString("type")) {
                            "clipboard" -> {
                                log.add(0, LogEntry("in", o.optString("from"), o.optString("ip"), "CLIP", "📋 Clipboard synced", true)); return@runCatching
                            }
                            "clipboard_response" -> {
                                log.add(0, LogEntry("in", o.optString("from"), o.optString("ip"), "CLIP", "📋 ${json.optString("content")}", true)); return@runCatching
                            }
                            "clipboard_request" -> return@runCatching
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
            @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val p = withContext(Dispatchers.IO) { runCatching { parsePeers(RustNet.nativeGetPeers()) }.getOrDefault(emptyList()) }
            val conn = withContext(Dispatchers.IO) { runCatching { RustNet.nativeConnectedPeers() }.getOrDefault("[]") }
            peers = p
            connected = runCatching {
                val a = JSONArray(conn); (0 until a.length()).map { a.getString(it) }.toSet()
            }.getOrDefault(emptySet())
            delay(2000)
        }
    }

    val sel = peers.firstOrNull { it.nodeId == selected }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(16.dp)) {
                    Text("◆  my-domain", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Your devices", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
                HorizontalDivider()
                Text("DEVICES", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                if (peers.isEmpty()) {
                    Text("No devices — Scan or Refresh.", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(16.dp))
                }
                peers.forEach { p ->
                    val isConn = connected.contains(p.nodeId)
                    NavigationDrawerItem(
                        icon = { Box(Modifier.size(10.dp).background(if (isConn) CONNECTED else ONLINE, CircleShape)) },
                        label = { Text("${p.name}  ·  ${p.ip}", maxLines = 1) },
                        selected = p.nodeId == selected,
                        onClick = { selected = p.nodeId; scope.launch { drawerState.close() } },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(label = { Text("⟳  Scan LAN") }, selected = false,
                    onClick = { RustNet.nativeScanLan(); status = "Scanning…" }, modifier = Modifier.padding(horizontal = 8.dp))
                NavigationDrawerItem(label = { Text("⤓  Refresh") }, selected = false,
                    onClick = { scope.launch { withContext(Dispatchers.IO) { RustNet.nativeRefresh() } } }, modifier = Modifier.padding(horizontal = 8.dp))
                NavigationDrawerItem(label = { Text("⚙  Settings") }, selected = false,
                    onClick = { showSettings = true; scope.launch { drawerState.close() } }, modifier = Modifier.padding(horizontal = 8.dp))
            }
        },
    ) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(sel?.name ?: "my-domain", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Text("≡", fontSize = 22.sp) } },
                actions = {
                    TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { RustNet.nativeRefresh() } } }) { Text("Refresh") }
                    TextButton(onClick = { RustNet.nativeScanLan() }) { Text("Scan") }
                },
            )
        }) { inner ->
            if (sel == null) {
                Column(
                    Modifier.padding(inner).fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("◆", fontSize = 48.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text("Select a device", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Open the menu (≡) to pick a device, or Scan/Refresh to find them.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(
                    Modifier.padding(inner).fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Device header
                    item {
                        val isConn = connected.contains(sel.nodeId)
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(sel.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Text("${sel.ip} · tcp ${sel.tcpPort} · udp ${sel.udpPort} · ws ${sel.wsPort}",
                                        fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                    Text(if (isConn) "● connected" else "● online",
                                        fontSize = 12.sp, color = if (isConn) CONNECTED else ONLINE)
                                }
                                if (sel.wsPort != 0) {
                                    OutlinedButton(enabled = !isConn, onClick = {
                                        scope.launch { val e = withContext(Dispatchers.IO) { RustNet.nativeConnectWs(sel.nodeId) }; if (e.isNotEmpty()) status = e }
                                    }) { Text(if (isConn) "WS ✓" else "Connect") }
                                }
                            }
                        }
                    }

                    // Send message
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("✉  Send message", fontWeight = FontWeight.SemiBold)
                                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("UDP", "TCP", "WS").forEach { pr ->
                                        FilterChip(selected = protocol == pr, onClick = { protocol = pr }, label = { Text(pr) })
                                    }
                                }
                                OutlinedTextField(message, { message = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
                                Button(onClick = {
                                    val node = sel.nodeId
                                    val text = message.trim()
                                    if (text.isEmpty()) { status = "Message is empty"; return@Button }
                                    scope.launch {
                                        val e = withContext(Dispatchers.IO) {
                                            if (protocol == "WS") { RustNet.nativeConnectWs(node); delay(400) }
                                            RustNet.nativeSend(node, protocol, text)
                                        }
                                        if (e.isEmpty()) { log.add(0, LogEntry("out", sel.name, sel.ip, protocol, text, true)); message = ""; status = "Sent over $protocol" }
                                        else status = e.removePrefix("ERROR: ")
                                    }
                                }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Send") }
                            }
                        }
                    }

                    // Clipboard
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("📋  Clipboard auto-sync", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    FilterChip(selected = clipSync, onClick = {
                                        clipSync = !clipSync
                                        NetService.clipboardSyncEnabled = clipSync
                                        prefs.edit().putBoolean("clip_sync", clipSync).apply()
                                        status = if (clipSync) "Clipboard auto-sync on" else "Clipboard auto-sync off"
                                    }, label = { Text(if (clipSync) "On" else "Off") })
                                }
                                Button(onClick = {
                                    val node = sel.nodeId
                                    val req = JSONObject().apply { put("type", "clipboard_request"); put("from", NetService.myNodeId) }.toString()
                                    scope.launch {
                                        val e = withContext(Dispatchers.IO) {
                                            val proto = if (connected.contains(node)) "WS" else "UDP"
                                            RustNet.nativeSend(node, proto, req)
                                        }
                                        status = if (e.isEmpty()) "Requested clipboard…" else e.removePrefix("ERROR: ")
                                    }
                                }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Get clipboard (once)") }
                            }
                        }
                    }

                    // Notification
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("🔔  Send notification", fontWeight = FontWeight.SemiBold)
                                OutlinedTextField(notifTitle, { notifTitle = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                                OutlinedTextField(notifBody, { notifBody = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                                Button(onClick = {
                                    if (notifTitle.trim().isEmpty()) { status = "Title required"; return@Button }
                                    scope.launch {
                                        withContext(Dispatchers.IO) { RustNet.nativeShareNotification(notifTitle.trim(), notifBody.trim(), "my-domain") }
                                        status = "Notification sent"; notifTitle = ""; notifBody = ""
                                    }
                                }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Send to my devices") }
                            }
                        }
                    }

                    item {
                        if (status.isNotEmpty()) Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        Text("Activity", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    }
                    items(log) { e ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text("[${e.protocol}] ${if (e.dir == "in") "from" else "to"} ${e.peer}  ${e.ip}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(e.text, color = if (e.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(onDismiss = { showSettings = false }, onLogout = {
            RustNet.nativeLogout(); showSettings = false; onLogout()
        })
    }
}
